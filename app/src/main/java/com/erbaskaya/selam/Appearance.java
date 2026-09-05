package com.erbaskaya.selam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import org.json.JSONObject;

/** Local per-chat choices; global preferences also synchronize to the signed-in account. */
final class Appearance {
    final SharedPreferences prefs;
    static final String[] COLORS = {"#1969E6", "#1251A5", "#007AA6", "#176F68", "#43546B"};
    static final String[] COLOR_NAMES = {"Selam mavisi", "Okyanus", "Turkuaz", "Deniz", "Grafit"};
    static final String[] WALLPAPERS = {"auto", "#E7F0FB", "#EEF3EC", "#F4EFE5", "#172635"};
    static final String[] WALLPAPER_NAMES = {"Temaya göre", "Buz mavisi", "Adaçayı", "Kum", "Gece"};
    private final Context context;
    Appearance(Context context) {
        this.context=context;
        prefs=context.getSharedPreferences("selam_appearance", Context.MODE_PRIVATE);
    }
    boolean dark() {
        String mode=prefs.getString("mode","system");
        return "dark".equals(mode) || ("system".equals(mode)
                && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
    }
    int background() { return Color.parseColor(dark()?"#0B1420":"#F2F6FB"); }
    int surface() { return Color.parseColor(dark()?"#182638":"#FFFFFF"); }
    int text() { return Color.parseColor(dark()?"#F0F5FC":"#142235"); }
    int muted() { return Color.parseColor(dark()?"#ADBDD0":"#677589"); }
    int border() { return Color.parseColor(dark()?"#33465D":"#DAE2ED"); }
    int tintSurface() { return Color.parseColor(dark()?"#233B58":"#DEEDFF"); }
    int accent(String chatId) { return color(value("accent",chatId,"#1969E6"),Color.rgb(25,105,230)); }
    int chatBackground(String chatId) { return color(value("wallpaper",chatId,"auto"),background()); }
    String value(String key,String chatId,String fallback) {
        if(chatId!=null && prefs.contains(key+":"+chatId)) return prefs.getString(key+":"+chatId,fallback);
        return prefs.getString(key,fallback);
    }
    void put(String key,String chatId,String value) { prefs.edit().putString(chatId==null?key:key+":"+chatId,value).apply(); }
    void clearChat(String chatId) {
        prefs.edit().remove("accent:"+chatId).remove("wallpaper:"+chatId).remove("photo:"+chatId).apply();
    }
    int messageSize() { return Math.max(14,Math.min(24,prefs.getInt("font",16))); }
    boolean compact() { return prefs.getBoolean("compact",false); }
    boolean preview() { return prefs.getBoolean("preview",true); }
    boolean sound() { return prefs.getBoolean("sound",true); }
    boolean vibration() { return prefs.getBoolean("vibration",true); }
    boolean quiet() {
        if(!prefs.getBoolean("quiet",false)) return false;
        int hour=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour>=22 || hour<8;
    }
    static int color(String value,int fallback) { try{return Color.parseColor(value);}catch(Exception ignored){return fallback;} }
    JSONObject exportGlobal() {
        JSONObject data=new JSONObject();
        try {
            for(String key:new String[]{"mode","accent","wallpaper"}) if(prefs.contains(key)) data.put(key,prefs.getString(key,""));
            data.put("font",messageSize());
            for(String key:new String[]{"compact","preview","sound","vibration","quiet"})
                data.put(key,prefs.getBoolean(key,key.equals("preview")||key.equals("sound")||key.equals("vibration")));
        }catch(Exception ignored){}
        return data;
    }
    void importGlobal(JSONObject data) {
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:new String[]{"mode","accent","wallpaper"}) if(data.has(key)) edit.putString(key,data.optString(key));
        if(data.has("font")) edit.putInt("font",Math.max(14,Math.min(24,data.optInt("font",16))));
        for(String key:new String[]{"compact","preview","sound","vibration","quiet"}) if(data.has(key)) edit.putBoolean(key,data.optBoolean(key));
        edit.apply();
    }
}
