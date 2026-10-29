package me.qnnp.tvlive.control;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TokenStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dir;
    private FakeClock clock;

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("files");
        clock = new FakeClock(1_000_000L);
    }

    private TokenStore store() {
        return new TokenStore(dir, clock, new Random(7L));
    }

    @Test
    public void pinIsSixDigitsAndPersisted() {
        TokenStore store = store();
        String pin = store.pin();
        assertEquals(6, pin.length());
        for (int i = 0; i < pin.length(); i++) {
            assertTrue(Character.isDigit(pin.charAt(i)));
        }
        assertTrue(new File(dir, TokenStore.PIN_FILE).isFile());
        // 重新创建实例读同一个 PIN。
        assertEquals(pin, store().pin());
    }

    @Test
    public void cachedPinAvoidsFileAccessAfterFirstRead() throws Exception {
        TokenStore store = store();
        assertNull("未生成前 UI 不应看到 PIN", store.cachedPin());

        String pin = store.pin();
        assertEquals(pin, store.cachedPin());

        // 文件被改写/删除后仍命中内存缓存：不再读盘，也不重新生成。
        File file = new File(dir, TokenStore.PIN_FILE);
        TestFiles.write(file, "999999");
        assertEquals(pin, store.pin());
        assertEquals(pin, store.cachedPin());

        assertTrue(file.delete());
        assertEquals(pin, store.pin());
        assertFalse("缓存命中不得回写文件", file.exists());
    }

    @Test
    public void corruptPinFileIsRegenerated() throws Exception {
        TestFiles.write(new File(dir, TokenStore.PIN_FILE), "oops");
        String pin = store().pin();
        assertEquals(6, pin.length());
        assertEquals(pin, store().pin());
    }

    @Test
    public void correctPinPassesAndClearsFailures() {
        TokenStore store = store();
        String pin = store.pin();
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("000000"));
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("111111"));
        assertEquals(2, store.failureCount());
        assertEquals(TokenStore.Auth.OK, store.authenticate(pin));
        assertEquals(0, store.failureCount());
        assertFalse(store.isLocked());
    }

    @Test
    public void fiveFailuresInAMinuteThrottleForOneMinute() {
        TokenStore store = store();
        store.pin();
        for (int i = 0; i < TokenStore.MAX_FAILURES; i++) {
            assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("nope" + i));
        }
        assertTrue(store.isLocked());
        assertEquals(TokenStore.Auth.THROTTLED, store.authenticate("nope"));
        assertEquals(TokenStore.Auth.THROTTLED, store.authenticate(store.pin()));
        assertTrue(store.retryAfterSeconds() > 0);
        assertTrue(store.retryAfterSeconds() <= 60);

        clock.advance(61_000L);
        assertFalse(store.isLocked());
        assertEquals(TokenStore.Auth.OK, store.authenticate(store.pin()));
    }

    @Test
    public void failuresOutsideWindowDoNotLock() {
        TokenStore store = store();
        String pin = store.pin();
        for (int i = 0; i < TokenStore.MAX_FAILURES; i++) {
            assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("bad"));
            clock.advance(20_000L);
        }
        assertFalse(store.isLocked());
        assertEquals(TokenStore.Auth.OK, store.authenticate(pin));
    }

    @Test
    public void missingPinCountsAsFailure() {
        TokenStore store = store();
        store.pin();
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate(null));
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("   "));
    }

    @Test
    public void tokenIssuedAndExpires() {
        TokenStore store = store();
        String token = store.issueToken();
        assertNotNull(token);
        assertEquals(32, token.length());
        assertTrue(store.verifyToken(token));
        assertFalse(store.verifyToken("nope"));
        clock.advance(TokenStore.TOKEN_TTL_MS + 1L);
        assertFalse(store.verifyToken(token));
    }

    @Test
    public void currentTokenIsNullUntilIssued() {
        TokenStore store = store();
        assertNull(store.currentToken());
        assertFalse(store.isTokenValid());
        String token = store.issueToken();
        assertEquals(token, store.currentToken());
        assertTrue(store.isTokenValid());
    }

    @Test
    public void currentTokenIsReusedWhileValid() {
        TokenStore store = store();
        String token = store.issueToken();
        // 有效期边界（now == expiresAt）仍然可复用，与 verifyToken 一致。
        clock.advance(TokenStore.TOKEN_TTL_MS);
        assertEquals(token, store.currentToken());
        assertTrue(store.isTokenValid());
        assertEquals(token, store.currentToken());
        assertTrue(store.verifyToken(token));
    }

    @Test
    public void expiredTokenIsClearedAndCanBeReissued() {
        TokenStore store = store();
        String first = store.issueToken();
        clock.advance(TokenStore.TOKEN_TTL_MS + 1L);
        assertNull(store.currentToken());
        assertFalse(store.isTokenValid());
        assertFalse(store.verifyToken(first));

        String second = store.issueToken();
        assertEquals(second, store.currentToken());
        assertTrue(store.isTokenValid());
        assertTrue(store.verifyToken(second));
        assertFalse(store.verifyToken(first));
    }

    @Test
    public void tokenAuthenticatesWithoutPinAndSkipsThrottle() {
        TokenStore store = store();
        String pin = store.pin();
        String token = store.issueToken();
        assertEquals(TokenStore.Auth.OK, store.authenticate(token));
        assertEquals(0, store.failureCount());
        assertTrue(store.hasToken());

        // 别人 PIN 试错把 PIN 锁住后，二维码 token 仍应可用。
        for (int i = 0; i < TokenStore.MAX_FAILURES; i++) {
            assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate("bad" + i));
        }
        assertEquals(TokenStore.Auth.THROTTLED, store.authenticate(pin));
        assertEquals(TokenStore.Auth.OK, store.authenticate(token));
        assertFalse(store.isLocked());
    }

    @Test
    public void expiredTokenFallsBackToPin() {
        TokenStore store = store();
        String pin = store.pin();
        String token = store.issueToken();
        clock.advance(TokenStore.TOKEN_TTL_MS + 1L);
        assertFalse(store.hasToken());
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate(token));
        assertEquals(TokenStore.Auth.OK, store.authenticate(pin));
    }

    @Test
    public void reissuedTokenInvalidatesPrevious() {
        TokenStore store = store();
        String first = store.issueToken();
        String second = store.issueToken();
        assertFalse(store.verifyToken(first));
        assertTrue(store.verifyToken(second));
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate(first));
        assertEquals(TokenStore.Auth.OK, store.authenticate(second));
    }

    @Test
    public void blankCandidateIsNotToken() {
        TokenStore store = store();
        store.issueToken();
        assertFalse(store.verifyToken(null));
        assertFalse(store.verifyToken(" "));
        assertEquals(TokenStore.Auth.BAD_PIN, store.authenticate(null));
    }

    private static final class FakeClock implements TokenStore.Clock {

        private long now;

        FakeClock(long now) {
            this.now = now;
        }

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long now() {
            return now;
        }
    }

    private static final class TestFiles {

        static void write(File file, String text) throws Exception {
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        }
    }
}
