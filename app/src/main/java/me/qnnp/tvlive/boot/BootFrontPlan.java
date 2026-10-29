package me.qnnp.tvlive.boot;

/**
 * 拉起后的前台确认计划：纯 Java、无 Android 依赖，全部决策可在 JVM 单测里覆盖。
 *
 * <p>批次 21：TCL 上开机拉起 MainActivity 后，巴马桌面还会再起 {@code AllAppsActivity}
 * 把前台抢回去。因此拉起成功后不是「结束」，而是每 {@link #INTERVAL_MS} 查一次
 * top activity：自己在前台就结束，被桌面盖住就 {@code startActivity} 带回来，
 * 最多 {@link #MAX_ATTEMPTS} 次（约 24 秒窗口），避免无限抢占。</p>
 *
 * <p>{@code getRunningTasks} 不可判定时不做盲拉（拉不出证据还刷启动动画），
 * 最多重试 {@link #MAX_UNKNOWN} 次后放弃。用户主动退出（{@code userExited}）时
 * 立即结束，本计划只在开机窗口内有效。</p>
 */
public final class BootFrontPlan {

    /** 前台确认最多做这么多次（首次算第 1 次）。 */
    public static final int MAX_ATTEMPTS = 12;
    /** 两次前台确认之间的间隔。 */
    public static final long INTERVAL_MS = 2_000L;
    /** top activity 连续不可判定这么多次后放弃。 */
    public static final int MAX_UNKNOWN = 3;

    /**
     * 批次 27：开机初期前几次前台确认顺带补发一次切信源广播。
     *
     * <p>第一次开机时 {@code com.tcl.sourcemananger} 可能还没起来，拉起前的切源广播
     * 发不出去；前台确认窗口内（每 {@link #INTERVAL_MS} 一次）每次都补发，等组件起来后
     * 就能把信源从 HDMI 切回主页。TCL 开机时 sourcemananger 的启动时间不稳定
     * （实测有时 6 秒后才注册），所以覆盖整个 12 次前台确认窗口，而不是只发前几次。</p>
     */
    public static final int HOME_SWITCH_MAX_ATTEMPT = 12;

    public enum Action {
        /** 自己已经在前台（或用户已退出）：结束，不再排 alarm。 */
        OK,
        /** 被别的应用盖住：立即带回前台，然后排下一次确认。 */
        BRING,
        /** top activity 不可判定：不盲拉，只排下一次确认。 */
        RETRY,
        /** 次数用尽/连续不可判定：放弃。 */
        GIVE_UP
    }

    /** 一次判定结果：动作 + 下次要带的计数 + 下次触发延迟。 */
    public static final class Step {

        private final Action action;
        private final int attempt;
        private final int unknown;
        private final long delayMs;

        Step(Action action, int attempt, int unknown, long delayMs) {
            this.action = action;
            this.attempt = attempt;
            this.unknown = unknown;
            this.delayMs = delayMs;
        }

        public Action action() {
            return action;
        }

        /** 下一次确认要带的 attempt；{@link Action#OK}/{@link Action#GIVE_UP} 时为当前值。 */
        public int attempt() {
            return attempt;
        }

        /** 已经连续多少次不可判定。 */
        public int unknown() {
            return unknown;
        }

        /** 下一次 alarm 的延迟；结束类动作为 0。 */
        public long delayMs() {
            return delayMs;
        }
    }

    private BootFrontPlan() {
    }

    /**
     * 批次 27：这次前台确认要不要先补发切信源广播。
     *
     * <p>只在前 {@link #HOME_SWITCH_MAX_ATTEMPT} 次带上——开机初期这几次已经覆盖
     * sourcemananger 的启动窗口，再往后发只是每 2 秒刷一遍广播。</p>
     */
    public static boolean shouldSendHomeSwitch(int attempt) {
        return Math.max(1, attempt) <= HOME_SWITCH_MAX_ATTEMPT;
    }

    /**
     * 计算下一步。
     *
     * @param topPackage  当前 top activity 的包名；查询失败/无权限传 null
     * @param selfPackage 自己（{@code me.qnnp.tvlive}）的包名
     * @param attempt     本次是第几次确认（从 1 开始）
     * @param unknown     之前连续多少次不可判定
     * @param userExited  用户是否已在本次开机内主动退出
     */
    public static Step next(String topPackage, String selfPackage, int attempt, int unknown,
                            boolean userExited) {
        int current = Math.max(1, attempt);
        int misses = Math.max(0, unknown);
        if (userExited) {
            // 用户自己切走/退出了：不再拉回，避免和用户抢。
            return new Step(Action.OK, current, misses, 0L);
        }
        if (topPackage != null && topPackage.equals(selfPackage)) {
            return new Step(Action.OK, current, misses, 0L);
        }
        if (topPackage == null) {
            // 不可判定：盲拉只会刷启动动画，连续失败 3 次就直接放弃。
            int nextUnknown = misses + 1;
            if (nextUnknown >= MAX_UNKNOWN) {
                return new Step(Action.GIVE_UP, current, nextUnknown, 0L);
            }
            return new Step(Action.RETRY, current + 1, nextUnknown, INTERVAL_MS);
        }
        if (current >= MAX_ATTEMPTS) {
            return new Step(Action.GIVE_UP, current, 0, 0L);
        }
        return new Step(Action.BRING, current + 1, 0, INTERVAL_MS);
    }
}
