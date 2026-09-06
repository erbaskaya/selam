-- Inert until the dispatcher URL is configured after Firebase credentials are installed.
create table private.push_devices (
 user_id uuid not null references public.profiles(id) on update cascade on delete cascade,
 installation uuid not null, token text not null unique,
 updated_at timestamptz not null default now(), primary key(user_id,installation)
);
create table private.push_jobs (
 id uuid primary key default gen_random_uuid(),
 secret text not null default encode(extensions.gen_random_bytes(32),'hex'),
 user_id uuid not null references public.profiles(id) on update cascade on delete cascade,
 message_id bigint not null references public.messages(id) on delete cascade,
 attempts int not null default 0, available_at timestamptz not null default now(),
 lease_until timestamptz, lease_id uuid, done boolean not null default false,
 created_at timestamptz not null default now(), unique(user_id,message_id)
);
create index push_jobs_pending on private.push_jobs(available_at) where not done;
create table private.push_config(singleton boolean primary key default true check(singleton),url text not null);
alter table private.push_devices enable row level security;
alter table private.push_jobs enable row level security;
alter table private.push_config enable row level security;
revoke all on private.push_devices,private.push_jobs,private.push_config from public,anon,authenticated;

create function private.selam_register_push(p_installation uuid,p_token text) returns boolean
language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null or not exists(select 1 from public.profiles where id=auth.uid() and phone_hash is not null) then raise exception 'Oturum gerekli'; end if;
 if p_installation is null or p_token is null or length(p_token) not between 40 and 4096 then raise exception 'Geçersiz cihaz kaydı';end if;
 if exists(select 1 from private.push_devices where token=p_token and user_id<>auth.uid()) then raise exception 'Cihaz kaydı başka hesapta';end if;
 insert into private.push_devices(user_id,installation,token) values(auth.uid(),p_installation,p_token)
 on conflict(user_id,installation) do update set token=excluded.token,updated_at=now();
 return true;
end $$;
create function public.selam_register_push(p_installation uuid,p_token text) returns boolean
language sql security invoker set search_path='' as $$select private.selam_register_push(p_installation,p_token)$$;
revoke all on function private.selam_register_push(uuid,text),public.selam_register_push(uuid,text) from public,anon,authenticated;
grant execute on function private.selam_register_push(uuid,text),public.selam_register_push(uuid,text) to authenticated;

-- One database-derived recipient list. A mobile client never supplies push recipients.
create function private.selam_queue_push() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if auth.uid() is null or new.sender_id<>auth.uid() then return new;end if;
 insert into private.push_jobs(user_id,message_id)
 select cm.user_id,new.id from public.conversation_members cm
 where cm.conversation_id=new.conversation_id and cm.user_id<>new.sender_id
 and exists(select 1 from private.push_devices d where d.user_id=cm.user_id)
 on conflict(user_id,message_id) do nothing;
 return new;
end $$;
revoke all on function private.selam_queue_push() from public,anon,authenticated;
create trigger selam_queue_message_push after insert on public.messages for each row execute function private.selam_queue_push();

-- Webhook contains only an unguessable, expiring job capability. No message text or FCM keys.
create function private.selam_dispatch_push() returns trigger language plpgsql security definer set search_path='' as $$
declare endpoint text;
begin
 select url into endpoint from private.push_config where singleton;
 if endpoint is null or to_regnamespace('net') is null then return new;end if;
 begin
  execute 'select net.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 5000)'
  using endpoint,jsonb_build_object('job_id',new.id,'secret',new.secret),'{"Content-Type":"application/json"}'::jsonb;
 exception when others then null; -- Sending a message must not fail when a push endpoint is down.
 end;
 return new;
end $$;
revoke all on function private.selam_dispatch_push() from public,anon,authenticated;
create trigger selam_dispatch_job after insert on private.push_jobs for each row execute function private.selam_dispatch_push();

-- Service-role-only invoker RPCs read/write private rows with explicit grants; no definer bypass.
grant usage on schema private to service_role;
grant select,update,delete on private.push_jobs to service_role;
grant select,delete on private.push_devices to service_role;
grant select on public.messages,public.conversation_members,public.conversation_user_states,public.user_settings,public.message_user_states,public.profiles to service_role;
create function public.selam_claim_push(p_id uuid,p_secret text) returns jsonb
language plpgsql security invoker set search_path='' as $$
declare j private.push_jobs; m public.messages; lease uuid:=gen_random_uuid(); allowed boolean;
begin
 update private.push_jobs set lease_id=lease,lease_until=now()+interval '90 seconds',attempts=attempts+1
 where id=p_id and secret=p_secret and length(p_secret)=64 and not done and available_at<=now()
 and (lease_until is null or lease_until<now()) and attempts<8 and created_at>now()-interval '1 day'
 returning * into j;
 if j.id is null then return null;end if;
 select * into m from public.messages where id=j.message_id;
 select exists(select 1 from public.conversation_members cm
 left join public.conversation_user_states st on st.user_id=cm.user_id and st.conversation_id=cm.conversation_id
 left join public.user_settings us on us.user_id=cm.user_id
 where cm.user_id=j.user_id and cm.conversation_id=m.conversation_id and m.deleted_at is null
 and coalesce(us.notifications_enabled,true) and m.id>coalesce(st.last_read_id,0)
 and (st.muted_until is null or st.muted_until<=now()) and (st.cleared_at is null or m.created_at>st.cleared_at)
 and not exists(select 1 from public.message_user_states ms join public.profiles p on p.safety_code=ms.account_code
 where p.id=j.user_id and ms.message_id=m.id and ms.hidden)) into allowed;
 if not allowed then update private.push_jobs set done=true where id=j.id;return null;end if;
 return jsonb_build_object('id',j.id,'lease_id',lease,'user_id',j.user_id,'message_id',m.id::text,'chat_id',m.conversation_id,
 'tokens',coalesce((select jsonb_agg(token) from private.push_devices where user_id=j.user_id and updated_at>now()-interval '90 days'),'[]'::jsonb));
end $$;
create function public.selam_finish_push(p_id uuid,p_lease uuid,p_success boolean,p_invalid_tokens text[] default '{}') returns boolean
language plpgsql security invoker set search_path='' as $$
declare j private.push_jobs;
begin
 select * into j from private.push_jobs where id=p_id and lease_id=p_lease for update;
 if j.id is null then return false;end if;
 delete from private.push_devices where user_id=j.user_id and token=any(p_invalid_tokens);
 update private.push_jobs set done=p_success,lease_until=null,lease_id=null,
 available_at=now()+make_interval(secs=>least(300,5*(2^least(attempts,6))::int)) where id=j.id;
 return true;
end $$;
revoke all on function public.selam_claim_push(uuid,text),public.selam_finish_push(uuid,uuid,boolean,text[]) from public,anon,authenticated;
grant execute on function public.selam_claim_push(uuid,text),public.selam_finish_push(uuid,uuid,boolean,text[]) to service_role;

create function private.selam_retry_push() returns void language plpgsql security definer set search_path='' as $$
declare j record;endpoint text;
begin
 select url into endpoint from private.push_config where singleton;
 if endpoint is null or to_regnamespace('net') is null then return;end if;
 for j in select * from private.push_jobs where not done and attempts<8 and available_at<=now()
 and (lease_until is null or lease_until<now()) and created_at>now()-interval '1 day' order by available_at limit 100 loop
  perform 1;
  execute 'select net.http_post(url := $1, body := $2, headers := $3, timeout_milliseconds := 5000)'
   using endpoint,jsonb_build_object('job_id',j.id,'secret',j.secret),'{"Content-Type":"application/json"}'::jsonb;
 end loop;
 delete from private.push_jobs where created_at<now()-interval '2 days';
 delete from private.push_devices where updated_at<now()-interval '90 days';
end $$;
revoke all on function private.selam_retry_push() from public,anon,authenticated;

-- Foreground conversation sounds respect mute/global settings even after marking a message read.
create function private.selam_chat_alert_enabled(p_chat_id uuid) returns boolean
language sql security definer set search_path='' stable as $$
 select auth.uid() is not null and exists(select 1 from public.conversation_members cm
 left join public.conversation_user_states st on st.user_id=cm.user_id and st.conversation_id=cm.conversation_id
 left join public.user_settings us on us.user_id=cm.user_id
 where cm.user_id=auth.uid() and cm.conversation_id=p_chat_id and coalesce(us.notifications_enabled,true)
 and (st.muted_until is null or st.muted_until<=now()));
$$;
create function public.selam_chat_alert_enabled(p_chat_id uuid) returns boolean
language sql security invoker set search_path='' as $$select private.selam_chat_alert_enabled(p_chat_id)$$;
revoke all on function private.selam_chat_alert_enabled(uuid),public.selam_chat_alert_enabled(uuid) from public,anon,authenticated;
grant execute on function private.selam_chat_alert_enabled(uuid),public.selam_chat_alert_enabled(uuid) to authenticated;
