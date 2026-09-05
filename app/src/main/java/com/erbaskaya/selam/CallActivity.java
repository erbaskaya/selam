package com.erbaskaya.selam;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CallActivity extends Activity {
    static final String EXTRA_CALL_ID = "call_id";
    static final String EXTRA_CHAT_ID = "chat_id";
    static final String EXTRA_NAME = "call_name";
    static final String EXTRA_INCOMING = "incoming";
    private static final int REQUEST_AUDIO = 701;
    private static final int BLUE = Color.rgb(25, 105, 230);
    private static final int NAVY = Color.rgb(7, 17, 31);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollState = this::pollCallState;
    private final Runnable pollIce = this::pollIceCandidates;
    private final List<IceCandidate> pendingLocalIce = new ArrayList<>();
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<>();
    private SupabaseClient api;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioManager audioManager;
    private TextView statusView;
    private Button acceptButton;
    private Button declineButton;
    private Button muteButton;
    private Button speakerButton;
    private volatile String callId;
    private String chatId;
    private String contactName;
    private boolean incoming;
    private boolean accepted;
    private volatile boolean remoteDescriptionSet;
    private volatile boolean finished;
    private boolean muted;
    private boolean speaker;
    private volatile boolean localOfferReady;
    private volatile boolean localAnswerReady;
    private boolean offerPublished;
    private boolean answerPublished;
    private long lastIceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        api = new SupabaseClient(this);
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        contactName = getIntent().getStringExtra(EXTRA_NAME);
        incoming = getIntent().getBooleanExtra(EXTRA_INCOMING, false);
        if (contactName == null || contactName.trim().isEmpty()) contactName = "Selam kullanıcısı";
        buildUi();
        if (!incoming) ensureAudioThenStart();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(NAVY);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(46), dp(24), dp(34));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView badge = text("🤝", 54, Color.WHITE, false);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(Color.rgb(25, 105, 230), 52));
        page.addView(badge, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView name = text(contactName, 28, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        page.addView(name, margins(-1, -2, 0, 24, 0, 8));
        statusView = text(incoming ? "Gelen Selam internet araması" : "Aranıyor…",
                16, Color.rgb(184, 203, 230), false);
        statusView.setGravity(Gravity.CENTER);
        page.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        muteButton = control("🎙\nMikrofon");
        muteButton.setOnClickListener(v -> toggleMute());
        speakerButton = control("🔊\nHoparlör");
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        controls.addView(muteButton, new LinearLayout.LayoutParams(0, dp(82), 1));
        controls.addView(speakerButton, new LinearLayout.LayoutParams(0, dp(82), 1));
        page.addView(controls, new LinearLayout.LayoutParams(-1, dp(82)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (incoming) {
            declineButton = action("Reddet", Color.rgb(218, 55, 67));
            acceptButton = action("Yanıtla", Color.rgb(31, 174, 92));
            declineButton.setOnClickListener(v -> decline());
            acceptButton.setOnClickListener(v -> ensureAudioThenStart());
            actions.addView(declineButton, margins(dp(132), dp(58), 0, 0, 12, 0));
            actions.addView(acceptButton, margins(dp(132), dp(58), 12, 0, 0, 0));
        } else {
            Button hangup = action("Kapat", Color.rgb(218, 55, 67));
            hangup.setOnClickListener(v -> hangUp());
            actions.addView(hangup, new LinearLayout.LayoutParams(dp(150), dp(58)));
        }
        page.addView(actions, margins(-1, dp(74), 0, 20, 0, 0));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void ensureAudioThenStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (incoming) acceptIncoming(); else startOutgoing();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (incoming) acceptIncoming(); else startOutgoing();
        } else {
            Toast.makeText(this, "İnternet araması için mikrofon izni gereklidir.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void initializePeer() {
        if (peerConnection != null) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(getApplicationContext()).createInitializationOptions());
        JavaAudioDeviceModule audioModule = JavaAudioDeviceModule.builder(getApplicationContext())
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory();
        audioModule.release();
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(servers);
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(configuration, new PeerObserver());
        if (peerConnection == null) {
            fail("Telefon bu internet aramasını başlatamadı.");
            return;
        }
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("selam_audio", audioSource);
        peerConnection.addTrack(localAudioTrack, Collections.singletonList("selam_stream"));
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(false);
    }

    private void startOutgoing() {
        initializePeer();
        if (peerConnection == null || chatId == null) return;
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerConnection.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription offer) {
                peerConnection.setLocalDescription(new SdpAdapter() {
                    @Override public void onSetSuccess() {
                        localOfferReady = true;
                        status("Bağlantı hazırlanıyor…");
                        // ICE adaylarını SDP'ye de ekleyebilmek için kısa süre topluyoruz.
                        // COMPLETE olayı gelmezse bu zamanlayıcı aramayı yine başlatır.
                        handler.postDelayed(() -> publishOutgoingOffer(true), 3_500L);
                    }
                }, offer);
            }
        }, constraints);
    }

    private synchronized void publishOutgoingOffer(boolean force) {
        if (finished || offerPublished || !localOfferReady || peerConnection == null) return;
        if (!force && peerConnection.iceGatheringState()
                != PeerConnection.IceGatheringState.COMPLETE) return;
        SessionDescription local = peerConnection.getLocalDescription();
        if (local == null || local.description == null || local.description.isEmpty()) return;
        offerPublished = true;
        api.startAudioCall(chatId, local.description, new SupabaseClient.Callback<String>() {
            @Override public void onSuccess(String id) {
                callId = id;
                flushLocalIce();
                schedulePolling();
                status("Aranıyor…");
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> fail(message));
            }
        });
    }

    private void acceptIncoming() {
        if (accepted || callId == null) return;
        accepted = true;
        if (acceptButton != null) acceptButton.setEnabled(false);
        if (declineButton != null) declineButton.setEnabled(false);
        status("Bağlantı kuruluyor…");
        initializePeer();
        api.getCallState(callId, new SupabaseClient.Callback<SupabaseClient.CallState>() {
            @Override public void onSuccess(SupabaseClient.CallState state) {
                if (!"ringing".equals(state.state)) {
                    runOnUiThread(() -> fail("Arama artık aktif değil."));
                    return;
                }
                setRemote(new SessionDescription(SessionDescription.Type.OFFER, state.offerSdp), () -> {
                    MediaConstraints constraints = new MediaConstraints();
                    constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                    peerConnection.createAnswer(new SdpAdapter() {
                        @Override public void onCreateSuccess(SessionDescription answer) {
                            peerConnection.setLocalDescription(new SdpAdapter() {
                                @Override public void onSetSuccess() {
                                    localAnswerReady = true;
                                    handler.postDelayed(() -> publishIncomingAnswer(true), 3_500L);
                                }
                            }, answer);
                        }
                    }, constraints);
                });
            }
            @Override public void onError(String message) { runOnUiThread(() -> fail(message)); }
        });
    }

    private synchronized void publishIncomingAnswer(boolean force) {
        if (finished || answerPublished || !localAnswerReady || peerConnection == null) return;
        if (!force && peerConnection.iceGatheringState()
                != PeerConnection.IceGatheringState.COMPLETE) return;
        SessionDescription local = peerConnection.getLocalDescription();
        if (local == null || local.description == null || local.description.isEmpty()) return;
        answerPublished = true;
        api.answerAudioCall(callId, local.description, new SupabaseClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean value) {
                flushLocalIce();
                schedulePolling();
                status("Bağlanıyor…");
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> fail(message));
            }
        });
    }

    private void setRemote(SessionDescription description, Runnable after) {
        peerConnection.setRemoteDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                List<IceCandidate> queued;
                synchronized (pendingRemoteIce) {
                    queued = new ArrayList<>(pendingRemoteIce);
                    pendingRemoteIce.clear();
                }
                for (IceCandidate candidate : queued) peerConnection.addIceCandidate(candidate);
                after.run();
            }
        }, description);
    }

    private void schedulePolling() {
        handler.removeCallbacks(pollState);
        handler.removeCallbacks(pollIce);
        handler.post(pollState);
        handler.post(pollIce);
    }

    private void pollCallState() {
        if (finished || callId == null) return;
        api.getCallState(callId, new SupabaseClient.Callback<SupabaseClient.CallState>() {
            @Override public void onSuccess(SupabaseClient.CallState state) {
                if (!incoming && "accepted".equals(state.state) && !remoteDescriptionSet
                        && state.answerSdp != null && !state.answerSdp.isEmpty()) {
                    setRemote(new SessionDescription(SessionDescription.Type.ANSWER, state.answerSdp),
                            () -> runOnUiThread(() -> status("Bağlanıyor…")));
                } else if ("declined".equals(state.state)) {
                    runOnUiThread(() -> fail("Arama reddedildi."));
                    return;
                } else if ("ended".equals(state.state) || "missed".equals(state.state)) {
                    runOnUiThread(() -> fail("Arama sona erdi."));
                    return;
                }
                handler.postDelayed(pollState, 1_000L);
            }
            @Override public void onError(String message) { handler.postDelayed(pollState, 2_000L); }
        });
    }

    private void pollIceCandidates() {
        if (finished || callId == null) return;
        api.listIceCandidates(callId, lastIceId,
                new SupabaseClient.Callback<List<SupabaseClient.IceCandidateData>>() {
                    @Override public void onSuccess(List<SupabaseClient.IceCandidateData> items) {
                        for (SupabaseClient.IceCandidateData item : items) {
                            lastIceId = Math.max(lastIceId, item.id);
                            IceCandidate candidate = new IceCandidate(item.sdpMid,
                                    item.sdpMLineIndex, item.candidate);
                            if (remoteDescriptionSet) {
                                peerConnection.addIceCandidate(candidate);
                            } else {
                                synchronized (pendingRemoteIce) {
                                    if (remoteDescriptionSet) peerConnection.addIceCandidate(candidate);
                                    else pendingRemoteIce.add(candidate);
                                }
                            }
                        }
                        handler.postDelayed(pollIce, 900L);
                    }
                    @Override public void onError(String message) {
                        handler.postDelayed(pollIce, 1_800L);
                    }
                });
    }

    private void sendLocalIce(IceCandidate candidate) {
        if (callId == null) {
            synchronized (pendingLocalIce) {
                pendingLocalIce.add(candidate);
            }
            return;
        }
        api.addIceCandidate(callId, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex,
                new SupabaseClient.Callback<Boolean>() {
                    @Override public void onSuccess(Boolean value) { }
                    @Override public void onError(String message) {
                        synchronized (pendingLocalIce) {
                            pendingLocalIce.add(candidate);
                        }
                        handler.postDelayed(CallActivity.this::flushLocalIce, 1_500L);
                    }
                });
    }

    private void flushLocalIce() {
        if (finished || callId == null) return;
        List<IceCandidate> copy;
        synchronized (pendingLocalIce) {
            copy = new ArrayList<>(pendingLocalIce);
            pendingLocalIce.clear();
        }
        for (IceCandidate candidate : copy) sendLocalIce(candidate);
    }

    private void decline() {
        finished = true;
        if (callId != null) api.declineAudioCall(callId, emptyCallback());
        finish();
    }

    private void hangUp() {
        finished = true;
        if (callId != null) api.endAudioCall(callId, emptyCallback());
        finish();
    }

    private SupabaseClient.Callback<Boolean> emptyCallback() {
        return new SupabaseClient.Callback<Boolean>() {
            @Override public void onSuccess(Boolean value) { }
            @Override public void onError(String message) { }
        };
    }

    private void toggleMute() {
        muted = !muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
        muteButton.setText(muted ? "🔇\nSessiz" : "🎙\nMikrofon");
    }

    private void toggleSpeaker() {
        speaker = !speaker;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speaker);
        speakerButton.setText(speaker ? "🔊\nHoparlör açık" : "🔈\nHoparlör");
    }

    private void status(String value) { runOnUiThread(() -> statusView.setText(value)); }

    private void fail(String message) {
        if (isFinishing()) return;
        status(message);
        handler.postDelayed(() -> {
            finished = true;
            finish();
        }, 1_800L);
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this).setTitle("Arama kapatılsın mı?")
                .setNegativeButton("Devam et", null)
                .setPositiveButton("Kapat", (dialog, which) -> hangUp()).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (!finished && callId != null) api.endAudioCall(callId, emptyCallback());
        if (peerConnection != null) peerConnection.dispose();
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (audioSource != null) audioSource.dispose();
        if (factory != null) factory.dispose();
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        api.close();
        super.onDestroy();
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (state == PeerConnection.IceConnectionState.CONNECTED
                    || state == PeerConnection.IceConnectionState.COMPLETED) status("Bağlandı");
            else if (state == PeerConnection.IceConnectionState.FAILED) fail("Bağlantı kurulamadı.");
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                if (incoming) publishIncomingAnswer(false);
                else publishOutgoingOffer(false);
            }
        }
        @Override public void onIceCandidate(IceCandidate candidate) { sendLocalIce(candidate); }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel dataChannel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) { }
    }

    private class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String message) { runOnUiThread(() -> fail(message)); }
        @Override public void onSetFailure(String message) { runOnUiThread(() -> fail(message)); }
    }

    private Button control(String value) {
        Button button = action(value, Color.rgb(28, 43, 64));
        button.setTextSize(14);
        return button;
    }

    private Button action(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(round(color, 22));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int width, int height,
                                               int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
