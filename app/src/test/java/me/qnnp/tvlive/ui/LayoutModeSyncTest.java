package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批次 5c：UiState 是唯一状态源，布局只跟随。
 *
 * <p>TV / 手机横屏不产生 {@code FULLSCREEN}：OK 弹导航、再按 OK 收起导航；
 * 手机竖屏保留全屏（进全屏、Back 回列表）。同时覆盖布局卡在全屏时的自愈路径。</p>
 */
public class LayoutModeSyncTest {

    @Test
    public void tvHiddenOkShowsNavWithoutFullscreen() {
        UiState ui = new UiState();
        FakeLayout layout = new FakeLayout();

        ui.showNav(0L);
        LayoutModeSync.apply(ui, layout, true);

        assertEquals(UiState.Mode.NAV_SHOWN, ui.mode());
        assertFalse(ui.isFullscreen());
        assertFalse(layout.fullscreen);
        assertTrue(layout.navShown);
        assertEquals(Arrays.asList("showNav(true)"), layout.calls);
    }

    @Test
    public void tvNavShownOkCollapsesWithoutFullscreen() {
        UiState ui = new UiState();
        FakeLayout layout = new FakeLayout();
        ui.showNav(0L);
        LayoutModeSync.apply(ui, layout, true);
        layout.calls.clear();

        // TV 第二段 OK：收起导航看画面（不是 FULLSCREEN）。
        ui.hideNav();
        LayoutModeSync.apply(ui, layout, true);

        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertFalse(ui.isFullscreen());
        assertFalse(layout.fullscreen);
        assertFalse(layout.navShown);
        assertEquals(Arrays.asList("hideNav(true)"), layout.calls);
    }

    @Test
    public void tvBackFromNavHidesAndStaysOutOfFullscreen() {
        UiState ui = new UiState();
        FakeLayout layout = new FakeLayout();
        ui.showNav(0L);
        LayoutModeSync.apply(ui, layout, false);
        layout.calls.clear();

        ui.hideNav();
        LayoutModeSync.apply(ui, layout, true);

        assertEquals(Arrays.asList("hideNav(true)"), layout.calls);
        assertFalse(ui.isFullscreen());
        assertFalse(layout.fullscreen);
    }

    @Test
    public void phonePortraitFullscreenEntersThenBackReturnsToNav() {
        UiState ui = new UiState();
        FakeLayout layout = new FakeLayout();
        ui.showNav(0L);
        LayoutModeSync.apply(ui, layout, false);
        layout.calls.clear();

        ui.enterFullscreen();
        LayoutModeSync.apply(ui, layout, false);
        assertTrue(layout.fullscreen);
        assertEquals(Arrays.asList("enterFullscreen"), layout.calls);

        layout.calls.clear();
        ui.exitFullscreen();
        ui.showNav(1L);
        LayoutModeSync.apply(ui, layout, true);
        assertFalse(layout.fullscreen);
        assertTrue(layout.navShown);
        assertEquals(Arrays.asList("exitFullscreen", "showNav(true)"), layout.calls);
    }

    @Test
    public void rotationRestoresPhoneFullscreenOnNewLayout() {
        UiState ui = new UiState();
        ui.enterFullscreen();
        FakeLayout rotated = new FakeLayout();

        LayoutModeSync.apply(ui, rotated, false);

        assertTrue(ui.isFullscreen());
        assertTrue(rotated.fullscreen);
        assertEquals(Arrays.asList("enterFullscreen"), rotated.calls);
    }

    @Test
    public void rotationAfterTvKeepsNavHiddenWithoutFullscreen() {
        UiState ui = new UiState();
        FakeLayout rotated = new FakeLayout();
        rotated.showNav(false);
        rotated.calls.clear();

        LayoutModeSync.apply(ui, rotated, false);

        assertFalse(ui.isFullscreen());
        assertFalse(rotated.fullscreen);
        assertFalse(rotated.navShown);
        assertEquals(Arrays.asList("hideNav(false)"), rotated.calls);
    }

    @Test
    public void staleLayoutFullscreenIsRepairedBeforeShowingNav() {
        // 5c 前的现场：ui 已是 NAV_SHOWN，但 TvLayout 还卡在 fullscreen=true，
        // showNav() 内部 if(fullscreen) return，导航永远弹不出来。
        UiState ui = new UiState();
        ui.showNav(0L);
        FakeLayout layout = new FakeLayout();
        layout.enterFullscreen();
        layout.calls.clear();

        LayoutModeSync.apply(ui, layout, true);

        assertFalse(layout.fullscreen);
        assertTrue(layout.navShown);
        assertEquals(Arrays.asList("exitFullscreen", "showNav(true)"), layout.calls);
    }

    /** 记录调用序列的布局 fake；全屏与导航互斥，跟真实布局的兜底语义一致。 */
    private static final class FakeLayout implements LayoutModeSync.Target {
        final List<String> calls = new ArrayList<String>();
        boolean fullscreen;
        boolean navShown;

        @Override
        public boolean isFullscreen() {
            return fullscreen;
        }

        @Override
        public void enterFullscreen() {
            calls.add("enterFullscreen");
            fullscreen = true;
            navShown = false;
        }

        @Override
        public void exitFullscreen() {
            calls.add("exitFullscreen");
            fullscreen = false;
        }

        @Override
        public void showNav(boolean animate) {
            calls.add("showNav(" + animate + ")");
            if (!fullscreen) {
                navShown = true;
            }
        }

        @Override
        public void hideNav(boolean animate) {
            calls.add("hideNav(" + animate + ")");
            navShown = false;
        }
    }
}
