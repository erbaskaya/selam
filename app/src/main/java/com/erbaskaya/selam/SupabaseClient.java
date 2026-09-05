package com.erbaskaya.selam;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class SupabaseClient {
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

    static final class RecoveryResult {
        final boolean success;
        final String message;
        final Profile profile;

        RecoveryResult(boolean success, String message, Profile profile) {
            this.success = success;
            this.message = message;
            this.profile = profile;
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
        boolean favorite;
        long unreadCount;
        String otherUserId;
        final String id;
        final String kind;
        final String username;
        final String displayName;
        final String lastMessage;
        final String lastMessageAt;
        final boolean archived;
        final boolean pinned;
        final String mutedUntil;

        Chat(String id, String kind, String username, String displayName,
             String lastMessage, String lastMessageAt, boolean archived,
             boolean pinned, String mutedUntil) {
            this.id = id;
            this.kind = kind;
            this.username = username;
            this.displayName = displayName;
            this.lastMessage = lastMessage;
            this.lastMessageAt = lastMessageAt;
            this.archived = archived;
            this.pinned = pinned;
            this.mutedUntil = mutedUntil;
        }
    }

    static final class Settings {
        final String displayName;
        final String about;
        final boolean readReceipts;
        final boolean showLastSeen;
        final boolean notifications;
        final boolean callNotifications;
        final boolean compactMode;

        Settings(String displayName, String about, boolean readReceipts,
                 boolean showLastSeen, boolean notifications,
                 boolean callNotifications, boolean compactMode) {
            this.displayName = displayName;
            this.about = about;
            this.readReceipts = readReceipts;
            this.showLastSeen = showLastSeen;
            this.notifications = notifications;
            this.callNotifications = callNotifications;
            this.compactMode = compactMode;
        }
    }

    static final class StatusUpdate {
        final long id;
        final String userId;
        final String displayName;
        final String body;
        final String color;
        final String createdAt;
        final boolean mine;

        StatusUpdate(long id, String userId, String displayName, String body,
                     String color, String createdAt, boolean mine) {
            this.id = id;
            this.userId = userId;
            this.displayName = displayName;
            this.body = body;
            this.color = color;
            this.createdAt = createdAt;
            this.mine = mine;
        }
    }

    static final class Community {
        final String id;
        final String name;
        final String description;
        final long memberCount;
        final String role;

        Community(String id, String name, String description, long memberCount, String role) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.memberCount = memberCount;
            this.role = role;
        }
    }

    static final class Message {
        String senderName = "";
        String replyPreview = "";
        String reactions = "";
        String editedAt = "";
        boolean deleted;
        boolean starred;
        boolean readByOther;
        long replyToId;
        String fingerprint = "";
        final long id;
        final String senderId;
        final String body;
        final String createdAt;
        final String type;
        final String filePath;
        final String fileName;
        final String fileMimeType;
        final long fileSize;

        Message(long id, String senderId, String body, String createdAt,
                String type, String filePath, String fileName,
                String fileMimeType, long fileSize) {
            this.id = id;
            this.senderId = senderId;
            this.body = body;
            this.createdAt = createdAt;
            this.type = type;
            this.filePath = filePath;
            this.fileName = fileName;
            this.fileMimeType = fileMimeType;
            this.fileSize = fileSize;
        }

        boolean isFile() { return !deleted && "file".equals(type); }
        boolean isAudio() { return isFile() && fileMimeType != null && fileMimeType.startsWith("audio/"); }
        static Message parse(JSONObject item) {
            Message m = new Message(item.optLong("message_id"), item.optString("sender_id"),
                item.optString("message_body"), item.optString("created_at"),item.optString("message_type","text"),
                item.optString("file_path"),item.optString("file_name"),item.optString("file_mime_type"),item.optLong("file_size_bytes"));
            m.senderName=item.optString("sender_name","");
            m.replyPreview=item.isNull("reply_preview")?"":item.optString("reply_preview","");
            m.replyToId=item.optLong("reply_to_id");m.reactions=item.optString("reactions","");
            m.editedAt=item.isNull("edited_at")?"":item.optString("edited_at","");
            m.deleted=!item.isNull("deleted_at")&&item.has("deleted_at");
            m.starred=item.optBoolean("starred");m.readByOther=item.optBoolean("read_by_other");
            m.fingerprint=item.toString();return m;
        }
    }

    static final class MessageNotification {
        final long id;
        final String conversationId;
        final String senderName;
        final String preview;

        MessageNotification(long id, String conversationId, String senderName, String preview) {
            this.id = id;
            this.conversationId = conversationId;
            this.senderName = senderName;
            this.preview = preview;
        }
    }

    static final class IncomingCall {
        final String id;
        final String conversationId;
        final String callerName;

        IncomingCall(String id, String conversationId, String callerName) {
            this.id = id;
            this.conversationId = conversationId;
            this.callerName = callerName;
        }
    }

    static final class CallState {
        final String id;
        final String callerId;
        final String calleeId;
        final String state;
        final String offerSdp;
        final String answerSdp;

        CallState(String id, String callerId, String calleeId, String state,
                  String offerSdp, String answerSdp) {
            this.id = id;
            this.callerId = callerId;
            this.calleeId = calleeId;
            this.state = state;
            this.offerSdp = offerSdp;
            this.answerSdp = answerSdp;
        }
    }

    static final class CallLog {
        final String id;
        final String conversationId;
        final String otherName;
        final String state;
        final String startedAt;
        final boolean outgoing;

        CallLog(String id, String conversationId, String otherName, String state,
                String startedAt, boolean outgoing) {
            this.id = id;
            this.conversationId = conversationId;
            this.otherName = otherName;
            this.state = state;
            this.startedAt = startedAt;
            this.outgoing = outgoing;
        }
    }

    static final class IceCandidateData {
        final long id;
        final String candidate;
        final String sdpMid;
        final int sdpMLineIndex;

        IceCandidateData(long id, String candidate, String sdpMid, int sdpMLineIndex) {
            this.id = id;
            this.candidate = candidate;
            this.sdpMid = sdpMid;
            this.sdpMLineIndex = sdpMLineIndex;
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

    /** One entry point for first installation and returning accounts. */
    void enterProfile(String displayName, String phoneE164, String recoveryPin,
                      Callback<Profile> callback) {
        setupProfile(displayName, phoneE164, recoveryPin, new Callback<Profile>() {
            @Override public void onSuccess(Profile profile) {
                callback.onSuccess(profile);
            }

            @Override public void onError(String message) {
                // Only the server's explicit number collision can start recovery.
                // Network failures and validation errors must never become login attempts.
                if (!"Bu numara kayıtlı. Hesabımı kurtar seçeneğini kullanın".equals(message)) {
                    callback.onError(message);
                    return;
                }
                recoverProfile(phoneE164, recoveryPin, new Callback<RecoveryResult>() {
                    @Override public void onSuccess(RecoveryResult result) {
                        if (result.success && result.profile != null && result.profile.ready) {
                            callback.onSuccess(result.profile);
                        } else {
                            callback.onError(result.message == null || result.message.isEmpty()
                                    ? "Hesap açılamadı. Numaranızı ve PIN'inizi kontrol edin."
                                    : result.message);
                        }
                    }

                    @Override public void onError(String error) {
                        callback.onError(error);
                    }
                });
            }
        });
    }

    void setupProfile(String displayName, String phoneE164, String recoveryPin,
                      Callback<Profile> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("new_display_name", displayName.trim())
                        .put("phone_e164", phoneE164)
                        .put("recovery_pin", recoveryPin);
                Response response = authorizedRequest(
                        "POST", "/rest/v1/rpc/setup_profile_with_pin", payload);
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

    void recoverProfile(String phoneE164, String recoveryPin,
                        Callback<RecoveryResult> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("phone_e164", phoneE164)
                        .put("recovery_pin", recoveryPin);
                Response response = authorizedRequest(
                        "POST", "/rest/v1/rpc/recover_profile", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("Kurtarma sonucu alınamadı.");
                JSONObject item = array.getJSONObject(0);
                boolean success = item.optBoolean("success");
                Profile profile = success ? new Profile(
                        item.optString("username"), item.optString("display_name"),
                        item.optString("phone_last4"), item.optString("safety_code"), true
                ) : null;
                callback.onSuccess(new RecoveryResult(
                        success, item.optString("result_message"), profile));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void setRecoveryPin(String recoveryPin, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest(
                        "POST", "/rest/v1/rpc/set_recovery_pin",
                        new JSONObject().put("new_pin", recoveryPin));
                ensureSuccess(response);
                callback.onSuccess(true);
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
                Response response = authorizedRequest("POST", "/rest/v1/rpc/selam_chats", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<Chat> chats = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    chats.add(new Chat(
                            item.optString("conversation_id"), item.optString("conversation_kind"),
                            item.optString("username"),
                            item.optString("display_name"), item.optString("last_message"),
                            item.optString("last_message_at"), item.optBoolean("archived"),
                            item.optBoolean("pinned"), item.isNull("muted_until")
                                    ? "" : item.optString("muted_until")
                    ));
                    Chat added=chats.get(chats.size()-1);
                    added.favorite=item.optBoolean("favorite");added.unreadCount=item.optLong("unread_count");
                    added.otherUserId=item.optString("other_user_id","");
                }
                callback.onSuccess(chats);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void getSettings(Callback<Settings> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/get_my_settings", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("Ayarlar bulunamadı.");
                JSONObject item = array.getJSONObject(0);
                callback.onSuccess(new Settings(
                        item.optString("display_name"), item.optString("about"),
                        item.optBoolean("read_receipts", true),
                        item.optBoolean("show_last_seen", true),
                        item.optBoolean("notifications_enabled", true),
                        item.optBoolean("call_notifications_enabled", true),
                        item.optBoolean("compact_mode", false)
                ));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void updateSettings(Settings settings, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("new_display_name", settings.displayName)
                        .put("new_about", settings.about)
                        .put("new_read_receipts", settings.readReceipts)
                        .put("new_show_last_seen", settings.showLastSeen)
                        .put("new_notifications_enabled", settings.notifications)
                        .put("new_call_notifications_enabled", settings.callNotifications)
                        .put("new_compact_mode", settings.compactMode);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/update_my_settings", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void createStatus(String body, String color, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("status_body", body.trim()).put("status_color", color);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/create_status", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listStatuses(Callback<List<StatusUpdate>> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/list_visible_statuses", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<StatusUpdate> statuses = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    statuses.add(new StatusUpdate(item.optLong("status_id"),
                            item.optString("user_id"), item.optString("display_name"),
                            item.optString("status_body"), item.optString("background_color"),
                            item.optString("created_at"), item.optBoolean("is_mine")));
                }
                callback.onSuccess(statuses);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void deleteStatus(long statusId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/delete_my_status",
                        new JSONObject().put("status_id", statusId));
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void createCommunity(String name, String description, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("community_name", name.trim())
                        .put("community_description", description.trim());
                Response response = authorizedRequest("POST", "/rest/v1/rpc/create_community", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listCommunities(Callback<List<Community>> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST", "/rest/v1/rpc/list_my_communities", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<Community> communities = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    communities.add(new Community(item.optString("community_id"),
                            item.optString("community_name"), item.optString("community_description"),
                            item.optLong("member_count"), item.optString("my_role")));
                }
                callback.onSuccess(communities);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void setChatState(String chatId, Boolean archived, Boolean pinned,
                      Integer muteHours, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("chat_id", chatId);
                if (archived != null) payload.put("new_archived", archived); else payload.put("new_archived", JSONObject.NULL);
                if (pinned != null) payload.put("new_pinned", pinned); else payload.put("new_pinned", JSONObject.NULL);
                if (muteHours != null) payload.put("mute_hours", muteHours); else payload.put("mute_hours", JSONObject.NULL);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/set_chat_state", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
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
                String result = parseTextResult(response.body);
                if (result.isEmpty() || "null".equals(result)) throw new IOException("Sohbet oluşturulamadı.");
                callback.onSuccess(result);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void createGroup(String groupName, List<String> memberIds, Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONArray members = new JSONArray();
                for (String id : memberIds) members.put(id);
                JSONObject payload = new JSONObject()
                        .put("group_name", groupName.trim())
                        .put("member_user_ids", members);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/create_group", payload);
                ensureSuccess(response);
                String result = parseTextResult(response.body);
                if (result.isEmpty() || "null".equals(result)) throw new IOException("Grup oluşturulamadı.");
                callback.onSuccess(result);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void deleteChat(String conversationId, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("chat_id", conversationId);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/delete_chat", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listMessages(String conversationId, Callback<List<Message>> callback) {
        messages(conversationId,"",false,null,callback);
    }

    static JSONObject json(Object... pairs) {
        JSONObject result=new JSONObject();
        try {for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);}
        catch(JSONException e){throw new IllegalArgumentException(e);}
        return result;
    }
    void rpc(String name,JSONObject payload,Callback<String> callback) {
        executor.execute(()->{
            try {Response response=authorizedRequest("POST","/rest/v1/rpc/"+name,payload);
                ensureSuccess(response);callback.onSuccess(response.body);
            }catch(Exception e){callback.onError(friendly(e));}
        });
    }
    void messages(String chatId,String query,boolean starred,Long before,Callback<List<Message>> callback) {
        rpc("selam_messages",json("p_chat_id",chatId,"p_query",query,"p_starred",starred,"p_before",before),new Callback<String>(){
            public void onSuccess(String body){try{JSONArray array=new JSONArray(body);List<Message> messages=new ArrayList<>();
                for(int i=0;i<array.length();i++)messages.add(Message.parse(array.getJSONObject(i)));callback.onSuccess(messages);
            }catch(Exception e){callback.onError(friendly(e));}}
            public void onError(String m){callback.onError(m);}
        });
    }

    void listMessageNotifications(long afterMessageId,
                                  Callback<List<MessageNotification>> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("after_message_id", afterMessageId);
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/list_message_notifications", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<MessageNotification> items = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    items.add(new MessageNotification(
                            item.optLong("message_id"),
                            item.optString("conversation_id"),
                            item.optString("sender_name", "Selam"),
                            item.optString("message_preview", "Yeni mesaj")
                    ));
                }
                callback.onSuccess(items);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void startAudioCall(String conversationId, String offerSdp, Callback<String> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("chat_id", conversationId)
                        .put("session_offer", offerSdp);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/start_audio_call", payload);
                ensureSuccess(response);
                String callId = parseTextResult(response.body);
                if (callId.isEmpty() || "null".equals(callId)) throw new IOException("Arama başlatılamadı.");
                callback.onSuccess(callId);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listIncomingCalls(Callback<List<IncomingCall>> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/list_incoming_audio_calls", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<IncomingCall> calls = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    calls.add(new IncomingCall(item.optString("call_id"),
                            item.optString("conversation_id"),
                            item.optString("caller_name", "Selam kullanıcısı")));
                }
                callback.onSuccess(calls);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listCallHistory(Callback<List<CallLog>> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/list_audio_call_history", new JSONObject());
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<CallLog> calls = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    calls.add(new CallLog(item.optString("call_id"),
                            item.optString("conversation_id"),
                            item.optString("other_name", "Selam kullanıcısı"),
                            item.optString("call_state"), item.optString("started_at"),
                            item.optBoolean("is_outgoing")));
                }
                callback.onSuccess(calls);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void hideCallHistoryEntry(String callId, Callback<Boolean> callback) {
        simpleCallAction("hide_audio_call_history_entry", callId, null, callback);
    }

    void clearCallHistory(Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/clear_audio_call_history", new JSONObject());
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void getCallState(String callId, Callback<CallState> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("selected_call_id", callId);
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/get_audio_call_state", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                if (array.length() == 0) throw new IOException("Arama bulunamadı.");
                JSONObject item = array.getJSONObject(0);
                callback.onSuccess(new CallState(item.optString("call_id"),
                        item.optString("caller_id"), item.optString("callee_id"),
                        item.optString("call_state"), item.optString("offer_sdp"),
                        item.optString("answer_sdp")));
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void answerAudioCall(String callId, String answerSdp, Callback<Boolean> callback) {
        simpleCallAction("answer_audio_call", callId, answerSdp, callback);
    }

    void declineAudioCall(String callId, Callback<Boolean> callback) {
        simpleCallAction("decline_audio_call", callId, null, callback);
    }

    void endAudioCall(String callId, Callback<Boolean> callback) {
        simpleCallAction("end_audio_call", callId, null, callback);
    }

    void addIceCandidate(String callId, String candidate, String sdpMid,
                         int sdpMLineIndex, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("selected_call_id", callId)
                        .put("ice_candidate", candidate)
                        .put("candidate_sdp_mid", sdpMid == null ? JSONObject.NULL : sdpMid)
                        .put("candidate_sdp_mline_index", sdpMLineIndex);
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/add_audio_ice_candidate", payload);
                ensureSuccess(response);
                callback.onSuccess(true);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    void listIceCandidates(String callId, long afterId,
                           Callback<List<IceCandidateData>> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("selected_call_id", callId)
                        .put("after_candidate_id", afterId);
                Response response = authorizedRequest("POST",
                        "/rest/v1/rpc/list_audio_ice_candidates", payload);
                ensureSuccess(response);
                JSONArray array = new JSONArray(response.body);
                List<IceCandidateData> candidates = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    candidates.add(new IceCandidateData(item.optLong("candidate_id"),
                            item.optString("ice_candidate"),
                            item.optString("candidate_sdp_mid", null),
                            item.optInt("candidate_sdp_mline_index")));
                }
                callback.onSuccess(candidates);
            } catch (Exception exception) {
                callback.onError(friendly(exception));
            }
        });
    }

    private void simpleCallAction(String functionName, String callId, String answerSdp,
                                  Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject().put("selected_call_id", callId);
                if (answerSdp != null) payload.put("session_answer", answerSdp);
                Response response = authorizedRequest("POST", "/rest/v1/rpc/" + functionName, payload);
                ensureSuccess(response);
                callback.onSuccess(true);
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

    void sendFile(String conversationId, InputStream input, String fileName,
                  String mimeType, long declaredSize, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                if (declaredSize > 10L * 1024L * 1024L) {
                    throw new IOException("Dosya en fazla 10 MB olabilir.");
                }
                byte[] bytes;
                try (InputStream source = input) {
                    bytes = readBytesLimited(source, 10 * 1024 * 1024);
                }
                if (bytes.length == 0) throw new IOException("Dosya boş.");

                String safeName = safeFileName(fileName);
                String filePath = conversationId + "/" + userId() + "/"
                        + UUID.randomUUID() + "_" + safeName;
                String encodedPath = encodePath(filePath);
                Response uploaded = authorizedBinaryRequest(
                        "POST", "/storage/v1/object/chat-files/" + encodedPath,
                        mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType,
                        bytes);
                ensureSuccess(uploaded);

                JSONObject payload = new JSONObject()
                        .put("chat_id", conversationId)
                        .put("uploaded_file_path", filePath)
                        .put("uploaded_file_name", safeName)
                        .put("uploaded_mime_type", mimeType == null ? "" : mimeType)
                        .put("uploaded_size_bytes", bytes.length);
                Response message = authorizedRequest("POST", "/rest/v1/rpc/send_chat_file", payload);
                ensureSuccess(message);
                callback.onSuccess(true);
            } catch (Exception exception) {
                try { input.close(); } catch (Exception ignored) { }
                callback.onError(friendly(exception));
            }
        });
    }

    void createSignedFileUrl(String filePath, Callback<String> callback) {
        executor.execute(() -> {
            try {
                Response response = authorizedRequest(
                        "POST", "/storage/v1/object/sign/chat-files/" + encodePath(filePath),
                        new JSONObject().put("expiresIn", 300));
                ensureSuccess(response);
                String signed = new JSONObject(response.body).optString("signedURL");
                if (signed.isEmpty()) throw new IOException("Dosya bağlantısı oluşturulamadı.");
                callback.onSuccess(signed.startsWith("http") ? signed : trimSlash(baseUrl) + signed);
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

    private Response authorizedBinaryRequest(String method, String path, String mimeType, byte[] bytes)
            throws IOException, JSONException {
        Response response = binaryRequest(method, path, accessToken(), mimeType, bytes);
        if (response.code != 401 || refreshToken().isEmpty()) return response;
        JSONObject refreshPayload = new JSONObject().put("refresh_token", refreshToken());
        Response refreshed = request("POST", "/auth/v1/token?grant_type=refresh_token", null, refreshPayload);
        if (!refreshed.ok()) {
            clearSession();
            return response;
        }
        saveSession(new JSONObject(refreshed.body));
        return binaryRequest(method, path, accessToken(), mimeType, bytes);
    }

    private Response binaryRequest(String method, String path, String token,
                                   String mimeType, byte[] bytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(trimSlash(baseUrl) + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("apikey", publishableKey);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", mimeType);
        connection.setRequestProperty("x-upsert", "false");
        connection.setDoOutput(true);
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

    private static String parseTextResult(String body) throws JSONException {
        String result = body == null ? "" : body.trim();
        if (result.startsWith("\"") && result.endsWith("\"")) {
            result = new JSONArray("[" + result + "]").getString(0);
        }
        return result;
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String part : parts) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(Uri.encode(part));
        }
        return encoded.toString();
    }

    private static String safeFileName(String value) {
        String clean = value == null ? "dosya" : value.trim();
        clean = clean.replaceAll("[\\r\\n\\t/\\\\]", "_");
        if (clean.isEmpty()) clean = "dosya";
        return clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }

    private static byte[] readBytesLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("Dosya en fazla 10 MB olabilir.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
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
