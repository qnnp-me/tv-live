package me.qnnp.tvlive.boot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import me.qnnp.tvlive.prefs.AppPrefs;

/**
 * 拉起后的前台确认落点（批次 21）：只由 {@link BootLauncher#scheduleFrontCheck} 用显式
 * Intent 调度，不导出、不接系统广播。
 *
 * <p>每次醒来查一次 top activity，判定交给纯 Java 的 {@link BootFrontPlan}：
 * 自己在最前 → 结束；被桌面盖住 → {@link BootLauncher#bringToFront} 带回并排下一次；
 * 查询不可判定 → 最多重试 3 次；用户主动退出 → 立即结束。</p>
 *
 * <h2>批次 27：开机初期补发切信源（实机 TCL L32F3800A，MT5507 / F3800T，Android 4.4.3）</h2>
 *
 * <ul>
 *   <li>现象：关机前信源在 HDMI 时，第一次开机自启本 App 有画无声；不做任何操作再重启
 *       一次就正常（第二次关机前信源已是 Android 主页）。</li>
 *   <li>原因：开机瞬间 {@code com.tcl.sourcemananger} 还没起来，拉起前那次
 *       {@code ENTER_HOME} 发不出去，音频路由停在 HDMI。</li>
 *   <li>处理：每次 {@link BootFrontPlan.Action#BRING} 前先 {@link BootHooks#runAll}
 *       补发一次切源广播（只在前 {@link BootFrontPlan#HOME_SWITCH_MAX_ATTEMPT} 次），
 *       等组件起来后就能切回主页信源；切源后排下一次确认，给信源切换留出时间。
 *       发广播失败只记日志，不影响 bring。</li>
 *   <li>设备层：{@code /system/etc/appwhitelist_bama.xml} 白名单（root 改，见设备 notes），
 *       与 App 代码无关——本接收器只管前台抢占与补发。</li>
 * </ul>
 */
public final class BootFrontCheckReceiver extends BroadcastReceiver {

    public static final String ACTION_BOOT_FRONT = "me.qnnp.tvlive.boot.BOOT_FRONT";

    private static final String TAG = "TvLive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        int attempt = 1;
        int unknown = 0;
        if (intent != null) {
            attempt = Math.max(1,
                    intent.getIntExtra(BootLauncher.EXTRA_FRONT_ATTEMPT, 1));
            unknown = Math.max(0,
                    intent.getIntExtra(BootLauncher.EXTRA_FRONT_UNKNOWN, 0));
        }

        // 用户主动退出/切走后不再拉回：只在开机窗口内有效，退出即结束整条链。
        AppPrefs prefs = new AppPrefs(app);
        if (prefs.userExited()) {
            Log.i(TAG, "BootFront skip user-exited attempt=" + attempt);
            return;
        }

        String top = BootAlarmReceiver.topPackage(app);
        BootFrontPlan.Step step = BootFrontPlan.next(top, app.getPackageName(),
                attempt, unknown, false);
        Log.i(TAG, "BootFront check attempt=" + attempt + " top=" + top
                + " -> " + label(step.action()));

        switch (step.action()) {
            case OK:
                Log.i(TAG, "BootFront ok attempt=" + attempt);
                break;
            case GIVE_UP:
                Log.w(TAG, "BootFront give up attempts=" + attempt + " top=" + top);
                break;
            case BRING:
                // 批次 27：先把信源从 HDMI 切回主页，再带回前台——否则音频还跟着 HDMI，
                // 画面回来了还是没声（TCL L32F3800A / MT5507，Android 4.4.3）。
                sendHomeSwitch(app, attempt);
                boolean brought = BootLauncher.bringToFront(app);
                Log.i(TAG, "BootFront bring attempt=" + attempt
                        + " result=" + (brought ? "ok" : "fail"));
                schedule(app, step);
                break;
            case RETRY:
                schedule(app, step);
                break;
            default:
                break;
        }
    }

    /**
     * 批次 27：bring 之前先补发一次切信源广播（TCL = 从 HDMI 回到主页信源）。
     *
     * <p>实机 TCL L32F3800A（MT5507 / F3800T，Android 4.4.3）：第一次开机时
     * {@code com.tcl.sourcemananger} 还没起来，拉起前那次 {@code ENTER_HOME} 发不出去，
     * 音频路由停 HDMI → App 有画无声；再重启一次（信源已是 Android）才正常。
     * 前台确认每 2 秒一轮，等组件起来后再补几次即可切回主页。</p>
     *
     * <p>只在前 {@link BootFrontPlan#HOME_SWITCH_MAX_ATTEMPT} 次发，避免整条链每 2 秒刷
     * 广播；发失败（通用设备空表、组件仍未就绪、广播异常）只记日志，不影响 bring。
     * 通用设备 {@link BootHooks} 返回空表，这里等于空转，行为不变。</p>
     */
    private static void sendHomeSwitch(Context app, int attempt) {
        if (!BootFrontPlan.shouldSendHomeSwitch(attempt)) {
            return;
        }
        boolean sent;
        try {
            sent = BootHooks.runAll(app);
        } catch (RuntimeException e) {
            sent = false;
        }
        Log.i(TAG, "BootFront home switch attempt=" + attempt + " sent=" + sent);
    }

    private static void schedule(Context app, BootFrontPlan.Step step) {
        boolean scheduled = BootLauncher.scheduleFrontCheck(app, step.delayMs(),
                step.attempt(), step.unknown());
        if (!scheduled) {
            Log.w(TAG, "BootFront reschedule failed attempt=" + step.attempt());
        }
    }

    private static String label(BootFrontPlan.Action action) {
        switch (action) {
            case OK:
                return "ok";
            case BRING:
                return "bring";
            case RETRY:
                return "retry";
            case GIVE_UP:
                return "give-up";
            default:
                return action.name();
        }
    }
}
