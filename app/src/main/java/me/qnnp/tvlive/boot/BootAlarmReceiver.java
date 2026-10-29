package me.qnnp.tvlive.boot;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * 等待 alarm 的落点：查 top activity → 判断默认桌面是否已就绪 → 决定继续等还是拉起。
 *
 * <p>每次醒来只做一次判定并立刻排下一次 alarm（不在这里 sleep/阻塞），所以即使
 * 进程在两次轮询之间被回收，下一次 alarm 仍会把等待流程接上。判定全部委托给纯 Java
 * 的 {@link BootWaitPlan}：桌面判定用 {@link HomeResolver} 得到的默认桌面包名，
 * 不硬编码厂商包名。</p>
 *
 * <p>批次 23：{@link BootLaunchPlan#DIRECT_LAUNCH} 为 true 时 alarm 带
 * {@link BootLauncher#EXTRA_DIRECT}，这里直接 {@link BootLauncher#launchNow}，
 * 不查 top activity、不轮询。下面的等待/轮询/兜底分支原样保留，开关改回 false 即恢复。</p>
 *
 * <p>批次 25/26/27：直接拉起前先跑「启动前厂商动作表」（{@link BootHooks}，TCL 上是切回
 * 主页信源）；有钩子做了异步动作则再排一次
 * {@link BootLaunchPlan#BOOT_HOME_SWITCH_DELAY_MS}（2.5 秒）后的 alarm（带
 * {@link BootLauncher#EXTRA_DIRECT_LAUNCH}）；那条 alarm 只拉起、不再跑钩子，避免来回
 * 切源。没有钩子命中时仍按原来的直接拉起走。</p>
 */
public final class BootAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_BOOT_WAIT = "me.qnnp.tvlive.boot.BOOT_WAIT";

    private static final String TAG = "TvLive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        BootSource source = source(intent.getStringExtra(BootLauncher.EXTRA_SOURCE));
        if (intent.getBooleanExtra(BootLauncher.EXTRA_DIRECT_LAUNCH, false)) {
            directAfterHome(app, source);
            return;
        }
        if (intent.getBooleanExtra(BootLauncher.EXTRA_DIRECT, false)) {
            direct(app, source);
            return;
        }
        long waitStart = intent.getLongExtra(BootLauncher.EXTRA_WAIT_START,
                BootWaitPlan.NO_WAIT);
        if (waitStart < 0L) {
            // extras 丢失（被系统重建等）：从此刻开始计时，避免 waited 永远为 0 而无限轮询。
            waitStart = SystemClock.elapsedRealtime();
        }
        long waited = Math.max(0L, SystemClock.elapsedRealtime() - waitStart);
        int streak = intent.getIntExtra(BootLauncher.EXTRA_STREAK, 0);

        String top = topPackage(app);
        String home = HomeResolver.homePackage(app.getPackageManager());
        BootWaitPlan.Step step = BootWaitPlan.next(top, home, streak, waited);
        boolean ready = top != null && top.equals(home);
        Log.i(TAG, "BootWait top=" + top + " home=" + home + " ready=" + ready
                + " waited=" + waited + "ms mode=" + step.mode().logValue()
                + " -> " + step.action().name());

        if (step.action() == BootWaitPlan.Action.LAUNCH) {
            fire(app, source, waited, step.mode());
            return;
        }
        boolean scheduled = BootLauncher.scheduleNext(app, source, waitStart,
                step.streak(), step.delayMs());
        if (!scheduled) {
            // 排不上 alarm 时不能把自启拖没：直接走兜底拉起。
            Log.w(TAG, "BootWait reschedule failed, launch now");
            fire(app, source, waited, BootWaitPlan.Mode.ELAPSED);
        }
    }

    /** 批次 23：直接拉起模式落点，不查 top activity、不重排等待。 */
    private void direct(Context app, BootSource source) {
        Log.i(TAG, "BootLaunch launchNow");
        if (!BootLaunchPlan.shouldLaunch(isForeground())) {
            Log.i(TAG, "BootLaunch launchNow skip already foreground");
            return;
        }
        // 批次 25/26：先跑启动前厂商动作（TCL = 切回主页信源）。排上了延迟 alarm 就等它
        // 回来再拉起；没有钩子命中或 alarm 排不上时立刻拉起，不丢自启。
        if (BootLauncher.switchHomeThenDelay(app, source)) {
            return;
        }
        boolean ok = BootLauncher.launchNow(app);
        Log.i(TAG, "BootLaunch launchNow result=" + (ok ? "ok" : "fail"));
        BootSignalReceiver.record(app, source, ok ? "LAUNCH_DIRECT" : "LAUNCH_DIRECT_FAIL");
    }

    /** 批次 25：切源等待结束，直接拉起；不再发切源广播，避免反复切。 */
    private void directAfterHome(Context app, BootSource source) {
        Log.i(TAG, "BootLaunch direct launch after home");
        if (!BootLaunchPlan.shouldLaunch(isForeground())) {
            Log.i(TAG, "BootLaunch direct launch after home skip already foreground");
            return;
        }
        boolean ok = BootLauncher.launchNow(app);
        Log.i(TAG, "BootLaunch launchNow result=" + (ok ? "ok" : "fail"));
        BootSignalReceiver.record(app, source, ok ? "LAUNCH_DIRECT" : "LAUNCH_DIRECT_FAIL");
    }

    private void fire(Context app, BootSource source, long waited, BootWaitPlan.Mode mode) {
        if (!BootLaunchPlan.shouldLaunch(isForeground())) {
            Log.i(TAG, "BootLaunch skip already foreground waited=" + waited + "ms");
            return;
        }
        boolean ok = BootLauncher.launchNow(app);
        Log.i(TAG, "BootAlarm fire waited=" + waited + "ms mode=" + mode.logValue()
                + " result=" + (ok ? "ok" : "fail"));
        BootSignalReceiver.record(app, source, ok ? "LAUNCH_OK" : "LAUNCH_FAIL");
    }

    /** MainActivity 还活着就视为已在前台，不再二次拉起抢焦点。 */
    private static boolean isForeground() {
        return BootLauncher.isActivityAlive();
    }

    /** top activity 的包名；ROM 禁用 getRunningTasks / 无权限时返回 null（不可判定）。 */
    static String topPackage(Context context) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            ActivityManager.RunningTaskInfo info = tasks.get(0);
            if (info == null || info.topActivity == null) {
                return null;
            }
            return info.topActivity.getPackageName();
        } catch (SecurityException e) {
            Log.w(TAG, "topPackage getRunningTasks 无权限", e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "topPackage getRunningTasks 失败", e);
            return null;
        }
    }

    private static BootSource source(String name) {
        if (name == null) {
            return BootSource.VENDOR;
        }
        try {
            return BootSource.valueOf(name);
        } catch (IllegalArgumentException e) {
            return BootSource.VENDOR;
        }
    }
}
