package com.erbaskaya.selam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private int BLUE = Color.rgb(25, 105, 230);
    private Appearance appearance;
    private int SURFACE = Color.WHITE;
    private String appliedAppearance = "";
    private ListView homeList;
    private EditText homeSearch;
    private List<SupabaseClient.Chat> homeChats = new ArrayList<>();
    private String homeFilter = "Tümü";
    private boolean resumed;
    private final Runnable homePoll = this::refreshHome;
    private static final int NAVY = Color.rgb(7, 17, 31);
    private int BACKGROUND = Color.rgb(242, 246, 251);
    private int TEXT = Color.rgb(20, 34, 53);
    private int MUTED = Color.rgb(103, 117, 137);
    private int BORDER = Color.rgb(218, 226, 237);
    private static final int REQUEST_CONTACTS = 410;
    private static final int REQUEST_GROUP_CONTACTS = 411;
    private static final int REQUEST_FILE = 412;
    private static final int REQUEST_CAMERA = 413;
    private static final int REQUEST_CAMERA_PERMISSION = 414;
    private static final int REQUEST_AUDIO_PERMISSION = 415;
    private static final int REQUEST_NOTIFICATIONS = 416;
    private static final long POLL_INTERVAL = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollMessages = this::refreshMessages;
    private SupabaseClient api;
    private UpdateManager updateManager;
    private SelamAlerts alerts;
    private SupabaseClient.Profile myProfile;
    private FrameLayout root;
    private ProgressBar progress;
    private String screen = "boot";
    private String activeChatId;
    private ListView activeMessageList;
    private List<SupabaseClient.Message> activeMessages = new ArrayList<>();
    private Map<String, String> localContactNames = new LinkedHashMap<>();
    private String pendingCameraChatId;
    private String pendingCameraChatName;
    private boolean pendingCameraChatCanCall;
    private String pendingCallChatId;
    private String pendingCallName;
    private String visibleIncomingCallId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appearance = new Appearance(this);
        setTheme(appearance.dark() ? R.style.Theme_Selam_Dark : R.style.Theme_Selam);
        applyAppearance();
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        api = new SupabaseClient(this);
        updateManager = new UpdateManager(this);
        alerts = new SelamAlerts(this, api);

        root = new FrameLayout(this);
        // Android 15+ sistem çubuklarını içerik üstüne bindirir. Kök görünüm
        // çubukların güvenli alanını taşır; sayfalar bu alanın içinde kalır.
        root.setBackgroundColor(NAVY);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        progress = new ProgressBar(this);
        progress.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        progress.setVisibility(View.GONE);

        if (!api.isConfigured()) showConfigurationNotice();
        else startDeviceAccount();
        updateManager.checkForUpdates(false);
    }

    private void applyAppearance() {
        BLUE=appearance.accent(null);BACKGROUND=appearance.background();TEXT=appearance.text();
        MUTED=appearance.muted();BORDER=appearance.border();SURFACE=appearance.surface();
        appliedAppearance=appearance.exportGlobal().toString()+appearance.dark();
    }
    @Override protected void onResume() {
        super.onResume();resumed=true;
        if (updateManager != null) updateManager.resumeInstallIfReady();
        if(appearance!=null && !appliedAppearance.equals(appearance.exportGlobal().toString()+appearance.dark())){recreate();return;}
        if("external-chat".equals(screen))showHome();
        else if("home".equals(screen))refreshHome();
    }
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(homePoll);super.onPause();}

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);consumeNotification();}
    private void consumeNotification(){
        String id=getIntent().getStringExtra("open_chat_id");
        if(id==null||myProfile==null||!myProfile.ready)return;
        getIntent().removeExtra("open_chat_id");
        api.listChats(uiCallback(chats->{for(SupabaseClient.Chat c:chats)if(id.equals(c.id)){openChat(c.id,preferredName(c.displayName,c.username),"direct".equals(c.kind));return;}}));
    }

    private void startDeviceAccount() {
        showBoot("Cihaz hesabın hazırlanıyor…");
        if (api.hasSession()) {
            loadProfile();
            return;
        }
        api.createDeviceSession(uiCallback(done -> loadProfile(),
                this::showConnectionError));
    }

    private void loadProfile() {
        api.getMyProfile(uiCallback(profile -> {
            myProfile = profile;
            setBusy(false);
            if (profile.ready) {
                requestNotificationPermission();
                alerts.start();
                showHome();
                consumeNotification();
                String syncKey="synced:"+api.userId();
                if(!appearance.prefs.getBoolean(syncKey,false))api.rpc("selam_preferences",SupabaseClient.json(),uiCallback(value->{
                    try {appearance.importGlobal(new org.json.JSONObject(value));appearance.prefs.edit().putBoolean(syncKey,true).apply();
                        if(!appliedAppearance.equals(appearance.exportGlobal().toString()+appearance.dark()))recreate();
                    }catch(Exception ignored){}
                }));
            }
            else showOnboarding();
        }, this::showConnectionError));
    }

    private void showBoot(String message) {
        stopPolling();
        screen = "boot";
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(30), dp(40), dp(30), dp(40));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        page.addView(logo, new LinearLayout.LayoutParams(dp(92), dp(92)));
        TextView title = label("Selam", 30, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, margin(-1, -2, 0, 18, 0, 8));
        TextView status = label(message, 16, MUTED, false);
        status.setGravity(Gravity.CENTER);
        page.addView(status);
        setPage(page);
        setBusy(true);
    }

    private void showConfigurationNotice() {
        screen = "config";
        LinearLayout page = centeredPage();
        page.addView(labelCentered("Selam sunucusu hazırlanıyor", 24, TEXT, true),
                margin(-1, -2, 0, 0, 0, 12));
        page.addView(labelCentered("Sunucu bağlantısı APK'ya eklenmemiş.", 16, MUTED, false),
                margin(-1, -2, 0, 0, 0, 24));
        Button retry = primaryButton("Tekrar kontrol et");
        retry.setOnClickListener(v -> recreate());
        page.addView(retry, new LinearLayout.LayoutParams(-1, dp(54)));
        setPage(page);
    }

    private void showConnectionError(String message) {
        setBusy(false);
        screen = "error";
        LinearLayout page = centeredPage();
        page.addView(labelCentered("Bağlantı kurulamadı", 24, TEXT, true),
                margin(-1, -2, 0, 0, 0, 12));
        page.addView(labelCentered(message, 16, MUTED, false),
                margin(-1, -2, 0, 0, 0, 24));
        Button retry = primaryButton("Tekrar dene");
        retry.setOnClickListener(v -> startDeviceAccount());
        page.addView(retry, new LinearLayout.LayoutParams(-1, dp(54)));
        setPage(page);
    }

    private void showOnboarding() {
        stopPolling();
        screen = "onboarding";
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(34), dp(28), dp(34));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        page.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));
        page.addView(labelCentered("Selam'a hoş geldin", 28, TEXT, true),
                margin(-1, -2, 0, 14, 0, 6));
        page.addView(labelCentered("Numaran ve 6 haneli PIN'inle devam et. Hesabın varsa açılır; ilk kez kullanıyorsan yeni hesabın oluşturulur.", 16, MUTED, false),
                margin(-1, -2, 0, 0, 0, 26));

        EditText displayName = input("Adınız ve soyadınız", InputType.TYPE_CLASS_TEXT);
        page.addView(displayName, margin(-1, dp(56), 0, 0, 0, 12));
        EditText phone = input("Telefon numaranız (05xx xxx xx xx)",
                InputType.TYPE_CLASS_PHONE);
        page.addView(phone, margin(-1, dp(56), 0, 0, 0, 12));
        EditText pin = input("6 haneli PIN'iniz",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setMaxLines(1);
        pin.setSaveEnabled(false);
        page.addView(pin, margin(-1, dp(56), 0, 0, 0, 14));

        TextView note = label("SMS kodu gönderilmez. İlk kayıtta PIN'ini kendin belirle. Yeniden kurduğunda aynı numarayı ve önceden belirlediğin PIN'i yazıp Devam et'e dokun.", 14, MUTED, false);
        note.setPadding(dp(14), dp(12), dp(14), dp(12));
        note.setBackground(rounded(SURFACE, BORDER, 12));
        page.addView(note, margin(-1, -2, 0, 0, 0, 18));

        Button continueButton = primaryButton("Devam et");
        page.addView(continueButton, new LinearLayout.LayoutParams(-1, dp(56)));
        continueButton.setOnClickListener(v -> {
            if (!continueButton.isEnabled()) return;
            String nameValue = displayName.getText().toString().trim();
            String normalized = normalizePhone(phone.getText().toString());
            String pinValue = pin.getText().toString().trim();
            if (nameValue.length() < 2 || nameValue.length() > 60) {
                toast("Adınızı 2-60 karakter arasında yazın.");
                return;
            }
            if (normalized == null) {
                toast("Geçerli bir telefon numarası yazın.");
                return;
            }
            if (!isValidPin(pinValue)) {
                toast("PIN tam 6 rakam olmalı ve kolay bir sayı olmamalı.");
                return;
            }
            setBusy(true);
            continueButton.setEnabled(false);
            continueButton.setText("Hesabın açılıyor…");
            hideKeyboard();
            api.enterProfile(nameValue, normalized, pinValue, uiCallback(profile -> {
                myProfile = profile;
                pin.setText("");
                loadProfile();
            }, error -> {
                continueButton.setEnabled(true);
                continueButton.setText("Devam et");
            }));
        });
        setPage(scroll);
    }

    private void showHome() {
        stopPolling();
        screen = "home";
        activeChatId = null;
        LinearLayout page = vertical();
        page.addView(mainHeader("Sohbetler"));

        EditText search = input("Sohbetlerde ara", InputType.TYPE_CLASS_TEXT);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(dp(10));
        page.addView(search, margin(-1, dp(50), 16, 12, 16, 8));
        homeSearch=search;
        android.widget.HorizontalScrollView filterScroll=new android.widget.HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters=horizontal();filters.setPadding(dp(16),0,dp(16),dp(6));
        for(String title:new String[]{"Tümü","Okunmamış","Favoriler","Gruplar"}){
            TextView filter=label(title,14,title.equals(homeFilter)?BLUE:MUTED,true);
            filter.setPadding(dp(12),dp(10),dp(12),dp(10));
            filter.setBackground(title.equals(homeFilter)?rounded(appearance.tintSurface(),appearance.tintSurface(),20):null);
            filter.setOnClickListener(v->{homeFilter=title;showHome();});filters.addView(filter);
        }
        filterScroll.addView(filters);page.addView(filterScroll);

        FrameLayout content = new FrameLayout(this);
        ListView list = plainList();
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(82));
        TextView empty = label("Henüz sohbet yok. Sağ alttaki düğmeden rehberindeki Selam kullanıcılarını bul.", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(34), dp(34), dp(34), dp(34));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        Button newChat = primaryButton("＋");
        newChat.setTextSize(28);
        newChat.setContentDescription("Yeni sohbet başlat");
        newChat.setElevation(dp(8));
        newChat.setBackground(rounded(BLUE, BLUE, 30));
        newChat.setOnClickListener(v -> showContacts());
        FrameLayout.LayoutParams newChatParams = new FrameLayout.LayoutParams(
                dp(60), dp(60), Gravity.END | Gravity.BOTTOM);
        newChatParams.setMargins(0, 0, dp(18), dp(18));
        content.addView(newChat, newChatParams);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomNavigation(page, "chats");
        setPage(page);
        setBusy(true);
        homeList=list;
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filterHome();}
            public void afterTextChanged(Editable s){}
        });
        refreshHome();
    }
    private void refreshHome(){
        handler.removeCallbacks(homePoll);
        if(!resumed||!"home".equals(screen))return;
        ListView target=homeList;
        api.listChats(uiCallback(chats->{
            if(!"home".equals(screen)||target!=homeList)return;
            setBusy(false);homeChats=chats;filterHome();
            if(resumed)handler.postDelayed(homePoll,5000);
        },error->{setBusy(false);if(resumed&&"home".equals(screen))handler.postDelayed(homePoll,7000);}));
        api.rpc("selam_presence",SupabaseClient.json(),uiCallback(s->{},e->{}));
    }
    private void filterHome(){
        if(homeList==null||homeSearch==null)return;
        String query=homeSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
        List<SupabaseClient.Chat> filtered=new ArrayList<>();
        for(SupabaseClient.Chat c:homeChats){
            if(c.archived || (homeFilter.equals("Okunmamış")&&c.unreadCount==0)
                || (homeFilter.equals("Favoriler")&&!c.favorite) || (homeFilter.equals("Gruplar")&&!"group".equals(c.kind)))continue;
            String name=preferredName(c.displayName,c.username).toLowerCase(Locale.getDefault());
            String message=c.lastMessage==null?"":c.lastMessage.toLowerCase(Locale.getDefault());
            if(query.isEmpty()||name.contains(query)||message.contains(query))filtered.add(c);
        }
        int pos=homeList.getFirstVisiblePosition(),top=homeList.getChildCount()>0?homeList.getChildAt(0).getTop():0;
        bindChats(homeList,filtered);homeList.setSelectionFromTop(pos,top);
    }

    private void loadChats(ListView list) {
        setBusy(true);
        api.listChats(uiCallback(chats -> {
            setBusy(false);
            bindChats(list, chats);
        }));
    }

    private void bindChats(ListView list, List<SupabaseClient.Chat> chats) {
        list.setAdapter(new ChatAdapter(chats));
        list.setOnItemClickListener((parent, view, position, id) -> {
            SupabaseClient.Chat chat = chats.get(position);
            openChat(chat.id, preferredName(chat.displayName, chat.username),
                    "direct".equals(chat.kind));
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showChatActions(view, chats.get(position));
            return true;
        });
    }

    private LinearLayout mainHeader(String section) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(8), dp(10));
        header.setBackgroundColor(NAVY);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titles = vertical();
        titles.setPadding(dp(10), 0, 0, 0);
        titles.addView(label("Selam", 23, Color.WHITE, true));
        titles.addView(label(section, 12, Color.rgb(184, 203, 230), false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton camera = headerIconButton(R.drawable.ic_camera, "Kamera");
        camera.setOnClickListener(v -> chooseCameraChat());
        header.addView(camera, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button menu = headerButton("⋮");
        menu.setTextSize(28);
        menu.setContentDescription("Menü");
        menu.setOnClickListener(this::showMainMenu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(48)));
        return header;
    }

    private void addBottomNavigation(LinearLayout page, String selected) {
        LinearLayout nav = horizontal();
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(3), dp(5), dp(3), dp(4));
        nav.setBackgroundColor(SURFACE);
        addNavButton(nav, "Sohbetler", R.drawable.ic_nav_chats,
                "chats", selected, this::showHome);
        addNavButton(nav, "Güncellemeler", R.drawable.ic_nav_updates,
                "updates", selected, this::showUpdates);
        addNavButton(nav, "Topluluklar", R.drawable.ic_nav_communities,
                "communities", selected, this::showCommunities);
        addNavButton(nav, "Aramalar", R.drawable.ic_phone,
                "calls", selected, this::showCalls);
        page.addView(nav, new LinearLayout.LayoutParams(-1, dp(78)));
    }

    private void addNavButton(LinearLayout nav, String title, int iconResource,
                              String id, String selected, Runnable action) {
        boolean active = id.equals(selected);
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(1), dp(2), dp(1), 0);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(title);
        item.setOnClickListener(v -> action.run());

        FrameLayout iconHolder = new FrameLayout(this);
        iconHolder.setBackground(active
                ? rounded(appearance.tintSurface(), appearance.tintSurface(), 18)
                : null);
        ImageView icon = new ImageView(this);
        Drawable drawable = getDrawable(iconResource).mutate();
        drawable.setTint(active ? BLUE : TEXT);
        icon.setImageDrawable(drawable);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(27), dp(27), Gravity.CENTER);
        iconHolder.addView(icon, iconParams);
        item.addView(iconHolder, new LinearLayout.LayoutParams(dp(58), dp(34)));

        TextView label = label(title, 10, active ? BLUE : TEXT, active);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        item.addView(label, new LinearLayout.LayoutParams(-1, dp(24)));
        nav.addView(item, new LinearLayout.LayoutParams(0, dp(68), 1));
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Yeni sohbet");
        menu.getMenu().add("Yeni grup");
        menu.getMenu().add("Yeni topluluk");
        menu.getMenu().add("Arşivlenmiş sohbetler");
        menu.getMenu().add("QR kodum");
        if ("calls".equals(screen)) menu.getMenu().add("Arama kayıtlarını temizle");
        menu.getMenu().add("Ayarlar");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Yeni sohbet".equals(title)) showContacts();
            else if ("Yeni grup".equals(title)) showCreateGroup();
            else if ("Yeni topluluk".equals(title)) showCreateCommunityDialog();
            else if ("Arşivlenmiş sohbetler".equals(title)) showArchivedChats();
            else if ("QR kodum".equals(title)) showMyQr();
            else if ("Arama kayıtlarını temizle".equals(title)) confirmClearCallHistory();
            else if ("Ayarlar".equals(title)) showSettings();
            return true;
        });
        menu.show();
    }

    private void showChatActions(View anchor, SupabaseClient.Chat chat) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(chat.favorite ? "Favorilerden çıkar" : "Favorilere ekle");
        menu.getMenu().add(chat.pinned ? "Sabitlemeyi kaldır" : "Sohbeti sabitle");
        menu.getMenu().add(chat.archived ? "Arşivden çıkar" : "Arşivle");
        if (isFuture(chat.mutedUntil)) menu.getMenu().add("Sessizi kaldır");
        else menu.getMenu().add("8 saat sessize al");
        menu.getMenu().add("Sohbeti temizle");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.startsWith("Favori")) api.rpc("selam_favorite",SupabaseClient.json("p_chat_id",chat.id,"p_favorite",!chat.favorite),uiCallback(done->showHome()));
            else if (title.toLowerCase(Locale.ROOT).contains("sabitle")) updateChatState(chat, null, !chat.pinned, null);
            else if (title.contains("Arşiv")) updateChatState(chat, !chat.archived, null, null);
            else if (title.startsWith("8")) updateChatState(chat, null, null, 8);
            else if (title.startsWith("Sessizi")) updateChatState(chat, null, null, 0);
            else confirmDeleteChat(chat.id, preferredName(chat.displayName, chat.username));
            return true;
        });
        menu.show();
    }

    private void updateChatState(SupabaseClient.Chat chat, Boolean archived,
                                 Boolean pinned, Integer muteHours) {
        setBusy(true);
        api.setChatState(chat.id, archived, pinned, muteHours, uiCallback(done -> {
            setBusy(false);
            toast("Sohbet ayarı güncellendi.");
            if (chat.archived) showArchivedChats(); else showHome();
        }));
    }

    private void showArchivedChats() {
        stopPolling();
        screen = "archived";
        LinearLayout page = vertical();
        page.addView(topBar("Arşivlenmiş sohbetler", this::showHome));
        ListView list = plainList();
        TextView empty = label("Arşivlenmiş sohbet yok.", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        list.setEmptyView(empty);
        FrameLayout body = new FrameLayout(this);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setPage(page);
        setBusy(true);
        api.listChats(uiCallback(chats -> {
            setBusy(false);
            List<SupabaseClient.Chat> archived = new ArrayList<>();
            for (SupabaseClient.Chat chat : chats) if (chat.archived) archived.add(chat);
            bindChats(list, archived);
        }));
    }

    private void showUpdates() {
        stopPolling();
        screen = "updates";
        LinearLayout page = vertical();
        page.addView(mainHeader("Güncellemeler"));
        Button add = primaryButton("＋ Durum paylaş");
        add.setOnClickListener(v -> showCreateStatusDialog());
        page.addView(add, margin(-1, dp(50), 16, 12, 16, 8));
        TextView hint = label("Durumlar 24 saat sonra otomatik kaybolur.", 13, MUTED, false);
        hint.setPadding(dp(18), dp(2), dp(18), dp(6));
        page.addView(hint);
        ListView list = plainList();
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomNavigation(page, "updates");
        setPage(page);
        setBusy(true);
        api.listStatuses(uiCallback(statuses -> {
            setBusy(false);
            list.setAdapter(new StatusAdapter(statuses));
            list.setOnItemClickListener((p, v, position, id) -> showStatus(statuses.get(position)));
            list.setOnItemLongClickListener((p, v, position, id) -> {
                SupabaseClient.StatusUpdate status = statuses.get(position);
                if (status.mine) confirmDeleteStatus(status);
                return true;
            });
        }));
    }

    private void showCalls() {
        stopPolling();
        screen = "calls";
        LinearLayout page = vertical();
        page.addView(mainHeader("Aramalar"));

        FrameLayout content = new FrameLayout(this);
        ListView list = plainList();
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(82));
        TextView empty = label("Henüz arama yok. Sağ alttaki + simgesinden bir Selam kullanıcısını internet üzerinden ara.",
                16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(34), dp(34), dp(34), dp(34));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);

        Button newCall = primaryButton("＋");
        newCall.setTextSize(28);
        newCall.setContentDescription("Yeni internet araması");
        newCall.setElevation(dp(8));
        newCall.setBackground(rounded(BLUE, BLUE, 30));
        newCall.setOnClickListener(v -> chooseCallChat());
        FrameLayout.LayoutParams newCallParams = new FrameLayout.LayoutParams(
                dp(60), dp(60), Gravity.END | Gravity.BOTTOM);
        newCallParams.setMargins(0, 0, dp(18), dp(18));
        content.addView(newCall, newCallParams);

        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomNavigation(page, "calls");
        setPage(page);
        setBusy(true);
        api.listCallHistory(uiCallback(calls -> {
            setBusy(false);
            list.setAdapter(new CallLogAdapter(calls));
            list.setOnItemClickListener((parent, view, position, id) -> {
                SupabaseClient.CallLog call = calls.get(position);
                startAudioCall(call.conversationId, call.otherName);
            });
            list.setOnItemLongClickListener((parent, view, position, id) -> {
                confirmDeleteCallLog(calls.get(position));
                return true;
            });
        }));
    }

    private void confirmDeleteCallLog(SupabaseClient.CallLog call) {
        new AlertDialog.Builder(this)
                .setTitle("Arama kaydı silinsin mi?")
                .setMessage(call.otherName + " ile yapılan bu arama yalnızca sizin geçmişinizden kaldırılacak.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (dialog, which) -> {
                    setBusy(true);
                    api.hideCallHistoryEntry(call.id, uiCallback(done -> {
                        setBusy(false);
                        toast("Arama kaydı silindi.");
                        showCalls();
                    }));
                })
                .show();
    }

    private void confirmClearCallHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Arama geçmişi temizlensin mi?")
                .setMessage("Tüm gelen, giden ve cevapsız arama kayıtları yalnızca sizin hesabınızdan kaldırılacak.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Tümünü temizle", (dialog, which) -> {
                    setBusy(true);
                    api.clearCallHistory(uiCallback(done -> {
                        setBusy(false);
                        toast("Arama geçmişi temizlendi.");
                        showCalls();
                    }));
                })
                .show();
    }

    private void chooseCallChat() {
        setBusy(true);
        api.listChats(uiCallback(chats -> {
            setBusy(false);
            List<SupabaseClient.Chat> directChats = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (SupabaseClient.Chat chat : chats) {
                if (!"direct".equals(chat.kind)) continue;
                directChats.add(chat);
                names.add(preferredName(chat.displayName, chat.username));
            }
            if (directChats.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Aranabilecek kişi yok")
                        .setMessage("Arama başlatmak için önce rehberinden bir Selam kullanıcısıyla sohbet başlat.")
                        .setNegativeButton("Kapat", null)
                        .setPositiveButton("Yeni sohbet", (dialog, which) -> showContacts())
                        .show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Kimi aramak istersin?")
                    .setItems(names.toArray(new String[0]), (dialog, which) -> {
                        SupabaseClient.Chat chat = directChats.get(which);
                        startAudioCall(chat.id, names.get(which));
                    })
                    .setNegativeButton("Vazgeç", null)
                    .show();
        }));
    }

    private void showCreateStatusDialog() {
        final String[] colors = {"#1969E6", "#135B9A", "#0F8B8D", "#7B2CBF", "#C44536"};
        LinearLayout form = vertical();
        form.setPadding(dp(18), dp(8), dp(18), 0);
        EditText body = input("Ne paylaşmak istersin?", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        body.setMinLines(4);
        form.addView(body, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Yeni durum")
                .setView(form).setSingleChoiceItems(new String[]{"Mavi", "Lacivert", "Turkuaz", "Mor", "Kırmızı"}, 0, null)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Paylaş", (dialog, which) -> {
                    int selected = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    String text = body.getText().toString().trim();
                    if (text.isEmpty()) { toast("Durum metnini yazın."); return; }
                    setBusy(true);
                    api.createStatus(text, colors[Math.max(0, selected)], uiCallback(done -> {
                        setBusy(false); toast("Durum paylaşıldı."); showUpdates();
                    }));
                }).show();
    }

    private void showStatus(SupabaseClient.StatusUpdate status) {
        TextView view = label(status.body, 25, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(28), dp(42), dp(28), dp(42));
        try { view.setBackgroundColor(Color.parseColor(status.color)); }
        catch (Exception ignored) { view.setBackgroundColor(BLUE); }
        new AlertDialog.Builder(this)
                .setTitle((status.mine ? "Durumum" : status.displayName) + " • " + time(status.createdAt))
                .setView(view).setPositiveButton("Kapat", null).show();
    }

    private void confirmDeleteStatus(SupabaseClient.StatusUpdate status) {
        new AlertDialog.Builder(this).setTitle("Durum silinsin mi?")
                .setMessage(status.body).setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (d, w) -> {
                    setBusy(true);
                    api.deleteStatus(status.id, uiCallback(done -> { setBusy(false); showUpdates(); }));
                }).show();
    }

    private void showCommunities() {
        stopPolling();
        screen = "communities";
        LinearLayout page = vertical();
        page.addView(mainHeader("Topluluklar"));
        Button add = primaryButton("＋ Yeni topluluk");
        add.setOnClickListener(v -> showCreateCommunityDialog());
        page.addView(add, margin(-1, dp(50), 16, 12, 16, 10));
        TextView info = label("Bir okul, aile, ekip veya proje için sohbetlerini tek çatı altında düzenle.", 14, MUTED, false);
        info.setPadding(dp(18), dp(4), dp(18), dp(10));
        page.addView(info);
        ListView list = plainList();
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        addBottomNavigation(page, "communities");
        setPage(page);
        setBusy(true);
        api.listCommunities(uiCallback(items -> {
            setBusy(false);
            list.setAdapter(new CommunityAdapter(items));
        }));
    }

    private void showCreateCommunityDialog() {
        LinearLayout form = vertical();
        form.setPadding(dp(18), dp(8), dp(18), 0);
        EditText name = input("Topluluk adı", InputType.TYPE_CLASS_TEXT);
        EditText description = input("Kısa açıklama", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(name, margin(-1, dp(54), 0, 0, 0, 10));
        form.addView(description, margin(-1, dp(80), 0, 0, 0, 0));
        new AlertDialog.Builder(this).setTitle("Yeni topluluk").setView(form)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Oluştur", (d, w) -> {
                    if (name.getText().toString().trim().length() < 2) {
                        toast("Topluluk adını yazın."); return;
                    }
                    setBusy(true);
                    api.createCommunity(name.getText().toString(), description.getText().toString(),
                            uiCallback(done -> { setBusy(false); toast("Topluluk oluşturuldu."); showCommunities(); }));
                }).show();
    }

    private void showSettings() {
        stopPolling();
        screen = "settings";
        LinearLayout page = vertical();
        page.addView(topBar("Ayarlar", this::showHome));
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = vertical();
        form.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setPage(page);
        setBusy(true);
        api.getSettings(uiCallback(settings -> {
            setBusy(false);
            Button appearanceButton=textButton("Kişiselleştirme • tema, duvar kâğıdı, yazı ve ses");
            appearanceButton.setTextColor(BLUE);appearanceButton.setBackground(rounded(SURFACE,BORDER,14));
            appearanceButton.setOnClickListener(v->startActivity(new Intent(this,AppearanceActivity.class)));
            form.addView(appearanceButton,margin(-1,dp(66),0,0,0,18));
            form.addView(avatar(settings.displayName, 70), margin(dp(70), dp(70), 0, 0, 0, 12));
            EditText name = input("Adınız", InputType.TYPE_CLASS_TEXT);
            name.setText(settings.displayName);
            form.addView(name, margin(-1, dp(54), 0, 0, 0, 10));
            EditText about = input("Hakkımda", InputType.TYPE_CLASS_TEXT);
            about.setText(settings.about);
            form.addView(about, margin(-1, dp(54), 0, 0, 0, 14));
            CheckBox receipts = settingCheck("Okundu bilgisi", settings.readReceipts);
            CheckBox lastSeen = settingCheck("Son görülmemi göster", settings.showLastSeen);
            CheckBox notifications = settingCheck("Mesaj bildirimleri", settings.notifications);
            CheckBox callNotifications = settingCheck("Arama bildirimleri", settings.callNotifications);
            form.addView(receipts); form.addView(lastSeen); form.addView(notifications);
            form.addView(callNotifications);
            TextView privacy = label("Gizlilik notu: telefon numaran açık olarak saklanmaz; kişiler eşleştirilirken tek yönlü özeti kullanılır.", 13, MUTED, false);
            privacy.setPadding(dp(12), dp(12), dp(12), dp(12));
            privacy.setBackground(rounded(appearance.tintSurface(), BORDER, 12));
            form.addView(privacy, margin(-1, -2, 0, 14, 0, 14));
            Button qr = textButton("Güvenlik ve QR kodum");
            qr.setBackground(rounded(SURFACE, BORDER, 14));
            qr.setOnClickListener(v -> showMyQr());
            form.addView(qr, margin(-1, dp(52), 0, 0, 0, 10));
            Button updates = textButton("Güncellemeleri kontrol et");
            updates.setBackground(rounded(SURFACE, BORDER, 14));
            updates.setOnClickListener(v -> updateManager.checkForUpdates(true));
            form.addView(updates, margin(-1, dp(52), 0, 0, 0, 10));
            EditText recoveryPin = input("Yeni 6 haneli kurtarma PIN'i",
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            form.addView(recoveryPin, margin(-1, dp(54), 0, 2, 0, 8));
            Button changePin = textButton("Kurtarma PIN'ini değiştir");
            changePin.setBackground(rounded(SURFACE, BLUE, 14));
            changePin.setOnClickListener(v -> {
                String pinValue = recoveryPin.getText().toString().trim();
                if (!isValidPin(pinValue)) {
                    toast("PIN tam 6 rakam olmalı ve kolay bir sayı olmamalı.");
                    return;
                }
                setBusy(true);
                api.setRecoveryPin(pinValue, uiCallback(done -> {
                    setBusy(false);
                    recoveryPin.setText("");
                    toast("Kurtarma PIN'i değiştirildi.");
                }));
            });
            form.addView(changePin, margin(-1, dp(52), 0, 0, 0, 12));
            Button save = primaryButton("Ayarları kaydet");
            save.setOnClickListener(v -> {
                SupabaseClient.Settings changed = new SupabaseClient.Settings(
                        name.getText().toString().trim(), about.getText().toString().trim(),
                        receipts.isChecked(), lastSeen.isChecked(), notifications.isChecked(),
                        callNotifications.isChecked(), appearance.compact());
                setBusy(true);
                api.updateSettings(changed, uiCallback(done -> {
                    setBusy(false); toast("Ayarlar kaydedildi."); loadProfile();
                }));
            });
            form.addView(save, new LinearLayout.LayoutParams(-1, dp(54)));
        }));
    }

    private CheckBox settingCheck(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(TEXT);
        box.setTextSize(16);
        box.setChecked(checked);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        return box;
    }

    private void chooseCameraChat() {
        setBusy(true);
        api.listChats(uiCallback(chats -> {
            setBusy(false);
            if (chats.isEmpty()) { toast("Fotoğraf göndermek için önce bir sohbet başlatın."); return; }
            String[] names = new String[chats.size()];
            for (int i = 0; i < chats.size(); i++) names[i] = preferredName(chats.get(i).displayName, chats.get(i).username);
            new AlertDialog.Builder(this).setTitle("Fotoğrafı hangi sohbete gönderelim?")
                    .setItems(names, (d, which) -> {
                        pendingCameraChatId = chats.get(which).id;
                        pendingCameraChatName = names[which];
                        pendingCameraChatCanCall = "direct".equals(chats.get(which).kind);
                        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera();
                        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                    }).show();
        }));
    }

    private void launchCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) startActivityForResult(intent, REQUEST_CAMERA);
        else toast("Kamera uygulaması bulunamadı.");
    }

    private void showContacts() {
        stopPolling();
        screen = "contacts";
        LinearLayout page = vertical();
        page.addView(topBar("Selam kullananlar", this::showHome));

        LinearLayout actions = horizontal();
        actions.setPadding(dp(14), dp(12), dp(14), dp(8));
        Button scanQr = primaryButton("QR tara");
        scanQr.setOnClickListener(v -> scanQrCode());
        actions.addView(scanQr, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button search = textButton("Kullanıcı ara");
        search.setBackground(rounded(SURFACE, BORDER, 14));
        search.setOnClickListener(v -> showPeopleSearch());
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        searchParams.setMargins(dp(10), 0, 0, 0);
        actions.addView(search, searchParams);
        page.addView(actions);

        FrameLayout content = new FrameLayout(this);
        ListView list = plainList();
        TextView empty = label("Rehberinizde henüz Selam kullanan kişi bulunamadı.", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), dp(30), dp(30), dp(30));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setPage(page);

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            empty.setText("Selam kullanan kişileri gösterebilmek için rehber erişimine izin verin.");
            Button allow = primaryButton("Rehbere izin ver");
            allow.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS));
            page.addView(allow, margin(-1, dp(54), 18, 8, 18, 18));
        } else {
            loadContactMatches(list, empty);
        }
    }

    private void loadContactMatches(ListView list, TextView empty) {
        setBusy(true);
        new Thread(() -> {
            Map<String, String> contacts = readPhoneBook();
            runOnUiThread(() -> {
                localContactNames = contacts;
                if (contacts.isEmpty()) {
                    setBusy(false);
                    empty.setText("Rehberinizde telefon numarası bulunamadı.");
                    return;
                }
                api.matchContacts(new ArrayList<>(contacts.keySet()), uiCallback(matches -> {
                    setBusy(false);
                    empty.setText("Rehberinizde henüz Selam kullanan kişi bulunamadı.");
                    list.setAdapter(new ContactAdapter(matches));
                    list.setOnItemClickListener((parent, view, position, id) -> {
                        SupabaseClient.ContactMatch person = matches.get(position);
                        String localName = localContactNames.get(person.matchedPhone);
                        startChat(person, localName == null
                                ? preferredName(person.displayName, person.username) : localName);
                    });
                }));
            });
        }).start();
    }

    private Map<String, String> readPhoneBook() {
        Map<String, String> contacts = new LinkedHashMap<>();
        String[] columns = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        try (Cursor cursor = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                columns, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (cursor == null) return contacts;
            int nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
            while (cursor.moveToNext() && contacts.size() < 2000) {
                String normalized = normalizePhone(cursor.getString(numberIndex));
                if (normalized != null && !contacts.containsKey(normalized)) {
                    String name = cursor.getString(nameIndex);
                    contacts.put(normalized, name == null || name.trim().isEmpty() ? normalized : name.trim());
                }
            }
        } catch (SecurityException ignored) {
            return contacts;
        }
        return contacts;
    }

    private void showPeopleSearch() {
        screen = "search";
        LinearLayout page = vertical();
        page.addView(topBar("Kullanıcı ara", this::showContacts));
        LinearLayout searchBar = horizontal();
        searchBar.setPadding(dp(16), dp(16), dp(16), dp(12));
        EditText query = input("Kullanıcı adı veya ad", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        queryParams.setMargins(0, 0, dp(10), 0);
        searchBar.addView(query, queryParams);
        Button find = primaryButton("Bul");
        searchBar.addView(find, new LinearLayout.LayoutParams(dp(78), dp(52)));
        page.addView(searchBar);
        ListView results = plainList();
        page.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView help = label("Kullanıcı adıyla da arkadaşınızı bulabilirsiniz.", 15, MUTED, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(32), dp(16), dp(32), dp(24));
        page.addView(help);
        find.setOnClickListener(v -> {
            String text = query.getText().toString().trim();
            if (text.length() < 2) {
                toast("En az 2 karakter yazın.");
                return;
            }
            setBusy(true);
            api.searchPeople(text, uiCallback(people -> {
                setBusy(false);
                help.setText(people.isEmpty() ? "Eşleşen kullanıcı bulunamadı." : "Mesajlaşmak için kullanıcıya dokunun.");
                results.setAdapter(new PersonAdapter(people));
                results.setOnItemClickListener((parent, view, position, id) -> {
                    SupabaseClient.Person person = people.get(position);
                    startChat(person, preferredName(person.displayName, person.username));
                });
            }));
        });
        setPage(page);
        query.requestFocus();
    }

    private void showCreateGroup() {
        stopPolling();
        screen = "group";
        LinearLayout page = vertical();
        page.addView(topBar("Yeni grup", this::showHome));

        EditText groupName = input("Grup adı", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        page.addView(groupName, margin(-1, dp(54), 16, 16, 16, 8));
        TextView help = label("Rehberinizde Selam kullanan kişilerden seçim yapın.", 14, MUTED, false);
        help.setPadding(dp(18), dp(4), dp(18), dp(10));
        page.addView(help);

        ListView people = plainList();
        people.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        page.addView(people, new LinearLayout.LayoutParams(-1, 0, 1));
        Button create = primaryButton("Grubu oluştur");
        create.setEnabled(false);
        page.addView(create, margin(-1, dp(56), 16, 10, 16, 16));
        setPage(page);

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            help.setText("Grup üyelerini bulmak için rehber erişimine izin verin.");
            create.setText("Rehbere izin ver");
            create.setEnabled(true);
            create.setOnClickListener(v -> requestPermissions(
                    new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_GROUP_CONTACTS));
            return;
        }
        loadGroupContacts(groupName, people, help, create);
    }

    private void loadGroupContacts(EditText groupName, ListView list,
                                   TextView help, Button create) {
        setBusy(true);
        new Thread(() -> {
            Map<String, String> contacts = readPhoneBook();
            runOnUiThread(() -> {
                localContactNames = contacts;
                if (contacts.isEmpty()) {
                    setBusy(false);
                    help.setText("Rehberinizde telefon numarası bulunamadı.");
                    return;
                }
                api.matchContacts(new ArrayList<>(contacts.keySet()), uiCallback(matches -> {
                    setBusy(false);
                    if (matches.isEmpty()) {
                        help.setText("Rehberinizde grup kurulabilecek başka Selam kullanıcısı yok.");
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    for (SupabaseClient.ContactMatch person : matches) {
                        String localName = localContactNames.get(person.matchedPhone);
                        names.add(localName == null
                                ? preferredName(person.displayName, person.username) : localName);
                    }
                    list.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_list_item_multiple_choice, names));
                    create.setEnabled(true);
                    help.setText("Bir veya daha fazla kişi seçin.");
                    create.setOnClickListener(v -> {
                        String title = groupName.getText().toString().trim();
                        if (title.length() < 2) {
                            toast("Lütfen grup adını yazın.");
                            return;
                        }
                        List<String> selectedIds = new ArrayList<>();
                        for (int i = 0; i < matches.size(); i++) {
                            if (list.isItemChecked(i)) selectedIds.add(matches.get(i).id);
                        }
                        if (selectedIds.isEmpty()) {
                            toast("Gruba en az bir kişi seçin.");
                            return;
                        }
                        setBusy(true);
                        api.createGroup(title, selectedIds, uiCallback(chatId -> {
                            setBusy(false);
                            toast("Grup oluşturuldu.");
                            openChat(chatId, title, false);
                        }));
                    });
                }));
            });
        }).start();
    }

    private void startChat(SupabaseClient.Person person, String shownName) {
        setBusy(true);
        api.startDirectChat(person.id, uiCallback(chatId -> {
            setBusy(false);
            openChat(chatId, shownName, true);
        }));
    }

    private void showMyQr() {
        screen = "profile";
        LinearLayout page = vertical();
        page.addView(topBar("QR kodum", this::showHome));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = vertical();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(26), dp(24), dp(26), dp(30));
        content.addView(avatar(myProfile.displayName, 72),
                new LinearLayout.LayoutParams(dp(72), dp(72)));
        content.addView(labelCentered(myProfile.displayName, 23, TEXT, true),
                margin(-1, -2, 0, 12, 0, 3));
        content.addView(labelCentered("@" + myProfile.username + "  •  •••• " + myProfile.phoneLast4,
                        14, MUTED, false), margin(-1, -2, 0, 0, 0, 18));
        try {
            Bitmap bitmap = new BarcodeEncoder().encodeBitmap(
                    "SELAM:" + myProfile.safetyCode, BarcodeFormat.QR_CODE, 700, 700);
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(bitmap);
            qr.setAdjustViewBounds(true);
            qr.setBackgroundColor(Color.WHITE);
            content.addView(qr, new LinearLayout.LayoutParams(dp(270), dp(270)));
        } catch (Exception exception) {
            content.addView(labelCentered("QR kod oluşturulamadı.", 15, MUTED, false));
        }
        content.addView(labelCentered("Güvenlik kodu: " + spacedCode(myProfile.safetyCode),
                        17, TEXT, true), margin(-1, -2, 0, 16, 0, 8));
        content.addView(labelCentered("Arkadaşınız bu kodu tarayarak doğru kişiyle konuştuğunu kontrol edebilir.", 14, MUTED, false),
                margin(-1, -2, 0, 0, 0, 22));
        Button scan = primaryButton("Arkadaşımın QR kodunu tara");
        scan.setOnClickListener(v -> scanQrCode());
        content.addView(scan, new LinearLayout.LayoutParams(-1, dp(54)));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setPage(page);
    }

    private void scanQrCode() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Selam QR kodunu çerçeveye yerleştirin")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CAMERA) {
            if (resultCode == RESULT_OK && data != null && pendingCameraChatId != null) {
                Object image = data.getExtras() == null ? null : data.getExtras().get("data");
                if (image instanceof Bitmap) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    ((Bitmap) image).compress(Bitmap.CompressFormat.JPEG, 90, output);
                    byte[] bytes = output.toByteArray();
                    setBusy(true);
                    api.sendFile(pendingCameraChatId, new ByteArrayInputStream(bytes),
                            "Selam-Fotograf-" + System.currentTimeMillis() + ".jpg",
                            "image/jpeg", bytes.length, uiCallback(done -> {
                                setBusy(false);
                                toast("Fotoğraf " + pendingCameraChatName + " sohbetine gönderildi.");
                                openChat(pendingCameraChatId, pendingCameraChatName,
                                        pendingCameraChatCanCall);
                            }));
                }
            }
            return;
        }
        if (requestCode == REQUEST_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                sendPickedFile(data.getData());
            }
            return;
        }
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() == null) {
                toast("QR tarama iptal edildi.");
                return;
            }
            String contents = result.getContents().trim();
            if (!contents.startsWith("SELAM:")) {
                toast("Bu bir Selam QR kodu değil.");
                return;
            }
            setBusy(true);
            api.findInvite(contents.substring(6), uiCallback(person -> {
                setBusy(false);
                startChat(person, preferredName(person.displayName, person.username));
            }));
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showContacts();
            } else {
                toast("Rehber izni olmadan kullanıcı adı veya QR koduyla arkadaş ekleyebilirsiniz.");
            }
        } else if (requestCode == REQUEST_GROUP_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCreateGroup();
            } else {
                toast("Grup oluşturmak için rehber erişimi gereklidir.");
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                toast("Fotoğraf çekebilmek için kamera izni gereklidir.");
            }
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchAudioCall();
            } else {
                toast("İnternet araması için mikrofon izni gereklidir.");
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void startAudioCall(String chatId, String name) {
        pendingCallChatId = chatId;
        pendingCallName = name;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) launchAudioCall();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_AUDIO_PERMISSION);
    }

    void showIncomingCall(SupabaseClient.IncomingCall call) {
        if (isFinishing() || isDestroyed() || call == null
                || call.id.equals(visibleIncomingCallId)) return;
        visibleIncomingCallId = call.id;
        new AlertDialog.Builder(this)
                .setTitle(call.callerName)
                .setMessage("Gelen Selam internet araması")
                .setCancelable(false)
                .setNegativeButton("Reddet", (dialog, which) -> {
                    visibleIncomingCallId = null;
                    api.declineAudioCall(call.id, uiCallback(done -> { }));
                })
                .setPositiveButton("Yanıtla", (dialog, which) -> {
                    visibleIncomingCallId = null;
                    Intent answer = new Intent(this, CallActivity.class)
                            .putExtra(CallActivity.EXTRA_CALL_ID, call.id)
                            .putExtra(CallActivity.EXTRA_CHAT_ID, call.conversationId)
                            .putExtra(CallActivity.EXTRA_NAME, call.callerName)
                            .putExtra(CallActivity.EXTRA_INCOMING, true);
                    startActivity(answer);
                })
                .setOnDismissListener(dialog -> visibleIncomingCallId = null)
                .show();
    }

    private void launchAudioCall() {
        if (pendingCallChatId == null) return;
        Intent call = new Intent(this, CallActivity.class)
                .putExtra(CallActivity.EXTRA_CHAT_ID, pendingCallChatId)
                .putExtra(CallActivity.EXTRA_NAME, pendingCallName)
                .putExtra(CallActivity.EXTRA_INCOMING, false);
        pendingCallChatId = null;
        pendingCallName = null;
        startActivity(call);
    }

    private void openChat(String chatId, String chatName, boolean canCall) {
        stopPolling();screen="external-chat";activeChatId=null;
        startActivity(new Intent(this,ChatActivity.class).putExtra("chat_id",chatId).putExtra("name",chatName).putExtra("direct",canCall));
    }

    private void confirmDeleteChat(String chatId, String chatName) {
        new AlertDialog.Builder(this)
                .setTitle("Sohbet silinsin mi?")
                .setMessage(chatName + " sohbetinin mevcut geçmişi yalnızca sizden temizlenecek. Yeni mesaj gelirse sohbet tekrar görünür.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (dialog, which) -> {
                    stopPolling();
                    setBusy(true);
                    api.deleteChat(chatId, uiCallback(done -> {
                        setBusy(false);
                        toast("Sohbet temizlendi.");
                        showHome();
                    }));
                })
                .show();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE);
    }

    private void sendPickedFile(Uri uri) {
        String name = "dosya";
        long size = -1L;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
            if (size > 10L * 1024L * 1024L) {
                toast("Dosya en fazla 10 MB olabilir.");
                return;
            }
            String mimeType = getContentResolver().getType(uri);
            java.io.InputStream input = getContentResolver().openInputStream(uri);
            if (input == null) throw new Exception("Dosya açılamadı.");
            setBusy(true);
            api.sendFile(activeChatId, input, name, mimeType, size, uiCallback(done -> {
                setBusy(false);
                toast("Dosya gönderildi.");
                refreshMessages();
            }));
        } catch (Exception exception) {
            setBusy(false);
            toast(exception.getMessage() == null ? "Dosya açılamadı." : exception.getMessage());
        }
    }

    private void openFile(SupabaseClient.Message message) {
        setBusy(true);
        api.createSignedFileUrl(message.filePath, uiCallback(url -> {
            setBusy(false);
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(Intent.createChooser(view, "Dosyayı aç"));
            } catch (Exception exception) {
                toast("Dosyayı açabilecek bir uygulama bulunamadı.");
            }
        }));
    }

    private void refreshMessages() {
        if (!"chat".equals(screen) || activeChatId == null) return;
        api.listMessages(activeChatId, uiCallback(messages -> {
            if (!"chat".equals(screen) || activeMessageList == null) return;
            boolean changed = activeMessages.size() != messages.size()
                    || (!messages.isEmpty() && (activeMessages.isEmpty()
                    || activeMessages.get(activeMessages.size() - 1).id
                    != messages.get(messages.size() - 1).id));
            activeMessages = messages;
            if (changed) {
                activeMessageList.setAdapter(new MessageAdapter(messages));
                activeMessageList.post(() -> activeMessageList.setSelection(
                        Math.max(0, messages.size() - 1)));
            }
            handler.removeCallbacks(pollMessages);
            handler.postDelayed(pollMessages, POLL_INTERVAL);
        }, error -> {
            handler.removeCallbacks(pollMessages);
            handler.postDelayed(pollMessages, POLL_INTERVAL * 2);
        }));
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        boolean international = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
            international = true;
        }
        String result;
        if (international) result = "+" + digits;
        else if (digits.length() == 11 && digits.startsWith("0")) result = "+90" + digits.substring(1);
        else if (digits.length() == 10) result = "+90" + digits;
        else if (digits.length() == 12 && digits.startsWith("90")) result = "+" + digits;
        else return null;
        return result.matches("^\\+[1-9][0-9]{7,14}$") ? result : null;
    }

    private static boolean isValidPin(String pin) {
        return pin != null && pin.matches("^[0-9]{6}$")
                && !"000000".equals(pin)
                && !"111111".equals(pin)
                && !"123456".equals(pin)
                && !"654321".equals(pin);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollMessages);
        handler.removeCallbacks(homePoll);
    }

    private void setPage(View page) {
        page.setBackgroundColor(BACKGROUND);
        root.removeAllViews();
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        root.addView(progress);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private <T> SupabaseClient.Callback<T> uiCallback(Success<T> success) {
        return uiCallback(success, error -> { });
    }

    private <T> SupabaseClient.Callback<T> uiCallback(Success<T> success, Failure failure) {
        return new SupabaseClient.Callback<T>() {
            @Override public void onSuccess(T value) {
                runOnUiThread(() -> success.run(value));
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast(message);
                    failure.run(message);
                });
            }
        };
    }

    private interface Success<T> { void run(T value); }
    private interface Failure { void run(String message); }

    private LinearLayout centeredPage() {
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(30), dp(40), dp(30), dp(40));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        page.addView(logo, new LinearLayout.LayoutParams(dp(92), dp(92)));
        return page;
    }

    private LinearLayout topBar(String title, Runnable backAction) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(10), dp(14), dp(10));
        header.setBackgroundColor(NAVY);
        Button back = headerButton("‹");
        back.setTextSize(30);
        back.setOnClickListener(v -> backAction.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView titleView = label(title, 20, Color.WHITE, true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private ListView plainList() {
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(BACKGROUND);
        return list;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(0, 1.12f);
        return view;
    }

    private TextView labelCentered(String value, int size, int color, boolean bold) {
        TextView view = label(value, size, color, bold);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private EditText input(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(133, 145, 161));
        edit.setTextColor(TEXT);
        edit.setTextSize(16);
        edit.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        edit.setInputType(inputType);
        edit.setPadding(dp(16), dp(11), dp(16), dp(11));
        edit.setBackground(rounded(SURFACE, BORDER, 14));
        return edit;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(rounded(BLUE, BLUE, 14));
        return button;
    }

    private Button textButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(BLUE);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private Button headerButton(String value) {
        Button button = textButton(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setPadding(dp(3), 0, dp(3), 0);
        return button;
    }

    private ImageButton headerIconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        Drawable drawable = getDrawable(iconResource).mutate();
        drawable.setTint(Color.WHITE);
        button.setImageDrawable(drawable);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        return button;
    }

    private TextView avatar(String name, int size) {
        TextView avatar = label(initial(name), Math.max(14, size / 2 - 3), Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(BLUE, BLUE, size / 2));
        return avatar;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private static String preferredName(String displayName, String username) {
        return displayName == null || displayName.trim().isEmpty() ? "@" + username : displayName;
    }

    private static String initial(String name) {
        if (name == null || name.trim().isEmpty()) return "S";
        return name.trim().substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private static String time(String iso) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(iso));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isFuture(String iso) {
        if (iso == null || iso.isEmpty() || "null".equalsIgnoreCase(iso)) return false;
        try {
            return Instant.parse(iso).isAfter(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String spacedCode(String code) {
        if (code == null) return "";
        return code.replaceAll("(.{4})(?!$)", "$1 ");
    }

    @Override
    public void onBackPressed() {
        if ("chat".equals(screen) || "contacts".equals(screen) || "group".equals(screen)
                || "search".equals(screen) || "profile".equals(screen)
                || "updates".equals(screen) || "communities".equals(screen)
                || "calls".equals(screen)
                || "settings".equals(screen)
                || "archived".equals(screen)) showHome();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        if (alerts != null) alerts.close();
        if (updateManager != null) updateManager.close();
        api.close();
        super.onDestroy();
    }

    private final class ChatAdapter extends BaseAdapter {
        private final List<SupabaseClient.Chat> items;
        ChatAdapter(List<SupabaseClient.Chat> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Chat chat = items.get(position);
            String name = "group".equals(chat.kind) ? chat.displayName
                    : preferredName(chat.displayName, chat.username);
            String subtitle = chat.lastMessage == null || chat.lastMessage.isEmpty()
                    ? "Yeni sohbet" : chat.lastMessage;
            if (chat.pinned) subtitle = "📌 " + subtitle;
            if (isFuture(chat.mutedUntil)) subtitle = "🔕 " + subtitle;
            LinearLayout row = personRow(name, subtitle, 52);
            LinearLayout details=vertical();
            details.addView(label(time(chat.lastMessageAt),12,MUTED,false));
            if(chat.unreadCount>0){TextView badge=label(chat.unreadCount>99?"99+":String.valueOf(chat.unreadCount),13,Color.WHITE,true);badge.setGravity(Gravity.CENTER);badge.setBackground(rounded(BLUE,BLUE,12));details.addView(badge,margin(dp(30),dp(24),0,6,0,0));}
            if(chat.favorite)details.addView(label("★",15,BLUE,false));
            row.addView(details);
            row.setLayoutParams(rowParams(appearance.compact()?66:82));
            return row;
        }
    }

    private final class ContactAdapter extends BaseAdapter {
        private final List<SupabaseClient.ContactMatch> items;
        ContactAdapter(List<SupabaseClient.ContactMatch> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.ContactMatch person = items.get(position);
            String localName = localContactNames.get(person.matchedPhone);
            String name = localName == null ? preferredName(person.displayName, person.username) : localName;
            String subtitle = localName == null || localName.equals(person.displayName)
                    ? "Selam kullanıyor" : person.displayName + " • Selam kullanıyor";
            LinearLayout row = personRow(name, subtitle, 48);
            row.addView(label("Mesaj →", 14, BLUE, true));
            row.setLayoutParams(rowParams(74));
            return row;
        }
    }

    private final class PersonAdapter extends BaseAdapter {
        private final List<SupabaseClient.Person> items;
        PersonAdapter(List<SupabaseClient.Person> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Person person = items.get(position);
            String name = preferredName(person.displayName, person.username);
            LinearLayout row = personRow(name, "@" + person.username, 48);
            row.addView(label("Mesaj →", 14, BLUE, true));
            row.setLayoutParams(rowParams(72));
            return row;
        }
    }

    private final class CallLogAdapter extends BaseAdapter {
        private final List<SupabaseClient.CallLog> items;
        CallLogAdapter(List<SupabaseClient.CallLog> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.CallLog call = items.get(position);
            String direction = call.outgoing ? "↗ Giden" : "↙ Gelen";
            String state;
            if ("ended".equals(call.state)) state = "Tamamlandı";
            else if ("declined".equals(call.state)) state = "Reddedildi";
            else if ("missed".equals(call.state)) state = "Cevapsız";
            else if ("accepted".equals(call.state)) state = "Bağlandı";
            else state = "Arama";
            LinearLayout row = personRow(call.otherName,
                    direction + " • " + state + " • " + time(call.startedAt), 50);
            ImageView phone = new ImageView(MainActivity.this);
            Drawable drawable = getDrawable(R.drawable.ic_phone).mutate();
            drawable.setTint(BLUE);
            phone.setImageDrawable(drawable);
            phone.setContentDescription("Tekrar ara");
            phone.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.addView(phone, new LinearLayout.LayoutParams(dp(44), dp(44)));
            row.setLayoutParams(rowParams(76));
            return row;
        }
    }

    private final class StatusAdapter extends BaseAdapter {
        private final List<SupabaseClient.StatusUpdate> items;
        StatusAdapter(List<SupabaseClient.StatusUpdate> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.StatusUpdate item = items.get(position);
            LinearLayout row = personRow(item.mine ? "Durumum" : item.displayName, item.body, 50);
            row.addView(label(time(item.createdAt), 12, MUTED, false));
            row.setLayoutParams(rowParams(76));
            return row;
        }
    }

    private final class CommunityAdapter extends BaseAdapter {
        private final List<SupabaseClient.Community> items;
        CommunityAdapter(List<SupabaseClient.Community> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Community item = items.get(position);
            String subtitle = item.description == null || item.description.isEmpty()
                    ? item.memberCount + " üye" : item.description + " • " + item.memberCount + " üye";
            LinearLayout row = personRow(item.name, subtitle, 52);
            row.addView(label("Topluluk", 12, BLUE, true));
            row.setLayoutParams(rowParams(78));
            return row;
        }
    }

    private LinearLayout personRow(String name, String subtitle, int avatarSize) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(appearance.compact()?6:10), dp(18), dp(appearance.compact()?6:10));
        if(appearance.compact())avatarSize=Math.min(avatarSize,42);
        row.setBackgroundColor(SURFACE);
        row.addView(avatar(name, avatarSize), new LinearLayout.LayoutParams(dp(avatarSize), dp(avatarSize)));
        LinearLayout text = vertical();
        text.setPadding(dp(14), 0, dp(6), 0);
        text.addView(label(name, 17, TEXT, true));
        TextView sub = label(subtitle, 14, MUTED, false);
        sub.setMaxLines(1);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private LinearLayout.LayoutParams rowParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.setMargins(dp(12), dp(4), dp(12), dp(4));
        return params;
    }

    private final class MessageAdapter extends BaseAdapter {
        private final List<SupabaseClient.Message> items;
        MessageAdapter(List<SupabaseClient.Message> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Message message = items.get(position);
            boolean mine = api.userId().equals(message.senderId);
            LinearLayout outer = vertical();
            outer.setGravity(mine ? Gravity.END : Gravity.START);
            outer.setPadding(mine ? dp(58) : dp(10), dp(4),
                    mine ? dp(10) : dp(58), dp(4));
            LinearLayout bubble = vertical();
            bubble.setPadding(dp(13), dp(9), dp(11), dp(7));
            bubble.setBackground(rounded(mine ? BLUE : Color.WHITE,
                    mine ? BLUE : BORDER, 16));
            String content = message.isFile()
                    ? "📎 " + message.fileName + "\n" + fileSize(message.fileSize)
                    : message.body;
            bubble.addView(label(content, 16, mine ? Color.WHITE : TEXT, message.isFile()));
            TextView when = label(time(message.createdAt), 11,
                    mine ? Color.rgb(219, 232, 255) : MUTED, false);
            when.setGravity(Gravity.END);
            bubble.addView(when);
            if (message.isFile()) {
                bubble.setClickable(true);
                bubble.setOnClickListener(v -> openFile(message));
            }
            outer.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
            return outer;
        }
    }

    private static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
