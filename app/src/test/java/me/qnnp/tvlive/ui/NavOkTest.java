package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * 导航里 OK 的语义：非播放台切预览；当前台在 TV/横屏收起导航、手机竖屏进全屏。
 */
public class NavOkTest {

    @Test
    public void tvPlayingFocusHidesNav() {
        UiState ui = new UiState();
        ui.showNav(0L);

        NavOk.Action action = NavOk.decide(ui.isNavShown(), true, false);

        // TV / 手机横屏：当前台再按 OK 收起导航回到画面。
        assertEquals(NavOk.Action.HIDE_NAV, action);
        ui.hideNav();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertFalse(ui.isFullscreen());
    }

    @Test
    public void phonePortraitPlayingFocusStillEntersFullscreen() {
        UiState ui = new UiState();
        ui.showNav(0L);

        NavOk.Action action = NavOk.decide(ui.isNavShown(), true, true);
        assertEquals(NavOk.Action.FULLSCREEN, action);

        // MainActivity 对 FULLSCREEN 的执行：进入全屏。
        ui.enterFullscreen();
        assertEquals(UiState.Mode.FULLSCREEN, ui.mode());
    }

    @Test
    public void nonPlayingFocusStillPreviewsAndKeepsNavShown() {
        UiState ui = new UiState();
        ui.showNav(0L);

        assertEquals(NavOk.Action.PREVIEW, NavOk.decide(ui.isNavShown(), false, false));
        assertEquals(UiState.Mode.NAV_SHOWN, ui.mode());
    }
}
