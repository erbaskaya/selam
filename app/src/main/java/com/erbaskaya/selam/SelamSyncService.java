package com.erbaskaya.selam;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps message delivery independent of an Activity. Android shows a persistent control. */
public class SelamSyncService extends Service {
    private static final int ID=6201;
    private static final String CHANNEL="selam_connection";
    private SupabaseClient api;
    private SelamAlerts alerts;
    private RealtimeConnection realtime;
    private String user="";
    private final Handler main=new Handler(Looper.getMainLooper());
    static boolean enabled(Context context) { return context.getSharedPreferences("selam_delivery",0).getBoolean("background",true); }
    static void setEnabled(Context context,boolean value) {
        context.getSharedPreferences("selam_delivery",0).edit().putBoolean("background",value).apply();
        if(value)start(context);else context.stopService(new Intent(context,SelamSyncService.class));
    }
    static void start(Context context) {
        if(!enabled(context))return;
        try {context.startForegroundService(new Intent(context,SelamSyncService.class));}
        catch(IllegalStateException|SecurityException ignored) { /* Foreground screen will retry on resume. */ }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&"stop".equals(intent.getAction())){setEnabled(this,false);return START_NOT_STICKY;}
        if(!enabled(this)){stopSelf();return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Mesaj bağlantısı",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);manager.createNotificationChannel(channel);
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,SelamSyncService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_nav_chats)
            .setContentTitle("Selam mesajları dinliyor").setContentText("Arka plan bağlantısı açık")
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(null,"Durdur",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        else startForeground(ID,notification);
        if(api==null)api=new SupabaseClient(this);
        if(!api.hasSession()){stopSelf();return START_NOT_STICKY;}
        if(!user.equals(api.userId())){
            if(realtime!=null)realtime.close();if(alerts!=null)alerts.close();
            user=api.userId();alerts=new SelamAlerts(this,api);alerts.start();
            realtime=new RealtimeConnection(api,new RealtimeConnection.Listener(){
                public void onChange(String kind){alerts.refresh(kind);SyncEvents.dispatch(kind);}
                public void onConnection(boolean connected){alerts.setRealtimeConnected(connected);}
            });
            realtime.start();
        }else if(alerts!=null)alerts.refresh("all");
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);if(realtime!=null)realtime.close();if(alerts!=null)alerts.close();if(api!=null)api.close();super.onDestroy();}
}
