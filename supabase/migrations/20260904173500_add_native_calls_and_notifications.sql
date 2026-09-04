-- Selam 1.5 - Android bildirim akışı ve Jitsi'siz WebRTC sesli arama sinyalleşmesi
-- Eski call_events kayıtlarına dokunulmaz; yeni sistem ayrı tablolarda tutulur.

create table if not exists public.webrtc_calls (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  caller_id uuid not null references auth.users(id) on delete cascade,
  callee_id uuid not null references auth.users(id) on delete cascade,
  state text not null default 'ringing'
    check (state in ('ringing', 'accepted', 'declined', 'ended', 'missed')),
  offer_sdp text not null check (char_length(offer_sdp) between 20 and 100000),
  answer_sdp text,
  started_at timestamptz not null default clock_timestamp(),
  answered_at timestamptz,
  ended_at timestamptz,
  expires_at timestamptz not null default (clock_timestamp() + interval '60 seconds'),
  check (caller_id <> callee_id)
);
create index if not exists webrtc_calls_caller_state_idx
  on public.webrtc_calls (caller_id, state, started_at desc);
create index if not exists webrtc_calls_callee_state_idx
  on public.webrtc_calls (callee_id, state, started_at desc);
alter table public.webrtc_calls enable row level security;
revoke all on public.webrtc_calls from anon, authenticated;

create table if not exists public.webrtc_ice_candidates (
  id bigint generated always as identity primary key,
  call_id uuid not null references public.webrtc_calls(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  candidate text not null check (char_length(candidate) between 1 and 4096),
  sdp_mid text,
  sdp_mline_index integer not null check (sdp_mline_index between 0 and 32),
  created_at timestamptz not null default clock_timestamp()
);
create index if not exists webrtc_ice_candidates_call_id_idx
  on public.webrtc_ice_candidates (call_id, id);
alter table public.webrtc_ice_candidates enable row level security;
revoke all on public.webrtc_ice_candidates from anon, authenticated;

create or replace function public.list_message_notifications(after_message_id bigint default 0)
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
    and m.id > greatest(coalesce(after_message_id, 0), 0)
    and coalesce(settings.notifications_enabled, true)
    and (state.cleared_at is null or m.created_at > state.cleared_at)
    and (state.muted_until is null or state.muted_until <= clock_timestamp())
  order by m.id asc
  limit 50;
$$;

create or replace function public.start_audio_call(chat_id uuid, session_offer text)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  target_user_id uuid;
  new_id uuid := gen_random_uuid();
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if char_length(coalesce(session_offer, '')) not between 20 and 100000 then
    raise exception 'Arama bağlantı teklifi geçersiz';
  end if;
  if not exists (
    select 1 from public.conversations c
    join public.conversation_members mine on mine.conversation_id = c.id
    where c.id = chat_id and c.kind = 'direct' and mine.user_id = current_user_id
  ) then raise exception 'Yalnızca birebir sohbetten arama başlatabilirsiniz'; end if;

  select cm.user_id into target_user_id
  from public.conversation_members cm
  where cm.conversation_id = chat_id and cm.user_id <> current_user_id
  limit 1;
  if target_user_id is null then raise exception 'Aranacak kişi bulunamadı'; end if;

  update public.webrtc_calls ce
  set state = 'missed', ended_at = clock_timestamp()
  where ce.state = 'ringing' and ce.expires_at <= clock_timestamp()
    and (ce.caller_id in (current_user_id, target_user_id)
      or ce.callee_id in (current_user_id, target_user_id));

  if exists (
    select 1 from public.webrtc_calls ce
    where ce.state in ('ringing', 'accepted')
      and (ce.caller_id in (current_user_id, target_user_id)
        or ce.callee_id in (current_user_id, target_user_id))
  ) then raise exception 'Kullanıcılardan biri başka bir aramada'; end if;

  insert into public.webrtc_calls (
    id, conversation_id, caller_id, callee_id, state, offer_sdp, expires_at
  ) values (
    new_id, chat_id, current_user_id, target_user_id, 'ringing', session_offer,
    clock_timestamp() + interval '60 seconds'
  );
  return new_id;
end;
$$;

create or replace function public.list_incoming_audio_calls()
returns table (
  call_id uuid, conversation_id uuid, caller_id uuid,
  caller_name text, started_at timestamptz
)
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  update public.webrtc_calls ce
  set state = 'missed', ended_at = clock_timestamp()
  where ce.callee_id = auth.uid() and ce.state = 'ringing'
    and ce.expires_at <= clock_timestamp();
  return query
  select ce.id, ce.conversation_id, ce.caller_id, p.display_name, ce.started_at
  from public.webrtc_calls ce
  join public.profiles p on p.id = ce.caller_id
  left join public.user_settings settings on settings.user_id = auth.uid()
  where ce.callee_id = auth.uid() and ce.state = 'ringing'
    and ce.expires_at > clock_timestamp()
    and coalesce(settings.call_notifications_enabled, true)
  order by ce.started_at desc;
end;
$$;

create or replace function public.get_audio_call_state(selected_call_id uuid)
returns table (
  call_id uuid, conversation_id uuid, caller_id uuid, callee_id uuid,
  call_state text, offer_sdp text, answer_sdp text,
  started_at timestamptz, answered_at timestamptz
)
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  update public.webrtc_calls ce
  set state = 'missed', ended_at = clock_timestamp()
  where ce.id = selected_call_id and ce.state = 'ringing'
    and ce.expires_at <= clock_timestamp();
  return query
  select ce.id, ce.conversation_id, ce.caller_id, ce.callee_id,
         ce.state, ce.offer_sdp, ce.answer_sdp, ce.started_at, ce.answered_at
  from public.webrtc_calls ce
  where ce.id = selected_call_id
    and auth.uid() in (ce.caller_id, ce.callee_id);
end;
$$;

create or replace function public.answer_audio_call(selected_call_id uuid, session_answer text)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if char_length(coalesce(session_answer, '')) not between 20 and 100000 then
    raise exception 'Arama bağlantı cevabı geçersiz';
  end if;
  update public.webrtc_calls ce
  set state = 'accepted', answer_sdp = session_answer,
      answered_at = clock_timestamp(), expires_at = clock_timestamp() + interval '6 hours'
  where ce.id = selected_call_id and ce.callee_id = auth.uid()
    and ce.state = 'ringing' and ce.expires_at > clock_timestamp();
  if not found then raise exception 'Yanıtlanabilecek arama bulunamadı'; end if;
  return true;
end;
$$;

create or replace function public.decline_audio_call(selected_call_id uuid)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  update public.webrtc_calls ce
  set state = 'declined', ended_at = clock_timestamp()
  where ce.id = selected_call_id and ce.callee_id = auth.uid()
    and ce.state = 'ringing';
  if not found then raise exception 'Reddedilebilecek arama bulunamadı'; end if;
  return true;
end;
$$;

create or replace function public.end_audio_call(selected_call_id uuid)
returns boolean
language plpgsql security definer set search_path = ''
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  update public.webrtc_calls ce
  set state = 'ended', ended_at = clock_timestamp()
  where ce.id = selected_call_id
    and auth.uid() in (ce.caller_id, ce.callee_id)
    and ce.state in ('ringing', 'accepted');
  return found;
end;
$$;

create or replace function public.add_audio_ice_candidate(
  selected_call_id uuid, ice_candidate text, candidate_sdp_mid text,
  candidate_sdp_mline_index integer
)
returns bigint
language plpgsql security definer set search_path = ''
as $$
declare new_id bigint;
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if char_length(coalesce(ice_candidate, '')) not between 1 and 4096 then
    raise exception 'Bağlantı adayı geçersiz';
  end if;
  if coalesce(candidate_sdp_mline_index, -1) not between 0 and 32 then
    raise exception 'Bağlantı adayı sırası geçersiz';
  end if;
  if not exists (
    select 1 from public.webrtc_calls ce
    where ce.id = selected_call_id
      and auth.uid() in (ce.caller_id, ce.callee_id)
      and ce.state in ('ringing', 'accepted')
  ) then raise exception 'Aktif arama bulunamadı'; end if;
  insert into public.webrtc_ice_candidates (
    call_id, user_id, candidate, sdp_mid, sdp_mline_index
  ) values (
    selected_call_id, auth.uid(), ice_candidate,
    nullif(candidate_sdp_mid, ''), candidate_sdp_mline_index
  ) returning id into new_id;
  return new_id;
end;
$$;

create or replace function public.list_audio_ice_candidates(
  selected_call_id uuid, after_candidate_id bigint default 0
)
returns table (
  candidate_id bigint, ice_candidate text, candidate_sdp_mid text,
  candidate_sdp_mline_index integer
)
language plpgsql security definer set search_path = '' stable
as $$
begin
  if auth.uid() is null then raise exception 'Oturum gerekli'; end if;
  if not exists (
    select 1 from public.webrtc_calls ce
    where ce.id = selected_call_id
      and auth.uid() in (ce.caller_id, ce.callee_id)
  ) then raise exception 'Aramaya erişiminiz yok'; end if;
  return query
  select c.id, c.candidate, c.sdp_mid, c.sdp_mline_index
  from public.webrtc_ice_candidates c
  where c.call_id = selected_call_id and c.user_id <> auth.uid()
    and c.id > greatest(coalesce(after_candidate_id, 0), 0)
  order by c.id asc limit 100;
end;
$$;

create or replace function public.list_audio_call_history()
returns table (
  call_id uuid, conversation_id uuid, other_name text,
  call_state text, started_at timestamptz, is_outgoing boolean
)
language sql security definer set search_path = '' stable
as $$
  select c.id, c.conversation_id,
         case when c.caller_id = auth.uid()
              then callee.display_name else caller.display_name end,
         c.state, c.started_at, c.caller_id = auth.uid()
  from public.webrtc_calls c
  join public.profiles caller on caller.id = c.caller_id
  join public.profiles callee on callee.id = c.callee_id
  where auth.uid() is not null and auth.uid() in (c.caller_id, c.callee_id)
  order by c.started_at desc
  limit 100;
$$;

revoke all on function public.list_message_notifications(bigint) from public, anon, authenticated;
revoke all on function public.start_audio_call(uuid, text) from public, anon, authenticated;
revoke all on function public.list_incoming_audio_calls() from public, anon, authenticated;
revoke all on function public.get_audio_call_state(uuid) from public, anon, authenticated;
revoke all on function public.answer_audio_call(uuid, text) from public, anon, authenticated;
revoke all on function public.decline_audio_call(uuid) from public, anon, authenticated;
revoke all on function public.end_audio_call(uuid) from public, anon, authenticated;
revoke all on function public.add_audio_ice_candidate(uuid, text, text, integer) from public, anon, authenticated;
revoke all on function public.list_audio_ice_candidates(uuid, bigint) from public, anon, authenticated;
revoke all on function public.list_audio_call_history() from public, anon, authenticated;

grant execute on function public.list_message_notifications(bigint) to authenticated;
grant execute on function public.start_audio_call(uuid, text) to authenticated;
grant execute on function public.list_incoming_audio_calls() to authenticated;
grant execute on function public.get_audio_call_state(uuid) to authenticated;
grant execute on function public.answer_audio_call(uuid, text) to authenticated;
grant execute on function public.decline_audio_call(uuid) to authenticated;
grant execute on function public.end_audio_call(uuid) to authenticated;
grant execute on function public.add_audio_ice_candidate(uuid, text, text, integer) to authenticated;
grant execute on function public.list_audio_ice_candidates(uuid, bigint) to authenticated;
grant execute on function public.list_audio_call_history() to authenticated;
