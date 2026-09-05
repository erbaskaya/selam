package com.erbaskaya.selam;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SelamAlerts {
    private static final String MESSAGE_CHANNEL = "selam_messages";
    private static final String CALL_CHANNEL = "selam_calls";
    private static final long POLL_MS = 3_000L;

    private final Activity activity;
    private final SupabaseClient api;
    private final NotificationManager notifications;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> announcedCalls = new HashSet<>();
    private final Runnable pollMessages = this::checkMessages;
    private final Runnable pollCalls = this::checkCalls;
    private boolean running;

    SelamAlerts(Activity activity, SupabaseClient api) {
        this.activity = activity;
        this.api = api;
        notifications = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
        preferences = activity.getSharedPreferences("selam_alerts", Context.MODE_PRIVATE);
        createChannels();
    }

    void start() {
        if (running) return;
        running = true;
        handler.post(pollMessages);
        handler.post(pollCalls);
    }

    private void checkMessages() {
        if (!running || !api.hasSession()) return;
        long after = preferences.getLong("last_message_id", 0L);
        boolean initialized = preferences.getBoolean("messages_initialized", false);
        api.listMessageNotifications(after, new SupabaseClient.Callback<List<SupabaseClient.MessageNotification>>() {
            @Override public void onSuccess(List<SupabaseClient.MessageNotification> items) {
                long newest = after;
                for (SupabaseClient.MessageNotification item : items) newest = Math.max(newest, item.id);
                if (!initialized) {
                    preferences.edit().putBoolean("messages_initialized", true)
                            .putLong("last_message_id", newest).apply();
                } else {
                    for (SupabaseClient.MessageNotification item : items) postMessage(item);
                    if (newest > after) preferences.edit().putLong("last_message_id", newest).apply();
                }
                scheduleMessages();
            }
            @Override public void onError(String message) { scheduleMessages(); }
        });
    }

    private void checkCalls() {
        if (!running || !api.hasSession()) return;
        api.listIncomingCalls(new SupabaseClient.Callback<List<SupabaseClient.IncomingCall>>() {
            @Override public void onSuccess(List<SupabaseClient.IncomingCall> calls) {
                for (SupabaseClient.IncomingCall call : calls) {
                    if (announcedCalls.add(call.id)) postIncomingCall(call);
                }
                scheduleCalls();
            }
            @Override public void onError(String message) { scheduleCalls(); }
        });
    }

    private void scheduleMessages() {
        handler.removeCallbacks(pollMessages);
        if (running) handler.postDelayed(pollMessages, POLL_MS);
    }

    private void scheduleCalls() {
        handler.removeCallbacks(pollCalls);
        if (running) handler.postDelayed(pollCalls, POLL_MS);
    }

    private void postMessage(SupabaseClient.MessageNotification item) {
        if(item.conversationId.equals(ChatActivity.foregroundChat)) return;
        Appearance look=new Appearance(activity);
        boolean sound=look.sound()&&!look.quiet(),vibrate=look.vibration()&&!look.quiet();
        String channel=messageChannel(sound,vibrate);
        if (!canNotify()) {
            if(sound)playDefaultSound(RingtoneManager.TYPE_NOTIFICATION);
            if(vibrate){android.os.Vibrator vibrator=(android.os.Vibrator)activity.getSystemService(Context.VIBRATOR_SERVICE);if(vibrator!=null)vibrator.vibrate(android.os.VibrationEffect.createOneShot(160,android.os.VibrationEffect.DEFAULT_AMPLITUDE));}
            return;
        }
        Intent open = new Intent(activity, MainActivity.class).putExtra("open_chat_id",item.conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(activity, (int) (item.id & 0x7fffffff),
                open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(activity, channel)
                .setSmallIcon(R.drawable.ic_nav_chats)
                .setContentTitle(look.preview()?item.senderName:"Selam")
                .setContentText(look.preview()?item.preview:"Yeni mesajınız var")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(new Notification.Builder(activity,channel).setSmallIcon(R.drawable.ic_nav_chats).setContentTitle("Selam").setContentText("Yeni mesajınız var").build())
                .setStyle(new Notification.BigTextStyle().bigText(look.preview()?item.preview:"Yeni mesajınız var"))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        notifications.notify((int) (item.id & 0x7fffffff), notification);
    }

    private void postIncomingCall(SupabaseClient.IncomingCall call) {
        activity.runOnUiThread(()->{ChatActivity chat=ChatActivity.foreground.get();if(chat!=null)chat.incomingCall(call);});
        if (activity instanceof MainActivity && activity.hasWindowFocus()) {
            activity.runOnUiThread(() -> ((MainActivity) activity).showIncomingCall(call));
        }
        if (!canNotify()) {
            playDefaultSound(RingtoneManager.TYPE_RINGTONE);
            return;
        }
        Intent answer = new Intent(activity, CallActivity.class)
                .putExtra(CallActivity.EXTRA_CALL_ID, call.id)
                .putExtra(CallActivity.EXTRA_NAME, call.callerName)
                .putExtra(CallActivity.EXTRA_INCOMING, true);
        PendingIntent content = PendingIntent.getActivity(activity, call.id.hashCode(), answer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(activity, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(call.callerName)
                .setContentText("Selam internet araması")
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .build();
        notifications.notify(call.id.hashCode(), notification);
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void playDefaultSound(int type) {
        try {
            Ringtone ringtone = RingtoneManager.getRingtone(activity,
                    RingtoneManager.getDefaultUri(type));
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) { }
    }

    private String messageChannel(boolean sound,boolean vibrate){
        String id="selam_messages_"+(sound?"sound":"silent")+(vibrate?"_vibrate":"");
        NotificationChannel channel=new NotificationChannel(id,"Mesajlar • "+(sound?"sesli":"sessiz")+(vibrate?" • titreşim":""),NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(vibrate);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        channel.setSound(sound?RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION):null,
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT).build());
        notifications.createNotificationChannel(channel);return id;
    }
    private void createChannels() {
        Uri callSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        AudioAttributes callAudio = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build();
        NotificationChannel calls = new NotificationChannel(CALL_CHANNEL,
                "Selam aramaları", NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription("Gelen Selam internet aramaları");
        calls.enableVibration(true);
        calls.setSound(callSound, callAudio);
        notifications.createNotificationChannel(calls);
    }

    void close() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }
}
