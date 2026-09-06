// Dependency-free FCM HTTP v1 sender. All credentials stay in Edge Function secrets.
const encode = input => btoa(String.fromCharCode(...new Uint8Array(input))).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
const json64 = value => encode(new TextEncoder().encode(JSON.stringify(value)));
let cached;
async function accessToken(account,fetcher) {
  if(cached && cached.project===account.project_id && cached.expires>Date.now()+60000)return cached.token;
  const now=Math.floor(Date.now()/1000);
  const unsigned=json64({alg:'RS256',typ:'JWT'})+'.'+json64({iss:account.client_email,
    scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
  const pem=account.private_key.replace(/-----[^-]+-----|\s/g,'');
  const key=await crypto.subtle.importKey('pkcs8',Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  const sig=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,new TextEncoder().encode(unsigned));
  const response=await fetcher('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:unsigned+'.'+encode(sig)}),signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw new Error('FCM credential exchange failed');
  const data=await response.json();if(!data.access_token)throw new Error('FCM access token absent');
  cached={project:account.project_id,token:data.access_token,expires:Date.now()+Number(data.expires_in||3600)*1000};return cached.token;
}
export function payload(job,token){return {message:{token,data:{kind:'message',user_id:job.user_id,chat_id:job.chat_id,message_id:String(job.message_id)},
  android:{priority:'HIGH',ttl:'86400s',restricted_package_name:'com.erbaskaya.selam'}}};}
export async function handle(request,env,fetcher=fetch,tokenProvider=accessToken){
  if(request.method!=='POST')return new Response('Method not allowed',{status:405});
  if(!env.FIREBASE_SERVICE_ACCOUNT)return new Response('Push is not configured',{status:503});
  const raw=await request.text();if(raw.length>2048)return new Response('Too large',{status:413});
  let input;try{input=JSON.parse(raw);}catch{return new Response('Invalid request',{status:400});}
  if(!/^[0-9a-f-]{36}$/.test(input.job_id||'')||!/^[0-9a-f]{64}$/.test(input.secret||''))return new Response('Invalid request',{status:400});
  const rpc=async(name,args)=>{
    const response=await fetcher(env.SUPABASE_URL+'/rest/v1/rpc/'+name,{method:'POST',headers:{'Content-Type':'application/json',
      apikey:env.SUPABASE_SERVICE_ROLE_KEY,Authorization:'Bearer '+env.SUPABASE_SERVICE_ROLE_KEY},body:JSON.stringify(args),signal:AbortSignal.timeout(10000)});
    if(!response.ok)throw new Error('Push queue RPC failed');return response.json();
  };
  let job;
  try{
    job=await rpc('selam_claim_push',{p_id:input.job_id,p_secret:input.secret});
    if(!job)return new Response(null,{status:204});
    const account=JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
    if(!/^[a-z][a-z0-9-]{4,61}[a-z0-9]$/.test(account.project_id||''))throw new Error('Invalid Firebase project');
    const token=await tokenProvider(account,fetcher);let success=true;const invalid=[];
    for(const device of job.tokens){
      const response=await fetcher('https://fcm.googleapis.com/v1/projects/'+account.project_id+'/messages:send',{
        method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(payload(job,device)),signal:AbortSignal.timeout(10000)});
      if(!response.ok){
        const error=await response.json().catch(()=>({}));
        if(error.error?.details?.some(d=>d.errorCode==='UNREGISTERED'))invalid.push(device);
        else success=false;
      }
    }
    await rpc('selam_finish_push',{p_id:job.id,p_lease:job.lease_id,p_success:success,p_invalid_tokens:invalid});
    return new Response(JSON.stringify({sent:success}),{status:success?200:503,headers:{'Content-Type':'application/json'}});
  }catch{
    if(job)await rpc('selam_finish_push',{p_id:job.id,p_lease:job.lease_id,p_success:false,p_invalid_tokens:[]}).catch(()=>{});
    // Never log credentials, device tokens, job capabilities, or message contents.
    return new Response('Push temporarily unavailable',{status:503});
  }
}
