package com.erbaskaya.selam;

import android.Manifest;
import android.app.NotificationManager;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class DeliveryAndCallTest {
    private ActivityController<TestCall> activity;
    @After public void after(){if(activity!=null)activity.pause().stop().destroy();}
    public static class TestCall extends CallActivity {
        @Override SupabaseClient newClient(){return new FakeClient(this);}
        @Override void initializePeer(){throw new SecurityException("Simulated native startup failure");}
    }
    static class FakeClient extends SupabaseClient {
        FakeClient(Context c){super(c);}
        @Override boolean hasSession(){return true;}
        @Override String userId(){return "delivery-test";}
        @Override void realtimeToken(Callback<String> cb){cb.onSuccess("isolated-test-token");}
        @Override String sessionAccessToken(){return "isolated-test-token";}
        @Override void listMessageNotifications(long after,Callback<List<MessageNotification>> cb){cb.onSuccess(after==0?Collections.singletonList(new MessageNotification(7,"chat","Sender","Merhaba")):Collections.emptyList());}
        @Override void listIncomingCalls(Callback<List<IncomingCall>> cb){cb.onSuccess(Collections.emptyList());}
    }
    @Test public void nativeStartupFailureStaysVisibleInsteadOfClosing(){
        Context app=RuntimeEnvironment.getApplication();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        activity=Robolectric.buildActivity(TestCall.class,new Intent(app,TestCall.class).putExtra(CallActivity.EXTRA_CHAT_ID,"chat")).setup();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertFalse(activity.get().isFinishing());
        assertTrue(text(activity.get().getWindow().getDecorView()).contains("SecurityException"));
    }
    @Test public void apkDeclaresRequiredWebRtcNetworkPermission() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        String[] permissions=app.getPackageManager().getPackageInfo(app.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertTrue(Arrays.asList(permissions).contains(Manifest.permission.ACCESS_NETWORK_STATE));
    }
    @Test public void firstUnreadMessageNotifiesWithoutAnActivity(){
        Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("selam_alerts_delivery-test",0).edit().clear().commit();
        ChatActivity.foregroundChat=null;Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        FakeClient api=new FakeClient(app);SelamAlerts alerts=new SelamAlerts(app,api);
        try {alerts.start();Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification("chat".hashCode()));
            assertEquals(7,app.getSharedPreferences("selam_alerts_delivery-test",0).getLong("last_message_id",0));
        } finally {alerts.close();api.close();}
    }
    @Test public void deliveryHintsRejectOtherAccountsAndAcceptOwnMessage(){
        JSONObject message=SupabaseClient.json("event","postgres_changes","payload",SupabaseClient.json("data",SupabaseClient.json("schema","public","table","selam_delivery_events","record",SupabaseClient.json("user_id","mine","kind","message"))));
        assertNull(RealtimeConnection.changeKind(message,"other"));
        assertEquals("message",RealtimeConnection.changeKind(message,"mine"));
    }
    private String text(View view){String value=view instanceof TextView?((TextView)view).getText().toString():"";if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)value+=text(g.getChildAt(i));}return value;}

    @Test public void websocketAuthenticatesDeliversImmediatelyAndReconnects() throws Exception {
        FakeClient api=new FakeClient(RuntimeEnvironment.getApplication());List<String> events=new ArrayList<>();List<FakeSocket> sockets=new ArrayList<>();
        RealtimeConnection connection=new RealtimeConnection(api,events::add,(request,listener)->{
            FakeSocket socket=new FakeSocket(request,listener);sockets.add(socket);listener.onOpen(socket,null);return socket;
        });
        try {
            connection.start();Shadows.shadowOf(Looper.getMainLooper()).idle();
            FakeSocket first=sockets.get(0);JSONObject join=first.sent.get(0);
            assertEquals("phx_join",join.getString("event"));assertEquals(join.getString("ref"),join.getString("join_ref"));
            JSONObject payload=join.getJSONObject("payload");assertEquals("isolated-test-token",payload.getString("access_token"));
            assertEquals("user_id=eq.delivery-test",payload.getJSONObject("config").getJSONArray("postgres_changes").getJSONObject(0).getString("filter"));
            first.receive(SupabaseClient.json("topic",join.getString("topic"),"event","phx_reply","ref",join.getString("ref"),"payload",SupabaseClient.json("status","ok")));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(Collections.singletonList("all"),events);
            first.receive(SupabaseClient.json("event","postgres_changes","payload",SupabaseClient.json("data",SupabaseClient.json("schema","public","table","selam_delivery_events","record",SupabaseClient.json("user_id","delivery-test","kind","message")))));
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(Arrays.asList("all","message"),events);
            first.listener.onFailure(first,new java.io.IOException("network changed"),null);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(2,sockets.size());assertEquals("phx_join",sockets.get(1).sent.get(0).getString("event"));
        } finally {connection.close();api.close();}
    }
    static class FakeSocket implements okhttp3.WebSocket {
        final okhttp3.Request request;final okhttp3.WebSocketListener listener;final List<JSONObject> sent=new ArrayList<>();
        FakeSocket(okhttp3.Request request,okhttp3.WebSocketListener listener){this.request=request;this.listener=listener;}
        void receive(JSONObject message){listener.onMessage(this,message.toString());}
        public okhttp3.Request request(){return request;}public long queueSize(){return 0;}
        public boolean send(String value){try{sent.add(new JSONObject(value));return true;}catch(Exception e){throw new AssertionError(e);}}
        public boolean send(okio.ByteString value){return true;}public boolean close(int code,String reason){return true;}public void cancel(){}
    }
}
