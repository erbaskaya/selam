package com.erbaskaya.selam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.provider.Settings;

/** Shared delivery/deduplication for Realtime and FCM; respects Android channel choices. */
final class MessageNotifications {
    static final int TEST_ID=6301;
    static String channel(Context c){Appearance a=new Appearance(c);return channel(c,a.sound()&&!a.quiet(),a.vibration()&&!a.quiet());}
    static String channel(Context c,boolean sound,boolean vibrate){
        String id="selam_messages_"+(sound?"sound":"silent")+(vibrate?"_vibrate":"");
        NotificationChannel channel=new NotificationChannel(id,"Mesajlar • "+(sound?"sesli":"sessiz")+(vibrate?" • titreşim":""),NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(vibrate);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setSound(sound?RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION):null,
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);return id;
    }
    static boolean allowed(Context c,String channel){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        NotificationChannel ch=manager.getNotificationChannel(channel);
        return manager.areNotificationsEnabled()&&ch!=null&&ch.getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    static synchronized boolean deliver(Context c,String user,SupabaseClient.MessageNotification item){
        SharedPreferences seen=c.getSharedPreferences("selam_notified_"+user,0);
        String key="message:"+item.id;
        if(seen.contains(key))return false;
        String channel=channel(c);if(!allowed(c,channel))return false;
        // Both delivery paths use the same persistent marker: one message, one alert.
        SharedPreferences.Editor marker=seen.edit().putLong(key,System.currentTimeMillis());
        for(java.util.Map.Entry<String,?> entry:seen.getAll().entrySet())
            if(entry.getValue() instanceof Long && (Long)entry.getValue()<System.currentTimeMillis()-172800000L)marker.remove(entry.getKey());
        marker.commit();
        if(item.conversationId.equals(ChatActivity.foregroundChat)){playForeground(c,channel);return true;}
        Appearance a=new Appearance(c);
        Intent open=new Intent(c,MainActivity.class).putExtra("open_chat_id",item.conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap=PendingIntent.getActivity(c,item.conversationId.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification safe=new Notification.Builder(c,channel).setSmallIcon(R.drawable.ic_nav_chats).setContentTitle("Selam").setContentText("Yeni mesajınız var").build();
        c.getSystemService(NotificationManager.class).notify(item.conversationId.hashCode(),new Notification.Builder(c,channel)
            .setSmallIcon(R.drawable.ic_nav_chats).setContentTitle(a.preview()?item.senderName:"Selam")
            .setContentText(a.preview()?item.preview:"Yeni mesajınız var").setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(safe).setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).build());
        return true;
    }
    static void playForeground(Context c,String id){
        NotificationManager manager=c.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(id);
        AudioManager audio=c.getSystemService(AudioManager.class);
        if(!allowed(c,id)||channel.getImportance()<NotificationManager.IMPORTANCE_DEFAULT
            ||manager.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL
            ||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return;
        if(channel.getSound()!=null){try{
            Ringtone tone=RingtoneManager.getRingtone(c,channel.getSound());
            if(tone!=null){tone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());tone.play();new Handler(Looper.getMainLooper()).postDelayed(tone::stop,1500);}
        }catch(RuntimeException ignored){}}
    }
    static Intent settings(Context c){return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE,c.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,channel(c));}
    static String diagnostic(Context c){
        String id=channel(c);NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(!allowed(c,id))return "Telefon ayarlarında Selam bildirim izni veya mesaj kanalı kapalı.";
        Appearance a=new Appearance(c);
        if(!a.sound())return "Selam'da mesaj sesi kapalı.";
        if(a.quiet())return "Gece sessizliği şu anda sesi kapatıyor.";
        NotificationChannel ch=manager.getNotificationChannel(id);
        if(ch.getSound()==null||ch.getImportance()<NotificationManager.IMPORTANCE_DEFAULT)return "Mesaj kanalı telefonda sessize alınmış. Kanal ayarından sesi açabilirsiniz.";
        if(manager.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL)return "Telefonun Rahatsız Etmeyin modu açık.";
        if(c.getSystemService(AudioManager.class).getRingerMode()!=AudioManager.RINGER_MODE_NORMAL
            ||c.getSystemService(AudioManager.class).getStreamVolume(AudioManager.STREAM_NOTIFICATION)==0)return "Telefonun bildirim sesi kapalı veya ses düzeyi sıfır.";
        return "Bildirim izni ve mesaj sesi açık.";
    }
    static void test(Context c){String channel=channel(c);if(!allowed(c,channel))return;
        c.getSystemService(NotificationManager.class).notify(TEST_ID,new Notification.Builder(c,channel)
            .setSmallIcon(R.drawable.ic_nav_chats).setContentTitle("Selam ses testi")
            .setContentText("Bu bildirim, mesajlarla aynı ses ayarını kullanır.").setAutoCancel(true).setTimeoutAfter(10000).build());}
}
