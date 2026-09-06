package com.erbaskaya.selam;

import android.app.*;
import android.content.*;
import android.os.*;

/** Ringing dialogs follow the server state even before the callee answers. */
final class IncomingCallPrompt {
    private final Activity activity;
    private final SupabaseClient api;
    private final SupabaseClient.IncomingCall call;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=this::check;
    private final SyncEvents.Listener listener=kind->{if(!"message".equals(kind))check();};
    private boolean closed,busy;
    private AlertDialog dialog;
    IncomingCallPrompt(Activity activity,SupabaseClient api,SupabaseClient.IncomingCall call){this.activity=activity;this.api=api;this.call=call;}
    void show(Runnable dismissed){
        dialog=new AlertDialog.Builder(activity).setTitle(call.callerName).setMessage("Gelen Selam internet araması")
            .setNegativeButton("Reddet",(d,w)->CallTermination.send(activity,call.id,true))
            .setPositiveButton("Yanıtla",(d,w)->activity.startActivity(new Intent(activity,CallActivity.class)
                .putExtra(CallActivity.EXTRA_CALL_ID,call.id).putExtra(CallActivity.EXTRA_CHAT_ID,call.conversationId)
                .putExtra(CallActivity.EXTRA_NAME,call.callerName).putExtra(CallActivity.EXTRA_INCOMING,true)
                .putExtra(CallActivity.EXTRA_AUTO_ANSWER,true)))
            .setOnDismissListener(d->{close();dismissed.run();}).create();
        dialog.show();SyncEvents.add(listener);check();
    }
    private void check(){
        handler.removeCallbacks(poll);if(closed||busy)return;
        if(activity.isFinishing()||activity.isDestroyed()){dismiss();return;}busy=true;
        api.getCallState(call.id,new SupabaseClient.Callback<SupabaseClient.CallState>(){
            public void onSuccess(SupabaseClient.CallState state){handler.post(()->{busy=false;if(closed)return;
                if(!"ringing".equals(state.state)){dismiss();return;}handler.postDelayed(poll,1000);});}
            public void onError(String error){handler.post(()->{busy=false;if(!closed)handler.postDelayed(poll,1500);});}
        });
    }
    void dismiss(){if(dialog!=null)dialog.dismiss();close();activity.getSystemService(NotificationManager.class).cancel(call.id.hashCode());}
    private void close(){closed=true;handler.removeCallbacksAndMessages(null);SyncEvents.remove(listener);}
    boolean showing(){return dialog!=null&&dialog.isShowing();}
}
