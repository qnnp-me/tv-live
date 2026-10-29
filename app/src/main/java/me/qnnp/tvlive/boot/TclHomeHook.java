package me.qnnp.tvlive.boot;

import android.content.Context;

/**
 * 批次 25/26：TCL 拉起前先切回主页信源。
 *
 * <p>把 {@link BootHomeSwitch}（广播实现在那里）包成 {@link BootLaunchHook}，让
 * {@link BootHooks} 的表可以按设备组装，{@link BootLauncher} 不感知 TCL 细节。</p>
 *
 * <h2>实机情况（TCL L32F3800A，MT5507 / F3800T，Android 4.4.3）</h2>
 *
 * <ul>
 *   <li>现象：关机前信源停在 HDMI 时，开机自启拉起本 App 有画无声；不做任何操作
 *       再重启一次就正常（那次关机前信源已经是 Android 主页）。</li>
 *   <li>原因：开机瞬间 {@code com.tcl.sourcemananger} 还没起来，
 *       {@code ENTER_HOME} 没发出去，音频路由停在 HDMI。</li>
 *   <li>处理：批次 27 起 {@link BootHooks} 对 TCL 厂商名直接返回本钩子，不依赖组件探测；
 *       若这一轮广播仍因组件未就绪被 {@link BootHomeSwitch#send} 挡下，
 *       {@link BootFrontCheckReceiver} 会在开机初期的每次 bring 前补发，
 *       切换后延迟 {@link BootLaunchPlan#BOOT_HOME_SWITCH_DELAY_MS}（2.5 秒）再拉起。</li>
 *   <li>设备层：{@code /system/etc/appwhitelist_bama.xml} 白名单（root 改，见设备 notes），
 *       与 App 代码无关。</li>
 * </ul>
 */
public final class TclHomeHook implements BootLaunchHook {

    /** 日志名。 */
    public static final String NAME = "tcl-home";

    @Override
    public String name() {
        return NAME;
    }

    /**
     * TCL 上信源停在 HDMI1/HD 时，不切回主页拉起会「有画无声」。
     *
     * <p>优先反射 {@code TTvCommonManager.setInputSource(EN_TCL_STORAGE)}：直接切到
     * Android 应用层，不会把 App 切后台；失败（无权限/类不存在）再退回
     * {@link BootHomeSwitch} 的 {@code ENTER_HOME} 广播。</p>
     */
    @Override
    public boolean run(Context context) {
        if (TclSourceSwitch.setAndroidSource(context)) {
            return true;
        }
        return BootHomeSwitch.send(context);
    }
}
