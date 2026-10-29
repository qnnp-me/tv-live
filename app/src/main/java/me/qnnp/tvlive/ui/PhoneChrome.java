package me.qnnp.tvlive.ui;

/**
 * 手机主界面触摸入口的可见性（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5b：手机主界面底栏必须有触摸「设置」入口（不再只能靠遥控 MENU）；
 * 电视保持 4b 的遥控路径，不显示任何触摸按钮。</p>
 */
public final class PhoneChrome {

    private PhoneChrome() {
    }

    /** 底栏「设置」：手机 + 主界面（非全屏、非设置页、非首启页）。 */
    public static boolean settingsEntryVisible(boolean phone, boolean fullscreen,
            boolean settings, boolean setup) {
        return phone && !fullscreen && !settings && !setup;
    }

    /** 全屏半透明返回按钮：只有手机全屏、且 OSD 可见窗口内才显示（电视没有触摸）。 */
    public static boolean backChromeVisible(boolean phone, boolean fullscreen, boolean osdVisible) {
        return phone && fullscreen && osdVisible;
    }
}
