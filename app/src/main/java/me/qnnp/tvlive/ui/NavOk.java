package me.qnnp.tvlive.ui;

/**
 * 导航展开（{@link UiState.Mode#NAV_SHOWN}）时按 OK 的语义（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5c：焦点台 != 播放台时仍切预览；焦点台 == 播放台时，手机竖屏进全屏，
 * TV / 手机横屏收起导航回到画面——用户确认的交互是「当前台再按 OK = 收起导航看画面」。
 * 导航显示期间不叠任何 OSD。</p>
 */
public final class NavOk {

    public enum Action {
        /** 导航未展开：无操作。 */
        NONE,
        /** 焦点台不是播放台：切预览，导航保持展开。 */
        PREVIEW,
        /** 手机竖屏：进入 {@link UiState.Mode#FULLSCREEN}。 */
        FULLSCREEN,
        /** TV / 手机横屏 + 焦点台 == 播放台：收起导航回到画面。 */
        HIDE_NAV
    }

    private NavOk() {
    }

    public static Action decide(boolean navShown, boolean focusIsPlaying,
            boolean fullscreenSupported) {
        if (!navShown) {
            return Action.NONE;
        }
        if (!focusIsPlaying) {
            return Action.PREVIEW;
        }
        return fullscreenSupported ? Action.FULLSCREEN : Action.HIDE_NAV;
    }
}
