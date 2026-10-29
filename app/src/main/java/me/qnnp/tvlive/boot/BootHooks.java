package me.qnnp.tvlive.boot;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 批次 26：启动前厂商动作表 —— 按设备/厂商返回要执行的 {@link BootLaunchHook} 列表。
 *
 * <p>与 {@link BootActions}（开机信号 action 表）并列但职责不同：那边决定「收到什么广播
 * 算开机」，这边决定「真正拉起前要替厂商做什么」。{@link BootLauncher} 只认这张表，
 * 不再直接引用任何具体厂商实现。</p>
 *
 * <h2>TCL 判定</h2>
 *
 * <p>设备：TCL L32F3800A（MT5507 / F3800T，Android 4.4.3）。{@link Build#MANUFACTURER}
 * 含 {@code TCL}，或机器上真有 {@code com.tcl.sourcemananger} 的信源接收器
 * （换品牌固件/白牌机也能命中）。</p>
 *
 * <p>现象（批次 25 起跟踪，批次 27 定性）：关机前电视信源停在 HDMI 时，第一次开机自启
 * 拉起本 App 有画无声；不做任何操作再重启一次就正常（第二次关机前信源已经是 Android
 * 主页，切源广播不再必要）。</p>
 *
 * <p>原因：开机瞬间 {@code com.tcl.sourcemananger} 还没起来，这里查组件
 * （{@link BootHomeSwitch#installed}）返回 false，{@link TclHomeHook} 被整条跳过，
 * {@code ENTER_HOME} 没发出去，音频路由停在 HDMI，App 只有画面。</p>
 *
 * <p>批次 27 处理：厂商名命中 TCL 时<b>直接</b>返回 {@link TclHomeHook}，不再依赖组件探测；
 * 只有厂商名对不上的白牌/贴牌机才退回组件探测。通用设备两样都不满足 → 空表，
 * 行为与之前完全一致。</p>
 *
 * <p>设备层：本 App 需要能被 TCL 保活，靠 {@code /system/etc/appwhitelist_bama.xml}
 * 白名单（root 改，见设备 notes），与 App 代码无关。</p>
 */
public final class BootHooks {

    private static final String TAG = "TvLive";

    /** TCL 判定用的厂商关键字。 */
    static final String TCL_MANUFACTURER = "TCL";

    private BootHooks() {
    }

    /**
     * 不看 Context 的表：只按 {@link Build#MANUFACTURER} 判定。
     *
     * <p>组件探测需要 PackageManager，有 Context 时用 {@link #table(Context)}。</p>
     */
    public static List<BootLaunchHook> table() {
        return table(Build.MANUFACTURER, false);
    }

    /** 带 Context 的表：厂商命中或真有 TCL 信源组件时返回 TCL 钩子。 */
    public static List<BootLaunchHook> table(Context context) {
        return table(Build.MANUFACTURER, context);
    }

    /**
     * 带厂商 + Context 的判定（便于单测覆盖「厂商命中但不查组件」）。
     *
     * <p>厂商名命中 TCL 就直接给钩子，<b>不查组件</b>：第一次开机（TCL L32F3800A，
     * MT5507 / F3800T，Android 4.4.3）时 {@code com.tcl.sourcemananger} 往往还没注册，
     * 探测必然 false，会把切源钩子漏掉，信源留在 HDMI → App 有画无声。</p>
     *
     * <p>厂商名对不上才退回组件探测（白牌/贴牌机装了 TCL 信源服务）；
     * 都不满足返回空表，通用设备不切源、不延迟。</p>
     */
    static List<BootLaunchHook> table(String manufacturer, Context context) {
        if (isTcl(manufacturer)) {
            return table(manufacturer, false);
        }
        // installed() 内部已吞异常，查询失败当作没有该组件：最多不切源，不挡自启。
        boolean component = context != null && BootHomeSwitch.installed(context);
        return table(manufacturer, component);
    }

    /** 纯决策，便于单测覆盖厂商/组件两态。 */
    static List<BootLaunchHook> table(String manufacturer, boolean tclComponent) {
        if (tclComponent || isTcl(manufacturer)) {
            return Collections.<BootLaunchHook>singletonList(new TclHomeHook());
        }
        return Collections.emptyList();
    }

    /** 厂商名大小写不定（{@code TCL} / {@code tcl-television} 都算）。 */
    static boolean isTcl(String manufacturer) {
        return manufacturer != null
                && manufacturer.toUpperCase(Locale.US).contains(TCL_MANUFACTURER);
    }

    /**
     * 依次执行钩子：任一返回 true 即视为「需要延迟」，后面的不再执行（短路）。
     *
     * <p>单个 hook 抛异常只记日志并跳过，继续下一个——厂商私有接口挂掉不能把自启拖没。</p>
     *
     * @return true 表示至少一个钩子做了异步动作，调用方应延迟再拉起
     */
    public static boolean runAll(Context context) {
        return runAll(table(context), context);
    }

    /** 可注入列表：便于单测覆盖短路与异常继续。 */
    static boolean runAll(List<BootLaunchHook> hooks, Context context) {
        boolean delay = false;
        if (hooks != null) {
            for (BootLaunchHook hook : hooks) {
                if (hook == null) {
                    continue;
                }
                boolean acted;
                try {
                    acted = hook.run(context);
                } catch (RuntimeException e) {
                    acted = false;
                }
                Log.i(TAG, "BootHook " + hook.name() + " -> " + acted);
                if (acted) {
                    delay = true;
                    break;
                }
            }
        }
        Log.i(TAG, "BootHooks delay=" + delay);
        return delay;
    }
}
