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

    static final class AuthResult {
        final boolean signedIn;
        final String message;

        AuthResult(boolean signedIn, String message) {
            this.signedIn = signedIn;
            this.message = message;
        }
    }

    static final class Person {
        final String id;
        final String username;
        final String displayName;

        Person(String id, String username, String displayName) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
        }
    }

    static final class Chat {
        final String id;
        final String personId;
        final String username;
        final String displayName;
        final String lastMessage;
        final String lastMessageAt;

        Chat(String id, String personId, String username, String displayName,
             String lastMessage, String lastMessageAt) {
            this.id = id;
            this.personId = personId;
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
    private final String anonKey = BuildConfig.SUPABASE_ANON_KEY;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    SupabaseClient(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean isConfigured() {
        return baseUrl != null && baseUrl.startsWith("https://")
                && anonKey != null && !anonKey.trim().isEmpty();
    }

    boolean hasSession() {
        return !accessToken().isEmpty() && !userId().isEmpty();
    }

    String userId() {
        return preferences.getString(USER_ID, "");
    }

    void signIn(String email, String password, Callback<AuthResult> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("email", email.trim())
                        .put("password", password);
                Response response = request("POST", "/auth/v1/token?grant_type=password", null, payload);
                if (!response.ok()) {
                    callback.onError(authError(response));
                    return;
                }
                JSONObject data = new JSONObject(response.body);
                saveSession(data);
                callback.onSuccess(new AuthResult(true, "Giriş başarılı."));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void signUp(String email, String password, String username, String displayName,
                Callback<AuthResult> callback) {
        executor.execute(() -> {
            try {
                JSONObject metadata = new JSONObject()
                        .put("username", username.trim().toLowerCase())
                        .put("display_name", displayName.trim());
                JSONObject payload = new JSONObject()
                        .put("email", email.trim())
                        .put("password", password)
                        .put("data", metadata);
                Response response = request("POST", "/auth/v1/signup", null, payload);
                if (!response.ok()) {
                    callback.onError(authError(response));
                    return;
                }
                JSONObject data = new JSONObject(response.body);
                if (data.optString("access_token").isEmpty()) {
                    callback.onSuccess(new AuthResult(false,
                            "Kayıt oluşturuldu. E-postanıza gelen doğrulama bağlantısını açıp giriş yapın."));
                } else {
                    saveSession(data);
                    callback.onSuccess(new AuthResult(true, "Hesabınız hazır."));
                }
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
                            item.optString("conversation_id"),
                            item.optString("other_user_id"),
                            item.optString("username"),
                            item.optString("display_name"),
                            item.optString("last_message"),
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
                    people.add(new Person(
                            item.optString("user_id"),
                            item.optString("username"),
                            item.optString("display_name")
                    ));
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
                    messages.add(new Message(
                            item.optLong("message_id"),
                            item.optString("sender_id"),
                            item.optString("message_body"),
                            item.optString("created_at")
                    ));
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

    void signOut() {
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
        connection.setRequestProperty("apikey", anonKey);
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
            preferences.edit().clear().apply();
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

    private static String authError(Response response) {
        try {
            JSONObject data = new JSONObject(response.body);
            String message = data.optString("msg", data.optString("message", data.optString("error_description")));
            if (message.toLowerCase().contains("invalid login")) return "E-posta veya şifre yanlış.";
            if (message.toLowerCase().contains("already registered")) return "Bu e-posta zaten kayıtlı.";
            if (message.toLowerCase().contains("password")) return "Şifre en az 6 karakter olmalı.";
            return message.isEmpty() ? "Giriş işlemi tamamlanamadı." : message;
        } catch (Exception ignored) {
            return "Giriş işlemi tamamlanamadı.";
        }
    }

    private static String apiError(Response response) {
        try {
            JSONObject data = new JSONObject(response.body);
            String message = data.optString("message", data.optString("msg", data.optString("error")));
            if (response.code == 401) return "Oturum süreniz doldu. Tekrar giriş yapın.";
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
