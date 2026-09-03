-- Selam 1.1 - güvenli birebir mesajlaşma veritabanı
create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null,
  display_name text not null,
  created_at timestamptz not null default now(),
  constraint username_format check (username ~ '^[a-z0-9_.]{3,24}$')
);

create unique index if not exists profiles_username_lower_unique
  on public.profiles (lower(username));

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
security definer set search_path = ''
as $$
declare
  requested_username text;
  safe_username text;
begin
  requested_username := lower(coalesce(new.raw_user_meta_data ->> 'username', split_part(new.email, '@', 1)));
  safe_username := regexp_replace(requested_username, '[^a-z0-9_.]', '', 'g');
  if char_length(safe_username) < 3 then
    safe_username := 'selam_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;
  safe_username := left(safe_username, 24);
  if exists (select 1 from public.profiles where lower(username) = safe_username) then
    safe_username := left(safe_username, 15) || '_' || substr(replace(new.id::text, '-', ''), 1, 8);
  end if;

  insert into public.profiles (id, username, display_name)
  values (
    new.id,
    safe_username,
    coalesce(nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''), safe_username)
  );
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute procedure public.handle_new_user();

revoke all on function public.handle_new_user() from anon, authenticated, public;

create or replace function public.search_people(search_text text)
returns table (user_id uuid, username text, display_name text)
language sql
security definer set search_path = ''
stable
as $$
  select p.id, p.username, p.display_name
  from public.profiles p
  where auth.uid() is not null
    and p.id <> auth.uid()
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
security definer set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  chat_id uuid;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if other_user_id = current_user_id then raise exception 'Kendinizle sohbet başlatamazsınız'; end if;
  if not exists (select 1 from public.profiles where id = other_user_id) then
    raise exception 'Kullanıcı bulunamadı';
  end if;

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
    and 2 = (select count(*) from public.conversation_members m where m.conversation_id = c.id)
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
security definer set search_path = ''
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
returns table (message_id bigint, sender_id uuid, message_body text, created_at timestamptz)
language plpgsql
security definer set search_path = ''
stable
as $$
begin
  if not exists (
    select 1 from public.conversation_members
    where conversation_id = chat_id and user_id = auth.uid()
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
security definer set search_path = ''
as $$
declare
  new_id bigint;
  clean_body text := trim(message_body);
begin
  if not exists (
    select 1 from public.conversation_members
    where conversation_id = chat_id and user_id = auth.uid()
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

revoke all on function public.search_people(text) from anon, authenticated, public;
revoke all on function public.start_direct_chat(uuid) from anon, authenticated, public;
revoke all on function public.list_my_chats() from anon, authenticated, public;
revoke all on function public.list_chat_messages(uuid) from anon, authenticated, public;
revoke all on function public.send_chat_message(uuid, text) from anon, authenticated, public;

grant execute on function public.search_people(text) to authenticated;
grant execute on function public.start_direct_chat(uuid) to authenticated;
grant execute on function public.list_my_chats() to authenticated;
grant execute on function public.list_chat_messages(uuid) to authenticated;
grant execute on function public.send_chat_message(uuid, text) to authenticated;
