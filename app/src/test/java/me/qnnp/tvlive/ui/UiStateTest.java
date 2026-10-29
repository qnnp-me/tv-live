package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 4a：模式/菜单/焦点/退出确认的唯一状态源。
 */
public class UiStateTest {

    @Test
    public void defaultsToHiddenScene() {
        UiState ui = new UiState();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertTrue(ui.isNavHidden());
        assertFalse(ui.isNavShown());
        assertFalse(ui.isFullscreen());
        assertFalse(ui.isSetup());
        assertFalse(ui.isSettings());
        assertFalse(ui.isMenuOpen());
        assertFalse(ui.isFocusInGroups());
        assertFalse(ui.isFocusInActions());
        assertFalse(ui.isExitArmed());
        assertEquals(-1L, ui.autoHideAt());
    }

    @Test
    public void showNavArmsEightSecondTimer() {
        UiState ui = new UiState();
        ui.showNav(1000L);
        assertEquals(UiState.Mode.NAV_SHOWN, ui.mode());
        assertEquals(9000L, ui.autoHideAt());
        assertFalse(ui.consumeAutoHide(8999L));
        assertTrue(ui.consumeAutoHide(9000L));
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertEquals(-1L, ui.autoHideAt());
    }

    @Test
    public void noteKeyOnlyResetsInShownNav() {
        UiState ui = new UiState();
        ui.noteKey(0L);
        assertEquals(-1L, ui.autoHideAt());

        ui.showNav(0L);
        ui.noteKey(5000L);
        assertEquals(13000L, ui.autoHideAt());

        ui.enterFullscreen();
        ui.noteKey(14000L);
        assertEquals(-1L, ui.autoHideAt());
    }

    @Test
    public void autoHideDurationCanBeChangedAndDisabled() {
        UiState ui = new UiState();
        ui.setAutoHideMs(5000L);
        ui.showNav(1000L);
        assertEquals(6000L, ui.autoHideAt());
        assertFalse(ui.consumeAutoHide(5999L));
        assertTrue(ui.consumeAutoHide(6000L));

        UiState off = new UiState();
        off.setAutoHideMs(0L);
        off.showNav(0L);
        assertEquals("不收起时不设截止时间", -1L, off.autoHideAt());
        assertFalse(off.consumeAutoHide(999999L));
        assertEquals("Back/OK 仍可手动收起", UiState.Mode.NAV_SHOWN, off.mode());
        off.hideNav();
        assertEquals(UiState.Mode.NAV_HIDDEN, off.mode());
    }

    @Test
    public void autoHideClearsActionColumnFocus() {
        UiState ui = new UiState();
        ui.showNav(0L);
        ui.setActionColumnVisible(true);
        ui.setFocusInActions(true);
        assertTrue(ui.consumeAutoHide(8000L));
        assertFalse(ui.isActionColumnVisible());
        assertFalse(ui.isFocusInActions());
        assertFalse(ui.consumeAutoHide(16000L));
    }

    @Test
    public void fullscreenBackReturnsToHidden() {
        UiState ui = new UiState();
        ui.showNav(0L);
        ui.enterFullscreen();
        assertEquals(UiState.Mode.FULLSCREEN, ui.mode());
        assertTrue(ui.isFullscreen());
        assertEquals(-1L, ui.autoHideAt());

        ui.exitFullscreen();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertTrue(ui.isNavHidden());
    }

    @Test
    public void hideNavFromFullscreenAlsoHides() {
        UiState ui = new UiState();
        ui.enterFullscreen();
        ui.hideNav();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
    }

    @Test
    public void tvSceneUsesOnlyHiddenAndShownNav() {
        // 5c：TV 的「全屏」就是 NAV_HIDDEN，OK/Back 只在隐藏与导航之间切换，
        // 不经过 FULLSCREEN（该模式只留给手机竖屏）。
        UiState ui = new UiState();
        ui.showNav(0L);
        assertEquals(UiState.Mode.NAV_SHOWN, ui.mode());
        assertFalse(ui.isFullscreen());

        ui.hideNav();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
        assertFalse(ui.isFullscreen());

        ui.showNav(1L);
        assertEquals(UiState.Mode.NAV_SHOWN, ui.mode());
        assertFalse(ui.isFullscreen());
    }

    @Test
    public void settingsRestoresEachBaseMode() {
        UiState fromHidden = new UiState();
        assertTrue(fromHidden.enterSettings());
        assertEquals(UiState.Mode.SETTINGS, fromHidden.mode());
        assertEquals(UiState.Mode.NAV_HIDDEN, fromHidden.leaveSettings(0L));

        UiState fromShown = new UiState();
        fromShown.showNav(1000L);
        assertTrue(fromShown.enterSettings());
        assertTrue(fromShown.isNavShown());
        assertEquals(UiState.Mode.NAV_SHOWN, fromShown.leaveSettings(5000L));
        assertEquals(13000L, fromShown.autoHideAt());

        UiState fromFullscreen = new UiState();
        fromFullscreen.enterFullscreen();
        assertTrue(fromFullscreen.enterSettings());
        assertTrue(fromFullscreen.isFullscreen());
        assertEquals(UiState.Mode.FULLSCREEN, fromFullscreen.leaveSettings(0L));

        UiState fromSetup = new UiState();
        fromSetup.enterSetup();
        assertTrue(fromSetup.enterSettings());
        assertEquals(UiState.Mode.SETUP, fromSetup.leaveSettings(0L));
    }

    @Test
    public void dismissingSetupUnderSettingsSkipsSetup() {
        UiState ui = new UiState();
        ui.enterSetup();
        ui.enterSettings();
        ui.dismissSetup();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.leaveSettings(0L));
    }

    @Test
    public void setupResetsNavAndOverlays() {
        UiState ui = new UiState();
        ui.showNav(0L);
        ui.openMenu();
        ui.setActionColumnVisible(true);
        ui.setFocusInGroups(true);
        ui.enterSetup();
        assertEquals(UiState.Mode.SETUP, ui.mode());
        assertFalse(ui.isMenuOpen());
        assertFalse(ui.isActionColumnVisible());
        assertFalse(ui.isFocusInGroups());
        assertEquals(-1L, ui.autoHideAt());

        ui.dismissSetup();
        assertEquals(UiState.Mode.NAV_HIDDEN, ui.mode());
    }

    @Test
    public void menuFlagToggles() {
        UiState ui = new UiState();
        ui.openMenu();
        assertTrue(ui.isMenuOpen());
        ui.closeMenu();
        assertFalse(ui.isMenuOpen());
    }

    @Test
    public void blockedTimerRestartsWhenReleased() {
        UiState ui = new UiState();
        ui.showNav(0L);
        ui.setBlocked(true, 0L);
        assertEquals(-1L, ui.autoHideAt());
        assertFalse(ui.consumeAutoHide(999999L));
        ui.setBlocked(false, 1000L);
        assertEquals(9000L, ui.autoHideAt());
    }

    @Test
    public void exitArmedNotifiesListener() {
        UiState ui = new UiState();
        final boolean[] last = new boolean[] {false};
        final int[] changes = new int[] {0};
        ui.setExitListener(new UiState.ExitListener() {
            @Override
            public void onExitArmedChanged(boolean armed) {
                last[0] = armed;
                changes[0]++;
            }
        });

        ui.armExit();
        assertTrue(ui.isExitArmed());
        assertTrue(last[0]);
        ui.armExit();
        assertEquals(1, changes[0]);
        ui.disarmExit();
        assertFalse(ui.isExitArmed());
        assertFalse(last[0]);
        assertEquals(2, changes[0]);
        ui.disarmExit();
        assertEquals(2, changes[0]);
    }

    @Test
    public void clearingPlaybackSceneKeepsSetupPage() {
        UiState ui = new UiState();
        ui.enterSetup();
        ui.hidePlaybackScene();
        assertEquals(UiState.Mode.SETUP, ui.mode());
    }

    @Test
    public void noListenerIsFine() {
        UiState ui = new UiState();
        ui.setExitListener(null);
        ui.armExit();
        assertTrue(ui.isExitArmed());
        ui.disarmExit();
        assertFalse(ui.isExitArmed());
    }
}
