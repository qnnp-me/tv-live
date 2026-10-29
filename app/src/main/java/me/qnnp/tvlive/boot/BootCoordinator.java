package me.qnnp.tvlive.boot;

/**
 * 开机信号判定：纯 Java、无 Android 依赖，全部规则可在 JVM 单测里覆盖。
 *
 * <p>时间轴统一用 {@code SystemClock.elapsedRealtime()}（毫秒）：{@code nowElapsed}
 * 是信号到达时刻，也就是「开机以来多久」。批次 28 起窗口按系统开机时长算，
 * 不再用进程启动时刻——手动打开 App 后插 USB 不应再被当成开机信号。</p>
 *
 * <p>规则顺序由 {@link BootActionPolicy} 决定：开关 → 用户主动退出 → 本 action
 * 的有效窗口（BOOT 5 分钟 / DEVICE 2 分钟）→ 同类 action 去重 → 拉起。
 * 去重键是 {@link BootActionPolicy.Kind}，所以 USB 与 MEDIA 不会重复拉起。</p>
 */
public final class BootCoordinator {

    /** 兼容旧调用：系统开机动作的默认窗口。 */
    public static final long BOOT_WINDOW_MS = BootActionPolicy.BOOT_WINDOW_MS;
    /** 兼容旧调用：同类 action 默认去重窗口。 */
    public static final long DEDUP_WINDOW_MS = BootActionPolicy.DEDUP_WINDOW_MS;
    /** 没有历史信号时的哨兵值。 */
    public static final long NO_SIGNAL = -1L;

    /** 判定结果；日志/诊断页直接显示 {@link #label()}。 */
    public enum Decision {
        LAUNCH("拉起"),
        SKIP_DISABLED("开关关闭"),
        SKIP_USER_EXITED("用户已退出"),
        SKIP_TOO_LATE("超过开机窗口"),
        SKIP_DEDUP("重复信号");

        private final String label;

        Decision(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private BootCoordinator() {
    }

    /**
     * @param policy            该 action 的策略（分类/窗口/去重）
     * @param nowElapsed        信号到达时刻（elapsedRealtime，即开机以来）
     * @param enabled           设置页「开机自启」开关
     * @param userExited        用户是否主动退出且尚未重新启动
     * @param lastSignalElapsed 同类上一次信号时刻；没有传 {@link #NO_SIGNAL}
     */
    public static Decision decide(BootActionPolicy policy, long nowElapsed,
                                  boolean enabled, boolean userExited,
                                  long lastSignalElapsed) {
        if (policy == null) {
            // 未知 action 不应进入这里；防御性地当作超时，永不拉起。
            return Decision.SKIP_TOO_LATE;
        }
        if (!enabled) {
            return Decision.SKIP_DISABLED;
        }
        if (userExited) {
            return Decision.SKIP_USER_EXITED;
        }
        if (!policy.accepts(nowElapsed)) {
            return Decision.SKIP_TOO_LATE;
        }
        if (lastSignalElapsed >= 0 && nowElapsed >= lastSignalElapsed
                && nowElapsed - lastSignalElapsed < policy.dedupWindowMs()) {
            return Decision.SKIP_DEDUP;
        }
        return Decision.LAUNCH;
    }
}
