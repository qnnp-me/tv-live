package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 批次 4a：按键路由——模式优先级、OK 两段/长按、Back 语义、数字/CH/音量/INFO。
 */
public class KeyRouterTest {

    private static final long LONG_PRESS_MS = 500L;
    private static final long EXIT_WINDOW_MS = 2000L;

    private UiState ui;
    private FakeHost host;
    private FakeTimers timers;
    private KeyRouter router;

    @Before
    public void setUp() {
        ui = new UiState();
        host = new FakeHost();
        timers = new FakeTimers();
        router = new KeyRouter(ui, host, timers, LONG_PRESS_MS, EXIT_WINDOW_MS);
    }

    // ------------------------------------------------------------ 隐藏态 OK

    @Test
    public void hiddenOkPopsNavOnDownAndDoesNotRepeatOnUp() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(1, host.select);
        // Activity 的 onSelect 会把模式切到 NAV_SHOWN；这里模拟这一点。
        ui.showNav(timers.now());

        assertTrue(up(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(1, host.select);
        assertEquals(0, host.longSelect);
    }

    @Test
    public void phonePortraitFullscreenOkBehavesLikeHidden() {
        ui.enterFullscreen();
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        ui.exitFullscreen();
        ui.showNav(timers.now());
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void tvHiddenOkPopsNavAndNeverEntersFullscreen() {
        UiState tvUi = new UiState();
        FakeHost tvHost = new FakeHost(tvUi);
        KeyRouter tvRouter = new KeyRouter(tvUi, tvHost, timers, LONG_PRESS_MS, EXIT_WINDOW_MS);

        tvRouter.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0);
        assertEquals(1, tvHost.select);
        // TV 宿主 onSelect：隐藏态 → 弹导航；不进入 FULLSCREEN。
        assertEquals(UiState.Mode.NAV_SHOWN, tvUi.mode());
        assertFalse(tvUi.isFullscreen());

        tvRouter.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0);
        // DOWN 已弹导航，UP 不重复处理。
        assertEquals(1, tvHost.select);

        // 导航态再按一次 OK：焦点=播放台无操作（不出 OSD、不收起导航），仍不产生 FULLSCREEN。
        timers.advance(400L);
        tvRouter.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0);
        tvRouter.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0);
        assertEquals(2, tvHost.select);
        assertEquals(UiState.Mode.NAV_SHOWN, tvUi.mode());
        assertFalse(tvUi.isFullscreen());
    }

    @Test
    public void okUpWithinSuppressWindowIsConsumedEvenWithoutPoppedFlag() {
        // 5c 兜底：DOWN 已弹导航后，UP 即使 okPoppedNav 已被清掉也要落在抑制窗口内。
        assertTrue(down(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(1, host.select);

        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);

        timers.advance(100L);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void okUpAfterSuppressWindowFiresNormalSelect() {
        assertTrue(down(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(1, host.select);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);

        timers.advance(400L);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(2, host.select);
    }

    @Test
    public void navShownOkFiresOnceOnUp() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(0, host.select);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        assertEquals(0, host.longSelect);
    }

    @Test
    public void okRepeatDoesNotFireExtraSelect() {
        ui.showNav(0L);
        router.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0);
        router.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1);
        router.handle(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 2);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void longPressInHiddenPopsNavAndSuppressesShortSelect() {
        // 宿主不切模式（模拟弹导航失败/纯机制测试）：长按应回调 onLongSelect。
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        timers.advance(LONG_PRESS_MS);
        assertEquals(1, host.longSelect);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void phonePortraitLongPressInFullscreenPopsNav() {
        ui.enterFullscreen();
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        timers.advance(LONG_PRESS_MS);
        assertEquals(1, host.longSelect);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void longPressInShownNavHasNoAction() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        timers.advance(LONG_PRESS_MS);
        assertEquals(0, host.longSelect);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        // 长按后抬起不再触发短按。
        assertEquals(0, host.select);
    }

    // ------------------------------------------------------------------ Back

    @Test
    public void backInMenuOnlyClosesMenu() {
        ui.showNav(0L);
        ui.openMenu();
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.back);
        assertEquals(0, host.exit);
        assertFalse(ui.isExitArmed());
    }

    @Test
    public void phonePortraitBackInFullscreenReturnsToHiddenScene() {
        ui.enterFullscreen();
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.back);
        assertEquals(0, host.exit);
    }

    @Test
    public void backInShownNavCollapses() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.back);
        assertEquals(0, host.exit);
    }

    @Test
    public void tvBackFromShownNavCollapsesWithoutFullscreen() {
        UiState tvUi = new UiState();
        FakeHost tvHost = new FakeHost(tvUi);
        KeyRouter tvRouter = new KeyRouter(tvUi, tvHost, timers, LONG_PRESS_MS, EXIT_WINDOW_MS);
        tvUi.showNav(0L);

        tvRouter.handle(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0);

        assertEquals(1, tvHost.back);
        assertEquals(UiState.Mode.NAV_HIDDEN, tvUi.mode());
        assertFalse(tvUi.isFullscreen());
    }

    @Test
    public void backInHiddenNeedsSecondPressWithinWindow() {
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(0, host.back);
        assertEquals(0, host.exit);
        assertTrue(ui.isExitArmed());

        timers.advance(EXIT_WINDOW_MS - 1);
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.exit);
        assertFalse(ui.isExitArmed());
    }

    @Test
    public void backWindowExpires() {
        down(KeyEvent.KEYCODE_BACK);
        assertTrue(ui.isExitArmed());
        timers.advance(EXIT_WINDOW_MS);
        assertFalse(ui.isExitArmed());

        down(KeyEvent.KEYCODE_BACK);
        assertEquals(0, host.exit);
        assertTrue(ui.isExitArmed());
    }

    @Test
    public void otherKeyCancelsExitWindow() {
        down(KeyEvent.KEYCODE_BACK);
        assertTrue(ui.isExitArmed());
        down(KeyEvent.KEYCODE_DPAD_UP);
        assertFalse(ui.isExitArmed());
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(0, host.exit);
    }

    @Test
    public void backRepeatIsIgnored() {
        router.handle(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0);
        router.handle(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 1);
        assertEquals(0, host.exit);
    }

    // ---------------------------------------------------------------- 菜单

    @Test
    public void menuPriorityOverPhoneFullscreenAndNav() {
        ui.enterFullscreen();
        ui.openMenu();
        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.directions);
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.back);
        assertEquals(0, host.exit);

        ui.closeMenu();
        ui.showNav(0L);
        ui.openMenu();
        down(KeyEvent.KEYCODE_MENU);
        assertEquals(1, host.menu);
        // 菜单里数字/CH 吞掉，不落到换台/焦点。
        down(KeyEvent.KEYCODE_5);
        assertEquals(0, host.digits);
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        assertEquals(0, host.channelSteps);
    }

    @Test
    public void menuOkActivatesOnUp() {
        ui.showNav(0L);
        ui.openMenu();
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(0, host.select);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
    }

    @Test
    public void menuLeftClosesThroughDirection() {
        ui.openMenu();
        down(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(1, host.directions);
        assertEquals(-1, host.lastDx);
    }

    // -------------------------------------------------------- 设置页 / 首启页

    @Test
    public void settingsOnlyKeepsPageKeys() {
        ui.enterSettings();

        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.directions);
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.back);

        down(KeyEvent.KEYCODE_MENU);
        down(KeyEvent.KEYCODE_5);
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        down(KeyEvent.KEYCODE_INFO);
        down(KeyEvent.KEYCODE_PROG_RED);
        assertEquals(0, host.menu);
        assertEquals(0, host.digits);
        assertEquals(0, host.channelSteps);
        assertEquals(0, host.infos);
        assertEquals(0, host.favorites);
        assertEquals(0, host.userInputs);
    }

    @Test
    public void setupOnlyKeepsPageKeysAndDoubleBackExit() {
        ui.enterSetup();

        down(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        up(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, host.select);
        down(KeyEvent.KEYCODE_MENU);
        assertEquals(1, host.menu);
        down(KeyEvent.KEYCODE_DPAD_LEFT);
        down(KeyEvent.KEYCODE_5);
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        down(KeyEvent.KEYCODE_INFO);
        assertEquals(0, host.directions);
        assertEquals(0, host.digits);
        assertEquals(0, host.channelSteps);
        assertEquals(0, host.infos);
        assertEquals(0, host.userInputs);

        down(KeyEvent.KEYCODE_BACK);
        assertEquals(0, host.exit);
        assertTrue(ui.isExitArmed());
        down(KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.exit);
    }

    // ------------------------------------------------------- 方向 / 音量 / CH

    /**
     * 小修：隐藏态的上下键按遥控习惯换台——上 = CH+（下一台），下 = CH−（上一台）；
     * 左右键仍是音量，组栏/焦点移动不受影响。
     */
    @Test
    public void hiddenVerticalInvertsChannelStepHorizontalChangesVolume() {
        down(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(1, host.directions);
        assertEquals(0, host.lastDx);
        assertEquals(1, host.lastDy);
        assertEquals(0, host.volumes);

        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(2, host.directions);
        assertEquals(-1, host.lastDy);
        assertEquals(0, host.volumes);

        down(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(2, host.directions);
        assertEquals(1, host.volumes);
        assertEquals(-1, host.lastVolume);
    }

    @Test
    public void phoneFullscreenInvertsVerticalChannelStep() {
        ui.enterFullscreen();
        down(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(1, host.directions);
        assertEquals(1, host.lastDy);
        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(2, host.directions);
        assertEquals(-1, host.lastDy);
        assertEquals(0, host.volumes);

        down(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(1, host.volumes);
        assertEquals(1, host.lastVolume);
    }

    /** CH+/− 语义不变：CH+ 下一台（+1）、CH− 上一台（-1），隐藏/全屏都如此。 */
    @Test
    public void channelKeySemanticsStayUnchanged() {
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        assertEquals(1, host.lastChannelStep);
        down(KeyEvent.KEYCODE_CHANNEL_DOWN);
        assertEquals(-1, host.lastChannelStep);

        ui.enterFullscreen();
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        assertEquals(1, host.lastChannelStep);
        down(KeyEvent.KEYCODE_CHANNEL_DOWN);
        assertEquals(-1, host.lastChannelStep);
        assertEquals(4, host.channelSteps);
    }

    /**
     * 批次 5b：手机硬件音量键不拦截。KeyRouter 对 VOLUME_UP/DOWN 返回 false
     * （Activity 还会在手机模式下先把它们交回系统），系统音量条照常工作；
     * 遥控左右键的自绘音量条只保留给隐藏/全屏态（电视行为不变）。
     */
    @Test
    public void hardwareVolumeKeysAreNotConsumed() {
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        // 任意场景：硬件音量键都不该被 App 吃掉。
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertEquals(0, host.volumes);

        ui.enterFullscreen();
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(up(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertEquals(0, host.volumes);

        ui.showNav(0L);
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(0, host.volumes);
    }

    @Test
    public void remoteLeftRightStillAdjustVolumeWhenNavHidden() {
        // TV 回归：隐藏态左右键仍然是自绘音量条（不是硬件音量键）。
        down(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(1, host.volumes);
        assertEquals(1, host.lastVolume);
        down(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(2, host.volumes);
        assertEquals(-1, host.lastVolume);
    }

    @Test
    public void shownNavDirectionsStayInNav() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(-1, host.lastDx);
        down(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(1, host.lastDx);
        assertEquals(2, host.directions);
        assertEquals(0, host.volumes);
    }

    /** 导航/列表不取反：上仍是上一行（dy=-1）、下仍是下一行（dy=+1）。 */
    @Test
    public void shownNavVerticalKeepsRowDirection() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(-1, host.lastDy);
        assertEquals(0, host.lastDx);
        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.lastDy);
        assertEquals(2, host.directions);
        assertEquals(0, host.volumes);
    }

    @Test
    public void channelKeysMoveFocusOrChannelByMode() {
        down(KeyEvent.KEYCODE_CHANNEL_UP);
        assertEquals(1, host.channelSteps);
        assertEquals(1, host.lastChannelStep);

        ui.showNav(0L);
        down(KeyEvent.KEYCODE_CHANNEL_DOWN);
        assertEquals(2, host.channelSteps);
        assertEquals(-1, host.lastChannelStep);
    }

    @Test
    public void digitsAndInfoAndFavoriteForward() {
        down(KeyEvent.KEYCODE_5);
        assertEquals(1, host.digits);
        assertEquals(5, host.lastDigit);
        down(KeyEvent.KEYCODE_INFO);
        assertEquals(1, host.infos);
        down(KeyEvent.KEYCODE_PROG_RED);
        down(KeyEvent.KEYCODE_BOOKMARK);
        assertEquals(2, host.favorites);
    }

    @Test
    public void repeatsOnlyRepeatWhereExpected() {
        down(KeyEvent.KEYCODE_INFO);
        router.handle(KeyEvent.KEYCODE_INFO, KeyEvent.ACTION_DOWN, 1);
        assertEquals(1, host.infos);

        down(KeyEvent.KEYCODE_5);
        router.handle(KeyEvent.KEYCODE_5, KeyEvent.ACTION_DOWN, 1);
        assertEquals(1, host.digits);

        down(KeyEvent.KEYCODE_DPAD_DOWN);
        router.handle(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 1);
        assertEquals(2, host.directions);
    }

    // ---------------------------------------------------------------- 杂项

    @Test
    public void userInputCancelsAutoNextOnlyInPlaybackModes() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.userInputs);

        ui.enterSettings();
        down(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.userInputs);
    }

    @Test
    public void unknownKeysFallThrough() {
        assertFalse(down(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(up(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(down(KeyEvent.KEYCODE_A));
    }

    @Test
    public void upOfConsumedKeysIsSwallowed() {
        assertTrue(up(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(up(KeyEvent.KEYCODE_5));
        assertTrue(up(KeyEvent.KEYCODE_BACK));
        assertTrue(up(KeyEvent.KEYCODE_CHANNEL_UP));
        assertFalse(up(KeyEvent.KEYCODE_A));
    }

    @Test
    public void cancelStopsLongPress() {
        ui.showNav(0L);
        down(KeyEvent.KEYCODE_DPAD_CENTER);
        router.cancel();
        timers.advance(LONG_PRESS_MS);
        assertEquals(0, host.longSelect);
    }

    // ---------------------------------------------------------------- helpers

    private boolean down(int code) {
        return router.handle(code, KeyEvent.ACTION_DOWN, 0);
    }

    private boolean up(int code) {
        return router.handle(code, KeyEvent.ACTION_UP, 0);
    }

    private static final class FakeHost implements KeyRouter.Host {
        /** 非空时模拟 TV 宿主的模式迁移：隐藏态 OK 弹导航，导航内 OK 不收起导航。 */
        private final UiState tvUi;

        FakeHost() {
            this(null);
        }

        FakeHost(UiState tvUi) {
            this.tvUi = tvUi;
        }

        int select;
        int longSelect;
        int back;
        int menu;
        int directions;
        int lastDx;
        int lastDy;
        int digits;
        int lastDigit;
        int channelSteps;
        int lastChannelStep;
        int volumes;
        int lastVolume;
        int infos;
        int favorites;
        int exit;
        int userInputs;

        @Override
        public void onSelect() {
            select++;
            if (tvUi != null && !tvUi.isNavShown()) {
                // 真实 Activity：隐藏态 OK 弹导航；导航里焦点=播放台的 OK 无操作。
                tvUi.showNav(0L);
            }
        }

        @Override
        public void onLongSelect() {
            longSelect++;
        }

        @Override
        public void onBack() {
            back++;
            if (tvUi != null) {
                tvUi.hideNav();
            }
        }

        @Override
        public void onMenu() {
            menu++;
        }

        @Override
        public void onDirection(int dx, int dy) {
            directions++;
            lastDx = dx;
            lastDy = dy;
        }

        @Override
        public void onDigit(int digit) {
            digits++;
            lastDigit = digit;
        }

        @Override
        public void onChannelStep(int delta) {
            channelSteps++;
            lastChannelStep = delta;
        }

        @Override
        public void onVolume(int delta) {
            volumes++;
            lastVolume = delta;
        }

        @Override
        public void onInfo() {
            infos++;
        }

        @Override
        public void onExit() {
            exit++;
        }

        @Override
        public void onFavorite() {
            favorites++;
        }

        @Override
        public void onUserInput() {
            userInputs++;
        }

        @Override
        public boolean isLayoutFullscreen() {
            return false;
        }
    }

    private static final class FakeTimers implements KeyRouter.Timers {

        private static final class Task {
            final Runnable runnable;
            final long dueAt;

            Task(Runnable runnable, long dueAt) {
                this.runnable = runnable;
                this.dueAt = dueAt;
            }
        }

        private long now;
        private final List<Task> tasks = new ArrayList<Task>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            tasks.add(new Task(runnable, now + delayMs));
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            Iterator<Task> iterator = tasks.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().runnable == runnable) {
                    iterator.remove();
                }
            }
        }

        /** 推进时间并执行所有到期任务（包括任务中新排的任务）。 */
        void advance(long ms) {
            now += ms;
            boolean ran = true;
            while (ran) {
                ran = false;
                for (Iterator<Task> iterator = tasks.iterator(); iterator.hasNext(); ) {
                    Task task = iterator.next();
                    if (task.dueAt <= now) {
                        iterator.remove();
                        task.runnable.run();
                        ran = true;
                    }
                }
            }
        }
    }
}
