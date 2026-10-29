package me.qnnp.tvlive.boot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import me.qnnp.tvlive.ui.MainActivity;

/**
 * 开机拉起的调度与执行入口。
 *
 * <p>boot 信号到达时不直接 startActivity，而是先用 {@link AlarmManager} 排一次
 * {@link BootWaitPlan#POLL_MS} 后的广播；{@link BootAlarmReceiver} 每次醒来查询
 * top activity，确认默认桌面就绪（再 settle 一会儿）才真正拉起，最多等
 * {@link BootWaitPlan#MAX_WAIT_MS}。这样开机瞬间不会与 TCL 桌面抢焦点/内存。</p>
 *
 * <p>PendingIntent 的 requestCode 固定 + {@code FLAG_UPDATE_CURRENT}：同一时刻只保留
 * 一个等待 alarm，重复开机信号不会叠出多次拉起。等待期内重复信号由
 * {@link BootWaitPlan#shouldStartWait} 直接跳过。</p>
 *
 * <p>批次 21：真正拉起后还要盯住前台——TCL 巴马桌面会在拉起后再起 {@code AllAppsActivity}
 * 抢回焦点。{@link #launchNow} 成功后立刻排一次 {@link BootFrontCheckReceiver}，
 * 由 {@link BootFrontPlan} 决定「结束 / 带回前台 / 重试 / 放弃」。</p>
 *
 * <p>批次 23：TCL 已把本 App 加入 {@code appwhitelist_bama.xml}，不再被
 * {@code AppWhiteListBama} 清理；开机自启改为「直接拉起」——{@link #launch} 在
 * {@link BootLaunchPlan#DIRECT_LAUNCH} 为 true 时按
 * {@link BootActionPolicy#launchDelayMs()} 排一次 alarm，醒来立刻 {@link #launchNow}，
 * 不再轮询默认桌面。等待链路（{@link BootWaitPlan} / {@link HomeResolver} /
 * {@link BootAlarmReceiver} 的轮询分支）原样保留，把开关改回 false 即可回退。</p>
 *
 * <p>批次 28：延迟不再固定，由 action 策略决定（BOOT 1s / 网络 6s / 用户 4s /
 * USB+存储 15s），见 {@link BootActionPolicy}。</p>
 *
 * <p>批次 25/26：TCL 开机停在 HDMI1/HD 时直接拉起本 App 会「有画无声」（音频跟 HDMI 走）。
 * 直接拉起那条 alarm 醒来后先跑 {@link BootHooks#runAll}（启动前厂商动作表），
 * 由它决定要不要切回主页信源；有钩子做了异步动作则再排
 * {@link BootLaunchPlan#BOOT_HOME_SWITCH_DELAY_MS} 后的一次 alarm
 * （带 {@link #EXTRA_DIRECT_LAUNCH}）才真正 startActivity，否则直接拉起。
 * 前台确认逻辑不变。</p>
 */
public final class BootLauncher {

    private static final String TAG = "TvLive";

    /** 拉起 Intent 里的标记：MainActivity 据此延迟 3 秒起播。 */
    public static final String EXTRA_FROM_BOOT = "from_boot";
    /** 本轮等待的起点（elapsedRealtime），随每次 alarm 传递，避免跨进程状态丢失。 */
    static final String EXTRA_WAIT_START = "boot_wait_start";
    /** 已连续判定桌面就绪的次数。 */
    static final String EXTRA_STREAK = "boot_wait_streak";
    /** 触发本次等待的 boot 来源（写 BootLog 用）。 */
    static final String EXTRA_SOURCE = "boot_wait_source";
    /** 前台确认是第几次（从 1 开始，随每次 alarm 传递）。 */
    static final String EXTRA_FRONT_ATTEMPT = "boot_front_attempt";
    /** 前台确认连续不可判定的次数。 */
    static final String EXTRA_FRONT_UNKNOWN = "boot_front_unknown";
    /** 本次 alarm 是批次 23 的直接拉起（跳过 top activity 轮询）。 */
    static final String EXTRA_DIRECT = "boot_direct";
    /** 批次 25：已切回主页信源，这次 alarm 直接拉起，不再发切源广播。 */
    static final String EXTRA_DIRECT_LAUNCH = "boot_direct_launch";

    /** 固定 requestCode：重复 schedule 只更新同一个 PendingIntent。 */
    static final int ALARM_REQUEST_CODE = 0x424F4F54;
    /** 前台确认 alarm 的 requestCode：与等待 alarm 分开，互不覆盖。 */
    static final int FRONT_ALARM_REQUEST_CODE = 0x4246524F;

    public static final long BOOT_LAUNCH_MAX_WAIT_MS = BootWaitPlan.MAX_WAIT_MS;
    public static final long BOOT_LAUNCH_POLL_MS = BootWaitPlan.POLL_MS;
    public static final long BOOT_LAUNCH_SETTLE_MS = BootWaitPlan.SETTLE_MS;
    public static final long BOOT_LAUNCH_FALLBACK_MS = BootWaitPlan.FALLBACK_MS;

    /** 进程内 MainActivity 是否存在（onCreate/onDestroy 维护）；进程被杀后自动回到 false。 */
    private static volatile boolean activityAlive;

    /** 本轮等待的起点；{@link BootWaitPlan#NO_WAIT} 表示没有进行中的计划。 */
    private static long waitStartElapsed = BootWaitPlan.NO_WAIT;

    private BootLauncher() {
    }

    public static void onActivityCreated() {
        activityAlive = true;
    }

    public static void onActivityDestroyed() {
        activityAlive = false;
    }

    public static boolean isActivityAlive() {
        return activityAlive;
    }

    /**
     * 收到开机信号：直接拉起模式下按策略延迟；开关关闭时退回「等桌面就绪」。
     *
     * <p>批次 28：延迟不再固定 1 秒，由 {@link BootActionPolicy#launchDelayMs()}
     * 决定（BOOT 1s / 网络 6s / 用户唤醒 4s / USB+存储 15s）。</p>
     *
     * @return 是否成功排上 alarm（直接拉起且 alarm 排不上时会立刻拉起）
     */
    public static boolean launch(Context context, BootActionPolicy policy) {
        Context app = appContext(context);
        if (app == null || policy == null) {
            return false;
        }
        BootSource source = policy.source();
        if (!BootLaunchPlan.shouldLaunch(activityAlive)) {
            // 已经在前台：不再排等待 alarm，免得白白轮询 20 秒后又被跳过。
            Log.i(TAG, "BootLaunch skip already foreground, no schedule");
            return false;
        }
        long directDelay = BootLaunchPlan.directLaunchDelayMs();
        if (directDelay >= 0L) {
            return directLaunch(app, source, policy.launchDelayMs());
        }
        // 回退路径（DIRECT_LAUNCH=false）：原「等桌面就绪」调度，逻辑保持不变。
        long now = SystemClock.elapsedRealtime();
        if (!BootWaitPlan.shouldStartWait(now, waitStartElapsed)) {
            Log.i(TAG, "BootSignal wait already running, skip schedule"
                    + " waited=" + (now - waitStartElapsed) + "ms");
            return false;
        }
        waitStartElapsed = now;
        boolean scheduled = schedule(app, source, now, 0, BootWaitPlan.POLL_MS, false);
        if (scheduled) {
            Log.i(TAG, "BootSignal schedule wait poll+" + BootWaitPlan.POLL_MS
                    + "ms max=" + BootWaitPlan.MAX_WAIT_MS
                    + "ms fallback=" + BootWaitPlan.FALLBACK_MS + "ms");
        } else {
            waitStartElapsed = BootWaitPlan.NO_WAIT;
            Log.w(TAG, "BootSignal schedule failed");
        }
        return scheduled;
    }

    /**
     * 批次 23：不再检测默认桌面，固定延迟后由 {@link BootAlarmReceiver} 直接 {@link #launchNow}。
     *
     * <p>仍走 AlarmManager（而不是 handler）：进程在延迟窗口内被回收也能拉起。
     * alarm 排不上时立刻拉起，不能把自启拖没。</p>
     */
    private static boolean directLaunch(Context app, BootSource source, long delayMs) {
        Log.i(TAG, "BootLaunch direct launch +" + (delayMs / 1000L) + "s");
        boolean scheduled = schedule(app, source, BootWaitPlan.NO_WAIT, 0, delayMs, true);
        if (scheduled) {
            return true;
        }
        Log.w(TAG, "BootLaunch direct schedule failed, launch now");
        boolean ok = launchNow(app);
        BootSignalReceiver.record(app, source, ok ? "LAUNCH_DIRECT" : "LAUNCH_DIRECT_FAIL");
        return ok;
    }

    /**
     * 批次 25/26：真正拉起前先跑启动前厂商动作表（TCL 是「切回主页信源」）。
     *
     * <p>任一钩子做了异步动作（返回 true）才再排一次
     * {@link BootLaunchPlan#BOOT_HOME_SWITCH_DELAY_MS} 后的 alarm（带
     * {@link #EXTRA_DIRECT_LAUNCH}），给厂商动作留出时间。没有钩子命中、钩子都说不用等
     * （或 alarm 排不上）返回 false，调用方立刻拉起，不丢自启。</p>
     *
     * @return 是否已排上「厂商动作后延迟拉起」的 alarm
     */
    public static boolean switchHomeThenDelay(Context context, BootSource source) {
        Context app = appContext(context);
        if (app == null) {
            return false;
        }
        boolean acted = BootHooks.runAll(app);
        long delayMs = BootLaunchPlan.launchDelayAfterHomeSwitch(acted);
        if (delayMs <= 0L) {
            Log.i(TAG, "BootLaunch no hook delay, launch now");
            return false;
        }
        Log.i(TAG, "BootLaunch hook delay -> launch in " + delayMs + "ms");
        boolean scheduled = schedule(app, source, BootWaitPlan.NO_WAIT, 0, delayMs, false, true);
        if (!scheduled) {
            Log.w(TAG, "BootLaunch hook delay schedule failed, launch now");
        }
        return scheduled;
    }

    /** 轮询未结束时排下一次 alarm；失败由调用方保底直接拉起。 */
    public static boolean scheduleNext(Context context, BootSource source, long waitStart,
                                       int streak, long delayMs) {
        Context app = appContext(context);
        if (app == null) {
            return false;
        }
        return schedule(app, source, waitStart, streak, delayMs, false);
    }

    private static boolean schedule(Context app, BootSource source, long waitStart,
                                    int streak, long delayMs, boolean direct) {
        return schedule(app, source, waitStart, streak, delayMs, direct, false);
    }

    private static boolean schedule(Context app, BootSource source, long waitStart,
                                    int streak, long delayMs, boolean direct,
                                    boolean afterHome) {
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            Log.w(TAG, "BootSignal 没有 AlarmManager");
            return false;
        }
        long triggerAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        PendingIntent pending = alarmIntent(app, source, waitStart, streak, direct, afterHome);
        try {
            // Android 4.4（API 19）起 setExact 可用，不需要精确 alarm 权限。
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            Log.i(TAG, "BootSignal alarm +" + delayMs + "ms streak=" + streak
                    + (direct ? " direct=true" : "") + (afterHome ? " afterHome=true" : "")
                    + " at=" + triggerAt);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "BootSignal alarm 调度失败", e);
            return false;
        }
    }

    private static PendingIntent alarmIntent(Context app, BootSource source, long waitStart,
                                            int streak, boolean direct, boolean afterHome) {
        Intent intent = new Intent(app, BootAlarmReceiver.class);
        intent.setAction(BootAlarmReceiver.ACTION_BOOT_WAIT);
        intent.putExtra(EXTRA_WAIT_START, waitStart);
        intent.putExtra(EXTRA_STREAK, streak);
        intent.putExtra(EXTRA_DIRECT, direct);
        intent.putExtra(EXTRA_DIRECT_LAUNCH, afterHome);
        if (source != null) {
            intent.putExtra(EXTRA_SOURCE, source.name());
        }
        // 固定 requestCode + UPDATE_CURRENT：重复 schedule 只保留一个 alarm，并刷新 extras。
        return PendingIntent.getBroadcast(app, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 真正 startActivity；只在 BootAlarmReceiver 判定该拉起时调用。 */
    public static boolean launchNow(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return false;
        }
        Intent launch = launchIntent(app);
        try {
            app.startActivity(launch);
            Log.i(TAG, "BootLaunch started alive=" + activityAlive);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "BootLaunch 拉起失败：找不到 MainActivity", e);
            return false;
        } catch (RuntimeException e) {
            // 厂商/系统拦截后台启动 Activity（SecurityException 等）：不崩溃。
            Log.w(TAG, "BootLaunch 拉起失败（系统拦截？）", e);
            return false;
        }
        // 拉起成功不代表守住前台：立刻确认一次，被桌面盖住就带回来。
        boolean scheduled = scheduleFrontCheck(app, 0L);
        Log.i(TAG, "BootFront schedule first check=" + (scheduled ? "ok" : "fail"));
        return true;
    }

    /**
     * 把已经在跑的 MainActivity 带回前台（批次 21）。
     *
     * <p>不用 {@code moveTaskToFront}（需要 REORDER_TASKS 权限）：{@code startActivity}
     * + singleTask/SINGLE_TOP 等价于把已有任务提到最前，不会重建 Activity。</p>
     */
    public static boolean bringToFront(Context context) {
        Context app = appContext(context);
        if (app == null) {
            return false;
        }
        try {
            app.startActivity(launchIntent(app));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "BootFront 拉起失败：找不到 MainActivity", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "BootFront 拉起失败（系统拦截？）", e);
            return false;
        }
    }

    /**
     * 排一次前台确认（首次由 {@link #launchNow} 调用，之后由
     * {@link BootFrontCheckReceiver} 接力）。
     */
    public static boolean scheduleFrontCheck(Context context, long delayMs) {
        return scheduleFrontCheck(context, delayMs, 1, 0);
    }

    static boolean scheduleFrontCheck(Context context, long delayMs, int attempt, int unknown) {
        Context app = appContext(context);
        if (app == null) {
            return false;
        }
        AlarmManager alarm = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarm == null) {
            Log.w(TAG, "BootFront 没有 AlarmManager");
            return false;
        }
        Intent intent = new Intent(app, BootFrontCheckReceiver.class);
        intent.setAction(BootFrontCheckReceiver.ACTION_BOOT_FRONT);
        intent.putExtra(EXTRA_FRONT_ATTEMPT, Math.max(1, attempt));
        intent.putExtra(EXTRA_FRONT_UNKNOWN, Math.max(0, unknown));
        // 固定 requestCode + UPDATE_CURRENT：前台确认链只保留一个 alarm。
        PendingIntent pending = PendingIntent.getBroadcast(app, FRONT_ALARM_REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAt = System.currentTimeMillis() + Math.max(0L, delayMs);
        try {
            alarm.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending);
            Log.i(TAG, "BootFront alarm +" + delayMs + "ms attempt=" + attempt
                    + " unknown=" + unknown + " at=" + triggerAt);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "BootFront alarm 调度失败", e);
            return false;
        }
    }

    /** 开机拉起/前台确认共用的 Intent：singleTask 复用已有任务，不重建 Activity。 */
    private static Intent launchIntent(Context app) {
        Intent launch = new Intent(app, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        launch.putExtra(EXTRA_FROM_BOOT, true);
        return launch;
    }

    private static Context appContext(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }
}
