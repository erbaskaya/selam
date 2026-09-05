package com.erbaskaya.selam;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AccountEntryTest {
    private static final String PHONE = "+905550000001";
    private static final String PIN = "748259";
    private static final String DUPLICATE = "Bu numara kayıtlı. Hesabımı kurtar seçeneğini kullanın";
    private FakeClient client;
    private Result result;

    @Before public void setUp() {
        client = new FakeClient();
        result = new Result();
    }

    @After public void tearDown() {
        client.close();
    }

    @Test public void newNumberCreatesAccountWithoutRecovery() {
        client.enterProfile("Yeni Kullanıcı", PHONE, PIN, result);
        assertSame(client.created, result.profile);
        assertEquals(1, result.successes);
        assertEquals(0, client.recoveryCalls);
        assertNull(result.error);
    }

    @Test public void existingNumberAndPinOpenOriginalProfileInOneAction() {
        client.setupError = DUPLICATE;
        client.enterProfile("Yeniden yazılan ad", PHONE, PIN, result);
        assertSame(client.existing, result.profile);
        assertEquals("Eski Profil", result.profile.displayName);
        assertEquals("original-safety-code", result.profile.safetyCode);
        assertEquals(PHONE, client.recoveryPhone);
        assertEquals(PIN, client.recoveryPin);
        assertEquals(1, client.setupCalls);
        assertEquals(1, client.recoveryCalls);
        assertEquals(1, result.successes);
        assertNull(result.error);
    }

    @Test public void incorrectPinNeverOpensOrOverwritesExistingAccount() {
        client.setupError = DUPLICATE;
        client.recoveryResult = new SupabaseClient.RecoveryResult(false, "Numara veya PIN hatalı", null);
        client.enterProfile("Yeni ad", PHONE, "825974", result);
        assertEquals("825974", client.recoveryPin);
        assertEquals("Numara veya PIN hatalı", result.error);
        assertEquals(0, result.successes);
        assertNull(result.profile);
        assertEquals(1, client.setupCalls);
        assertEquals(1, client.recoveryCalls);
    }

    @Test public void networkFailureDoesNotAttemptAccountRecovery() {
        client.setupError = "İnternet bağlantısı kurulamadı.";
        client.enterProfile("Yeni Kullanıcı", PHONE, PIN, result);
        assertEquals(client.setupError, result.error);
        assertEquals(0, client.recoveryCalls);
        assertEquals(0, result.successes);
    }

    @Test public void invalidInputDoesNotAttemptAccountRecovery() {
        client.setupError = "Daha zor bir 6 haneli PIN seçin";
        client.enterProfile("Yeni Kullanıcı", PHONE, "111111", result);
        assertEquals(client.setupError, result.error);
        assertEquals(0, client.recoveryCalls);
        assertNull(result.profile);
    }

    @Test public void recoveryRateLimitIsShownWithoutRetry() {
        client.setupError = DUPLICATE;
        client.recoveryError = "Çok fazla deneme. Daha sonra tekrar deneyin.";
        client.enterProfile("Yeni Kullanıcı", PHONE, PIN, result);
        assertEquals(client.recoveryError, result.error);
        assertEquals(1, client.recoveryCalls);
        assertEquals(0, result.successes);
    }

    @Test public void incompleteRecoveryResponseCannotOpenAccount() {
        client.setupError = DUPLICATE;
        client.recoveryResult = new SupabaseClient.RecoveryResult(true, "", null);
        client.enterProfile("Yeni Kullanıcı", PHONE, PIN, result);
        assertNotNull(result.error);
        assertEquals(0, result.successes);
        assertNull(result.profile);
    }

    private static class Result implements SupabaseClient.Callback<SupabaseClient.Profile> {
        SupabaseClient.Profile profile;
        String error;
        int successes;
        @Override public void onSuccess(SupabaseClient.Profile value) { profile = value; successes++; }
        @Override public void onError(String message) { error = message; }
    }

    /** No HTTP or production accounts: only the two existing RPC boundaries are simulated. */
    private static class FakeClient extends SupabaseClient {
        final Profile created = new Profile("new", "Yeni Kullanıcı", "0001", "new-code", true);
        final Profile existing = new Profile("original", "Eski Profil", "0001", "original-safety-code", true);
        String setupError;
        String recoveryError;
        String recoveryPhone;
        String recoveryPin;
        int setupCalls;
        int recoveryCalls;
        RecoveryResult recoveryResult = new RecoveryResult(true, "Hesap açıldı", existing);

        FakeClient() { super(RuntimeEnvironment.getApplication()); }

        @Override void setupProfile(String name, String phone, String pin, Callback<Profile> callback) {
            setupCalls++;
            if (setupError != null) callback.onError(setupError);
            else callback.onSuccess(created);
        }

        @Override void recoverProfile(String phone, String pin, Callback<RecoveryResult> callback) {
            recoveryCalls++;
            recoveryPhone = phone;
            recoveryPin = pin;
            if (recoveryError != null) callback.onError(recoveryError);
            else callback.onSuccess(recoveryResult);
        }
    }
}
