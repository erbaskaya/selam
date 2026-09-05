package com.erbaskaya.selam;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class ChatExperienceTest {
    private ActivityController<TestChat> controller;
    public static class TestChat extends ChatActivity {
        FakeClient fake;
        @Override SupabaseClient newClient(){fake=new FakeClient(this);return fake;}
    }
    static class FakeClient extends SupabaseClient {
        boolean failSend;String lastAction="";
        List<Message> items=new ArrayList<>();
        FakeClient(Context context){super(context);try{items.add(Message.parse(new JSONObject().put("message_id",1).put("sender_id","peer").put("sender_name","Other").put("message_body","Merhaba").put("created_at","2026-09-05T07:00:00Z")));}catch(Exception e){throw new RuntimeException(e);}}
        @Override boolean hasSession(){return true;}
        @Override String userId(){return "test-user";}
        @Override void messages(String id,String query,boolean starred,Long before,Callback<List<Message>> cb){cb.onSuccess(new ArrayList<>(items));}
        @Override void rpc(String name,JSONObject payload,Callback<String> cb){lastAction=name;if(name.equals("selam_send")&&failSend)cb.onError("Bağlantı yok");else cb.onSuccess("true");}
    }
    @After public void tearDown(){if(controller!=null)controller.pause().stop().destroy();}
    private TestChat launch(){Context app=RuntimeEnvironment.getApplication();app.getSharedPreferences("selam_drafts_test-user",0).edit().clear().commit();controller=Robolectric.buildActivity(TestChat.class,new Intent(app,TestChat.class).putExtra("chat_id","chat").putExtra("name","Fatma").putExtra("direct",true)).setup();Shadows.shadowOf(Looper.getMainLooper()).idle();return controller.get();}
    private View find(View view,String desc){if(desc.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),desc);if(found!=null)return found;}}return null;}
    private <T extends View>T ofType(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){T v=ofType(g.getChildAt(i),type);if(v!=null)return v;}}return null;}
    @Test public void failedSendKeepsDraftAndRetryClearsIt(){TestChat a=launch();View decor=a.getWindow().getDecorView();EditText field=ofType(decor,EditText.class);field.setText("Yarım kalan mesaj");a.fake.failSend=true;find(decor,"Mesajı gönder").performClick();assertEquals("Yarım kalan mesaj",field.getText().toString());assertEquals("Yarım kalan mesaj",a.getSharedPreferences("selam_drafts_test-user",0).getString("draft:chat",""));a.fake.failSend=false;find(decor,"Mesajı gönder").performClick();assertEquals("",field.getText().toString());}
    @Test public void composerRespectsBothSystemBarsOnSmallScreen(){TestChat a=launch();ViewGroup content=a.findViewById(android.R.id.content);View page=content.getChildAt(0);page.dispatchApplyWindowInsets(new WindowInsets(new Rect(0,24,0,48)));page.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));page.layout(0,0,360,640);assertEquals(24,page.getPaddingTop());assertEquals(48,page.getPaddingBottom());View send=find(page,"Mesajı gönder");assertNotNull(send);Rect bounds=new Rect();send.getDrawingRect(bounds);((ViewGroup)page).offsetDescendantRectToMyCoords(send,bounds);assertTrue("Composer above navigation area",bounds.bottom<=592);assertTrue("Composer is visible",bounds.height()>0);}
    @Test public void draftsSurviveActivityRecreation(){TestChat a=launch();ofType(a.getWindow().getDecorView(),EditText.class).setText("Taslak");controller.recreate();assertEquals("Taslak",ofType(controller.get().getWindow().getDecorView(),EditText.class).getText().toString());}
    @Test public void messageMenuOffersReplyAndStar(){TestChat a=launch();ListView list=ofType(a.getWindow().getDecorView(),ListView.class);list.getOnItemLongClickListener().onItemLongClick(list,null,0,1);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView menu=dialog.getListView();List<String> items=new ArrayList<>();for(int i=0;i<menu.getAdapter().getCount();i++)items.add(menu.getAdapter().getItem(i).toString());assertTrue(items.contains("Yanıtla"));assertTrue(items.contains("Yıldızla"));assertFalse(items.contains("Herkesten sil"));menu.performItemClick(null,items.indexOf("Yıldızla"),0);assertEquals("selam_message_action",a.fake.lastAction);}
    @Test public void themesUseGlobalFallbackAndChatOverride(){Context app=RuntimeEnvironment.getApplication();Appearance look=new Appearance(app);look.prefs.edit().clear().commit();look.put("accent",null,"#1251A5");look.put("accent","chat","#176F68");assertNotEquals(look.accent(null),look.accent("chat"));look.clearChat("chat");assertEquals(look.accent(null),look.accent("chat"));look.put("mode",null,"dark");assertTrue(look.dark());assertNotEquals(look.surface(),look.text());Appearance restored=new Appearance(app);assertTrue(restored.dark());look.prefs.edit().clear().commit();}
    @Test public void personalizationScreenCanRenderInDarkMode(){Context app=RuntimeEnvironment.getApplication();Appearance look=new Appearance(app);look.put("mode",null,"dark");try(ActivityController<AppearanceActivity> c=Robolectric.buildActivity(AppearanceActivity.class).setup()){assertNotNull(ofType(c.get().getWindow().getDecorView(),ScrollView.class));}look.prefs.edit().clear().commit();}
}
