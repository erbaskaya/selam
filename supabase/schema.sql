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

-- Selam 1.3 - gruplar, kullanıcıya özel sohbet temizleme ve özel dosya mesajları
alter table public.conversations add column if not exists title text;
alter table public.conversations add column if not exists created_by uuid
  references auth.users(id) on delete set null;
alter table public.conversations drop constraint if exists conversations_kind_check;
alter table public.conversations add constraint conversations_kind_check
  check (kind in ('direct', 'group'));
alter table public.conversations drop constraint if exists conversations_group_title_check;
alter table public.conversations add constraint conversations_group_title_check
  check (kind <> 'group' or char_length(trim(title)) between 2 and 60);
create index if not exists conversations_created_by_idx
  on public.conversations (created_by) where created_by is not null;

alter table public.conversation_members add column if not exists role text
  not null default 'member';
alter table public.conversation_members drop constraint if exists conversation_members_role_check;
alter table public.conversation_members add constraint conversation_members_role_check
  check (role in ('owner', 'admin', 'member'));

create table if not exists public.conversation_user_states (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  cleared_at timestamptz,
  primary key (conversation_id, user_id)
);
create index if not exists conversation_user_states_user_idx
  on public.conversation_user_states (user_id, conversation_id);
alter table public.conversation_user_states enable row level security;
revoke all on public.conversation_user_states from anon, authenticated;

alter table public.messages add column if not exists message_type text
  not null default 'text';
alter table public.messages add column if not exists file_path text;
alter table public.messages add column if not exists file_name text;
alter table public.messages add column if not exists file_mime_type text;
alter table public.messages add column if not exists file_size_bytes bigint;
alter table public.messages drop constraint if exists messages_type_check;
alter table public.messages add constraint messages_type_check
  check (message_type in ('text', 'file'));
alter table public.messages drop constraint if exists messages_file_fields_check;
alter table public.messages add constraint messages_file_fields_check check (
  (message_type = 'text' and file_path is null)
  or
  (message_type = 'file'
    and file_path is not null
    and file_name is not null
    and file_size_bytes between 1 and 10485760)
);
create unique index if not exists messages_file_path_unique
  on public.messages (file_path) where file_path is not null;

insert into storage.buckets (id, name, public, file_size_limit)
values ('chat-files', 'chat-files', false, 10485760)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit;

create or replace function private.can_access_chat_file(object_name text)
returns boolean
language plpgsql
security definer
set search_path = ''
stable
as $$
declare
  chat_text text := split_part(object_name, '/', 1);
  chat_uuid uuid;
begin
  if auth.uid() is null
     or chat_text !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' then
    return false;
  end if;
  chat_uuid := chat_text::uuid;
  return exists (
    select 1
    from public.conversation_members m
    where m.conversation_id = chat_uuid
      and m.user_id = auth.uid()
  );
end;
$$;
revoke all on function private.can_access_chat_file(text) from public, anon, authenticated;
grant usage on schema private to authenticated;
grant execute on function private.can_access_chat_file(text) to authenticated;

drop policy if exists "Selam chat files read" on storage.objects;
create policy "Selam chat files read"
on storage.objects for select to authenticated
using (
  bucket_id = 'chat-files'
  and (select private.can_access_chat_file(name))
);

drop policy if exists "Selam chat files upload" on storage.objects;
create policy "Selam chat files upload"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'chat-files'
  and (select private.can_access_chat_file(name))
  and (storage.foldername(name))[2] = (select auth.uid())::text
);

drop policy if exists "Selam chat files delete own" on storage.objects;
create policy "Selam chat files delete own"
on storage.objects for delete to authenticated
using (
  bucket_id = 'chat-files'
  and (select private.can_access_chat_file(name))
  and (storage.foldername(name))[2] = (select auth.uid())::text
);

create or replace function public.create_group(group_name text, member_user_ids uuid[])
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  clean_name text := trim(group_name);
  clean_members uuid[];
  chat_id uuid;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if char_length(clean_name) not between 2 and 60 then
    raise exception 'Grup adı 2-60 karakter arasında olmalı';
  end if;

  select coalesce(array_agg(distinct member_id), array[]::uuid[])
  into clean_members
  from unnest(coalesce(member_user_ids, array[]::uuid[])) member_id
  where member_id <> current_user_id;

  if cardinality(clean_members) not between 1 and 49 then
    raise exception 'Gruba 1-49 kişi ekleyebilirsiniz';
  end if;
  if (select count(*) from public.profiles p
      where p.id = any(clean_members) and p.phone_hash is not null)
      <> cardinality(clean_members) then
    raise exception 'Grup üyelerinden biri bulunamadı';
  end if;

  insert into public.conversations (kind, title, created_by)
  values ('group', clean_name, current_user_id)
  returning id into chat_id;

  insert into public.conversation_members (conversation_id, user_id, role)
  values (chat_id, current_user_id, 'owner');
  insert into public.conversation_members (conversation_id, user_id, role)
  select chat_id, member_id, 'member'
  from unnest(clean_members) member_id;

  return chat_id;
end;
$$;

create or replace function public.delete_chat(chat_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbeti silemezsiniz'; end if;

  insert into public.conversation_user_states (conversation_id, user_id, cleared_at)
  values (chat_id, auth.uid(), clock_timestamp())
  on conflict (conversation_id, user_id)
  do update set cleared_at = excluded.cleared_at;
  return true;
end;
$$;

drop function if exists public.list_my_chats();
create function public.list_my_chats()
returns table (
  conversation_id uuid,
  conversation_kind text,
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
         c.kind,
         other_profile.id,
         coalesce(other_profile.username, ''),
         case when c.kind = 'group' then c.title else other_profile.display_name end,
         case when last_message.message_type = 'file'
              then '📎 ' || coalesce(last_message.file_name, last_message.body)
              else last_message.body end,
         last_message.created_at
  from public.conversations c
  join public.conversation_members mine
    on mine.conversation_id = c.id and mine.user_id = auth.uid()
  left join public.conversation_user_states state
    on state.conversation_id = c.id and state.user_id = auth.uid()
  left join lateral (
    select p.id, p.username, p.display_name
    from public.conversation_members other_member
    join public.profiles p on p.id = other_member.user_id
    where c.kind = 'direct'
      and other_member.conversation_id = c.id
      and other_member.user_id <> auth.uid()
    limit 1
  ) other_profile on true
  left join lateral (
    select m.body, m.created_at, m.message_type, m.file_name
    from public.messages m
    where m.conversation_id = c.id
    order by m.created_at desc, m.id desc
    limit 1
  ) last_message on true
  where auth.uid() is not null
    and (state.cleared_at is null or last_message.created_at > state.cleared_at)
  order by coalesce(last_message.created_at, c.created_at) desc;
$$;

drop function if exists public.list_chat_messages(uuid);
create function public.list_chat_messages(chat_id uuid)
returns table (
  message_id bigint,
  sender_id uuid,
  message_body text,
  created_at timestamptz,
  message_type text,
  file_path text,
  file_name text,
  file_mime_type text,
  file_size_bytes bigint
)
language plpgsql
security definer
set search_path = ''
stable
as $$
declare
  visible_after timestamptz;
begin
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbete erişiminiz yok'; end if;

  select s.cleared_at into visible_after
  from public.conversation_user_states s
  where s.conversation_id = chat_id and s.user_id = auth.uid();

  return query
  select m.id, m.sender_id, m.body, m.created_at, m.message_type,
         m.file_path, m.file_name, m.file_mime_type, m.file_size_bytes
  from public.messages m
  where m.conversation_id = chat_id
    and (visible_after is null or m.created_at > visible_after)
  order by m.created_at asc, m.id asc
  limit 500;
end;
$$;

create or replace function public.send_chat_file(
  chat_id uuid,
  uploaded_file_path text,
  uploaded_file_name text,
  uploaded_mime_type text,
  uploaded_size_bytes bigint
)
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  clean_path text := trim(uploaded_file_path);
  clean_name text := trim(uploaded_file_name);
  new_id bigint;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = current_user_id
  ) then raise exception 'Bu sohbete dosya gönderemezsiniz'; end if;
  if char_length(clean_name) not between 1 and 255 then
    raise exception 'Dosya adı geçersiz';
  end if;
  if uploaded_size_bytes not between 1 and 10485760 then
    raise exception 'Dosya en fazla 10 MB olabilir';
  end if;
  if clean_path not like chat_id::text || '/' || current_user_id::text || '/%' then
    raise exception 'Dosya yolu geçersiz';
  end if;
  if not exists (
    select 1 from storage.objects o
    where o.bucket_id = 'chat-files'
      and o.name = clean_path
      and o.owner_id = current_user_id::text
  ) then raise exception 'Yüklenen dosya bulunamadı'; end if;

  insert into public.messages (
    conversation_id, sender_id, body, message_type,
    file_path, file_name, file_mime_type, file_size_bytes
  ) values (
    chat_id, current_user_id, clean_name, 'file', clean_path, clean_name,
    nullif(trim(uploaded_mime_type), ''), uploaded_size_bytes
  ) returning id into new_id;
  return new_id;
end;
$$;

revoke all on function public.create_group(text, uuid[]) from public, anon, authenticated;
revoke all on function public.delete_chat(uuid) from public, anon, authenticated;
revoke all on function public.list_my_chats() from public, anon, authenticated;
revoke all on function public.list_chat_messages(uuid) from public, anon, authenticated;
revoke all on function public.send_chat_file(uuid, text, text, text, bigint) from public, anon, authenticated;

grant execute on function public.create_group(text, uuid[]) to authenticated;
grant execute on function public.delete_chat(uuid) to authenticated;
grant execute on function public.list_my_chats() to authenticated;
grant execute on function public.list_chat_messages(uuid) to authenticated;
grant execute on function public.send_chat_file(uuid, text, text, text, bigint) to authenticated;

-- Selam 1.4.1 recovery PIN support is defined in:
-- supabase/migrations/20260904081500_add_recovery_pin.sql
