package com.erbaskaya.selam;
import android.content.Context;
import android.os.*;
/** Owned separately so closing CallActivity cannot cancel the hang-up HTTP request. */
final class CallTermination {
    static void send(Context context,String callId,boolean decline){if(callId!=null)attempt(new SupabaseClient(context.getApplicationContext()),callId,decline,0);}
    private static void attempt(SupabaseClient client,String id,boolean decline,int retry){
        SupabaseClient.Callback<Boolean> callback=new SupabaseClient.Callback<Boolean>(){
            public void onSuccess(Boolean result){client.close();SyncEvents.dispatch("call");}
            public void onError(String error){if(retry<2)new Handler(Looper.getMainLooper()).postDelayed(()->attempt(client,id,decline,retry+1),500L<<retry);else client.close();}
        };
        if(decline)client.declineAudioCall(id,callback);else client.endAudioCall(id,callback);
    }
}
