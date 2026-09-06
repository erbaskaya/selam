package com.erbaskaya.selam;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Tests real Application startup and SDK wiring; no live device push is sent. */
@RunWith(AndroidJUnit4.class)
public class FirebaseStartupTest {
    @Test public void applicationStartupProvidesTheConfiguredMessagingClient() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getApplicationContext() instanceof SelamApplication);
        FirebaseApp app = FirebaseApp.getInstance();
        assertEquals("selam-507819", app.getOptions().getProjectId());
        assertEquals("720165272753", app.getOptions().getGcmSenderId());
        assertEquals("com.erbaskaya.selam", context.getPackageName());
        assertNotNull(FirebaseMessaging.getInstance());
    }
}
