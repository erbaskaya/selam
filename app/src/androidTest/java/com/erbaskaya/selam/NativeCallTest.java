package com.erbaskaya.selam;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Two real native WebRTC peers, isolated inside the emulator. No Selam account or API is used. */
public class NativeCallTest {
    private android.app.Instrumentation getInstrumentation(){return InstrumentationRegistry.getInstrumentation();}
    @Test public void testTwoNativeAudioPeersConnect() throws Exception {
        android.content.Context context=getInstrumentation().getTargetContext();
        getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE));
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);
        JavaAudioDeviceModule module=JavaAudioDeviceModule.builder(context).createAudioDeviceModule();
        PeerConnectionFactory factory=PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory();module.release();
        PeerConnection a=null,b=null;AudioSource source=null;AudioTrack trackA=null,trackB=null;
        Observer obsA=new Observer(),obsB=new Observer();
        try {
            PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.emptyList());config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
            a=factory.createPeerConnection(config,obsA);b=factory.createPeerConnection(config,obsB);assertNotNull(a);assertNotNull(b);
            source=factory.createAudioSource(new MediaConstraints());trackA=factory.createAudioTrack("a",source);trackB=factory.createAudioTrack("b",source);
            a.addTrack(trackA,Collections.singletonList("a-stream"));b.addTrack(trackB,Collections.singletonList("b-stream"));
            set(a,true,create(a,true));set(b,false,a.getLocalDescription());
            set(b,true,create(b,false));set(a,false,b.getLocalDescription());
            // Android's network callbacks can arrive AFTER the first empty gathering cycle.
            // Relay every later candidate, exactly as the app's continual/trickle ICE path does.
            int sentA=0,sentB=0;long deadline=android.os.SystemClock.elapsedRealtime()+20000;
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                while(sentA<obsA.candidates.size())assertTrue(b.addIceCandidate(obsA.candidates.get(sentA++)));
                while(sentB<obsB.candidates.size())assertTrue(a.addIceCandidate(obsB.candidates.get(sentB++)));
                if(obsA.connected.getCount()==0&&obsB.connected.getCount()==0)break;
                Thread.sleep(50);
            }
            assertFalse("Caller has ICE candidates",obsA.candidates.isEmpty());
            assertFalse("Callee has ICE candidates",obsB.candidates.isEmpty());
            assertEquals("Caller connects",0,obsA.connected.getCount());
            assertEquals("Callee connects",0,obsB.connected.getCount());
            assertTrue(a.getLocalDescription().description.contains("m=audio"));
        } finally {if(a!=null)a.dispose();if(b!=null)b.dispose();if(trackA!=null)trackA.dispose();if(trackB!=null)trackB.dispose();if(source!=null)source.dispose();factory.dispose();}
    }
    private SessionDescription create(PeerConnection peer,boolean offer) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<SessionDescription> value=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        SdpObserver observer=new Sdp(){public void onCreateSuccess(SessionDescription s){value.set(s);done.countDown();}public void onCreateFailure(String s){error.set(s);done.countDown();}};
        if(offer)peer.createOffer(observer,new MediaConstraints());else peer.createAnswer(observer,new MediaConstraints());
        assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(value.get());return value.get();
    }
    private void set(PeerConnection peer,boolean local,SessionDescription value) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        SdpObserver observer=new Sdp(){public void onSetSuccess(){done.countDown();}public void onSetFailure(String s){error.set(s);done.countDown();}};
        if(local)peer.setLocalDescription(observer,value);else peer.setRemoteDescription(observer,value);
        assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error.get());
    }
    static class Sdp implements SdpObserver {public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}public void onCreateFailure(String s){}public void onSetFailure(String s){}}
    static class Observer implements PeerConnection.Observer {
        final CountDownLatch gathered=new CountDownLatch(1),connected=new CountDownLatch(1);
        final List<IceCandidate> candidates=new CopyOnWriteArrayList<>();
        public void onSignalingChange(PeerConnection.SignalingState s){}
        public void onIceConnectionChange(PeerConnection.IceConnectionState s){android.util.Log.i("SelamNativeTest","ICE state "+s);if(s==PeerConnection.IceConnectionState.CONNECTED||s==PeerConnection.IceConnectionState.COMPLETED)connected.countDown();}
        public void onIceConnectionReceivingChange(boolean b){}
        public void onIceGatheringChange(PeerConnection.IceGatheringState s){if(s==PeerConnection.IceGatheringState.COMPLETE)gathered.countDown();}
        public void onIceCandidate(IceCandidate c){candidates.add(c);android.util.Log.i("SelamNativeTest","ICE "+c.sdp);}public void onIceCandidatesRemoved(IceCandidate[] c){}
        public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel d){}
        public void onRenegotiationNeeded(){}public void onAddTrack(RtpReceiver r,MediaStream[] s){}
    }
}
