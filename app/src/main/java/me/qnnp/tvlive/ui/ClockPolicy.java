package me.qnnp.tvlive.ui;

/**
 * 整/半点时钟角标是否允许显示（纯逻辑，可在 JVM 单测）。
 *
 * <p>批次 18：除了原有的「菜单/设置/首启打开时不显示、全屏或导航收起时显示」，
 * 再叠加设置里的「时钟角标」开关。时间窗口（整点/半点前 30 秒）仍由
 * {@link Overlays#updateClock(boolean)} 判断。</p>
 */
public final class ClockPolicy {

    private ClockPolicy() {
    }

    public static boolean allowed(boolean clockBadge, boolean menuOpen, boolean settings,
            boolean setup, boolean fullscreen, boolean navShown) {
        return clockBadge && !menuOpen && !settings && !setup
                && (fullscreen || !navShown);
    }
}
