package me.qnnp.tvlive.boot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 返回系统桌面：App 不声明任何桌面入口（TCL 的 PackageVerifier 拒绝第三方桌面声明），
 * 只显式拉起 bama 桌面；bama 不存在或拉起失败时退回系统桌面选择器。
 *
 * <p>bama 桌面固定为 {@code com.tcl.bama.launcher/.Launcher}。</p>
 */
public final class HomeController {

    private static final String TAG = "TvLive";

    public static final String BAMA_PACKAGE = "com.tcl.bama.launcher";
    public static final String BAMA_LAUNCHER = "com.tcl.bama.launcher.Launcher";

    private HomeController() {
    }

    /**
     * 显式拉起 bama 桌面；不存在/失败则打开系统桌面选择器。
     *
     * @return 是否成功发起跳转
     */
    public static boolean launchSystemHome(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            launcher.setComponent(new ComponentName(BAMA_PACKAGE, BAMA_LAUNCHER));
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (launcher.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(launcher);
                return true;
            }
            Log.w(TAG, "未找到 bama 桌面，改为打开桌面选择器");
        } catch (RuntimeException e) {
            Log.w(TAG, "拉起系统桌面失败", e);
        }
        return openHomeChooser(context);
    }

    /** 系统桌面选择器；makeMainSelectorActivity 失败时退回纯 CATEGORY_HOME。 */
    private static boolean openHomeChooser(Context context) {
        try {
            Intent chooser = Intent.makeMainSelectorActivity(
                    Intent.ACTION_MAIN, Intent.CATEGORY_HOME);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "打开桌面选择器失败，尝试 CATEGORY_HOME", e);
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "打开系统桌面失败", e);
            return false;
        }
    }
}
