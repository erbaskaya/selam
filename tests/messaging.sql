\set ON_ERROR_STOP on
begin;
create function public.test_assert(result boolean,label text) returns void language plpgsql as $$begin if result is distinct from true then raise exception 'FAILED: %',label; end if; raise notice 'PASS: %',label;end$$;
create function public.test_denied(statement text) returns boolean language plpgsql security invoker as $$begin execute statement;return false;exception when others then return true;end$$;
grant execute on function public.test_assert(boolean,text),public.test_denied(text) to authenticated,anon;
insert into auth.users(id,email) values
('00000000-0000-0000-0000-000000000001','alice@ci.invalid'),
('00000000-0000-0000-0000-000000000002','bob@ci.invalid'),
('00000000-0000-0000-0000-000000000003','eve@ci.invalid'),
('00000000-0000-0000-0000-000000000004','restored@ci.invalid');
update public.profiles set phone_hash=extensions.digest(id::text,'sha256'),display_name=username where id<>'00000000-0000-0000-0000-000000000004';
insert into public.conversations(id,kind,title) values
('10000000-0000-0000-0000-000000000001','direct',null),
('10000000-0000-0000-0000-000000000002','group','CI group');
insert into public.conversation_members(conversation_id,user_id,role) values
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','member'),
('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','member'),
('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','owner'),
('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000002','member');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.selam_send('10000000-0000-0000-0000-000000000001','Original') as msg \gset
select public.selam_message_action(:msg,'star');
select public.selam_message_action(:msg,'react','👍');
select public.selam_message_action(:msg,'edit','Edited');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'message_body')='Edited','owner edit visible');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'edited_at') is not null,'edited timestamp');
select public.selam_send('10000000-0000-0000-0000-000000000001','Reply',:msg) as reply \gset
select public.test_assert(public.test_denied('select public.selam_send(''10000000-0000-0000-0000-000000000002'',''cross chat'', '||:msg||')'),'cross-conversation reply denied');
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001','',true))=1,'own starred filter');
select public.selam_preferences('{"mode":"dark","font":21}');
select public.selam_favorite('10000000-0000-0000-0000-000000000001',true);
select public.test_assert((public.selam_chats()->0->>'favorite')::boolean,'favorite persists');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001','',true))=0,'stars private per user');
select public.test_assert(public.selam_preferences()='{}'::jsonb,'appearance private per user');
select public.test_assert((public.selam_chats()->0->>'unread_count')::int=2,'incoming unread count');
select public.test_assert(public.test_denied('select public.selam_message_action('||:msg||',''edit'',''Forged'')'),'other sender edit denied');
select public.test_assert(public.test_denied('select public.selam_message_action('||:msg||',''delete'')'),'other sender delete denied');
select public.test_assert(public.test_denied('select public.selam_group_action(''10000000-0000-0000-0000-000000000002'',''rename'',''Hijack'')'),'ordinary member cannot rename group');
select public.selam_mark_read('10000000-0000-0000-0000-000000000001',:reply);
select public.test_assert((public.selam_chats()->0->>'unread_count')::int=0,'viewing latest clears unread');
select public.test_assert((select count(*) from public.list_message_notifications(0))=0,'read messages not notified');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'read_by_other')::boolean,'receipt after recipient read');
reset role;
insert into public.user_settings(user_id,read_receipts) values('00000000-0000-0000-0000-000000000002',false);
set local role authenticated;
select public.test_assert(not (public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'read_by_other')::boolean,'recipient receipt privacy honored');
select public.selam_message_action(:msg,'react','👍');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'reactions')='','same reaction toggles off');
select public.selam_group_action('10000000-0000-0000-0000-000000000002','rename','Renamed group');
select public.test_assert(public.selam_group_info('10000000-0000-0000-0000-000000000002')->>'title'='Renamed group','owner renames group');
select public.selam_group_action('10000000-0000-0000-0000-000000000002','add','00000000-0000-0000-0000-000000000003');
select public.test_assert(jsonb_array_length(public.selam_group_info('10000000-0000-0000-0000-000000000002')->'members')=3,'owner adds member');
select public.selam_group_action('10000000-0000-0000-0000-000000000002','remove','00000000-0000-0000-0000-000000000003');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.test_assert(public.test_denied('select public.selam_messages(''10000000-0000-0000-0000-000000000001'')'),'nonmember cannot read');
select public.test_assert(public.test_denied('select public.selam_send(''10000000-0000-0000-0000-000000000001'',''Intrusion'')'),'nonmember cannot send');
select public.test_assert(public.test_denied('select public.selam_message_action('||:msg||',''react'',''👍'')'),'nonmember cannot react');
select public.test_assert(public.test_denied('select public.selam_group_info(''10000000-0000-0000-0000-000000000002'')'),'removed member cannot view group');
select public.test_assert(public.test_denied('select * from public.message_reactions'),'direct table access denied');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.selam_message_action(:msg,'hide');
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001'))=1,'hide is personal');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'reply_preview') is null,'hidden original not leaked through reply');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001'))=2,'other participant still sees personally hidden message');
select public.selam_message_action(:msg,'delete');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->1->>'reply_preview')='Bu mesaj silindi.','deleted original removed from quote');
reset role;
insert into public.messages(conversation_id,sender_id,body,created_at) values('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Too old',now()-interval '16 minutes') returning id as old_msg \gset
set local role authenticated;
select public.test_assert(public.test_denied('select public.selam_message_action('||:old_msg||',''edit'',''Too late'')'),'edit time limit enforced');
reset role;
insert into public.messages(conversation_id,sender_id,body) select '10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Page '||i from generate_series(1,110) i;
set local role authenticated;
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001'))=100,'latest page capped at 100');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001')->99->>'message_body')='Page 110','latest page includes newest messages');
select (public.selam_messages('10000000-0000-0000-0000-000000000001')->0->>'message_id')::bigint as before_id \gset
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001','',false,:before_id))=13,'previous page preserves earlier messages');
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001','Page 1'))=22,'search spans entire conversation');
-- Restore the same account to a new anonymous identity, retaining preferences/stars/reactions.
select public.selam_message_action(:reply,'star');
select public.selam_message_action(:reply,'react','❤️');
reset role;
update public.profiles set phone_hash=private.phone_digest('+12025550123'),phone_last4='0123',recovery_pin_hash=extensions.crypt('739281',extensions.gen_salt('bf',4)) where id='00000000-0000-0000-0000-000000000001';
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',true);
select public.test_assert((select success from public.recover_profile('+12025550123','739281')),'PIN recovery succeeds in isolated database');
select public.test_assert(public.selam_preferences()->>'mode'='dark','appearance survives account recovery');
select public.test_assert(jsonb_array_length(public.selam_messages('10000000-0000-0000-0000-000000000001','',true))=1,'stars survive account recovery');
select public.test_assert((public.selam_messages('10000000-0000-0000-0000-000000000001','Reply')->0->>'reactions')='❤️ 1','reactions survive account recovery');
select public.selam_group_action('10000000-0000-0000-0000-000000000002','leave');
select public.test_assert(public.test_denied('select public.selam_group_info(''10000000-0000-0000-0000-000000000002'')'),'left member loses access');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.selam_group_action('10000000-0000-0000-0000-000000000002','rename','Transferred owner');
reset role;
set local role anon;
select public.test_assert(public.test_denied('select public.selam_preferences()'),'anonymous role cannot call preferences');
select public.test_assert(public.test_denied('select public.selam_messages(''10000000-0000-0000-0000-000000000001'')'),'anonymous role cannot read messages');
rollback;
