package me.qnnp.tvlive.boot;

/**
 * 开机拉起前的等待计划：纯 Java、无 Android 依赖，全部决策可在 JVM 单测里覆盖。
 *
 * <p>目标：不跟桌面抢开机窗口。boot 信号到达后先按 {@link #POLL_MS} 轮询 top activity，
 * 连续 {@link #READY_STREAK} 次确认默认桌面在前台，再等 {@link #SETTLE_MS} 才拉起；
 * 最多等 {@link #MAX_WAIT_MS}。查询失败/没有默认桌面时，改为从等待开始计
 * {@link #FALLBACK_MS} 后拉起。</p>
 *
 * <p>所有时间都是「已经等了多久」，由调用方用 elapsedRealtime 差值算好传入；
 * 本类只做判定，不读时钟、不调度。</p>
 *
 * <p>批次 23：{@link BootLaunchPlan#DIRECT_LAUNCH} 为 true 时 {@link BootLauncher}
 * 直接拉起，不再使用本类；本类及单测原样保留，开关改回 false 即恢复原来的等待行为。</p>
 */
public final class BootWaitPlan {

    /** 等待上限：超过就直接拉起，保证开机自启最终一定发生。 */
    public static final long MAX_WAIT_MS = 20_000L;
    /** 轮询 top activity 的间隔（批次 21：2 秒 → 1 秒，更早起拉）。 */
    public static final long POLL_MS = 1_000L;
    /** 连续确认桌面在前台后，再等这么久（让桌面 AllApps 稳定）才拉起。 */
    public static final long SETTLE_MS = 3_000L;
    /** top activity 无法判定（无 GET_TASKS / 无默认桌面）时的兜底等待。 */
    public static final long FALLBACK_MS = 12_000L;
    /** 连续多少次轮询判定桌面在前台才算「就绪」。 */
    public static final int READY_STREAK = 2;
    /** 没有等待中的计划时的哨兵值。 */
    public static final long NO_WAIT = -1L;

    /** 本次等待判定走的路径，日志 mode= 直接用 {@link #logValue()}。 */
    public enum Mode {
        TASK("task"),
        ELAPSED("elapsed"),
        MAX("max");

        private final String logValue;

        Mode(String logValue) {
            this.logValue = logValue;
        }

        public String logValue() {
            return logValue;
        }
    }

    public enum Action {
        /** 再来一次轮询（{@link Step#delayMs()} 后）。 */
        POLL,
        /** 桌面已连续就绪，等 {@link Step#delayMs()} 后拉起。 */
        SETTLE,
        /** 无法判定 top activity，等 {@link Step#delayMs()} 后拉起（elapsed 兜底）。 */
        FALLBACK,
        /** 立即拉起。 */
        LAUNCH
    }

    /** 一次判定结果：下一步动作、累计的就绪次数、下次触发延迟。 */
    public static final class Step {

        private final Action action;
        private final int streak;
        private final long delayMs;
        private final Mode mode;

        Step(Action action, int streak, long delayMs, Mode mode) {
            this.action = action;
            this.streak = streak;
            this.delayMs = delayMs;
            this.mode = mode;
        }

        public Action action() {
            return action;
        }

        /** 已经连续多少次轮询判定桌面在前台。 */
        public int streak() {
            return streak;
        }

        /** 下一次 alarm 的延迟；{@link Action#LAUNCH} 时为 0。 */
        public long delayMs() {
            return delayMs;
        }

        public Mode mode() {
            return mode;
        }
    }

    private BootWaitPlan() {
    }

    /**
     * 新信号是否应该开启一轮等待。
     *
     * <p>等待期内（{@link #MAX_WAIT_MS}）重复信号不再另起计划：同一批开机广播
     * （BOOT_COMPLETED + 厂商 action + 网络变化）只留一个 alarm。</p>
     */
    public static boolean shouldStartWait(long nowElapsed, long lastWaitStartElapsed) {
        if (lastWaitStartElapsed < 0L) {
            return true;
        }
        if (nowElapsed < lastWaitStartElapsed) {
            // 设备重启后 elapsedRealtime 归零：上一次记录作废。
            return true;
        }
        return nowElapsed - lastWaitStartElapsed >= MAX_WAIT_MS;
    }

    /**
     * 计算下一步。
     *
     * @param topPackage  getRunningTasks 得到的 top 包名；查询失败/无权限传 null
     * @param homePackage 默认桌面包名；无法解析传 null
     * @param streak      之前连续判定桌面在前台的次数
     * @param waitedMs    本轮等待已经持续的时间
     */
    public static Step next(String topPackage, String homePackage, int streak, long waitedMs) {
        long waited = Math.max(0L, waitedMs);
        if (topPackage == null || homePackage == null) {
            // 不可判定：不猜桌面，按 elapsed 兜底在 FALLBACK_MS 拉起。
            if (waited >= FALLBACK_MS) {
                return new Step(Action.LAUNCH, 0, 0L, Mode.ELAPSED);
            }
            return new Step(Action.FALLBACK, 0, FALLBACK_MS - waited, Mode.ELAPSED);
        }
        if (!homePackage.equals(topPackage)) {
            // 桌面还没到前台（系统启动画面/其他应用）：继续轮询，最多到上限。
            return pollOrLaunch(waited, 0, Mode.TASK);
        }
        int ready = Math.max(0, streak) + 1;
        if (ready > READY_STREAK) {
            // 这是 settle alarm 醒来后的确认：桌面仍在前台，直接拉起，不再重复 settle。
            return new Step(Action.LAUNCH, ready, 0L, Mode.TASK);
        }
        if (ready == READY_STREAK) {
            if (waited + SETTLE_MS > MAX_WAIT_MS) {
                // settle 会越过上限：直接拉起，保证「最多 20 秒」。
                return new Step(Action.LAUNCH, ready, 0L, Mode.MAX);
            }
            return new Step(Action.SETTLE, ready, SETTLE_MS, Mode.TASK);
        }
        // 第一次看到桌面：再确认一次，避免桌面刚起又被系统对话框/开机向导盖住。
        return pollOrLaunch(waited, ready, Mode.TASK);
    }

    private static Step pollOrLaunch(long waited, int streak, Mode mode) {
        if (waited >= MAX_WAIT_MS) {
            return new Step(Action.LAUNCH, streak, 0L, Mode.MAX);
        }
        long remaining = MAX_WAIT_MS - waited;
        return new Step(Action.POLL, streak, Math.min(POLL_MS, remaining), mode);
    }
}
