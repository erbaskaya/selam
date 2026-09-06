\set ON_ERROR_STOP on
begin;
create function public.test_assert(result boolean,label text) returns void language plpgsql as $$begin if result is distinct from true then raise exception 'FAILED: %',label;end if;raise notice 'PASS: %',label;end$$;
create function public.test_denied(statement text) returns boolean language plpgsql security invoker as $$begin execute statement;return false;exception when others then return true;end$$;
grant execute on function public.test_assert(boolean,text),public.test_denied(text) to authenticated,anon,service_role;
insert into auth.users(id,email) values('11000000-0000-0000-0000-000000000001','a@push.invalid'),('22000000-0000-0000-0000-000000000002','b@push.invalid'),('33000000-0000-0000-0000-000000000003','e@push.invalid');
update public.profiles set phone_hash=extensions.digest(id::text,'sha256'),display_name=username;
insert into public.conversations(id,kind) values('10000000-0000-0000-0000-000000000001','direct');
insert into public.conversation_members(conversation_id,user_id,role) values('10000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000001','member'),('10000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000002','member');
set local role authenticated;
select set_config('request.jwt.claim.sub','22000000-0000-0000-0000-000000000002',true);
select public.test_assert(public.selam_register_push('20000000-0000-0000-0000-000000000001',repeat('t',60)),'own push token registers');
select public.test_assert(public.selam_register_push('20000000-0000-0000-0000-000000000001',repeat('u',60)),'token rotation updates own device');
select public.test_assert(public.test_denied('select * from private.push_devices'),'device tokens cannot be listed by clients');
select public.test_assert(public.test_denied('select * from private.push_jobs'),'queue capabilities cannot be listed by clients');
select public.test_assert(public.test_denied('select public.selam_claim_push(gen_random_uuid(),repeat(''a'',64))'),'client cannot impersonate push worker');
select public.test_assert(public.selam_chat_alert_enabled('10000000-0000-0000-0000-000000000001'),'unmuted member hears foreground messages');
select set_config('request.jwt.claim.sub','33000000-0000-0000-0000-000000000003',true);
select public.test_assert(not public.selam_chat_alert_enabled('10000000-0000-0000-0000-000000000001'),'nonmember cannot obtain chat notification access');
select public.test_assert(public.test_denied('select public.selam_register_push(''20000000-0000-0000-0000-000000000002'',repeat(''u'',60))'),'another account cannot capture an existing token');
select set_config('request.jwt.claim.sub','11000000-0000-0000-0000-000000000001',true);
select public.selam_send('10000000-0000-0000-0000-000000000001','Push test') as mid \gset
reset role;
select public.test_assert((select count(*) from private.push_jobs)=1,'one job derived from actual recipient membership');
select id as job_id,secret as job_secret from private.push_jobs where message_id=:mid \gset
set local role service_role;
select public.test_assert(public.selam_claim_push(:'job_id',repeat('a',64)) is null,'wrong capability cannot claim job');
select public.selam_claim_push(:'job_id',:'job_secret') as claimed \gset
select public.test_assert(:'claimed'::jsonb->>'user_id'='22000000-0000-0000-0000-000000000002','worker receives actual recipient');
select public.test_assert(:'claimed'::jsonb->'tokens'=jsonb_build_array(repeat('u',60)),'worker uses rotated token');
select public.test_assert(public.selam_claim_push(:'job_id',:'job_secret') is null,'concurrent duplicate dispatch is leased');
select public.test_assert(not public.selam_finish_push(:'job_id',gen_random_uuid(),true),'stale lease cannot acknowledge a job');
select public.test_assert(public.selam_finish_push(:'job_id',(:'claimed'::jsonb->>'lease_id')::uuid,true),'valid worker acknowledges job');
reset role;
insert into public.conversation_user_states(conversation_id,user_id,muted_until) values('10000000-0000-0000-0000-000000000001','22000000-0000-0000-0000-000000000002',now()+interval '1 day');
set local role authenticated;
select set_config('request.jwt.claim.sub','22000000-0000-0000-0000-000000000002',true);
select public.test_assert(not public.selam_chat_alert_enabled('10000000-0000-0000-0000-000000000001'),'muting prevents foreground sound');
select set_config('request.jwt.claim.sub','11000000-0000-0000-0000-000000000001',true);
select public.selam_send('10000000-0000-0000-0000-000000000001','Muted test') as muted_mid \gset
reset role;
select id as muted_job,secret as muted_secret from private.push_jobs where message_id=:muted_mid \gset
set local role service_role;
select public.test_assert(public.selam_claim_push(:'muted_job',:'muted_secret') is null,'worker rechecks mute at dispatch time');
reset role;
update public.conversation_user_states set muted_until=null;
insert into public.user_settings(user_id,notifications_enabled) values('22000000-0000-0000-0000-000000000002',false);
set local role authenticated;
select set_config('request.jwt.claim.sub','22000000-0000-0000-0000-000000000002',true);
select public.test_assert(not public.selam_chat_alert_enabled('10000000-0000-0000-0000-000000000001'),'global notification off prevents foreground sound');
reset role;
set local role anon;
select public.test_assert(public.test_denied('select public.selam_register_push(gen_random_uuid(),repeat(''a'',60))'),'anonymous unauthenticated registration denied');
rollback;
