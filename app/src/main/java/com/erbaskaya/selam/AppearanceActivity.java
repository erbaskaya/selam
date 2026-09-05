package com.erbaskaya.selam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.*;

public final class AppearanceActivity extends Activity {
    private Appearance look;
    private String chatId;
    private LinearLayout form;
    private static final int PHOTO=91;
    @Override public void onCreate(Bundle state) {
        look=new Appearance(this);
        setTheme(look.dark()?R.style.Theme_Selam_Dark:R.style.Theme_Selam);
        super.onCreate(state);
        chatId=getIntent().getStringExtra("chat_id");
        render();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable round(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    private void render(){
        getWindow().setStatusBarColor(look.background());getWindow().setNavigationBarColor(look.background());
        getWindow().getDecorView().setSystemUiVisibility(look.dark()?0:android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setBackgroundColor(look.background());
        page.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        TextView title=text("‹   "+(chatId==null?"Kişiselleştirme":"Sohbet teması"),22,look.text());
        title.setTypeface(null,Typeface.BOLD);title.setPadding(dp(20),dp(20),dp(20),dp(20));title.setOnClickListener(v->finish());page.addView(title);
        ScrollView scroll=new ScrollView(this);form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),0,dp(20),dp(24));scroll.addView(form);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView intro=text(chatId==null?"Selam'ı kendine göre düzenle. Genel görünüm ve bildirim tercihlerin hesabına kaydedilir.":"Bu sohbetin renkleri ve duvar kâğıdı yalnızca senin bu cihazındaki görünümü değiştirir.",14,look.muted());form.addView(intro);
        LinearLayout preview=new LinearLayout(this);preview.setOrientation(1);preview.setPadding(dp(14),dp(20),dp(14),dp(20));preview.setBackground(round(look.chatBackground(chatId)));
        TextView incoming=text("Merhaba, nasılsın?",look.messageSize(),look.text());incoming.setPadding(dp(14),dp(12),dp(14),dp(12));incoming.setBackground(round(look.surface()));preview.addView(incoming,new LinearLayout.LayoutParams(-2,-2));
        TextView outgoing=text("İyiyim, selam! 🤝",look.messageSize(),Color.WHITE);outgoing.setPadding(dp(14),dp(12),dp(14),dp(12));outgoing.setBackground(round(look.accent(chatId)));LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(-2,-2);right.gravity=Gravity.END;right.topMargin=dp(12);preview.addView(outgoing,right);
        LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.setMargins(0,dp(18),0,dp(18));form.addView(preview,space);
        if(chatId==null){
            choice("Görünüm",new String[]{"Sistem ayarı","Açık","Koyu"},i->{look.put("mode",null,new String[]{"system","light","dark"}[i]);recreate();});
            choice("Mesaj yazı boyutu: "+look.messageSize(),new String[]{"Küçük · 14","Normal · 16","Büyük · 18","Çok büyük · 21","En büyük · 24"},i->{look.prefs.edit().putInt("font",new int[]{14,16,18,21,24}[i]).apply();render();});
            toggle("Kompakt sohbet listesi", "compact",false);
        }
        choice("Balon rengi",Appearance.COLOR_NAMES,i->{look.put("accent",chatId,Appearance.COLORS[i]);render();});
        choice("Duvar kâğıdı rengi",Appearance.WALLPAPER_NAMES,i->{look.put("wallpaper",chatId,Appearance.WALLPAPERS[i]);look.put("photo",chatId,"");render();});
        row("Galeriden duvar kâğıdı seç",()->{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(picker,PHOTO);});
        row("Fotoğraf duvar kâğıdını kaldır",()->{look.put("photo",chatId,"");Toast.makeText(this,"Duvar kâğıdı kaldırıldı",Toast.LENGTH_SHORT).show();render();});
        if(chatId==null){
            TextView notifications=text("Bildirimler",20,look.text());notifications.setPadding(0,dp(22),0,dp(8));form.addView(notifications);
            toggle("Mesaj içeriğini bildirimde göster","preview",true);
            toggle("Mesaj sesi","sound",true);
            toggle("Mesaj titreşimi","vibration",true);
            toggle("Gece sessizliği · 22.00–08.00","quiet",false);
            row("Telefonun bildirim ve zil sesi ayarları",()->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));
        }else row("Bu sohbet için genel ayarlara dön",()->{look.clearChat(chatId);render();});
        row("Kaydet",()->{
            if(chatId!=null){setResult(RESULT_OK);finish();return;}
            SupabaseClient api=new SupabaseClient(this);
            api.rpc("selam_preferences",SupabaseClient.json("p_value",look.exportGlobal()),new SupabaseClient.Callback<String>(){
                public void onSuccess(String s){api.close();runOnUiThread(()->{Toast.makeText(AppearanceActivity.this,"Tercihler kaydedildi",Toast.LENGTH_SHORT).show();setResult(RESULT_OK);finish();});}
                public void onError(String m){api.close();runOnUiThread(()->new AlertDialog.Builder(AppearanceActivity.this).setMessage("Tercihler bu cihazda kayıtlı. Hesabına kaydetmek için bağlantıyı kontrol edip tekrar Kaydet'e dokun.\n"+m).setPositiveButton("Tamam",null).show());}
            });
        });
        setContentView(page);page.requestApplyInsets();
    }
    private interface Select{void choose(int index);}
    private void choice(String title,String[] options,Select selected){row(title,()->new AlertDialog.Builder(this).setTitle(title).setItems(options,(d,i)->selected.choose(i)).show());}
    private void row(String name,Runnable click){
        TextView row=text(name+"   ›",16,look.text());row.setPadding(dp(16),dp(17),dp(16),dp(17));row.setBackground(round(look.surface()));row.setMinHeight(dp(54));row.setOnClickListener(v->click.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(9);form.addView(row,lp);
    }
    private void toggle(String title,String key,boolean fallback){Switch sw=new Switch(this);sw.setText(title);sw.setTextSize(15);sw.setTextColor(look.text());sw.setPadding(dp(12),dp(16),dp(12),dp(16));sw.setChecked(look.prefs.getBoolean(key,fallback));sw.setOnCheckedChangeListener((v,checked)->look.prefs.edit().putBoolean(key,checked).apply());form.addView(sw);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PHOTO||result!=RESULT_OK||data==null||data.getData()==null)return;
        new Thread(()->{
            File temp=null;
            try{
                temp=File.createTempFile("wallpaper-",".tmp",getCacheDir());
                try(InputStream input=getContentResolver().openInputStream(data.getData());OutputStream output=new FileOutputStream(temp)){
                    byte[] buffer=new byte[8192];int n,total=0;while((n=input.read(buffer))!=-1){total+=n;if(total>20*1024*1024)throw new IOException("Fotoğraf en fazla 20 MB olabilir");output.write(buffer,0,n);}
                }
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(temp.getPath(),options);
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1600)options.inSampleSize*=2;options.inJustDecodeBounds=false;
                Bitmap bitmap=BitmapFactory.decodeFile(temp.getPath(),options);if(bitmap==null)throw new IOException("Fotoğraf açılamadı");
                File target=new File(getFilesDir(),"wallpaper-"+(chatId==null?"global":chatId)+".jpg");try(OutputStream output=new FileOutputStream(target)){bitmap.compress(Bitmap.CompressFormat.JPEG,85,output);}bitmap.recycle();
                look.put("photo",chatId,target.getPath());runOnUiThread(()->{Toast.makeText(this,"Duvar kâğıdı seçildi",Toast.LENGTH_SHORT).show();render();});
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());}finally{if(temp!=null)temp.delete();}
        }).start();
    }
}
