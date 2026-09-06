package com.erbaskaya.selam;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/** No network fetch: display the user-visible notification within FCM's short callback. */
public final class SelamMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token){PushRegistration.save(this,token);}
    @Override public void onMessageReceived(RemoteMessage message){
        Map<String,String> data=message.getData();SupabaseClient api=new SupabaseClient(this);
        try {
            if(!api.hasSession()||!api.userId().equals(data.get("user_id"))||!"message".equals(data.get("kind")))return;
            String chat=data.get("chat_id");long id=Long.parseLong(data.getOrDefault("message_id","0"));
            if(chat==null||id<=0)return;
            MessageNotifications.deliver(this,api.userId(),new SupabaseClient.MessageNotification(id,chat,"Selam","Yeni mesajınız var"));
            SyncEvents.dispatch("message");
        }catch(NumberFormatException ignored){}finally{api.close();}
    }
    @Override public void onDeletedMessages(){SyncEvents.dispatch("all");}
}
