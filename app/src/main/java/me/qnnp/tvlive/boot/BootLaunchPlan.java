package me.qnnp.tvlive.boot;

/**
 * 拉起之后的开播决策：纯 Java，便于 JVM 单测。
 *
 * <p>开机拉起时先让画面/桌面稳定，首个频道延迟 {@link #BOOT_PLAYBACK_DELAY_MS}
 * 再起播；普通启动（用户点图标）不延迟。</p>
 */
public final class BootLaunchPlan {

    /** 开机拉起后首次起播的延迟：避开开机瞬间的解码器/内存争抢。 */
    public static final long BOOT_PLAYBACK_DELAY_MS = 3_000L;

    /**
     * 批次 23 开关：{@code true} = 收到开机信号后固定延迟直接拉起，不再检测默认桌面。
     *
     * <p>回退方式：改成 {@code false} 即恢复批次 22 的「等桌面就绪」路径
     * （{@link BootWaitPlan} + {@link HomeResolver} + {@link BootAlarmReceiver} 全部保留未删）。</p>
     */
    public static final boolean DIRECT_LAUNCH = true;

    /** 直接拉起前的固定延迟：让广播分发线程先返回，避免和开机流程抢。 */
    public static final long BOOT_DIRECT_DELAY_MS = 1_000L;

    /** {@link #directLaunchDelayMs()} 的「不走直接拉起」哨兵值。 */
    public static final long NO_DIRECT_DELAY = -1L;

    /**
     * 批次 25/27：先让 TCL 切回主页信源，再等这么久才拉起。
     *
     * <p>实机（TCL L32F3800A，MT5507 / F3800T，Android 4.4.3）上信源停在 HDMI 时拉起
     * App 有画无声——音频还跟着 HDMI 走。先发 {@code ENTER_HOME} 切回主页信源、等信源
     * 切完再拉起，声音才正常。批次 27 把 1.5 秒抬到 2.5 秒：第一次开机
     * {@code com.tcl.sourcemananger} 起得晚，组件起来 + 切源动画都要时间，1.5 秒常常
     * 不够；2.5 秒取在实机手工验证的 2 秒等待之上，又不至于把自启拖太久。</p>
     */
    public static final long BOOT_HOME_SWITCH_DELAY_MS = 2_500L;

    private BootLaunchPlan() {
    }

    /** 当前开关下直接拉起的延迟；返回 {@link #NO_DIRECT_DELAY} 表示仍走等桌面就绪。 */
    public static long directLaunchDelayMs() {
        return directLaunchDelayMs(DIRECT_LAUNCH);
    }

    /** 便于单测同时覆盖开关两态：true → 1 秒；false → 仍走等待。 */
    static long directLaunchDelayMs(boolean directLaunch) {
        return directLaunch ? BOOT_DIRECT_DELAY_MS : NO_DIRECT_DELAY;
    }

    /** 本次启动来自开机拉起时，首次起播是否需要延迟。 */
    public static boolean shouldDeferPlayback(boolean fromBoot) {
        return fromBoot;
    }

    /**
     * 批次 25：切信源成功后是否还要再等一轮才拉起。
     *
     * <p>TCL 上切源有过渡动画，广播返回时信源往往还没切完；通用设备没发出去就不用等。</p>
     */
    public static boolean shouldDelayAfterHomeSwitch(boolean homeSwitchSent) {
        return homeSwitchSent;
    }

    /**
     * 切信源后排下一次 alarm 的延迟：TCL 为 {@link #BOOT_HOME_SWITCH_DELAY_MS}，
     * 非 TCL（没发出去）为 0，直接拉起。
     */
    public static long launchDelayAfterHomeSwitch(boolean homeSwitchSent) {
        return shouldDelayAfterHomeSwitch(homeSwitchSent) ? BOOT_HOME_SWITCH_DELAY_MS : 0L;
    }

    /** 延迟 alarm 到达时是否还需要拉起：已经在前台就不抢第二次焦点。 */
    public static boolean shouldLaunch(boolean alreadyForeground) {
        return !alreadyForeground;
    }
}
