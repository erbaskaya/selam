import test from 'node:test';import assert from 'node:assert/strict';
import { handle,payload } from '../supabase/functions/selam-push/worker.mjs';
const job={id:'10000000-0000-0000-0000-000000000001',lease_id:'20000000-0000-0000-0000-000000000001',
 user_id:'user',chat_id:'chat',message_id:'7',tokens:['device-one']};
const env={SUPABASE_URL:'https://example.invalid',SUPABASE_SERVICE_ROLE_KEY:'isolated-test',FIREBASE_SERVICE_ACCOUNT:JSON.stringify({project_id:'selam-test'})};
const request=()=>new Request('https://example.invalid',{method:'POST',body:JSON.stringify({job_id:job.id,secret:'a'.repeat(64)})});
const response=(body,status=200)=>new Response(JSON.stringify(body),{status});
test('No Firebase configuration never claims a queue job or contacts a device',async()=>{
 assert.equal((await handle(request(),{},()=>{throw new Error('unexpected network')})).status,503);
});
test('Invalid capability never reaches FCM',async()=>{
 assert.equal((await handle(new Request('https://example.invalid',{method:'POST',body:'{}'}),env,()=>{throw new Error('unexpected network')})).status,400);
 assert.equal((await handle(request(),env,async()=>response(null))).status,204);
});
test('High priority carries account and event IDs without message content',()=>{
 const data=payload(job,'token');assert.equal(data.message.android.priority,'HIGH');assert.equal(data.message.android.ttl,'86400s');
 assert.deepEqual(Object.keys(data.message.data).sort(),['chat_id','kind','message_id','user_id']);
});
test('Recipient from claimed job is sent and acknowledged',async()=>{
 const calls=[];const result=await handle(request(),env,async(url,options)=>{
  const body=JSON.parse(options.body);calls.push({url,body});
  if(url.endsWith('selam_claim_push'))return response(job);
  if(url.includes('fcm.googleapis.com')){assert.equal(body.message.token,'device-one');return response({name:'sent'});}
  return response(true);
 },async()=>'mock-access-token');
 assert.equal(result.status,200);assert.equal(calls.at(-1).body.p_success,true);
});
test('Transient delivery failure queues a retry, unregistered token is removed',async()=>{
 for(const permanent of [true,false]){
  let finish;const result=await handle(request(),env,async(url,options)=>{
   if(url.endsWith('selam_claim_push'))return response(job);
   if(url.includes('fcm.googleapis.com'))return response({error:{details:[{errorCode:permanent?'UNREGISTERED':'UNAVAILABLE'}]}},permanent?404:503);
   finish=JSON.parse(options.body);return response(true);
  },async()=>'mock-access-token');
  assert.equal(finish.p_success,permanent);assert.deepEqual(finish.p_invalid_tokens,permanent?['device-one']:[]);
  assert.equal(result.status,permanent?200:503);
 }
});
