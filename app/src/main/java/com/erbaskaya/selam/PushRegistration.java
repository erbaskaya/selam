package com.erbaskaya.selam;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.UUID;

final class PushRegistration {
    static synchronized boolean initialize(Context c) {
        if(!BuildConfig.PUSH_CONFIGURED)return false;
        if(FirebaseApp.getApps(c).isEmpty())FirebaseApp.initializeApp(c,new FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build());
        return true;
    }
    static void sync(Context context) {
        Context c=context.getApplicationContext();
        if(!initialize(c))return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token->save(c,token))
            .addOnFailureListener(error->prefs(c).edit().putString("status","Bildirim bağlantısı kurulamadı. İnterneti ve Google Play hizmetlerini kontrol edin.").apply());
    }
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("selam_push",0);}
    static void save(Context c,String token){
        SharedPreferences p=prefs(c);String install=p.getString("installation","");
        if(install.isEmpty()){install=UUID.randomUUID().toString();p.edit().putString("installation",install).commit();}
        p.edit().putString("token",token).apply();
        SupabaseClient api=new SupabaseClient(c);
        if(!api.hasSession()){api.close();return;}
        String account=api.userId();
        api.rpc("selam_register_push",SupabaseClient.json("p_installation",install,"p_token",token),new SupabaseClient.Callback<String>(){
            public void onSuccess(String value){p.edit().putString("account",account).putString("status","Telefonun bildirim kaydı hazır").apply();api.close();}
            public void onError(String error){p.edit().putString("status","Telefonun bildirim kaydı tamamlanamadı. Uygulamayı yeniden açarak deneyin.").apply();api.close();}
        });
    }
    static String status(Context c){return BuildConfig.PUSH_CONFIGURED?prefs(c).getString("status","Bildirim bağlantısı hazırlanıyor"):
        "Uygulama kapalıyken bildirim bağlantısı henüz etkin değil";}
}
