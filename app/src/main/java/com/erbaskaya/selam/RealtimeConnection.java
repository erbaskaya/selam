package com.erbaskaya.selam;

import android.os.Handler;
import android.os.Looper;
import okhttp3.*;
import org.json.*;
import java.util.concurrent.TimeUnit;

/** Supabase Phoenix v1 protocol, with per-user RLS, reconnect and token rotation. */
final class RealtimeConnection {
    interface Listener { void onChange(String kind); }
    private final SupabaseClient api;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build();
    private WebSocket socket;
    private String topic,token;
    private boolean running,joined;
    private int generation,attempt,ref;
    private String heartbeatRef;
    private final Runnable reconnect = this::connect;
    private final Runnable joinTimeout = () -> disconnected(generation);
    private final Runnable heartbeat = this::beat;

    RealtimeConnection(SupabaseClient api,Listener listener) { this.api=api;this.listener=listener; }
    void start() { if(running)return;running=true;connect(); }
    private void connect() {
        if(!running)return;
        final int current=++generation;
        api.realtimeToken(new SupabaseClient.Callback<String>() {
            public void onSuccess(String value) { main.post(() -> {
                if(!running||current!=generation)return;
                token=value;topic="realtime:selam:"+api.userId();
                String url=BuildConfig.SUPABASE_URL.replaceFirst("^https:","wss:")
                        +"/realtime/v1/websocket?apikey="+BuildConfig.SUPABASE_ANON_KEY+"&vsn=1.0.0";
                socket=http.newWebSocket(new Request.Builder().url(url).build(),new WebSocketListener(){
                    @Override public void onOpen(WebSocket ws,Response response){main.post(()->{
                        if(current!=generation||!running){ws.cancel();return;}
                        socket=ws;send(topic,"phx_join",joinPayload(api.userId(),token));
                        main.postDelayed(joinTimeout,12000);
                    });}
                    @Override public void onMessage(WebSocket ws,String text){main.post(()->receive(current,text));}
                    @Override public void onFailure(WebSocket ws,Throwable error,Response response){main.post(()->disconnected(current));}
                    @Override public void onClosed(WebSocket ws,int code,String reason){main.post(()->disconnected(current));}
                    @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);main.post(()->disconnected(current));}
                });
            }); }
            public void onError(String error) { main.post(()->disconnected(current)); }
        });
    }
    static JSONObject joinPayload(String userId,String token) {
        return SupabaseClient.json("access_token",token,"config",SupabaseClient.json(
            "broadcast",SupabaseClient.json("self",false),"presence",SupabaseClient.json("enabled",false),
            "postgres_changes",new JSONArray().put(SupabaseClient.json("event","*","schema","public",
                "table","selam_delivery_events","filter","user_id=eq."+userId))));
    }
    static String changeKind(JSONObject message,String userId) {
        if(!"postgres_changes".equals(message.optString("event")))return null;
        JSONObject payload=message.optJSONObject("payload");
        JSONObject data=payload==null?null:payload.optJSONObject("data");
        if(data==null||!"public".equals(data.optString("schema"))||!"selam_delivery_events".equals(data.optString("table")))return null;
        JSONObject row=data.optJSONObject("record");
        if(row==null||!userId.equals(row.optString("user_id")))return null;
        String kind=row.optString("kind");return "call".equals(kind)||"message".equals(kind)?kind:null;
    }
    private void receive(int current,String text) {
        if(!running||current!=generation)return;
        try {
            JSONObject message=new JSONObject(text);String event=message.optString("event");
            if("phx_reply".equals(event)){
                if(message.optString("ref").equals(heartbeatRef)){heartbeatRef=null;return;}
                if(topic.equals(message.optString("topic"))&&!joined){
                    if(!"ok".equals(message.getJSONObject("payload").optString("status"))){disconnected(current);return;}
                    joined=true;attempt=0;main.removeCallbacks(joinTimeout);main.post(heartbeat);
                    listener.onChange("all"); // Catch up after a network gap before listening for more events.
                }
            } else if("phx_error".equals(event)||"phx_close".equals(event))disconnected(current);
            else if("system".equals(event)&&"error".equals(message.getJSONObject("payload").optString("status")))disconnected(current);
            else {String kind=changeKind(message,api.userId());if(kind!=null)listener.onChange(kind);}
        }catch(JSONException ignored) { }
    }
    private void beat() {
        if(!running||!joined)return;
        if(heartbeatRef!=null){disconnected(generation);return;}
        String latest=api.sessionAccessToken();
        if(!latest.equals(token)){token=latest;send(topic,"access_token",SupabaseClient.json("access_token",token));}
        heartbeatRef=send("phoenix","heartbeat",new JSONObject());
        main.postDelayed(heartbeat,20000);
    }
    private String send(String destination,String event,JSONObject payload) {
        String next=Integer.toString(++ref);
        if(socket!=null)socket.send(SupabaseClient.json("topic",destination,"event",event,"payload",payload,"ref",next).toString());
        return next;
    }
    private void disconnected(int current) {
        if(!running||current!=generation)return;
        generation++;joined=false;heartbeatRef=null;main.removeCallbacksAndMessages(null);
        if(socket!=null){socket.cancel();socket=null;}
        main.postDelayed(reconnect,Math.min(30000,1000L<<Math.min(attempt++,5)));
    }
    void close() { running=false;generation++;main.removeCallbacksAndMessages(null);if(socket!=null)socket.cancel();socket=null;http.dispatcher().executorService().shutdown();http.connectionPool().evictAll(); }
}
