package me.qnnp.tvlive.boot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * 批次 25：拉起前先把电视信源切回 TCL 主页。
 *
 * <p>实机（TCL L32F3800A，MT5507 / F3800T，Android 4.4.3）结论：关机前信源停在 HDMI 时，
 * 开机自启拉起本 App 有画无声——音频还跟着 HDMI 走；不做任何操作再重启一次就正常。
 * 手动向 TCL 的信源管理广播
 * {@code android.intent.action.ENTER_HOME}（{@code com.tcl.sourcemananger} 的
 * {@code com.tcl.insrc.ShortcutKeyReceiver}）并等约 2 秒再拉起，声音恢复正常。
 * 第一次开机组件可能还没注册，{@link #installed} 会返回 false——调用方
 * （{@link BootFrontCheckReceiver}）会在开机初期补发。</p>
 *
 * <p>通用设备没有这个组件：{@code sendBroadcast} 不会抛异常，只是没人接，
 * 这里按「没发出去」处理（返回 false），由调用方直接拉起，不打印错误。</p>
 */
public final class BootHomeSwitch {

    private static final String TAG = "TvLive";

    /** TCL 信源切换广播：回到主页信源。 */
    public static final String ACTION_ENTER_HOME = "android.intent.action.ENTER_HOME";
    /** TCL 信源管理包名（注意原厂拼写就是 sourcemananger）。 */
    public static final String COMPONENT_PACKAGE = "com.tcl.sourcemananger";
    /** 接收信源切换的广播接收器。 */
    public static final String COMPONENT_CLASS = "com.tcl.insrc.ShortcutKeyReceiver";

    /** 便于单测注入发送实现（真机上是 {@code Context.sendBroadcast}）。 */
    interface Sender {
        void send(Context context, Intent intent);
    }

    private static final Sender BROADCAST_SENDER = new Sender() {
        @Override
        public void send(Context context, Intent intent) {
            context.sendBroadcast(intent);
        }
    };

    private BootHomeSwitch() {
    }

    /** 切主页信源的显式组件 Intent；组件不存在时系统直接丢弃。 */
    public static Intent intent() {
        Intent intent = new Intent(ACTION_ENTER_HOME);
        intent.setComponent(new ComponentName(COMPONENT_PACKAGE, COMPONENT_CLASS));
        return intent;
    }

    /**
     * 发一次切信源广播。
     *
     * @return 是否真的发出去了：机器上没有 TCL 信源组件（通用设备）时返回 false，
     *         调用方据此直接拉起，不延迟
     */
    public static boolean send(Context context) {
        if (context == null) {
            Log.i(TAG, "BootHomeSwitch sent=false reason=no-context");
            return false;
        }
        // 不再用 installed() 提前拦截：TCL 开机初期 com.tcl.sourcemananger 往往还没注册，
        // queryBroadcastReceivers 会误判为「不存在」，导致第一次开机永远不切信源。
        // 直接发显式广播，组件未起时系统丢弃，不影响自启；通用设备根本不会走到这里
        // （BootHooks 在非 TCL 且组件不存在时返回空表）。
        return send(context, BROADCAST_SENDER);
    }

    /** TCL 信源接收器是否存在；查询失败一律当作不存在（静默，不刷错误）。 */
    static boolean installed(Context context) {
        try {
            List<ResolveInfo> receivers =
                    context.getPackageManager().queryBroadcastReceivers(intent(), 0);
            return receivers != null && !receivers.isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 可注入发送实现：异常一律吞掉并返回 false（非 TCL 设备不能因此崩溃/刷错误）。 */
    static boolean send(Context context, Sender sender) {
        boolean sent;
        try {
            sender.send(context, intent());
            sent = true;
        } catch (RuntimeException e) {
            sent = false;
        }
        Log.i(TAG, "BootHomeSwitch sent=" + sent);
        return sent;
    }
}
