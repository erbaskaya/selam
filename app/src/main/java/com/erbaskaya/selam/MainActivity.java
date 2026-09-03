package com.erbaskaya.selam;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int BLUE = Color.rgb(36, 107, 253);
    private static final int NAVY = Color.rgb(7, 17, 31);
    private static final int BACKGROUND = Color.rgb(242, 246, 251);
    private static final int TEXT = Color.rgb(20, 34, 53);
    private static final int MUTED = Color.rgb(103, 117, 137);
    private static final int BORDER = Color.rgb(218, 226, 237);
    private static final long POLL_INTERVAL = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollMessages = this::refreshMessages;
    private SupabaseClient api;
    private FrameLayout root;
    private ProgressBar progress;
    private String screen = "login";
    private String activeChatId;
    private ListView activeMessageList;
    private List<SupabaseClient.Message> activeMessages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        api = new SupabaseClient(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);

        progress = new ProgressBar(this);
        progress.setLayoutParams(new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        progress.setVisibility(View.GONE);

        if (!api.isConfigured()) showConfigurationNotice();
        else if (api.hasSession()) showHome();
        else showLogin(false);
    }

    private void showConfigurationNotice() {
        screen = "config";
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(30), dp(40), dp(30), dp(40));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        page.addView(logo, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView title = label("Selam sunucusu hazırlanıyor", 24, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, margin(-1, -2, 0, 24, 0, 8));

        TextView description = label(
                "Bu bağımsız sürüm gerçek mesajlaşma sunucusuna bağlanır. Sunucu adresi APK derlenirken eklenmelidir.",
                16, MUTED, false);
        description.setGravity(Gravity.CENTER);
        page.addView(description, margin(-1, -2, 0, 0, 0, 24));

        Button retry = primaryButton("Tekrar kontrol et");
        retry.setOnClickListener(v -> recreate());
        page.addView(retry, new LinearLayout.LayoutParams(-1, dp(54)));
        setPage(page);
    }

    private void showLogin(boolean registerMode) {
        stopPolling();
        screen = "login";

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = vertical();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(34), dp(28), dp(34));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        page.addView(logo, new LinearLayout.LayoutParams(dp(86), dp(86)));

        TextView brand = label("Selam", 32, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        page.addView(brand, margin(-1, -2, 0, 14, 0, 4));

        TextView subtitle = label(registerMode ? "Yeni hesabını oluştur" : "Mesajlarına devam et", 16, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        page.addView(subtitle, margin(-1, -2, 0, 0, 0, 28));

        EditText displayName = input("Adınız ve soyadınız", InputType.TYPE_CLASS_TEXT);
        EditText username = input("Kullanıcı adı", InputType.TYPE_CLASS_TEXT);
        if (registerMode) {
            page.addView(displayName, margin(-1, dp(54), 0, 0, 0, 12));
            page.addView(username, margin(-1, dp(54), 0, 0, 0, 12));
        }

        EditText email = input("E-posta adresi", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        page.addView(email, margin(-1, dp(54), 0, 0, 0, 12));

        EditText password = input("Şifre", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(password, margin(-1, dp(54), 0, 0, 0, 18));

        Button submit = primaryButton(registerMode ? "Hesap oluştur" : "Giriş yap");
        page.addView(submit, new LinearLayout.LayoutParams(-1, dp(54)));

        Button switchMode = textButton(registerMode ? "Zaten hesabım var" : "Yeni hesap oluştur");
        page.addView(switchMode, margin(-1, dp(52), 0, 12, 0, 0));
        switchMode.setOnClickListener(v -> showLogin(!registerMode));

        submit.setOnClickListener(v -> {
            String emailValue = email.getText().toString().trim();
            String passwordValue = password.getText().toString();
            if (emailValue.isEmpty() || passwordValue.length() < 6) {
                toast("Geçerli e-posta ve en az 6 karakterli şifre girin.");
                return;
            }
            setBusy(true);
            hideKeyboard();
            if (registerMode) {
                String usernameValue = username.getText().toString().trim();
                String nameValue = displayName.getText().toString().trim();
                if (!usernameValue.matches("[a-zA-Z0-9_.]{3,24}") || nameValue.length() < 2) {
                    setBusy(false);
                    toast("Kullanıcı adı 3-24 karakter olmalı; yalnızca harf, rakam, nokta ve alt çizgi kullanın.");
                    return;
                }
                api.signUp(emailValue, passwordValue, usernameValue, nameValue,
                        uiCallback(result -> {
                            setBusy(false);
                            toast(result.message);
                            if (result.signedIn) showHome();
                            else showLogin(false);
                        }));
            } else {
                api.signIn(emailValue, passwordValue, uiCallback(result -> {
                    setBusy(false);
                    showHome();
                }));
            }
        });

        setPage(scroll);
    }

    private void showHome() {
        stopPolling();
        screen = "home";
        activeChatId = null;
        LinearLayout page = vertical();

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(14), dp(12), dp(14));
        header.setBackgroundColor(NAVY);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = label("Selam", 25, Color.WHITE, true);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button search = headerButton("Ara");
        search.setOnClickListener(v -> showPeopleSearch());
        header.addView(search, new LinearLayout.LayoutParams(dp(66), dp(44)));

        Button logout = headerButton("Çıkış");
        logout.setOnClickListener(v -> {
            api.signOut();
            showLogin(false);
        });
        header.addView(logout, new LinearLayout.LayoutParams(dp(68), dp(44)));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView heading = label("Sohbetler", 21, TEXT, true);
        heading.setPadding(dp(20), dp(22), dp(20), dp(12));
        page.addView(heading);

        FrameLayout content = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(BACKGROUND);
        TextView empty = label("Henüz sohbet yok. Sağ üstteki Ara düğmesiyle bir arkadaşını bul.", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(34), dp(34), dp(34), dp(34));
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        setPage(page);
        loadChats(list);
    }

    private void loadChats(ListView list) {
        setBusy(true);
        api.listChats(uiCallback(chats -> {
            setBusy(false);
            list.setAdapter(new ChatAdapter(chats));
            list.setOnItemClickListener((parent, view, position, id) -> {
                SupabaseClient.Chat chat = chats.get(position);
                openChat(chat.id, preferredName(chat.displayName, chat.username));
            });
        }));
    }

    private void showPeopleSearch() {
        screen = "search";
        LinearLayout page = vertical();

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(12), dp(14), dp(12));
        header.setBackgroundColor(NAVY);

        Button back = headerButton("‹");
        back.setTextSize(30);
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = label("Yeni sohbet", 21, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        LinearLayout searchBar = horizontal();
        searchBar.setPadding(dp(16), dp(16), dp(16), dp(12));
        EditText query = input("Kullanıcı adı veya ad", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        queryParams.setMargins(0, 0, dp(10), 0);
        searchBar.addView(query, queryParams);
        Button find = primaryButton("Bul");
        searchBar.addView(find, new LinearLayout.LayoutParams(dp(78), dp(52)));
        page.addView(searchBar);

        ListView results = new ListView(this);
        results.setDividerHeight(0);
        page.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView help = label("Arkadaşınızın kullanıcı adını yazarak sohbet başlatabilirsiniz.", 16, MUTED, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(32), dp(18), dp(32), dp(24));
        page.addView(help, new LinearLayout.LayoutParams(-1, -2));

        find.setOnClickListener(v -> {
            String text = query.getText().toString().trim();
            if (text.length() < 2) {
                toast("En az 2 karakter yazın.");
                return;
            }
            setBusy(true);
            api.searchPeople(text, uiCallback(people -> {
                setBusy(false);
                help.setText(people.isEmpty() ? "Eşleşen kullanıcı bulunamadı." : "Kullanıcıya dokunarak sohbeti başlatın.");
                results.setAdapter(new PersonAdapter(people));
                results.setOnItemClickListener((parent, view, position, id) -> {
                    SupabaseClient.Person person = people.get(position);
                    setBusy(true);
                    api.startDirectChat(person.id, uiCallback(chatId -> {
                        setBusy(false);
                        openChat(chatId, preferredName(person.displayName, person.username));
                    }));
                });
            }));
        });

        setPage(page);
        query.requestFocus();
    }

    private void openChat(String chatId, String chatName) {
        stopPolling();
        screen = "chat";
        activeChatId = chatId;
        activeMessages = new ArrayList<>();

        LinearLayout page = vertical();
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(10), dp(14), dp(10));
        header.setBackgroundColor(NAVY);

        Button back = headerButton("‹");
        back.setTextSize(30);
        back.setOnClickListener(v -> showHome());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));

        TextView avatar = avatar(chatName, 42);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout names = vertical();
        names.setPadding(dp(10), 0, 0, 0);
        names.addView(label(chatName, 18, Color.WHITE, true));
        names.addView(label("mesajlaşma", 12, Color.rgb(181, 198, 222), false));
        header.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        activeMessageList = new ListView(this);
        activeMessageList.setDividerHeight(0);
        activeMessageList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        activeMessageList.setStackFromBottom(true);
        page.addView(activeMessageList, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = horizontal();
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(12), dp(10), dp(12), dp(12));
        composer.setBackgroundColor(Color.WHITE);
        EditText message = input("Mesaj", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        message.setMaxLines(4);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, -2, 1);
        messageParams.setMargins(0, 0, dp(10), 0);
        composer.addView(message, messageParams);
        Button send = primaryButton("Gönder");
        composer.addView(send, new LinearLayout.LayoutParams(dp(94), dp(52)));
        page.addView(composer);

        send.setOnClickListener(v -> {
            String body = message.getText().toString().trim();
            if (body.isEmpty()) return;
            send.setEnabled(false);
            api.sendMessage(activeChatId, body, uiCallback(done -> {
                message.setText("");
                send.setEnabled(true);
                refreshMessages();
            }, error -> send.setEnabled(true)));
        });

        setPage(page);
        refreshMessages();
    }

    private void refreshMessages() {
        if (!"chat".equals(screen) || activeChatId == null) return;
        api.listMessages(activeChatId, uiCallback(messages -> {
            if (!"chat".equals(screen) || activeMessageList == null) return;
            boolean changed = activeMessages.size() != messages.size()
                    || (!messages.isEmpty() && (activeMessages.isEmpty()
                    || activeMessages.get(activeMessages.size() - 1).id != messages.get(messages.size() - 1).id));
            activeMessages = messages;
            if (changed) {
                activeMessageList.setAdapter(new MessageAdapter(messages));
                activeMessageList.post(() -> activeMessageList.setSelection(Math.max(0, messages.size() - 1)));
            }
            handler.removeCallbacks(pollMessages);
            handler.postDelayed(pollMessages, POLL_INTERVAL);
        }, error -> {
            handler.removeCallbacks(pollMessages);
            handler.postDelayed(pollMessages, POLL_INTERVAL * 2);
        }));
    }

    private void stopPolling() {
        handler.removeCallbacks(pollMessages);
    }

    private void setPage(View page) {
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
            @Override
            public void onSuccess(T value) {
                runOnUiThread(() -> success.run(value));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast(message);
                    if (!api.hasSession() && !"login".equals(screen) && !"config".equals(screen)) {
                        showLogin(false);
                    }
                    failure.run(message);
                });
            }
        };
    }

    private interface Success<T> { void run(T value); }
    private interface Failure { void run(String message); }

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

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(0, 1.12f);
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
        edit.setBackground(rounded(Color.WHITE, BORDER, 14));
        return edit;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(12), 0, dp(12), 0);
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
        button.setPadding(dp(4), 0, dp(4), 0);
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
        return name.trim().substring(0, 1).toUpperCase();
    }

    private static String time(String iso) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(iso));
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        if ("chat".equals(screen) || "search".equals(screen)) showHome();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        api.close();
        super.onDestroy();
    }

    private final class ChatAdapter extends BaseAdapter {
        private final List<SupabaseClient.Chat> items;
        ChatAdapter(List<SupabaseClient.Chat> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Chat chat = items.get(position);
            String name = preferredName(chat.displayName, chat.username);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(12), dp(18), dp(12));
            row.setBackgroundColor(Color.WHITE);
            row.addView(avatar(name, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));

            LinearLayout text = vertical();
            text.setPadding(dp(14), 0, dp(6), 0);
            text.addView(label(name, 17, TEXT, true));
            TextView preview = label(chat.lastMessage == null || chat.lastMessage.isEmpty()
                    ? "Yeni sohbet" : chat.lastMessage, 14, MUTED, false);
            preview.setMaxLines(1);
            text.addView(preview);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label(time(chat.lastMessageAt), 12, MUTED, false));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(78));
            params.setMargins(dp(12), dp(4), dp(12), dp(4));
            row.setLayoutParams(params);
            return row;
        }
    }

    private final class PersonAdapter extends BaseAdapter {
        private final List<SupabaseClient.Person> items;
        PersonAdapter(List<SupabaseClient.Person> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Person person = items.get(position);
            String name = preferredName(person.displayName, person.username);
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setBackgroundColor(Color.WHITE);
            row.addView(avatar(name, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout text = vertical();
            text.setPadding(dp(14), 0, 0, 0);
            text.addView(label(name, 17, TEXT, true));
            text.addView(label("@" + person.username, 14, MUTED, false));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label("Mesaj →", 14, BLUE, true));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(72));
            params.setMargins(dp(12), dp(4), dp(12), dp(4));
            row.setLayoutParams(params);
            return row;
        }
    }

    private final class MessageAdapter extends BaseAdapter {
        private final List<SupabaseClient.Message> items;
        MessageAdapter(List<SupabaseClient.Message> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SupabaseClient.Message message = items.get(position);
            boolean mine = api.userId().equals(message.senderId);
            LinearLayout outer = vertical();
            outer.setGravity(mine ? Gravity.END : Gravity.START);
            outer.setPadding(mine ? dp(58) : dp(10), dp(4), mine ? dp(10) : dp(58), dp(4));

            LinearLayout bubble = vertical();
            bubble.setPadding(dp(13), dp(9), dp(11), dp(7));
            bubble.setBackground(rounded(mine ? BLUE : Color.WHITE, mine ? BLUE : BORDER, 16));
            bubble.addView(label(message.body, 16, mine ? Color.WHITE : TEXT, false));
            TextView when = label(time(message.createdAt), 11, mine ? Color.rgb(219, 232, 255) : MUTED, false);
            when.setGravity(Gravity.END);
            bubble.addView(when);
            outer.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
            return outer;
        }
    }
}
