package com.erbaskaya.selam;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class PushSoundAndHangupTest {
 Context app;
 @Before public void before(){app=RuntimeEnvironment.getApplication();app.getSharedPreferences("selam_notified_tester",0).edit().clear().commit();
  app.getSharedPreferences("selam_appearance",0).edit().clear().commit();ChatActivity.foregroundChat=null;
  Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);}
 @Test public void eachDistinctMessageAlertsOnceEvenOutOfOrder(){
  assertTrue(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(12,"chat","Sender","Two")));
  assertTrue(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(11,"chat","Sender","One")));
  assertFalse(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(12,"chat","Sender","Duplicate via polling")));
 }
 @Test public void soundOffChoosesSilentChannelAndSoundOnChoosesAudibleChannel(){
  Appearance a=new Appearance(app);a.prefs.edit().putBoolean("sound",false).commit();
  NotificationManager manager=app.getSystemService(NotificationManager.class);
  assertNull(manager.getNotificationChannel(MessageNotifications.channel(app)).getSound());
  a.prefs.edit().putBoolean("sound",true).commit();
  assertNotNull(manager.getNotificationChannel(MessageNotifications.channel(app)).getSound());
 }
 @Test public void deniedNotificationPermissionDoesNotFallBackToPlayingNoise(){
  Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
  assertFalse(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(1,"chat","Sender","Hello")));
  assertNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("chat".hashCode()));
  assertTrue(MessageNotifications.diagnostic(app).contains("kapalı"));
 }
 @Test public void openConversationHasNoTrayNotificationButDeduplicatesTheSound(){
  ChatActivity.foregroundChat="chat";
  assertTrue(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(1,"chat","Sender","Hello")));
  assertFalse(MessageNotifications.deliver(app,"tester",new SupabaseClient.MessageNotification(1,"chat","Sender","Hello")));
  assertNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("chat".hashCode()));
 }
 @Test public void soundTestUsesTheActualMessageChannel(){
  MessageNotifications.test(app);
  Notification n=Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification(MessageNotifications.TEST_ID);
  assertEquals(MessageNotifications.channel(app),n.getChannelId());
 }
 static class CallApi extends DeliveryAndCallTest.FakeClient {
  String state="ringing";CallApi(Context c){super(c);}
  @Override void getCallState(String id,Callback<CallState> cb){cb.onSuccess(new CallState(id,"caller","callee",state,"",""));}
 }
 @Test public void incomingDialogDisappearsWhenCallerHangsUp(){
  var controller=Robolectric.buildActivity(Activity.class).setup();CallApi api=new CallApi(app);
  try{
   SupabaseClient.IncomingCall call=new SupabaseClient.IncomingCall("call","chat","Caller");
   IncomingCallPrompt prompt=new IncomingCallPrompt(controller.get(),api,call);prompt.show(()->{});
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(prompt.showing());
   api.state="ended";SyncEvents.dispatch("call");Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(prompt.showing());
  }finally{controller.pause().stop().destroy();api.close();}
 }
 public static class EndedCall extends CallActivity {
  @Override SupabaseClient newClient(){CallApi client=new CallApi(this);client.state="ended";return client;}
 }
 @Test public void incomingFullScreenClosesForEndedCall(){
  var controller=Robolectric.buildActivity(EndedCall.class,new Intent(app,EndedCall.class)
   .putExtra(CallActivity.EXTRA_CALL_ID,"call").putExtra(CallActivity.EXTRA_INCOMING,true)).setup();
  try{Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(controller.get().isFinishing());}
  finally{controller.pause().stop().destroy();}
 }
}
