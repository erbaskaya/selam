-- Arama kayıtlarını silmek diğer katılımcının geçmişini etkilemez.
-- Kullanıcıya göre gizleme kaydı tutulur; gerçek arama verisi bütünlüğü korunur.
create table if not exists public.webrtc_call_history_hidden (
  user_id uuid not null references auth.users(id) on delete cascade,
  call_id uuid not null references public.webrtc_calls(id) on delete cascade,
  hidden_at timestamptz not null default clock_timestamp(),
  primary key (user_id, call_id)
);

alter table public.webrtc_call_history_hidden enable row level security;
revoke all on public.webrtc_call_history_hidden from anon, authenticated;

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
  where auth.uid() is not null
    and auth.uid() in (c.caller_id, c.callee_id)
    and not exists (
      select 1
      from public.webrtc_call_history_hidden hidden
      where hidden.user_id = auth.uid() and hidden.call_id = c.id
    )
  order by c.started_at desc
  limit 100;
$$;

create or replace function public.hide_audio_call_history_entry(selected_call_id uuid)
returns boolean
language plpgsql security definer set search_path = ''
as $$
declare current_user_id uuid := auth.uid();
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if not exists (
    select 1 from public.webrtc_calls c
    where c.id = selected_call_id
      and current_user_id in (c.caller_id, c.callee_id)
  ) then raise exception 'Bu arama kaydına erişiminiz yok'; end if;

  insert into public.webrtc_call_history_hidden (user_id, call_id)
  values (current_user_id, selected_call_id)
  on conflict (user_id, call_id) do update
    set hidden_at = clock_timestamp();
  return true;
end;
$$;

create or replace function public.clear_audio_call_history()
returns boolean
language plpgsql security definer set search_path = ''
as $$
declare current_user_id uuid := auth.uid();
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;

  insert into public.webrtc_call_history_hidden (user_id, call_id)
  select current_user_id, c.id
  from public.webrtc_calls c
  where current_user_id in (c.caller_id, c.callee_id)
  on conflict (user_id, call_id) do update
    set hidden_at = clock_timestamp();
  return true;
end;
$$;

revoke all on function public.list_audio_call_history() from public, anon, authenticated;
revoke all on function public.hide_audio_call_history_entry(uuid) from public, anon, authenticated;
revoke all on function public.clear_audio_call_history() from public, anon, authenticated;

grant execute on function public.list_audio_call_history() to authenticated;
grant execute on function public.hide_audio_call_history_entry(uuid) to authenticated;
grant execute on function public.clear_audio_call_history() to authenticated;
