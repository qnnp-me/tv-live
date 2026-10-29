package me.qnnp.tvlive.boot;

import android.content.Context;
import android.util.Log;

/**
 * TCL 信源切回 Android 应用层（反射版）。
 *
 * <h2>为什么需要它（TCL L32F3800A，MT5507 / F3800T，Android 4.4.3）</h2>
 *
 * <p>关机前信源停在 HDMI 时，开机自启拉起本 App 会「有画无声」：画面是 App 的，
 * 音频仍走 HDMI 那一路。发 {@code android.intent.action.ENTER_HOME} 广播能切回来，
 * 但巴马桌面（{@code com.tcl.bama.launcher}）启动时会执行</p>
 *
 * <pre>
 * getLastestSavedSource() → 如果当前信源不是 EN_TCL_STORAGE，就 setInputSource(上次保存的信源)
 * </pre>
 *
 * <p>于是刚切过去的 Android 层又被 HDMI 覆盖，第一次开机必现、第二次重启才好。
 * 这里直接反射 TCL 的 {@code TTvCommonManager} 把信源设成 {@code EN_TCL_STORAGE}
 * （Android 应用层），比广播更直接，也不会像 ENTER_HOME 那样把 App 切到后台。</p>
 *
 * <p>风险：{@code setInputSource} 可能需要系统权限；失败时只记日志并返回 false，
 * 由 {@link BootHomeSwitch} 的广播兜底。通用设备没有这些类，同样返回 false。</p>
 */
public final class TclSourceSwitch {

    private static final String TAG = "TvLive";

    private static final String MANAGER_CLASS = "com.tcl.tvmanager.TTvCommonManager";
    private static final String SOURCE_CLASS = "com.tcl.tvmanager.vo.EnTCLInputSource";
    /** Android 应用层；巴马也用这个值判断「当前不在 TV/HDMI 信源」。 */
    private static final String ANDROID_SOURCE = "EN_TCL_STORAGE";

    private TclSourceSwitch() {
    }

    /**
     * 把 TCL 信源切到 Android 应用层。
     *
     * @return true 表示调用成功（异步切换已发起）；false 表示类不存在/无权限/调用失败
     */
    public static boolean setAndroidSource(Context context) {
        if (context == null) {
            return false;
        }
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Object manager = managerClass.getMethod("getInstance", Context.class)
                    .invoke(null, context);
            if (manager == null) {
                Log.w(TAG, "TclSourceSwitch manager=null");
                return false;
            }
            Class<?> sourceClass = Class.forName(SOURCE_CLASS);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object source = Enum.valueOf((Class<? extends Enum>) sourceClass, ANDROID_SOURCE);
            managerClass.getMethod("setInputSource", sourceClass, boolean.class)
                    .invoke(manager, source, Boolean.TRUE);
            Log.i(TAG, "TclSourceSwitch setInputSource(EN_TCL_STORAGE) ok");
            return true;
        } catch (ClassNotFoundException e) {
            // 通用设备：没有 TCL 的 tvmanager，正常回落。
            Log.i(TAG, "TclSourceSwitch unavailable: no tvmanager");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "TclSourceSwitch failed: " + t);
            return false;
        }
    }

    /** 当前是否在 Android 应用层；查询失败返回 null（不阻塞任何流程）。 */
    public static Boolean isAndroidSource(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Object manager = managerClass.getMethod("getInstance", Context.class)
                    .invoke(null, context);
            if (manager == null) {
                return null;
            }
            Object current = managerClass.getMethod("getCurrentInputSource").invoke(manager);
            Class<?> sourceClass = Class.forName(SOURCE_CLASS);
            Object androidSource = Enum.valueOf(
                    (Class<? extends Enum>) sourceClass, ANDROID_SOURCE);
            return Boolean.valueOf(androidSource.equals(current));
        } catch (Throwable t) {
            return null;
        }
    }
}
