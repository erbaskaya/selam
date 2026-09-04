-- Selam 1.4 - durumlar, topluluklar, arama geçmişi, ayarlar ve sohbet düzeni

alter table public.profiles
  add column if not exists about text not null default 'Selam kullanıyorum.';
alter table public.profiles
  add column if not exists last_seen_at timestamptz not null default clock_timestamp();

alter table public.conversation_user_states
  add column if not exists archived boolean not null default false;
alter table public.conversation_user_states
  add column if not exists pinned boolean not null default false;
alter table public.conversation_user_states
  add column if not exists muted_until timestamptz;

create table if not exists public.user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  read_receipts boolean not null default true,
  show_last_seen boolean not null default true,
  notifications_enabled boolean not null default true,
  call_notifications_enabled boolean not null default true,
  compact_mode boolean not null default false,
  updated_at timestamptz not null default clock_timestamp()
);
alter table public.user_settings enable row level security;
revoke all on public.user_settings from anon, authenticated;

create table if not exists public.status_updates (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  body text not null check (char_length(trim(body)) between 1 and 700),
  background_color text not null default '#1969E6'
    check (background_color ~ '^#[0-9A-Fa-f]{6}$'),
  created_at timestamptz not null default clock_timestamp(),
  expires_at timestamptz not null default (clock_timestamp() + interval '24 hours')
);
create index if not exists status_updates_user_created_idx
  on public.status_updates (user_id, created_at desc);
create index if not exists status_updates_expires_idx
  on public.status_updates (expires_at);
alter table public.status_updates enable row level security;
revoke all on public.status_updates from anon, authenticated;

create table if not exists public.communities (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(trim(name)) between 2 and 60),
  description text not null default '' check (char_length(description) <= 300),
  owner_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default clock_timestamp()
);
create index if not exists communities_owner_idx on public.communities (owner_id);
alter table public.communities enable row level security;
revoke all on public.communities from anon, authenticated;

create table if not exists public.community_members (
  community_id uuid not null references public.communities(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'admin', 'member')),
  joined_at timestamptz not null default clock_timestamp(),
  primary key (community_id, user_id)
);
create index if not exists community_members_user_idx
  on public.community_members (user_id, community_id);
alter table public.community_members enable row level security;
revoke all on public.community_members from anon, authenticated;

create table if not exists public.call_events (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  caller_id uuid not null references auth.users(id) on delete cascade,
  mode text not null check (mode in ('voice', 'video')),
  state text not null default 'active' check (state in ('active', 'ended', 'missed')),
  room_name text not null unique,
  started_at timestamptz not null default clock_timestamp(),
  ended_at timestamptz
);
create index if not exists call_events_conversation_started_idx
  on public.call_events (conversation_id, started_at desc);
create index if not exists call_events_caller_idx on public.call_events (caller_id);
alter table public.call_events enable row level security;
revoke all on public.call_events from anon, authenticated;

create or replace function public.get_my_settings()
returns table (
  display_name text, about text, read_receipts boolean,
  show_last_seen boolean, notifications_enabled boolean,
  call_notifications_enabled boolean, compact_mode boolean
)
language plpgsql security definer set search_path = '' stable
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  return query
  select p.display_name, p.about,
         coalesce(s.read_receipts, true), coalesce(s.show_last_seen, true),
         coalesce(s.notifications_enabled, true),
         coalesce(s.call_notifications_enabled, true),
         coalesce(s.compact_mode, false)
  from public.profiles p
  left join public.user_settings s on s.user_id = p.id
  where p.id = auth.uid();
end;
$$;

create or replace function public.update_my_settings(
  new_display_name text, new_about text, new_read_receipts boolean,
  new_show_last_seen boolean, new_notifications_enabled boolean,
  new_call_notifications_enabled boolean, new_compact_mode boolean
)
returns boolean
language plpgsql security definer set search_path = ''
as $$
declare
  clean_name text := trim(new_display_name);
  clean_about text := trim(new_about);
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if char_length(clean_name) not between 2 and 60 then
    raise exception 'Ad 2-60 karakter arasında olmalı';
  end if;
  if char_length(clean_about) > 140 then
    raise exception 'Hakkımda en fazla 140 karakter olabilir';
  end if;
  update public.profiles
  set display_name = clean_name,
      about = coalesce(nullif(clean_about, ''), 'Selam kullanıyorum.'),
      last_seen_at = clock_timestamp()
  where id = auth.uid();
  insert into public.user_settings (
    user_id, read_receipts, show_last_seen, notifications_enabled,
    call_notifications_enabled, compact_mode, updated_at
  ) values (
    auth.uid(), coalesce(new_read_receipts, true),
    coalesce(new_show_last_seen, true),
    coalesce(new_notifications_enabled, true),
    coalesce(new_call_notifications_enabled, true),
    coalesce(new_compact_mode, false), clock_timestamp()
  )
  on conflict (user_id) do update set
    read_receipts = excluded.read_receipts,
    show_last_seen = excluded.show_last_seen,
    notifications_enabled = excluded.notifications_enabled,
    call_notifications_enabled = excluded.call_notifications_enabled,
    compact_mode = excluded.compact_mode,
    updated_at = excluded.updated_at;
  return true;
end;
$$;

create or replace function public.create_status(
  status_body text, status_color text default '#1969E6'
)
returns bigint
language plpgsql security definer set search_path = ''
as $$
declare new_id bigint;
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if char_length(trim(status_body)) not between 1 and 700 then
    raise exception 'Durum 1-700 karakter arasında olmalı';
  end if;
  if status_color !~ '^#[0-9A-Fa-f]{6}$' then
    raise exception 'Durum rengi geçersiz';
  end if;
  insert into public.status_updates(user_id, body, background_color)
  values (auth.uid(), trim(status_body), status_color)
  returning id into new_id;
  return new_id;
end;
$$;

create or replace function public.list_visible_statuses()
returns table (
  status_id bigint, user_id uuid, display_name text, status_body text,
  background_color text, created_at timestamptz, is_mine boolean
)
language sql security definer set search_path = '' stable
as $$
  select s.id, s.user_id, p.display_name, s.body, s.background_color,
         s.created_at, s.user_id = auth.uid()
  from public.status_updates s
  join public.profiles p on p.id = s.user_id
  where auth.uid() is not null
    and s.expires_at > clock_timestamp()
    and (
      s.user_id = auth.uid()
      or exists (
        select 1
        from public.conversation_members mine
        join public.conversation_members theirs
          on theirs.conversation_id = mine.conversation_id
        where mine.user_id = auth.uid()
          and theirs.user_id = s.user_id
      )
    )
  order by (s.user_id = auth.uid()) desc, s.created_at desc;
$$;

create or replace function public.delete_my_status(status_id bigint)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  delete from public.status_updates s
  where s.id = status_id and s.user_id = auth.uid();
  if not found then raise exception 'Durum bulunamadı'; end if;
  return true;
end;
$$;

create or replace function public.create_community(
  community_name text, community_description text default ''
)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare new_id uuid; clean_name text := trim(community_name);
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if char_length(clean_name) not between 2 and 60 then
    raise exception 'Topluluk adı 2-60 karakter arasında olmalı';
  end if;
  if char_length(trim(community_description)) > 300 then
    raise exception 'Açıklama en fazla 300 karakter olabilir';
  end if;
  insert into public.communities(name, description, owner_id)
  values (clean_name, trim(community_description), auth.uid())
  returning id into new_id;
  insert into public.community_members(community_id, user_id, role)
  values (new_id, auth.uid(), 'owner');
  return new_id;
end;
$$;

create or replace function public.list_my_communities()
returns table (
  community_id uuid, community_name text, community_description text,
  member_count bigint, my_role text, created_at timestamptz
)
language sql security definer set search_path = '' stable
as $$
  select c.id, c.name, c.description,
         (select count(*) from public.community_members all_members
          where all_members.community_id = c.id),
         mine.role, c.created_at
  from public.communities c
  join public.community_members mine
    on mine.community_id = c.id and mine.user_id = auth.uid()
  where auth.uid() is not null
  order by c.created_at desc;
$$;

create or replace function public.start_call(chat_id uuid, call_mode text)
returns table (call_id uuid, room_name text)
language plpgsql security definer set search_path = ''
as $$
declare new_id uuid; new_room text;
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if call_mode not in ('voice', 'video') then raise exception 'Arama türü geçersiz'; end if;
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbetten arama başlatamazsınız'; end if;
  new_id := gen_random_uuid();
  new_room := 'selam-' || replace(new_id::text, '-', '');
  insert into public.call_events(id, conversation_id, caller_id, mode, room_name)
  values (new_id, chat_id, auth.uid(), call_mode, new_room);
  return query select new_id, new_room;
end;
$$;

create or replace function public.list_my_calls()
returns table (
  call_id uuid, conversation_id uuid, caller_id uuid, display_name text,
  call_mode text, call_state text, room_name text,
  started_at timestamptz, ended_at timestamptz, is_outgoing boolean
)
language sql security definer set search_path = '' stable
as $$
  select ce.id, ce.conversation_id, ce.caller_id,
         case
           when ce.caller_id = auth.uid() then coalesce(other_person.display_name, 'Grup araması')
           else caller.display_name
         end,
         ce.mode, ce.state, ce.room_name, ce.started_at, ce.ended_at,
         ce.caller_id = auth.uid()
  from public.call_events ce
  join public.conversation_members mine
    on mine.conversation_id = ce.conversation_id and mine.user_id = auth.uid()
  join public.profiles caller on caller.id = ce.caller_id
  left join lateral (
    select p.display_name
    from public.conversation_members cm
    join public.profiles p on p.id = cm.user_id
    where cm.conversation_id = ce.conversation_id
      and cm.user_id <> auth.uid()
    order by p.display_name limit 1
  ) other_person on true
  where auth.uid() is not null
  order by ce.started_at desc limit 100;
$$;

create or replace function public.end_call(selected_call_id uuid)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  update public.call_events ce
  set state = 'ended', ended_at = clock_timestamp()
  where ce.id = selected_call_id and ce.state = 'active'
    and exists (
      select 1 from public.conversation_members m
      where m.conversation_id = ce.conversation_id and m.user_id = auth.uid()
    );
  if not found then raise exception 'Aktif arama bulunamadı'; end if;
  return true;
end;
$$;

create or replace function public.set_chat_state(
  chat_id uuid, new_archived boolean default null,
  new_pinned boolean default null, mute_hours integer default null
)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if not exists (
    select 1 from public.conversation_members m
    where m.conversation_id = chat_id and m.user_id = auth.uid()
  ) then raise exception 'Bu sohbeti değiştiremezsiniz'; end if;
  if mute_hours is not null and mute_hours not between 0 and 8760 then
    raise exception 'Sessize alma süresi geçersiz';
  end if;
  insert into public.conversation_user_states(
    conversation_id, user_id, archived, pinned, muted_until
  ) values (
    chat_id, auth.uid(), coalesce(new_archived, false), coalesce(new_pinned, false),
    case when mute_hours is null then null
         when mute_hours = 0 then null
         else clock_timestamp() + make_interval(hours => mute_hours) end
  )
  on conflict (conversation_id, user_id) do update set
    archived = coalesce(new_archived, public.conversation_user_states.archived),
    pinned = coalesce(new_pinned, public.conversation_user_states.pinned),
    muted_until = case
      when mute_hours is null then public.conversation_user_states.muted_until
      when mute_hours = 0 then null
      else clock_timestamp() + make_interval(hours => mute_hours) end;
  return true;
end;
$$;

drop function if exists public.list_my_chats();
create function public.list_my_chats()
returns table (
  conversation_id uuid, conversation_kind text, other_user_id uuid,
  username text, display_name text, last_message text,
  last_message_at timestamptz, archived boolean, pinned boolean,
  muted_until timestamptz
)
language sql security definer set search_path = '' stable
as $$
  select c.id, c.kind, other_profile.id, coalesce(other_profile.username, ''),
         case when c.kind = 'group' then c.title else other_profile.display_name end,
         case when last_message.message_type = 'file'
              then '📎 ' || coalesce(last_message.file_name, last_message.body)
              else last_message.body end,
         last_message.created_at,
         coalesce(state.archived, false), coalesce(state.pinned, false),
         state.muted_until
  from public.conversations c
  join public.conversation_members mine
    on mine.conversation_id = c.id and mine.user_id = auth.uid()
  left join public.conversation_user_states state
    on state.conversation_id = c.id and state.user_id = auth.uid()
  left join lateral (
    select p.id, p.username, p.display_name
    from public.conversation_members other_member
    join public.profiles p on p.id = other_member.user_id
    where c.kind = 'direct' and other_member.conversation_id = c.id
      and other_member.user_id <> auth.uid()
    limit 1
  ) other_profile on true
  left join lateral (
    select m.body, m.created_at, m.message_type, m.file_name
    from public.messages m where m.conversation_id = c.id
    order by m.created_at desc, m.id desc limit 1
  ) last_message on true
  where auth.uid() is not null
    and (state.cleared_at is null or last_message.created_at > state.cleared_at)
  order by coalesce(state.pinned, false) desc,
           coalesce(last_message.created_at, c.created_at) desc;
$$;

revoke all on function public.get_my_settings() from public, anon, authenticated;
revoke all on function public.update_my_settings(text, text, boolean, boolean, boolean, boolean, boolean) from public, anon, authenticated;
revoke all on function public.create_status(text, text) from public, anon, authenticated;
revoke all on function public.list_visible_statuses() from public, anon, authenticated;
revoke all on function public.delete_my_status(bigint) from public, anon, authenticated;
revoke all on function public.create_community(text, text) from public, anon, authenticated;
revoke all on function public.list_my_communities() from public, anon, authenticated;
revoke all on function public.start_call(uuid, text) from public, anon, authenticated;
revoke all on function public.list_my_calls() from public, anon, authenticated;
revoke all on function public.end_call(uuid) from public, anon, authenticated;
revoke all on function public.set_chat_state(uuid, boolean, boolean, integer) from public, anon, authenticated;
revoke all on function public.list_my_chats() from public, anon, authenticated;

grant execute on function public.get_my_settings() to authenticated;
grant execute on function public.update_my_settings(text, text, boolean, boolean, boolean, boolean, boolean) to authenticated;
grant execute on function public.create_status(text, text) to authenticated;
grant execute on function public.list_visible_statuses() to authenticated;
grant execute on function public.delete_my_status(bigint) to authenticated;
grant execute on function public.create_community(text, text) to authenticated;
grant execute on function public.list_my_communities() to authenticated;
grant execute on function public.start_call(uuid, text) to authenticated;
grant execute on function public.list_my_calls() to authenticated;
grant execute on function public.end_call(uuid) to authenticated;
grant execute on function public.set_chat_state(uuid, boolean, boolean, integer) to authenticated;
grant execute on function public.list_my_chats() to authenticated;
