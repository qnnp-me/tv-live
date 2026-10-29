package me.qnnp.tvlive.boot;

/**
 * 单个开机 action 的统一策略：来源、分类、拉起延迟、有效窗口、去重窗口。
 *
 * <p>批次 28：把「哪些 action 能拉起」「收到后等多久」「开机多久内还算数」
 * 从散落的常量收进一张表。{@link BootActions} 只负责 action → 策略，
 * {@link BootCoordinator} 只按策略判定，{@link BootLauncher} 只按策略延迟。</p>
 *
 * <p>分类的意义：同类 action 共享去重（例如 USB 与 MEDIA 都算 DEVICE，
 * 开机时先 USB 后 MEDIA 不会拉起两次）；不同类互不影响（BOOT 与 NETWORK 各自
 * 有自己的窗口）。</p>
 *
 * <p>延迟的目的：TCL L32F3800A（MT5507 / Android 4.4.3）在开机 25 秒左右
 * 就会发出 USB/网络广播，此时系统解码器/音频路由还没完全就绪。启动前先等
 * 一段时间，避免 App 在音频/解码器初始化窗口内起播。</p>
 */
public final class BootActionPolicy {

    /** 系统/厂商开机动作：可信、延迟最短。 */
    public static final long BOOT_WINDOW_MS = 5L * 60L * 1000L;
    /** 外设/存储动作只在开机早期算数，避免用户后来插 U 盘把 App 拉起。 */
    public static final long DEVICE_WINDOW_MS = 2L * 60L * 1000L;
    /** 同一类 action 的默认去重窗口。 */
    public static final long DEDUP_WINDOW_MS = 30L * 1000L;

    /** 系统/厂商开机：收到后 1 秒拉起（沿用原 DIRECT_LAUNCH 常量）。 */
    public static final long BOOT_DELAY_MS = BootLaunchPlan.BOOT_DIRECT_DELAY_MS;
    /** 网络就绪：等 DHCP/DNS/路由稳定。 */
    public static final long NETWORK_DELAY_MS = 6_000L;
    /** 用户唤醒：等系统窗口/输入稳定。 */
    public static final long USER_DELAY_MS = 4_000L;
    /** USB/存储：外设枚举与挂载最慢，放到最后。 */
    public static final long DEVICE_DELAY_MS = 15_000L;

    /** 策略分类；同类共享去重。 */
    public enum Kind {
        BOOT("开机"),
        NETWORK("网络"),
        USER("用户唤醒"),
        DEVICE("外设/存储");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final BootSource source;
    private final Kind kind;
    private final long launchDelayMs;
    private final long windowMs;
    private final long dedupWindowMs;

    public BootActionPolicy(BootSource source, Kind kind, long launchDelayMs,
                            long windowMs, long dedupWindowMs) {
        if (source == null) {
            throw new IllegalArgumentException("source == null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind == null");
        }
        if (launchDelayMs < 0L || windowMs <= 0L || dedupWindowMs < 0L) {
            throw new IllegalArgumentException("invalid policy timing");
        }
        this.source = source;
        this.kind = kind;
        this.launchDelayMs = launchDelayMs;
        this.windowMs = windowMs;
        this.dedupWindowMs = dedupWindowMs;
    }

    public BootSource source() {
        return source;
    }

    public Kind kind() {
        return kind;
    }

    /** 收到信号后到真正 startActivity 的延迟。 */
    public long launchDelayMs() {
        return launchDelayMs;
    }

    /** 开机以来多久内还算有效（用 elapsedRealtime，不用进程启动时刻）。 */
    public long windowMs() {
        return windowMs;
    }

    public long dedupWindowMs() {
        return dedupWindowMs;
    }

    /** 当前开机时长是否还在本 action 的有效窗口内。 */
    public boolean accepts(long elapsedRealtimeMs) {
        return elapsedRealtimeMs >= 0L && elapsedRealtimeMs <= windowMs;
    }
}
