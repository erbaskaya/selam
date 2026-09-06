-- A private-to-each-user wake-up counter; message bodies and SDP never enter Realtime.
create table public.selam_delivery_events (
  user_id uuid primary key references public.profiles(id) on update cascade on delete cascade,
  version bigint not null default 1,
  kind text not null check (kind in ('message','call')),
  changed_at timestamptz not null default clock_timestamp()
);
alter table public.selam_delivery_events enable row level security;
revoke all on public.selam_delivery_events from public,anon,authenticated;
grant select on public.selam_delivery_events to authenticated;
create policy own_delivery_events on public.selam_delivery_events for select to authenticated
  using (user_id = (select auth.uid()));

create function private.selam_wake_devices() returns trigger
language plpgsql security definer set search_path = '' as $$
declare recipient uuid; selected_chat uuid; event_kind text;
begin
  if auth.uid() is null then return new; end if;
  if tg_table_name = 'webrtc_ice_candidates' then
    select conversation_id into selected_chat from public.webrtc_calls where id=new.call_id;
    event_kind := 'call';
  else
    selected_chat := new.conversation_id;
    event_kind := case when tg_table_name='webrtc_calls' then 'call' else 'message' end;
  end if;
  -- Recipients are derived from membership, never from client-supplied recipient lists.
  for recipient in select user_id from public.conversation_members
      where conversation_id=selected_chat order by user_id loop
    insert into public.selam_delivery_events(user_id,kind) values(recipient,event_kind)
    on conflict(user_id) do update set version=public.selam_delivery_events.version+1,
      kind=excluded.kind,changed_at=clock_timestamp();
  end loop;
  return new;
end;
$$;
revoke all on function private.selam_wake_devices() from public,anon,authenticated;
create trigger selam_message_wakeup after insert or update of body,deleted_at on public.messages
  for each row execute function private.selam_wake_devices();
create trigger selam_read_wakeup after insert or update of last_read_id on public.conversation_user_states
  for each row execute function private.selam_wake_devices();
create trigger selam_call_wakeup after insert or update of state on public.webrtc_calls
  for each row execute function private.selam_wake_devices();
create trigger selam_ice_wakeup after insert on public.webrtc_ice_candidates
  for each row execute function private.selam_wake_devices();
do $$ begin
  if not exists(select 1 from pg_publication where pubname='supabase_realtime') then
    create publication supabase_realtime;
  end if;
  alter publication supabase_realtime add table public.selam_delivery_events;
end $$;
