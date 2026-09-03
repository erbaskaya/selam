-- Selam 1.2 - SMS'siz cihaz hesabı, güvenli rehber eşleştirme ve birebir mesajlaşma
create extension if not exists pgcrypto with schema extensions;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table if not exists private.app_secrets (
  name text primary key,
  secret text not null
);
revoke all on private.app_secrets from public, anon, authenticated;

insert into private.app_secrets (name, secret)
values ('phone_pepper', encode(extensions.gen_random_bytes(32), 'hex'))
on conflict (name) do nothing;

create or replace function private.phone_digest(phone_e164 text)
returns bytea
language sql
security definer
set search_path = ''
stable
as $$
  select extensions.hmac(
    phone_e164,
    (select s.secret from private.app_secrets s where s.name = 'phone_pepper'),
    'sha256'
  );
$$;
revoke all on function private.phone_digest(text) from public, anon, authenticated;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null,
  display_name text not null,
  phone_hash bytea,
  phone_last4 text,
  safety_code text not null default upper(encode(extensions.gen_random_bytes(8), 'hex')),
  created_at timestamptz not null default now(),
  constraint username_format check (username ~ '^[a-z0-9_.]{3,24}$'),
  constraint phone_last4_format check (phone_last4 is null or phone_last4 ~ '^[0-9]{4}$')
);

alter table public.profiles add column if not exists phone_hash bytea;
alter table public.profiles add column if not exists phone_last4 text;
alter table public.profiles add column if not exists safety_code text;
update public.profiles
set safety_code = upper(encode(extensions.gen_random_bytes(8), 'hex'))
where safety_code is null;
alter table public.profiles alter column safety_code
  set default upper(encode(extensions.gen_random_bytes(8), 'hex'));
alter table public.profiles alter column safety_code set not null;

create unique index if not exists profiles_username_lower_unique
  on public.profiles (lower(username));
create unique index if not exists profiles_phone_hash_unique
  on public.profiles (phone_hash) where phone_hash is not null;
create unique index if not exists profiles_safety_code_unique
  on public.profiles (safety_code);

create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null default 'direct' check (kind in ('direct')),
  created_at timestamptz not null default now()
);

create table if not exists public.conversation_members (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create table if not exists public.messages (
  id bigint generated always as identity primary key,
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 4000),
  created_at timestamptz not null default now()
);

create index if not exists conversation_members_user_idx
  on public.conversation_members (user_id, conversation_id);
create index if not exists messages_chat_time_idx
  on public.messages (conversation_id, created_at, id);

alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.conversation_members enable row level security;
alter table public.messages enable row level security;

revoke all on public.profiles from anon, authenticated;
revoke all on public.conversations from anon, authenticated;
revoke all on public.conversation_members from anon, authenticated;
revoke all on public.messages from anon, authenticated;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  requested_username text;
  safe_username text;
begin
  requested_username := lower(coalesce(
    nullif(trim(new.raw_user_meta_data ->> 'username'), ''),
    nullif(split_part(coalesce(new.email, ''), '@', 1), ''),
    'selam_' || substr(replace(new.id::text, '-', ''), 1, 8)
  ));
  safe_username := regexp_replace(requested_username, '[^a-z0-9_.]', '', 'g');
  if safe_username is null or char_length(safe_username) < 3 then
    safe_username := 'selam_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;
  safe_username := left(safe_username, 24);
  if exists (select 1 from public.profiles p where lower(p.username) = safe_username) then
    safe_username := left(safe_username, 15) || '_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;

  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    safe_username,
    coalesce(nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''), 'Selam kullanıcısı')
  );
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_user();
revoke all on function public.handle_new_user() from public, anon, authenticated;

create or replace function public.get_my_profile()
returns table (
  username text,
  display_name text,
  phone_last4 text,
  safety_code text,
  profile_ready boolean
)
language plpgsql
security definer
set search_path = ''
stable
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  return query
  select p.username, p.display_name, p.phone_last4, p.safety_code,
         p.phone_hash is not null
  from public.profiles p
  where p.id = auth.uid();
end;
$$;

create or replace function public.setup_profile(
  new_display_name text,
  phone_e164 text
)
returns table (
  username text,
  display_name text,
  phone_last4 text,
  safety_code text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  clean_name text := trim(new_display_name);
  clean_phone text := trim(phone_e164);
  wanted_hash bytea;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if char_length(clean_name) not between 2 and 60 then
    raise exception 'Ad 2-60 karakter arasında olmalı';
  end if;
  if clean_phone !~ '^\+[1-9][0-9]{7,14}$' then
    raise exception 'Telefon numarası geçersiz';
  end if;

  wanted_hash := private.phone_digest(clean_phone);
  if exists (
    select 1 from public.profiles p
    where p.phone_hash = wanted_hash and p.id <> current_user_id
  ) then
    raise exception 'Bu telefon numarası başka bir Selam cihaz hesabında kayıtlı';
  end if;

  update public.profiles p
  set display_name = clean_name,
      phone_hash = wanted_hash,
      phone_last4 = right(clean_phone, 4)
  where p.id = current_user_id;

  return query
  select p.username, p.display_name, p.phone_last4, p.safety_code
  from public.profiles p
  where p.id = current_user_id;
end;
$$;

create or replace function public.match_contacts(contact_phones text[])
returns table (
  user_id uuid,
  username text,
  display_name text,
  matched_phone text
)
language plpgsql
security definer
set search_path = ''
stable
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if coalesce(cardinality(contact_phones), 0) > 2000 then
    raise exception 'En fazla 2000 numara eşleştirilebilir';
  end if;

  return query
  with input_numbers as (
    select distinct trim(n.phone) as phone
    from unnest(coalesce(contact_phones, array[]::text[])) as n(phone)
    where trim(n.phone) ~ '^\+[1-9][0-9]{7,14}$'
  )
  select p.id, p.username, p.display_name, n.phone
  from input_numbers n
  join public.profiles p
    on p.phone_hash = private.phone_digest(n.phone)
  where p.id <> auth.uid()
  order by p.display_name;
end;
$$;

create or replace function public.find_invite(invite_code text)
returns table (user_id uuid, username text, display_name text)
language plpgsql
security definer
set search_path = ''
stable
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  return query
  select p.id, p.username, p.display_name
  from public.profiles p
  where p.safety_code = upper(trim(invite_code))
    and p.phone_hash is not null
    and p.id <> auth.uid()
  limit 1;
end;
$$;

create or replace function public.search_people(search_text text)
returns table (user_id uuid, username text, display_name text)
language sql
security definer
set search_path = ''
stable
as $$
  select p.id, p.username, p.display_name
  from public.profiles p
  where auth.uid() is not null
    and p.id <> auth.uid()
    and p.phone_hash is not null
    and char_length(trim(search_text)) >= 2
    and (
      p.username ilike '%' || trim(search_text) || '%'
      or p.display_name ilike '%' || trim(search_text) || '%'
    )
  order by case when lower(p.username) = lower(trim(search_text)) then 0 else 1 end,
           p.display_name
  limit 30;
$$;

create or replace function public.start_direct_chat(other_user_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  chat_id uuid;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if other_user_id = current_user_id then raise exception 'Kendinizle sohbet başlatamazsınız'; end if;
  if not exists (
    select 1 from public.profiles p
    where p.id = current_user_id and p.phone_hash is not null
  ) then raise exception 'Önce cihaz profilinizi tamamlayın'; end if;
  if not exists (
    select 1 from public.profiles p
    where p.id = other_user_id and p.phone_hash is not null
  ) then raise exception 'Kullanıcı bulunamadı'; end if;

  select c.id into chat_id
  from public.conversations c
  where c.kind = 'direct'
    and exists (
      select 1 from public.conversation_members m
      where m.conversation_id = c.id and m.user_id = current_user_id
    )
    and exists (
      select 1 from public.conversation_members m
      where m.conversation_id = c.id and m.user_id = other_user_id
    )
    and 2 = (
      select count(*) from public.conversation_members m
      where m.conversation_id = c.id
    )
  limit 1;

  if chat_id is null then
    insert into public.conversations default values returning id into chat_id;
    insert into public.conversation_members (conversation_id, user_id)
    values (chat_id, current_user_id), (chat_id, other_user_id);
  end if;
  return chat_id;
end;
$$;

create or replace function public.list_my_chats()
returns table (
  conversation_id uuid,
  other_user_id uuid,
  username text,
  display_name text,
  last_message text,
  last_message_at timestamptz
)
language sql
security definer
set search_path = ''
stable
as $$
  select c.id,
         other_profile.id,
         other_profile.username,
         other_profile.display_name,
         last_message.body,
         last_message.created_at
  from public.conversations c
  join public.conversation_members mine
    on mine.conversation_id = c.id and mine.user_id = auth.uid()
  join public.conversation_members other_member
    on other_member.conversation_id = c.id and other_member.user_id <> auth.uid()
  join public.profiles other_profile on other_profile.id = other_member.user_id
  left join lateral (
    select m.body, m.created_at
    from public.messages m
    where m.conversation_id = c.id
    order by m.created_at desc, m.id desc
    limit 1
  ) last_message on true
  where auth.uid() is not null and c.kind = 'direct'
  order by coalesce(last_message.created_at, c.created_at) desc;
$$;

create or replace function public.list_chat_messages(chat_id uuid)
returns table (
  message_id bigint,
  sender_id uuid,
  message_body text,
  created_at timestamptz
)
language plpgsql
security definer
set search_path = ''
stable
as $$
begin
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbete erişiminiz yok'; end if;

  return query
  select m.id, m.sender_id, m.body, m.created_at
  from public.messages m
  where m.conversation_id = chat_id
  order by m.created_at asc, m.id asc
  limit 500;
end;
$$;

create or replace function public.send_chat_message(chat_id uuid, message_body text)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  new_id bigint;
  clean_body text := trim(message_body);
begin
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbete mesaj gönderemezsiniz'; end if;
  if char_length(clean_body) not between 1 and 4000 then
    raise exception 'Mesaj 1-4000 karakter arasında olmalı';
  end if;

  insert into public.messages (conversation_id, sender_id, body)
  values (chat_id, auth.uid(), clean_body)
  returning id into new_id;
  return new_id;
end;
$$;

revoke all on function public.get_my_profile() from public, anon, authenticated;
revoke all on function public.setup_profile(text, text) from public, anon, authenticated;
revoke all on function public.match_contacts(text[]) from public, anon, authenticated;
revoke all on function public.find_invite(text) from public, anon, authenticated;
revoke all on function public.search_people(text) from public, anon, authenticated;
revoke all on function public.start_direct_chat(uuid) from public, anon, authenticated;
revoke all on function public.list_my_chats() from public, anon, authenticated;
revoke all on function public.list_chat_messages(uuid) from public, anon, authenticated;
revoke all on function public.send_chat_message(uuid, text) from public, anon, authenticated;

grant execute on function public.get_my_profile() to authenticated;
grant execute on function public.setup_profile(text, text) to authenticated;
grant execute on function public.match_contacts(text[]) to authenticated;
grant execute on function public.find_invite(text) to authenticated;
grant execute on function public.search_people(text) to authenticated;
grant execute on function public.start_direct_chat(uuid) to authenticated;
grant execute on function public.list_my_chats() to authenticated;
grant execute on function public.list_chat_messages(uuid) to authenticated;
grant execute on function public.send_chat_message(uuid, text) to authenticated;
