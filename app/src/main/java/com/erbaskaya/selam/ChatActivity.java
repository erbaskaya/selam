package com.erbaskaya.selam;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** Native conversation UI. Server RPCs enforce membership and message ownership. */
public class ChatActivity extends Activity {
    static volatile String foregroundChat;
    static java.lang.ref.WeakReference<ChatActivity> foreground=new java.lang.ref.WeakReference<>(null);
    private static final int FILE=71, EXPORT=72, RECORD=73;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SupabaseClient api;
    private Appearance look;
    private String chatId,chatName,query="",fingerprint="";
    private boolean direct,starred,active,fetching,sending,olderMode;
    private int requestGeneration;
    private long markedRead;
    private List<SupabaseClient.Message> messages=new ArrayList<>();
    private ListView list;
    private EditText composer;
    private TextView status,reply,heading;
    private Button send,older;
    private SupabaseClient.Message replying;
    private SharedPreferences drafts;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File voiceFile;
    private AlertDialog recordDialog,playDialog;
    private final Runnable poll=()->refresh(false);
    private final Runnable recordTimeout=()->stopRecording(true);
    private final Runnable presence=new Runnable(){public void run(){if(active){rpc("selam_presence",SupabaseClient.json(),s->{});handler.postDelayed(this,30000);}}};

    SupabaseClient newClient(){return new SupabaseClient(this);}
    @Override public void onCreate(Bundle state){
        look=new Appearance(this);
        setTheme(look.dark()?R.style.Theme_Selam_Dark:R.style.Theme_Selam);
        super.onCreate(state);
        api=newClient();
        chatId=getIntent().getStringExtra("chat_id");chatName=getIntent().getStringExtra("name");
        direct=getIntent().getBooleanExtra("direct",true);
        if(chatId==null||!api.hasSession()){finish();return;}
        drafts=getSharedPreferences("selam_drafts_"+api.userId(),MODE_PRIVATE);
        build();
    }
    @Override public void onResume(){super.onResume();if(api==null||chatId==null)return;active=true;foregroundChat=chatId;foreground=new java.lang.ref.WeakReference<>(this);olderMode=false;build();refresh(true);handler.post(presence);}
    @Override public void onPause(){active=false;foregroundChat=null;foreground.clear();handler.removeCallbacks(poll);handler.removeCallbacks(presence);saveDraft();super.onPause();}
    @Override public void onStop(){if(recorder!=null)stopRecording(false);stopPlayer();super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);stopPlayer();if(api!=null)api.close();super.onDestroy();}
    private String draftKey(){return "draft:"+chatId;}
    private void saveDraft(){if(drafts!=null&&composer!=null)drafts.edit().putString(draftKey(),composer.getText().toString()).apply();}
    private void build(){
        saveDraft();look=new Appearance(this);
        getWindow().setStatusBarColor(look.surface());getWindow().setNavigationBarColor(look.surface());
        getWindow().getDecorView().setSystemUiVisibility(look.dark()?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page=column();page.setBackgroundColor(look.background());
        page.setOnApplyWindowInsetsListener((v,i)->{int bottom=i.getSystemWindowInsetBottom();if(Build.VERSION.SDK_INT>=30)bottom=Math.max(bottom,i.getInsets(WindowInsets.Type.ime()).bottom);v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),bottom);return i;});
        LinearLayout bar=row();bar.setBackgroundColor(look.surface());
        bar.addView(action("‹","Geri",this::finish),size(48,56));
        heading=text(chatName,19,look.text(),true);heading.setMaxLines(1);heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setOnClickListener(v->showInfo());bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        if(direct){ImageButton phone=new ImageButton(this);phone.setImageResource(R.drawable.ic_phone);phone.setColorFilter(look.accent(chatId));phone.setBackgroundColor(Color.TRANSPARENT);phone.setContentDescription("İnternet araması");phone.setOnClickListener(v->startActivity(new Intent(this,CallActivity.class).putExtra(CallActivity.EXTRA_CHAT_ID,chatId).putExtra(CallActivity.EXTRA_NAME,chatName).putExtra(CallActivity.EXTRA_INCOMING,false)));bar.addView(phone,size(48,48));}
        bar.addView(action("⋮","Sohbet seçenekleri",this::menu),size(48,56));page.addView(bar);
        status=text("Bağlanıyor…",12,look.muted(),false);status.setPadding(dp(16),dp(4),dp(16),dp(4));status.setOnClickListener(v->refresh(true));page.addView(status);
        LinearLayout controls=row();older=action("Önceki mesajlar","Önceki mesajları yükle",this::loadOlder);older.setTextSize(13);controls.addView(older,new LinearLayout.LayoutParams(0,dp(40),1));
        Button latest=action("En yeni ↓","En yeni mesajlara dön",()->{olderMode=false;query="";starred=false;fingerprint="";refresh(true);});latest.setTextSize(13);controls.addView(latest,new LinearLayout.LayoutParams(0,dp(40),1));page.addView(controls);
        FrameLayout area=new FrameLayout(this);area.setBackgroundColor(look.chatBackground(chatId));
        String photo=look.value("photo",chatId,"");if(!photo.isEmpty()){BitmapDrawable drawable=new BitmapDrawable(getResources(),photo);drawable.setGravity(Gravity.FILL);area.setBackground(drawable);}
        list=new ListView(this);list.setDivider(null);list.setCacheColorHint(Color.TRANSPARENT);list.setStackFromBottom(true);list.setClipToPadding(false);list.setPadding(dp(10),dp(8),dp(10),dp(8));list.setAdapter(new MessagesAdapter());
        list.setOnItemLongClickListener((p,v,pos,id)->{messageMenu(messages.get(pos));return true;});
        list.setOnItemClickListener((p,v,pos,id)->{SupabaseClient.Message m=messages.get(pos);if(m.isFile())openFile(m);});
        list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){if(state==0)markRead();}public void onScroll(AbsListView v,int first,int count,int total){}});
        area.addView(list,new FrameLayout.LayoutParams(-1,-1));page.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        reply=text("",14,look.accent(chatId),false);reply.setPadding(dp(16),dp(10),dp(16),dp(10));reply.setBackgroundColor(look.surface());reply.setOnClickListener(v->{replying=null;updateReply();});page.addView(reply);updateReply();
        LinearLayout input=row();input.setPadding(dp(4),dp(6),dp(4),dp(6));input.setBackgroundColor(look.surface());
        input.addView(action("＋","Fotoğraf veya dosya ekle",this::pickFile),size(44,48));
        composer=new EditText(this);composer.setTextColor(look.text());composer.setHintTextColor(look.muted());composer.setTextSize(look.messageSize());composer.setHint("Mesaj");composer.setMaxLines(4);composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});composer.setBackground(round(look.background(),18));composer.setPadding(dp(12),dp(10),dp(12),dp(10));composer.setText(drafts.getString(draftKey(),""));
        composer.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){saveDraft();}public void afterTextChanged(Editable e){}});
        input.addView(composer,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton mic=new ImageButton(this);mic.setImageResource(android.R.drawable.ic_btn_speak_now);mic.setColorFilter(look.accent(chatId));mic.setBackgroundColor(Color.TRANSPARENT);mic.setContentDescription("Sesli mesaj kaydet");mic.setOnClickListener(v->record());input.addView(mic,size(44,48));
        send=action("➤","Mesajı gönder",this::send);send.setTextColor(Color.WHITE);send.setBackground(round(look.accent(chatId),24));input.addView(send,size(48,48));page.addView(input);
        setContentView(page);page.requestApplyInsets();
    }
    private void updateReply(){if(reply==null)return;reply.setVisibility(replying==null?View.GONE:View.VISIBLE);if(replying!=null)reply.setText("Yanıt: "+replying.body+"   ✕");}
    private void refresh(boolean immediate){
        handler.removeCallbacks(poll);if(!active)return;
        if(fetching){handler.postDelayed(poll,1000);return;}if(olderMode&&!immediate){handler.postDelayed(poll,3000);return;}
        fetching=true;int generation=++requestGeneration;
        api.messages(chatId,query,starred,null,cb(items->{fetching=false;if(generation!=requestGeneration)return;
            StringBuilder fp=new StringBuilder();for(SupabaseClient.Message m:items)fp.append(m.fingerprint);
            boolean bottom=list.getCount()==0||list.getLastVisiblePosition()>=list.getCount()-2;
            int first=list.getFirstVisiblePosition(),offset=list.getChildCount()>0?list.getChildAt(0).getTop():0;
            if(!fp.toString().equals(fingerprint)||immediate){messages=items;fingerprint=fp.toString();list.setAdapter(new MessagesAdapter());if(bottom||immediate)list.setSelection(items.size()-1);else list.setSelectionFromTop(first,offset);}
            older.setEnabled(items.size()==100);status.setText(starred?"Yıldızlı mesajlar":!query.isEmpty()?"Arama: "+query:items.isEmpty()?"İlk mesajı gönderin":"");
            list.post(this::markRead);handler.postDelayed(poll,2500);
        },error->{fetching=false;status.setText(error+" · Yeniden denemek için dokunun");handler.postDelayed(poll,5000);}));
    }
    private void markRead(){if(!active||olderMode||starred||!query.isEmpty()||messages.isEmpty()||list.getLastVisiblePosition()<messages.size()-1)return;long id=messages.get(messages.size()-1).id;if(id<=markedRead)return;markedRead=id;api.rpc("selam_mark_read",SupabaseClient.json("p_chat_id",chatId,"p_message_id",id),cb(s->{},s->markedRead=0));}
    private void loadOlder(){if(fetching||messages.isEmpty())return;fetching=true;olderMode=true;api.messages(chatId,query,starred,messages.get(0).id,cb(items->{fetching=false;int count=items.size();messages.addAll(0,items);list.setAdapter(new MessagesAdapter());list.setSelection(count);older.setEnabled(count==100);status.setText("Önceki mesajlar · Yeni mesajlar için En yeni'ye dokunun");},s->{fetching=false;toast(s);}));}
    private void send(){if(sending)return;String body=composer.getText().toString().trim();if(body.isEmpty())return;sending=true;send.setEnabled(false);Long replyId=replying==null?null:replying.id;
        api.rpc("selam_send",SupabaseClient.json("p_chat_id",chatId,"p_body",body,"p_reply_to",replyId),cb(s->{sending=false;send.setEnabled(true);if(composer.getText().toString().trim().equals(body))composer.setText("");replying=null;updateReply();olderMode=false;query="";starred=false;refresh(true);},s->{sending=false;send.setEnabled(true);toast(s);}));}
    private void menu(){String[] choices={"Sohbette ara","Yıldızlı mesajlar","Sohbet teması ve duvar kâğıdı",direct?"Kişi bilgisi":"Grup bilgisi ve üyeler","Bu sohbete özel notum","Görünen mesajları dışa aktar","Sohbeti temizle"};new AlertDialog.Builder(this).setTitle(chatName).setItems(choices,(d,i)->{switch(i){case 0:editDialog("Sohbette ara",query,v->{query=v;starred=false;olderMode=false;refresh(true);});break;case 1:starred=true;query="";olderMode=false;refresh(true);break;case 2:startActivity(new Intent(this,AppearanceActivity.class).putExtra("chat_id",chatId));break;case 3:showInfo();break;case 4:editDialog("Özel not • yalnızca bu cihazda",drafts.getString("note:"+chatId,""),v->drafts.edit().putString("note:"+chatId,v).apply());break;case 5:export();break;case 6:new AlertDialog.Builder(this).setTitle("Sohbet temizlensin mi?").setMessage("Mesajlar yalnızca sizin sohbet görünümünüzden kaldırılır.").setNegativeButton("Vazgeç",null).setPositiveButton("Temizle",(a,b)->api.deleteChat(chatId,cb(done->{olderMode=false;refresh(true);}))).show();}}).show();}
    private void messageMenu(SupabaseClient.Message m){
        List<String> titles=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(!m.deleted){add(titles,actions,"Yanıtla",()->{replying=m;updateReply();composer.requestFocus();});add(titles,actions,"Kopyala",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Selam mesajı",m.body));toast("Kopyalandı");});
            if(!m.isFile())add(titles,actions,"İlet",()->forward(m));
            add(titles,actions,m.starred?"Yıldızı kaldır":"Yıldızla",()->messageAction(m,"star",""));
            add(titles,actions,"Tepki bırak / kaldır",()->{String[] emojis={"👍","❤️","😂","😮","😢","🙏"};new AlertDialog.Builder(this).setTitle("Aynı tepkiye dokunarak kaldırabilirsiniz").setItems(emojis,(d,i)->messageAction(m,"react",emojis[i])).show();});
            if(m.senderId.equals(api.userId())){try{if(!m.isFile()&&Instant.parse(m.createdAt).isAfter(Instant.now().minusSeconds(900)))add(titles,actions,"Düzenle",()->editDialog("Mesajı düzenle",m.body,v->messageAction(m,"edit",v)));}catch(Exception ignored){}add(titles,actions,"Herkesten sil",()->confirm("Mesaj herkesten silinsin mi?",()->messageAction(m,"delete","")));}
        }
        add(titles,actions,"Benden sil",()->confirm("Mesaj sizden silinsin mi?",()->messageAction(m,"hide","")));
        new AlertDialog.Builder(this).setTitle("Mesaj seçenekleri").setItems(titles.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
    }
    private void add(List<String> names,List<Runnable> actions,String title,Runnable action){names.add(title);actions.add(action);}
    private void messageAction(SupabaseClient.Message m,String action,String value){rpc("selam_message_action",SupabaseClient.json("p_message_id",m.id,"p_action",action,"p_value",value),s->{olderMode=false;refresh(true);});}
    private void forward(SupabaseClient.Message m){api.listChats(cb(chats->{String[] names=new String[chats.size()];for(int i=0;i<names.length;i++)names[i]=chats.get(i).displayName;new AlertDialog.Builder(this).setTitle("İletilecek sohbet").setItems(names,(d,i)->confirm(names[i]+" sohbetine iletilsin mi?",()->rpc("selam_send",SupabaseClient.json("p_chat_id",chats.get(i).id,"p_body",m.body),s->toast("İletildi")))).show();}));}
    private void showInfo(){rpc("selam_group_info",SupabaseClient.json("p_chat_id",chatId),body->{try{JSONObject info=new JSONObject(body);JSONArray members=info.optJSONArray("members");if(members==null)return;boolean admin=false;StringBuilder description=new StringBuilder();List<JSONObject> others=new ArrayList<>();for(int i=0;i<members.length();i++){JSONObject p=members.getJSONObject(i);boolean mine=api.userId().equals(p.optString("id"));if(mine)admin=Arrays.asList("owner","admin").contains(p.optString("role"));else others.add(p);description.append(p.optString("name")).append(mine?" (siz)":"").append("\n");if(!p.optString("about").isEmpty())description.append(p.optString("about")).append("\n");if(!p.isNull("last_seen"))description.append("Son görülme: ").append(date(p.optString("last_seen"))).append("\n");description.append("\n");}
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(direct?chatName:info.optString("title")).setMessage(description.toString()).setPositiveButton("Tamam",null);
        if(!direct){b.setNegativeButton("Gruptan ayrıl",(d,w)->confirm("Gruptan ayrılmak istiyor musunuz?",()->groupAction("leave","",this::finish)));if(admin)b.setNeutralButton("Yönet",(d,w)->manageGroup(others));}b.show();
    }catch(Exception e){toast("Bilgiler alınamadı");}});}
    private void manageGroup(List<JSONObject> members){new AlertDialog.Builder(this).setTitle("Grubu yönet").setItems(new String[]{"Grup adını değiştir","Üye ekle","Üye çıkar"},(d,i)->{if(i==0)editDialog("Grup adı",chatName,v->groupAction("rename",v,()->{chatName=v;heading.setText(v);}));else if(i==1)editDialog("Kullanıcı adıyla kişi ara","",v->api.searchPeople(v,cb(people->{String[] names=new String[people.size()];for(int n=0;n<names.length;n++)names[n]=people.get(n).displayName;if(names.length==0){toast("Kişi bulunamadı");return;}new AlertDialog.Builder(this).setTitle("Eklenecek kişi").setItems(names,(a,n)->confirm(names[n]+" gruba eklensin mi?",()->groupAction("add",people.get(n).id,this::showInfo))).show();})));else{String[] names=new String[members.size()];for(int n=0;n<names.length;n++)names[n]=members.get(n).optString("name");new AlertDialog.Builder(this).setTitle("Çıkarılacak üye").setItems(names,(a,n)->confirm(names[n]+" çıkarılsın mı?",()->groupAction("remove",members.get(n).optString("id"),this::showInfo))).show();}}).show();}
    private void groupAction(String action,String value,Runnable done){rpc("selam_group_action",SupabaseClient.json("p_chat_id",chatId,"p_action",action,"p_value",value),s->done.run());}
    private void pickFile(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),FILE);}
    private void export(){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Selam-sohbet.txt"),EXPORT);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(req==FILE){try{String name="Dosya";long size=-1;try(Cursor c=getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),z=c.getColumnIndex(OpenableColumns.SIZE);if(n>=0)name=c.getString(n);if(z>=0)size=c.getLong(z);}}String fileName=name;long fileSize=size;String mime=getContentResolver().getType(uri);confirm(fileName+" gönderilsin mi?",()->{try{api.sendFile(chatId,getContentResolver().openInputStream(uri),fileName,mime,fileSize,cb(done->{toast("Dosya gönderildi");olderMode=false;refresh(true);}));}catch(Exception e){toast("Dosya açılamadı");}});}catch(Exception e){toast("Dosya açılamadı");}}else if(req==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri)){StringBuilder content=new StringBuilder(chatName+"\nGörünen "+messages.size()+" mesaj\n\n");for(SupabaseClient.Message m:messages)content.append(date(m.createdAt)).append(" · ").append(m.senderName).append(": ").append(m.body).append("\n");out.write(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("Kaydedildi");}catch(Exception e){toast("Kaydedilemedi");}}}
    private void openFile(SupabaseClient.Message m){if(m.isAudio()){api.createSignedFileUrl(m.filePath,cb(url->play(url,m.fileName)));return;}api.createSignedFileUrl(m.filePath,cb(url->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){toast("Dosyayı açacak uygulama bulunamadı");}}));}
    private void record(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},RECORD);return;}if(recorder!=null)return;try{voiceFile=File.createTempFile("selam-ses-",".m4a",getCacheDir());recorder=new MediaRecorder();recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioEncodingBitRate(64000);recorder.setAudioSamplingRate(44100);recorder.setOutputFile(voiceFile.getAbsolutePath());recorder.prepare();recorder.start();recordDialog=new AlertDialog.Builder(this).setTitle("Ses kaydediliyor").setMessage("Kayıt en fazla 2 dakika sürer. Durdurduktan sonra dinleyebilir ve gönderebilirsiniz.").setCancelable(false).setNegativeButton("Vazgeç",(d,w)->stopRecording(false)).setPositiveButton("Durdur",(d,w)->stopRecording(true)).show();handler.postDelayed(recordTimeout,120000);}catch(Exception e){stopRecording(false);toast("Mikrofon açılamadı. İzinleri kontrol edin.");}}
    private void stopRecording(boolean keep){handler.removeCallbacks(recordTimeout);if(recorder==null)return;try{recorder.stop();}catch(Exception e){keep=false;}recorder.release();recorder=null;if(recordDialog!=null){recordDialog.dismiss();recordDialog=null;}File file=voiceFile;voiceFile=null;if(file==null)return;if(!keep){file.delete();return;}AlertDialog preview=new AlertDialog.Builder(this).setTitle("Sesli mesaj hazır").setMessage("Göndermeden önce dinleyebilirsiniz.").setNeutralButton("Dinle",null).setNegativeButton("Sil",(d,w)->{stopPlayer();file.delete();}).setPositiveButton("Gönder",(d,w)->{stopPlayer();try{api.sendFile(chatId,new FileInputStream(file),"Sesli-mesaj.m4a","audio/mp4",file.length(),cb(done->{file.delete();olderMode=false;refresh(true);},error->{file.delete();toast(error);}));}catch(Exception e){file.delete();toast("Ses gönderilemedi");}}).setOnCancelListener(d->{stopPlayer();file.delete();}).create();
        preview.setOnShowListener(d->preview.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->play(file.getAbsolutePath(),"Sesli mesaj önizlemesi")));preview.show();
    }
    void incomingCall(SupabaseClient.IncomingCall call){
        if(!active||isFinishing())return;
        new AlertDialog.Builder(this).setTitle(call.callerName).setMessage("Gelen Selam internet araması")
            .setNegativeButton("Reddet",(d,w)->api.declineAudioCall(call.id,cb(done->{})))
            .setPositiveButton("Yanıtla",(d,w)->startActivity(new Intent(this,CallActivity.class)
                .putExtra(CallActivity.EXTRA_CALL_ID,call.id).putExtra(CallActivity.EXTRA_CHAT_ID,call.conversationId)
                .putExtra(CallActivity.EXTRA_NAME,call.callerName).putExtra(CallActivity.EXTRA_INCOMING,true))).show();
    }
    private void play(String source,String title){stopPlayer();try{player=new MediaPlayer();MediaPlayer current=player;current.setDataSource(source);LinearLayout box=column();box.setPadding(dp(20),dp(12),dp(20),dp(12));TextView state=text("Yükleniyor…",16,look.text(),false);box.addView(state);Button speed=action("Hız: 1×","Oynatma hızı",()->{try{float rate=current.getPlaybackParams().getSpeed();float next=rate<1.4f?1.5f:rate<1.9f?2f:1f;current.setPlaybackParams(current.getPlaybackParams().setSpeed(next));state.setText("Oynatılıyor • "+next+"×");}catch(Exception ignored){}});speed.setTextSize(16);box.addView(speed);playDialog=new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Kapat",(d,w)->stopPlayer()).setOnCancelListener(d->stopPlayer()).show();current.setOnPreparedListener(p->{if(player==p){p.start();state.setText("Oynatılıyor");}});current.setOnCompletionListener(p->state.setText("Tamamlandı"));current.setOnErrorListener((p,a,b)->{toast("Ses oynatılamadı");stopPlayer();return true;});current.prepareAsync();}catch(Exception e){stopPlayer();toast("Ses açılamadı");}}
    private void stopPlayer(){if(player!=null){player.release();player=null;}if(playDialog!=null){playDialog.dismiss();playDialog=null;}}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] grants){super.onRequestPermissionsResult(r,p,grants);if(r==RECORD&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)record();else if(r==RECORD)toast("Ses kaydı için mikrofon izni gerekli.");}
    private class MessagesAdapter extends BaseAdapter{
        public int getCount(){return messages.size();}public Object getItem(int p){return messages.get(p);}public long getItemId(int p){return messages.get(p).id;}
        public View getView(int p,View recycled,ViewGroup parent){SupabaseClient.Message m=messages.get(p);boolean mine=api.userId().equals(m.senderId);LinearLayout outer=column();outer.setPadding(0,dp(4),0,dp(4));outer.setGravity(mine?Gravity.END:Gravity.START);LinearLayout bubble=column();bubble.setPadding(dp(12),dp(8),dp(12),dp(8));bubble.setBackground(round(mine?look.accent(chatId):look.surface(),18));int foreground=mine?Color.WHITE:look.text();if(!direct&&!mine)bubble.addView(text(m.senderName,12,look.accent(chatId),true));if(m.replyToId>0)bubble.addView(text("↪ "+(m.replyPreview.isEmpty()?"Önceki mesaj":m.replyPreview),13,foreground,false));String body=m.isAudio()?"▶  Sesli mesaj":m.isFile()?"↗  "+m.fileName:m.body;bubble.addView(text(body,look.messageSize(),foreground,false));if(!m.reactions.isEmpty())bubble.addView(text(m.reactions,16,foreground,false));String meta=(m.starred?"★  ":"")+(!m.editedAt.isEmpty()?"Düzenlendi · ":"")+time(m.createdAt);if(mine&&!m.deleted)meta+=m.readByOther?(direct?" · Okundu":" · En az bir kişi okudu"):" · Gönderildi";TextView stamp=text(meta,11,mine?Color.rgb(220,236,255):look.muted(),false);stamp.setGravity(Gravity.END);bubble.addView(stamp);int max=getResources().getDisplayMetrics().widthPixels-dp(64);bubble.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));for(int i=0;i<bubble.getChildCount();i++)if(bubble.getChildAt(i) instanceof TextView)((TextView)bubble.getChildAt(i)).setMaxWidth(max-dp(24));outer.addView(bubble);return outer;}
    }
    private <T> SupabaseClient.Callback<T> cb(Consumer<T> ok){return cb(ok,this::toast);}
    private <T> SupabaseClient.Callback<T> cb(Consumer<T> ok,Consumer<String> error){return new SupabaseClient.Callback<T>(){public void onSuccess(T value){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())ok.accept(value);});}public void onError(String s){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())error.accept(s);});}};}
    private void rpc(String name,JSONObject args,Consumer<String> success){api.rpc(name,args,cb(success));}
    private void editDialog(String title,String value,Consumer<String> done){EditText field=new EditText(this);field.setTextColor(look.text());field.setText(value);field.setMinLines(2);field.setMaxLines(6);field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("Vazgeç",null).setPositiveButton("Tamam",(d,w)->done.accept(field.getText().toString().trim())).show();}
    private void confirm(String title,Runnable action){new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Vazgeç",null).setPositiveButton("Evet",(d,w)->action.run()).show();}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(sp);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    private Button action(String value,String description,Runnable run){Button b=new Button(this);b.setText(value);b.setTextSize(26);b.setTextColor(look.accent(chatId));b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);b.setBackgroundColor(Color.TRANSPARENT);b.setContentDescription(description);b.setOnClickListener(v->run.run());return b;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(dp(w),dp(h));}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String m){Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    private String time(String s){try{return Instant.parse(s).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception e){return "";}}
    private String date(String s){try{return Instant.parse(s).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));}catch(Exception e){return "";}}
}
