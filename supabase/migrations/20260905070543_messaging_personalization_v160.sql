-- Additive upgrade; existing Android/iOS RPC signatures are preserved.
alter table public.messages add column if not exists reply_to_id bigint references public.messages(id) on delete set null;
alter table public.messages add column if not exists edited_at timestamptz;
alter table public.messages add column if not exists deleted_at timestamptz;
create index if not exists messages_reply_to_idx on public.messages(reply_to_id) where reply_to_id is not null;
alter table public.conversation_user_states add column if not exists last_read_id bigint not null default 0;
alter table public.conversation_user_states add column if not exists favorite boolean not null default false;
alter table public.user_settings add column if not exists personalization jsonb not null default '{}'::jsonb;
create table if not exists public.message_user_states (
  user_id uuid not null references auth.users(id) on delete cascade,
  message_id bigint not null references public.messages(id) on delete cascade,
  starred boolean not null default false, hidden boolean not null default false,
  primary key(user_id, message_id)
);
create index if not exists message_user_states_message_idx on public.message_user_states(message_id);
create table if not exists public.message_reactions (
  message_id bigint not null references public.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  emoji text not null check (emoji in ('👍','❤️','😂','😮','😢','🙏')),
  primary key(message_id,user_id)
);
create index if not exists message_reactions_user_idx on public.message_reactions(user_id);
alter table public.message_user_states enable row level security;
alter table public.message_reactions enable row level security;
revoke all on public.message_user_states, public.message_reactions from public, anon, authenticated;

create or replace function private.selam_visible_message(p_message_id bigint) returns boolean
language sql stable security definer set search_path = '' as $$
 select exists (
   select 1 from public.messages m
   join public.conversation_members cm on cm.conversation_id=m.conversation_id and cm.user_id=auth.uid()
   left join public.conversation_user_states cs on cs.conversation_id=m.conversation_id and cs.user_id=auth.uid()
   left join public.message_user_states us on us.message_id=m.id and us.user_id=auth.uid()
   where m.id=p_message_id and auth.uid() is not null and not coalesce(us.hidden,false)
     and (cs.cleared_at is null or m.created_at>cs.cleared_at)
 );
$$;

create or replace function private.selam_messages(p_chat_id uuid, p_query text default '', p_starred boolean default false, p_before bigint default null)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
 if auth.uid() is null or not exists(select 1 from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid()) then
   raise exception 'Bu sohbete erişiminiz yok'; end if;
 return coalesce((select jsonb_agg(to_jsonb(items) order by items.message_id) from (
  select m.id message_id,m.sender_id,m.body message_body,m.created_at,m.message_type,m.file_path,m.file_name,m.file_mime_type,m.file_size_bytes,
    p.display_name sender_name,m.edited_at,m.deleted_at,m.reply_to_id,
    case when original.id is not null and private.selam_visible_message(original.id) then left(original.body,180) else null end reply_preview,
    coalesce(us.starred,false) starred,
    coalesce((select string_agg(r.emoji||' '||r.total::text,'  ' order by r.emoji) from
      (select emoji,count(*) total from public.message_reactions where message_id=m.id group by emoji) r),'') reactions,
    case when m.sender_id=auth.uid() and coalesce(own_settings.read_receipts,true) then exists (
      select 1 from public.conversation_members other
      join public.conversation_user_states rs on rs.user_id=other.user_id and rs.conversation_id=other.conversation_id
      left join public.user_settings other_settings on other_settings.user_id=other.user_id
      where other.conversation_id=p_chat_id and other.user_id<>auth.uid() and rs.last_read_id>=m.id and coalesce(other_settings.read_receipts,true)
    ) else false end read_by_other
  from public.messages m join public.profiles p on p.id=m.sender_id
  left join public.message_user_states us on us.message_id=m.id and us.user_id=auth.uid()
  left join public.conversation_user_states cs on cs.conversation_id=p_chat_id and cs.user_id=auth.uid()
  left join public.user_settings own_settings on own_settings.user_id=auth.uid()
  left join public.messages original on original.id=m.reply_to_id and original.conversation_id=p_chat_id
  where m.conversation_id=p_chat_id and not coalesce(us.hidden,false)
    and (cs.cleared_at is null or m.created_at>cs.cleared_at)
    and (p_before is null or m.id<p_before)
    and (coalesce(p_query,'')='' or strpos(lower(m.body),lower(p_query))>0)
    and (not coalesce(p_starred,false) or (us.starred and m.deleted_at is null))
  order by m.id desc limit 100
 ) items),'[]'::jsonb);
end; $$;

create or replace function private.selam_send(p_chat_id uuid,p_body text,p_reply_to bigint default null)
returns bigint language plpgsql security definer set search_path = '' as $$
declare new_id bigint;
begin
 if auth.uid() is null or not exists(select 1 from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid()) then
   raise exception 'Bu sohbete mesaj gönderemezsiniz'; end if;
 if coalesce(char_length(trim(p_body)),0) not between 1 and 4000 then raise exception 'Mesaj 1-4000 karakter olmalı'; end if;
 if p_reply_to is not null and not exists(select 1 from public.messages where id=p_reply_to and conversation_id=p_chat_id and deleted_at is null and private.selam_visible_message(id)) then
   raise exception 'Yanıtlanacak mesaj artık erişilebilir değil'; end if;
 insert into public.messages(conversation_id,sender_id,body,reply_to_id) values(p_chat_id,auth.uid(),trim(p_body),p_reply_to) returning id into new_id;
 return new_id;
end; $$;

create or replace function private.selam_message_action(p_message_id bigint,p_action text,p_value text default '')
returns boolean language plpgsql security definer set search_path = '' as $$
declare msg public.messages; chosen text;
begin
 if not private.selam_visible_message(p_message_id) then raise exception 'Bu mesaja erişiminiz yok'; end if;
 select * into msg from public.messages where id=p_message_id for update;
 if p_action='hide' then
   insert into public.message_user_states(user_id,message_id,hidden) values(auth.uid(),p_message_id,true)
     on conflict(user_id,message_id) do update set hidden=true;
 elsif p_action='star' and msg.deleted_at is null then
   insert into public.message_user_states(user_id,message_id,starred) values(auth.uid(),p_message_id,true)
     on conflict(user_id,message_id) do update set starred=not public.message_user_states.starred;
 elsif p_action='react' and msg.deleted_at is null then
   if p_value not in ('👍','❤️','😂','😮','😢','🙏') then raise exception 'Tepki geçersiz'; end if;
   select emoji into chosen from public.message_reactions where message_id=p_message_id and user_id=auth.uid();
   if chosen=p_value then delete from public.message_reactions where message_id=p_message_id and user_id=auth.uid();
   else insert into public.message_reactions(message_id,user_id,emoji) values(p_message_id,auth.uid(),p_value)
     on conflict(message_id,user_id) do update set emoji=excluded.emoji; end if;
 elsif p_action='edit' and msg.deleted_at is null then
   if msg.sender_id<>auth.uid() or msg.message_type<>'text' or msg.created_at<clock_timestamp()-interval '15 minutes' then
     raise exception 'Yalnızca kendi metin mesajınızı ilk 15 dakikada düzenleyebilirsiniz'; end if;
   if coalesce(char_length(trim(p_value)),0) not between 1 and 4000 then raise exception 'Mesaj 1-4000 karakter olmalı'; end if;
   update public.messages set body=trim(p_value),edited_at=clock_timestamp() where id=p_message_id;
 elsif p_action='delete' and msg.deleted_at is null then
   if msg.sender_id<>auth.uid() then raise exception 'Yalnızca kendi mesajınızı herkesten silebilirsiniz'; end if;
   update public.messages set body='Bu mesaj silindi.',deleted_at=clock_timestamp(),message_type='text',file_path=null,file_name=null,file_mime_type=null,file_size_bytes=null where id=p_message_id;
   delete from public.message_reactions where message_id=p_message_id;
 else raise exception 'İşlem yapılamadı'; end if;
 return true;
end; $$;

create or replace function private.selam_mark_read(p_chat_id uuid,p_message_id bigint)
returns boolean language plpgsql security definer set search_path = '' as $$
begin
 if not exists(select 1 from public.messages where id=p_message_id and conversation_id=p_chat_id and private.selam_visible_message(id)) then raise exception 'Mesaj erişilebilir değil'; end if;
 insert into public.conversation_user_states(conversation_id,user_id,last_read_id) values(p_chat_id,auth.uid(),p_message_id)
 on conflict(conversation_id,user_id) do update set last_read_id=greatest(public.conversation_user_states.last_read_id,excluded.last_read_id);
 return true;
end; $$;

create or replace function private.selam_chats() returns jsonb
language sql stable security definer set search_path = '' as $$
 select coalesce(jsonb_agg(to_jsonb(c)||jsonb_build_object(
   'favorite',coalesce(cs.favorite,false),
   'unread_count',(select count(*) from public.messages m
       left join public.message_user_states ms on ms.message_id=m.id and ms.user_id=auth.uid()
       where m.conversation_id=c.conversation_id and m.sender_id<>auth.uid() and m.deleted_at is null
       and m.id>coalesce(cs.last_read_id,0) and not coalesce(ms.hidden,false)
       and (cs.cleared_at is null or m.created_at>cs.cleared_at)),
   'last_message',coalesce((select m.body from public.messages m
       left join public.message_user_states ms on ms.message_id=m.id and ms.user_id=auth.uid()
       where m.conversation_id=c.conversation_id and not coalesce(ms.hidden,false)
       and (cs.cleared_at is null or m.created_at>cs.cleared_at) order by m.id desc limit 1),''))
   order by c.pinned desc,c.last_message_at desc nulls last),'[]'::jsonb)
 from public.list_my_chats() c left join public.conversation_user_states cs on cs.conversation_id=c.conversation_id and cs.user_id=auth.uid();
$$;

create or replace function private.selam_favorite(p_chat_id uuid,p_favorite boolean) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
 if auth.uid() is null or not exists(select 1 from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid()) then raise exception 'Sohbete erişiminiz yok'; end if;
 insert into public.conversation_user_states(conversation_id,user_id,favorite) values(p_chat_id,auth.uid(),coalesce(p_favorite,false))
 on conflict(conversation_id,user_id) do update set favorite=excluded.favorite;
 return true;
end; $$;

create or replace function private.selam_group_info(p_chat_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
 if auth.uid() is null or not exists(select 1 from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid()) then raise exception 'Sohbete erişiminiz yok'; end if;
 return (select jsonb_build_object('title',c.title,'kind',c.kind,'members',
   (select jsonb_agg(jsonb_build_object('id',p.id,'name',p.display_name,'about',p.about,'role',cm.role,
     'last_seen',case when coalesce(us.show_last_seen,true) and coalesce((select mine.show_last_seen from public.user_settings mine where mine.user_id=auth.uid()),true) then p.last_seen_at else null end) order by cm.joined_at)
   from public.conversation_members cm join public.profiles p on p.id=cm.user_id left join public.user_settings us on us.user_id=p.id where cm.conversation_id=c.id))
 from public.conversations c where c.id=p_chat_id);
end; $$;

create or replace function private.selam_group_action(p_chat_id uuid,p_action text,p_value text default '') returns boolean
language plpgsql security definer set search_path = '' as $$
declare my_role text; target uuid; group_kind text;
begin
 select kind into group_kind from public.conversations where id=p_chat_id for update;
 if group_kind is distinct from 'group' or auth.uid() is null then raise exception 'Grup bulunamadı'; end if;
 select role into my_role from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid();
 if my_role is null then raise exception 'Gruba erişiminiz yok'; end if;
 if p_action='leave' then
   if my_role='owner' then
     select user_id into target from public.conversation_members where conversation_id=p_chat_id and user_id<>auth.uid() order by joined_at limit 1;
     if target is not null then update public.conversation_members set role='owner' where conversation_id=p_chat_id and user_id=target; end if;
   end if;
   delete from public.conversation_members where conversation_id=p_chat_id and user_id=auth.uid();
 elsif my_role in ('owner','admin') then
   if p_action='rename' then
     if coalesce(char_length(trim(p_value)),0) not between 2 and 60 then raise exception 'Grup adı 2-60 karakter olmalı'; end if;
     update public.conversations set title=trim(p_value) where id=p_chat_id;
   elsif p_action='add' then
     target:=p_value::uuid;
     if (select count(*) from public.conversation_members where conversation_id=p_chat_id)>=50 then raise exception 'Grup en fazla 50 kişi olabilir'; end if;
     if not exists(select 1 from public.profiles where id=target and phone_hash is not null) then raise exception 'Kişi bulunamadı'; end if;
     insert into public.conversation_members(conversation_id,user_id) values(p_chat_id,target) on conflict do nothing;
   elsif p_action='remove' then
     target:=p_value::uuid;
     if target=auth.uid() then raise exception 'Gruptan ayrıl seçeneğini kullanın'; end if;
     delete from public.conversation_members where conversation_id=p_chat_id and user_id=target and role<>'owner' and (role='member' or my_role='owner');
     if not found then raise exception 'Bu üyeyi çıkaramazsınız'; end if;
   else raise exception 'Grup işlemi geçersiz'; end if;
 else raise exception 'Bu işlem için grup yöneticisi olmalısınız'; end if;
 return true;
end; $$;

create or replace function private.selam_preferences(p_value jsonb default null) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
 if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
 if p_value is not null then
   if jsonb_typeof(p_value)<>'object' or octet_length(p_value::text)>4096 then raise exception 'Ayarlar geçersiz'; end if;
   insert into public.user_settings(user_id,personalization) values(auth.uid(),p_value)
   on conflict(user_id) do update set personalization=excluded.personalization;
 end if;
 return coalesce((select personalization from public.user_settings where user_id=auth.uid()),'{}'::jsonb);
end; $$;

create or replace function private.selam_presence() returns boolean language plpgsql security definer set search_path = '' as $$
begin
 if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
 update public.profiles set last_seen_at=clock_timestamp() where id=auth.uid();
 return found;
end; $$;

create index if not exists messages_conversation_id_id_idx on public.messages(conversation_id,id desc);

-- Public entry points use invoker privileges; privileged implementation stays private.
grant usage on schema private to authenticated;
revoke all on function private.selam_visible_message(bigint) from public,anon,authenticated;

create or replace function public.selam_messages(p_chat_id uuid, p_query text default '', p_starred boolean default false, p_before bigint default null) returns jsonb
language sql security invoker set search_path = '' as $$ select private.selam_messages(p_chat_id,p_query,p_starred,p_before); $$;
revoke all on function private.selam_messages(uuid,text,boolean,bigint) from public,anon,authenticated;
revoke all on function public.selam_messages(uuid,text,boolean,bigint) from public,anon,authenticated;
grant execute on function private.selam_messages(uuid,text,boolean,bigint) to authenticated;
grant execute on function public.selam_messages(uuid,text,boolean,bigint) to authenticated;

create or replace function public.selam_send(p_chat_id uuid, p_body text, p_reply_to bigint default null) returns bigint
language sql security invoker set search_path = '' as $$ select private.selam_send(p_chat_id,p_body,p_reply_to); $$;
revoke all on function private.selam_send(uuid,text,bigint) from public,anon,authenticated;
revoke all on function public.selam_send(uuid,text,bigint) from public,anon,authenticated;
grant execute on function private.selam_send(uuid,text,bigint) to authenticated;
grant execute on function public.selam_send(uuid,text,bigint) to authenticated;

create or replace function public.selam_message_action(p_message_id bigint, p_action text, p_value text default '') returns boolean
language sql security invoker set search_path = '' as $$ select private.selam_message_action(p_message_id,p_action,p_value); $$;
revoke all on function private.selam_message_action(bigint,text,text) from public,anon,authenticated;
revoke all on function public.selam_message_action(bigint,text,text) from public,anon,authenticated;
grant execute on function private.selam_message_action(bigint,text,text) to authenticated;
grant execute on function public.selam_message_action(bigint,text,text) to authenticated;

create or replace function public.selam_mark_read(p_chat_id uuid, p_message_id bigint) returns boolean
language sql security invoker set search_path = '' as $$ select private.selam_mark_read(p_chat_id,p_message_id); $$;
revoke all on function private.selam_mark_read(uuid,bigint) from public,anon,authenticated;
revoke all on function public.selam_mark_read(uuid,bigint) from public,anon,authenticated;
grant execute on function private.selam_mark_read(uuid,bigint) to authenticated;
grant execute on function public.selam_mark_read(uuid,bigint) to authenticated;

create or replace function public.selam_chats() returns jsonb
language sql security invoker set search_path = '' as $$ select private.selam_chats(); $$;
revoke all on function private.selam_chats() from public,anon,authenticated;
revoke all on function public.selam_chats() from public,anon,authenticated;
grant execute on function private.selam_chats() to authenticated;
grant execute on function public.selam_chats() to authenticated;

create or replace function public.selam_favorite(p_chat_id uuid,p_favorite boolean) returns boolean
language sql security invoker set search_path = '' as $$ select private.selam_favorite(p_chat_id,p_favorite); $$;
revoke all on function private.selam_favorite(uuid,boolean) from public,anon,authenticated;
revoke all on function public.selam_favorite(uuid,boolean) from public,anon,authenticated;
grant execute on function private.selam_favorite(uuid,boolean) to authenticated;
grant execute on function public.selam_favorite(uuid,boolean) to authenticated;

create or replace function public.selam_group_info(p_chat_id uuid) returns jsonb
language sql security invoker set search_path = '' as $$ select private.selam_group_info(p_chat_id); $$;
revoke all on function private.selam_group_info(uuid) from public,anon,authenticated;
revoke all on function public.selam_group_info(uuid) from public,anon,authenticated;
grant execute on function private.selam_group_info(uuid) to authenticated;
grant execute on function public.selam_group_info(uuid) to authenticated;

create or replace function public.selam_group_action(p_chat_id uuid,p_action text,p_value text default '') returns boolean
language sql security invoker set search_path = '' as $$ select private.selam_group_action(p_chat_id,p_action,p_value); $$;
revoke all on function private.selam_group_action(uuid,text,text) from public,anon,authenticated;
revoke all on function public.selam_group_action(uuid,text,text) from public,anon,authenticated;
grant execute on function private.selam_group_action(uuid,text,text) to authenticated;
grant execute on function public.selam_group_action(uuid,text,text) to authenticated;

create or replace function public.selam_preferences(p_value jsonb default null) returns jsonb
language sql security invoker set search_path = '' as $$ select private.selam_preferences(p_value); $$;
revoke all on function private.selam_preferences(jsonb) from public,anon,authenticated;
revoke all on function public.selam_preferences(jsonb) from public,anon,authenticated;
grant execute on function private.selam_preferences(jsonb) to authenticated;
grant execute on function public.selam_preferences(jsonb) to authenticated;

create or replace function public.selam_presence() returns boolean
language sql security invoker set search_path = '' as $$ select private.selam_presence(); $$;
revoke all on function private.selam_presence() from public,anon,authenticated;
revoke all on function public.selam_presence() from public,anon,authenticated;
grant execute on function private.selam_presence() to authenticated;
grant execute on function public.selam_presence() to authenticated;

create or replace function private.selam_notifications(after_message_id bigint default 0)
returns table (
  message_id bigint, conversation_id uuid, sender_id uuid,
  sender_name text, message_preview text, created_at timestamptz
)
language sql security definer set search_path = '' stable
as $$
  select m.id, m.conversation_id, m.sender_id, p.display_name,
         case when m.message_type = 'file'
              then '📎 ' || coalesce(m.file_name, 'Dosya')
              else left(m.body, 180) end,
         m.created_at
  from public.messages m
  join public.conversation_members mine
    on mine.conversation_id = m.conversation_id
   and mine.user_id = auth.uid()
  join public.profiles p on p.id = m.sender_id
  left join public.conversation_user_states state
    on state.conversation_id = m.conversation_id
   and state.user_id = auth.uid()
  left join public.user_settings settings on settings.user_id = auth.uid()
  where auth.uid() is not null
    and m.sender_id <> auth.uid()
    and m.deleted_at is null
    and m.id > coalesce(state.last_read_id,0)
    and not exists(select 1 from public.message_user_states ms where ms.message_id=m.id and ms.user_id=auth.uid() and ms.hidden)
    and m.id > greatest(coalesce(after_message_id, 0), 0)
    and coalesce(settings.notifications_enabled, true)
    and (state.cleared_at is null or m.created_at > state.cleared_at)
    and (state.muted_until is null or state.muted_until <= clock_timestamp())
  order by m.id asc
  limit 50;
$$;


create or replace function public.list_message_notifications(after_message_id bigint default 0)
returns table(message_id bigint,conversation_id uuid,sender_id uuid,sender_name text,message_preview text,created_at timestamptz)
language sql security invoker set search_path = '' as $$ select * from private.selam_notifications(after_message_id); $$;
revoke all on function private.selam_notifications(bigint) from public,anon,authenticated;
revoke all on function public.list_message_notifications(bigint) from public,anon,authenticated;
grant execute on function private.selam_notifications(bigint) to authenticated;
grant execute on function public.list_message_notifications(bigint) to authenticated;

-- Preserve new per-account data when recovery replaces the anonymous device identity.
create or replace function public.recover_profile(
  phone_e164 text,
  recovery_pin text
)
returns table (
  success boolean,
  result_message text,
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
  old_user_id uuid;
  clean_phone text := trim(phone_e164);
  clean_pin text := trim(recovery_pin);
  wanted_hash bytea;
  stored_pin_hash text;
  dummy_pin_hash text;
  requester_attempts integer;
  requester_window timestamptz;
  target_attempts integer;
  target_window timestamptz;
  now_at timestamptz := clock_timestamp();
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if clean_phone !~ '^\+[1-9][0-9]{7,14}$' or clean_pin !~ '^[0-9]{6}$' then
    return query select false, 'Numara veya PIN hatalı', null::text, null::text, null::text, null::text;
    return;
  end if;

  if not exists (
    select 1 from public.profiles p
    where p.id = current_user_id and p.phone_hash is null
  ) then
    return query select false, 'Bu cihazda zaten aktif bir Selam hesabı var', null::text, null::text, null::text, null::text;
    return;
  end if;

  if exists (select 1 from public.conversation_members m where m.user_id = current_user_id)
     or exists (select 1 from public.messages m where m.sender_id = current_user_id)
     or exists (select 1 from public.conversation_user_states s where s.user_id = current_user_id)
     or exists (select 1 from public.user_settings s where s.user_id = current_user_id)
     or exists (select 1 from public.status_updates s where s.user_id = current_user_id)
     or exists (select 1 from public.communities c where c.owner_id = current_user_id)
     or exists (select 1 from public.community_members m where m.user_id = current_user_id)
     or exists (select 1 from public.call_events c where c.caller_id = current_user_id)
     or exists (select 1 from public.conversations c where c.created_by = current_user_id) then
    return query select false, 'Boş olmayan cihaz hesabına kurtarma yapılamaz', null::text, null::text, null::text, null::text;
    return;
  end if;

  wanted_hash := private.phone_digest(clean_phone);

  insert into private.account_recovery_target_limits(phone_hash)
  values (wanted_hash)
  on conflict (phone_hash) do nothing;

  insert into private.account_recovery_attempts(requester_id, phone_hash)
  values (current_user_id, wanted_hash)
  on conflict (requester_id, phone_hash) do nothing;

  select l.failed_attempts, l.window_started_at
  into target_attempts, target_window
  from private.account_recovery_target_limits l
  where l.phone_hash = wanted_hash
  for update;

  select a.failed_attempts, a.window_started_at
  into requester_attempts, requester_window
  from private.account_recovery_attempts a
  where a.requester_id = current_user_id and a.phone_hash = wanted_hash
  for update;

  if target_window < now_at - interval '1 hour' then
    update private.account_recovery_target_limits
    set failed_attempts = 0, window_started_at = now_at
    where phone_hash = wanted_hash;
    target_attempts := 0;
  end if;

  if requester_window < now_at - interval '15 minutes' then
    update private.account_recovery_attempts
    set failed_attempts = 0, window_started_at = now_at
    where requester_id = current_user_id and phone_hash = wanted_hash;
    requester_attempts := 0;
  end if;

  if requester_attempts >= 5 or target_attempts >= 25 then
    return query select false, 'Çok fazla deneme yapıldı. Bir süre sonra yeniden deneyin', null::text, null::text, null::text, null::text;
    return;
  end if;

  select p.id, p.recovery_pin_hash
  into old_user_id, stored_pin_hash
  from public.profiles p
  where p.phone_hash = wanted_hash and p.id <> current_user_id;

  select s.secret into dummy_pin_hash
  from private.app_secrets s
  where s.name = 'recovery_dummy_hash';

  if old_user_id is null
     or stored_pin_hash is null
     or extensions.crypt(clean_pin, coalesce(stored_pin_hash, dummy_pin_hash))
        <> coalesce(stored_pin_hash, dummy_pin_hash) then
    update private.account_recovery_attempts
    set failed_attempts = failed_attempts + 1
    where requester_id = current_user_id and phone_hash = wanted_hash;
    update private.account_recovery_target_limits
    set failed_attempts = failed_attempts + 1
    where phone_hash = wanted_hash;
    return query select false, 'Numara veya PIN hatalı', null::text, null::text, null::text, null::text;
    return;
  end if;

  update public.message_user_states set user_id=current_user_id where user_id=old_user_id;
  update public.message_reactions set user_id=current_user_id where user_id=old_user_id;
  update public.webrtc_call_history_hidden set user_id=current_user_id where user_id=old_user_id;
  update public.webrtc_ice_candidates set user_id=current_user_id where user_id=old_user_id;
  update public.webrtc_calls set caller_id=current_user_id where caller_id=old_user_id;
  update public.webrtc_calls set callee_id=current_user_id where callee_id=old_user_id;
  update public.messages set sender_id = current_user_id where sender_id = old_user_id;
  update public.conversation_members set user_id = current_user_id where user_id = old_user_id;
  update public.conversation_user_states set user_id = current_user_id where user_id = old_user_id;
  update public.user_settings set user_id = current_user_id where user_id = old_user_id;
  update public.status_updates set user_id = current_user_id where user_id = old_user_id;
  update public.communities set owner_id = current_user_id where owner_id = old_user_id;
  update public.community_members set user_id = current_user_id where user_id = old_user_id;
  update public.call_events set caller_id = current_user_id where caller_id = old_user_id;
  update public.conversations set created_by = current_user_id where created_by = old_user_id;
  update storage.objects set owner_id = current_user_id::text where owner_id = old_user_id::text;

  delete from public.profiles where id = current_user_id;
  update public.profiles set id = current_user_id where id = old_user_id;
  delete from auth.users where id = old_user_id;

  delete from private.account_recovery_attempts where requester_id = current_user_id;
  delete from private.account_recovery_target_limits where phone_hash = wanted_hash;

  return query
  select true, 'Hesabınız bu cihaza geri yüklendi', p.username,
         p.display_name, p.phone_last4, p.safety_code
  from public.profiles p
  where p.id = current_user_id;
end;
$$;

