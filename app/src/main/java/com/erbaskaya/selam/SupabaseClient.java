package com.erbaskaya.selam;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SupabaseClient {
    interface Callback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    static final class Profile {
        final String username;
        final String displayName;
        final String phoneLast4;
        final String safetyCode;
        final boolean ready;

        Profile(String username, String displayName, String phoneLast4,
                String safetyCode, boolean ready) {
            this.username = username;
            this.displayName = displayName;
            this.phoneLast4 = phoneLast4;
            this.safetyCode = safetyCode;
            this.ready = ready;
        }
    }

    static class Person {
        final String id;
        final String username;
        final String displayName;

        Person(String id, String username, String displayName) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
        }
    }

    static final class ContactMatch extends Person {
        final String matchedPhone;

        ContactMatch(String id, String username, String displayName, String matchedPhone) {
            super(id, username, displayName);
            this.matchedPhone = matchedPhone;
        }
    }

    static final class Chat {
        final String id;
        final String username;
        final String displayName;
        final String lastMessage;
        final String lastMessageAt;

        Chat(String id, String username, String displayName,
             String lastMessage, String lastMessageAt) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.lastMessage = lastMessage;
            this.lastMessageAt = lastMessageAt;
        }
    }

    static final class Message {
        final long id;
        final String senderId;
        final String body;
        final String createdAt;

        Message(long id, String senderId, String body, String createdAt) {
            this.id = id;
            this.senderId = senderId;
            this.body = body;
            this.createdAt = createdAt;
        }
    }

    private static final String PREFS = "selam_session";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String USER_ID = "user_id";

    private final String baseUrl = BuildConfig.SUPABASE_URL;
    private final String publishableKey = BuildConfig.SUPABASE_ANON_KEY;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    SupabaseClient(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isConfigured() {
        return baseUrl != null && baseUrl.startsWith("https://")
                && publishableKey != null && !publishableKey.trim().isEmpty();
    }

    boolean hasSession() {
        return !accessToken().isEmpty() && !userId().isEmpty();
    }

    String userId() {
        return preferences.getString(USER_ID, "");
    }

    void createDeviceSession(Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("data", new JSONObject())
                        .put("gotrue_meta_security", new JSONObject());
                Response response = request("POST", "/auth/v1/signup", null, payload);
                if (!response.ok()) {
                    String detail = apiError(response);
                    if (detail.toLowerCase().contains("anonymous")
                            || detail.toLowerCase().contains("provider")) {
                        detail = "Cihaz hesabı sunucuda etkin değil. Lütfen daha sonra tekrar deneyin.";
                    }
                    callback.onError(detail);
                    return;
                }
                saveSession(new JSONObject(response.body));
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void getMyProfile(Callback<Profile> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/get_my_profile", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("Profil bulunamadı.");
                JSONObject item = array.getJSONObject(0);
                callback.onSuccess(new Profile(
                        item.optString("username"), item.optString("display_name"),
                        item.optString("phone_last4"), item.optString("safety_code"),
                        item.optBoolean("profile_ready")
                ));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void setupProfile(String displayName, String phoneE164, Callback<Profile> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("new_display_name", displayName.trim())
                        .put("phone_e164", phoneE164);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/setup_profile", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("Profil oluşturulamadı.");
                JSONObject item = array.getJSONObject(0);
                callback.onSuccess(new Profile(
                        item.optString("username"), item.optString("display_name"),
                        item.optString("phone_last4"), item.optString("safety_code"), true));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void matchContacts(List<String> phones, Callback<List<ContactMatch>> callback) {
        executor.execute(() -> {
            try {
                JSONArray numbers = new JSONArray();
                for (String phone : phones) numbers.put(phone);
                JSONObject payload = new JSONObject().put("contact_phones", numbers);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/match_contacts", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<ContactMatch> matches = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    matches.add(new ContactMatch(
                            item.optString("user_id"), item.optString("username"),
                            item.optString("display_name"), item.optString("matched_phone")
                    ));
                }
                callback.onSuccess(matches);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void findInvite(String inviteCode, Callback<Person> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("invite_code", inviteCode.trim());
                Response response = authorizedRequest("POST", "/rest/v1/rpc/find_invite", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("QR kod geçersiz.");
                JSONObject item = array.getJSONObject(0);
                callback.onSuccess(new Person(item.optString("user_id"),
                        item.optString("username"), item.optString("display_name")));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listChats(Callback<List<Chat>> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/list_my_chats", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<Chat> chats = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    chats.add(new Chat(
                            item.optString("conversation_id"), item.optString("username"),
                            item.optString("display_name"), item.optString("last_message"),
                            item.optString("last_message_at")
                    ));
                }
                callback.onSuccess(chats);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void searchPeople(String query, Callback<List<Person>> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("search_text", query.trim());
                Response response = authorizedRequest("POST", "/rest/v1/rpc/search_people", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<Person> people = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    people.add(new Person(item.optString("user_id"),
                            item.optString("username"), item.optString("display_name")));
                }
                callback.onSuccess(people);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void startDirectChat(String otherUserId, Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("other_user_id", otherUserId);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/start_direct_chat", payload);
                ensureSuccess(response);
                String result = response.body.trim();
                if (result.startsWith("\"") && result.endsWith("\"")) {
                    result = new JSONArray("[" + result + "]").getString(0);
                }
                if (result.isEmpty() || "null".equals(result)) throw new IOException("Sohbet oluşturulamadı.");
                callback.onSuccess(result);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listMessages(String conversationId, Callback<List<Message>> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("chat_id", conversationId);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/list_chat_messages", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<Message> messages = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    messages.add(new Message(item.optLong("message_id"),
                            item.optString("sender_id"), item.optString("message_body"),
                            item.optString("created_at")));
                }
                callback.onSuccess(messages);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void sendMessage(String conversationId, String body, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("chat_id", conversationId)
                        .put("message_body", body.trim());
                Response response = authorizedRequest("POST", "/rest/v1/rpc/send_chat_message", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void clearSession() {
        preferences.edit().clear().apply();
    }

    void close() {
        executor.shutdownNow();
    }

    private String accessToken() {
        return preferences.getString(ACCESS_TOKEN, "");
    }

    private String refreshToken() {
        return preferences.getString(REFRESH_TOKEN, "");
    }

    private void saveSession(JSONObject data) throws JSONException {
        JSONObject user = data.getJSONObject("user");
        preferences.edit()
                .putString(ACCESS_TOKEN, data.getString("access_token"))
                .putString(REFRESH_TOKEN, data.optString("refresh_token"))
                .putString(USER_ID, user.getString("id"))
                .apply();
    }

    private Response request(String method, String path, String token, JSONObject payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(trimSlash(baseUrl) + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("apikey", publishableKey);
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400
                ? connection.getInputStream() : connection.getErrorStream();
        String body = read(stream);
        connection.disconnect();
        return new Response(code, body);
    }

    private Response authorizedRequest(String method, String path, JSONObject payload)
            throws IOException, JSONException {
        Response response = request(method, path, accessToken(), payload);
        if (response.code != 401 || refreshToken().isEmpty()) return response;

        JSONObject refreshPayload = new JSONObject().put("refresh_token", refreshToken());
        Response refreshed = request("POST", "/auth/v1/token?grant_type=refresh_token", null, refreshPayload);
        if (!refreshed.ok()) {
            clearSession();
            return response;
        }
        saveSession(new JSONObject(refreshed.body));
        return request(method, path, accessToken(), payload);
    }

    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void ensureSuccess(Response response) throws IOException {
        if (!response.ok()) throw new IOException(apiError(response));
    }

    private static String apiError(Response response) {
        try {
            JSONObject data = new JSONObject(response.body);
            String message = data.optString("message",
                    data.optString("msg", data.optString("error_description", data.optString("error"))));
            if (response.code == 401) return "Cihaz oturumu sona erdi. Uygulamayı yeniden açın.";
            return message.isEmpty() ? "Sunucu hatası (" + response.code + ")" : message;
        } catch (Exception ignored) {
            return "Sunucu hatası (" + response.code + ")";
        }
    }

    private static String friendly(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return "Beklenmeyen bir hata oluştu.";
        if (message.contains("Unable to resolve host") || message.contains("failed to connect")) {
            return "İnternet bağlantısı kurulamadı.";
        }
        return message;
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }
}
