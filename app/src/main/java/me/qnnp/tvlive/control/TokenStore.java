package me.qnnp.tvlive.control;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Random;

import me.qnnp.tvlive.data.PlaylistRepository;

/**
 * 手机页鉴权：6 位 PIN（首次随机生成并持久化）+ 失败退避 + 二维码短时 token。
 *
 * <p>PIN 存在 {@code filesDir/control_pin.txt}，只含 6 位数字；文件损坏/缺失时重新生成。
 * 同一分钟内失败 {@value #MAX_FAILURES} 次后进入退避窗口，窗口内一律 429；
 * 成功一次立即清空失败计数。二维码 token（32 位随机字符）只存内存、有效期
 * {@value #TOKEN_TTL_MS} 毫秒，命中即视为通过，过期回落到 PIN。</p>
 *
 * <p>纯 Java（{@link File} + 可注入时钟），单测直接传临时目录。</p>
 */
public final class TokenStore {

    public static final String PIN_FILE = "control_pin.txt";
    public static final int PIN_LENGTH = 6;
    /** 窗口内允许的失败次数。 */
    public static final int MAX_FAILURES = 5;
    /** 退避窗口：60 秒。 */
    public static final long FAILURE_WINDOW_MS = 60L * 1000L;
    /** 二维码 token 有效期（10 分钟）。 */
    public static final long TOKEN_TTL_MS = 10L * 60L * 1000L;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final char[] TOKEN_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /** 鉴权结果：{@link #OK} 通过；{@link #BAD_PIN} PIN 错误；{@link #THROTTLED} 退避中。 */
    public enum Auth {
        OK,
        BAD_PIN,
        THROTTLED
    }

    /** 可注入时钟（单测用）。 */
    public interface Clock {
        long now();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    private final File dir;
    private final Clock clock;
    private final Random random;
    private final Deque<Long> failures = new ArrayDeque<Long>();

    private String pin;
    private String token;
    private long tokenExpiresAt;

    public TokenStore(File filesDir) {
        this(filesDir, SYSTEM_CLOCK, new SecureRandom());
    }

    TokenStore(File filesDir, Clock clock, Random random) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir == null");
        }
        this.dir = filesDir;
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
        this.random = random == null ? new SecureRandom() : random;
    }

    /** 持久化 PIN；不存在/损坏时生成并落盘。 */
    public synchronized String pin() {
        if (pin == null) {
            pin = readPin();
        }
        return pin;
    }

    /**
     * 已生成的 PIN；未生成时返回 null，不读文件。
     *
     * <p>UI 线程只走这个：首次生成可能写文件，交给后台线程，
     * 避免 refreshControlInfo 每次访问都碰磁盘。</p>
     */
    public synchronized String cachedPin() {
        return pin;
    }

    /**
     * PIN 或二维码 token 校验；成功清空失败计数，失败计数进入退避窗口。
     *
     * <p>token 在有效期内可重复使用（手机页刷新/搜索仍免 PIN），过期或被新 token
     * 覆盖后自动回落到 PIN；token 命中不消耗/不触发失败退避，避免二维码用户被
     * 别人的 PIN 试错锁住。</p>
     */
    public synchronized Auth authenticate(String candidate) {
        long now = clock.now();
        String provided = candidate == null ? "" : candidate.trim();
        if (tokenMatches(provided, now)) {
            failures.clear();
            return Auth.OK;
        }
        prune(now);
        if (isLocked(now)) {
            return Auth.THROTTLED;
        }
        String expected = pin();
        if (constantTimeEquals(expected, provided)) {
            failures.clear();
            return Auth.OK;
        }
        failures.addLast(Long.valueOf(now));
        prune(now);
        return Auth.BAD_PIN;
    }

    /** 当前是否在退避窗口内。 */
    public synchronized boolean isLocked() {
        long now = clock.now();
        prune(now);
        return isLocked(now);
    }

    /** 退避剩余秒数（向上取整）；不在退避中返回 0。 */
    public synchronized int retryAfterSeconds() {
        long now = clock.now();
        prune(now);
        if (!isLocked(now)) {
            return 0;
        }
        Long oldest = failures.peekFirst();
        if (oldest == null) {
            return 0;
        }
        long left = oldest.longValue() + FAILURE_WINDOW_MS - now;
        if (left <= 0L) {
            return 0;
        }
        return (int) ((left + 999L) / 1000L);
    }

    /** 当前窗口内失败次数（调试/测试用）。 */
    public synchronized int failureCount() {
        prune(clock.now());
        return failures.size();
    }

    /**
     * 生成二维码 token（二维码 URL 用）。只存内存，进程退出即失效；重新生成会
     * 立即作废旧 token，新 token {@value #TOKEN_TTL_MS} 毫秒内免 PIN。
     */
    public synchronized String issueToken() {
        StringBuilder out = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            out.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]);
        }
        token = out.toString();
        tokenExpiresAt = clock.now() + TOKEN_TTL_MS;
        return token;
    }

    /**
     * 当前未过期 token；已过期时清空并返回 null。
     *
     * <p>设置页进入时优先复用这个 token，避免重签把已扫码连接的手机踢回 PIN。</p>
     */
    public synchronized String currentToken() {
        if (token == null || clock.now() > tokenExpiresAt) {
            token = null;
            tokenExpiresAt = 0L;
            return null;
        }
        return token;
    }

    /** 当前是否有未过期 token（设置页复用/重生成二维码用）；过期即清理。 */
    public synchronized boolean isTokenValid() {
        return currentToken() != null;
    }

    /** {@link #isTokenValid()} 的旧名，保留给已有调用方。 */
    public synchronized boolean hasToken() {
        return isTokenValid();
    }

    /** 校验 token（区分大小写；过期返回 false）。 */
    public synchronized boolean verifyToken(String candidate) {
        return tokenMatches(candidate == null ? "" : candidate.trim(), clock.now());
    }

    // ------------------------------------------------------------------ 内部

    private boolean tokenMatches(String candidate, long now) {
        return token != null && !candidate.isEmpty()
                && now <= tokenExpiresAt
                && constantTimeEquals(token, candidate);
    }

    private void prune(long now) {
        long cutoff = now - FAILURE_WINDOW_MS;
        while (!failures.isEmpty() && failures.peekFirst().longValue() <= cutoff) {
            failures.pollFirst();
        }
    }

    private boolean isLocked(long now) {
        return failures.size() >= MAX_FAILURES && retryAfterSecondsLocked(now) > 0;
    }

    private int retryAfterSecondsLocked(long now) {
        Long oldest = failures.peekFirst();
        if (oldest == null) {
            return 0;
        }
        long left = oldest.longValue() + FAILURE_WINDOW_MS - now;
        return left <= 0L ? 0 : (int) ((left + 999L) / 1000L);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(UTF_8), right.getBytes(UTF_8));
    }

    private String readPin() {
        File file = new File(dir, PIN_FILE);
        if (file.isFile()) {
            try {
                String stored = new String(PlaylistRepository.read(file), UTF_8).trim();
                if (isValidPin(stored)) {
                    return stored;
                }
            } catch (IOException ignored) {
                // 读失败按缺失处理，重新生成。
            } catch (RuntimeException ignored) {
                // 同上。
            }
        }
        String generated = generatePin();
        try {
            // PIN 丢失的代价只是重新生成/重新输入，不需要 fsync。
            PlaylistRepository.writeAtomic(file, generated.getBytes(UTF_8), false);
        } catch (IOException ignored) {
            // 落盘失败仍返回本次 PIN；下次启动会重新生成，页面需重新输入。
        }
        return generated;
    }

    private String generatePin() {
        int value = 100000 + random.nextInt(900000);
        return String.format(Locale.US, "%06d", Integer.valueOf(value));
    }

    private static boolean isValidPin(String value) {
        if (value == null || value.length() != PIN_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
