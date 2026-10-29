package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 4a：NavVisibility 只负责 8 秒自动收起计时；模式迁移由 {@link UiState} 承担。
 */
public class NavVisibilityTest {

    @Test
    public void defaultsToNotArmed() {
        NavVisibility timer = new NavVisibility();
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.isDue(0L));
        assertFalse(timer.consume(8000L));
    }

    @Test
    public void armStartsEightSecondTimer() {
        NavVisibility timer = new NavVisibility();
        timer.arm(1000L);
        assertEquals(1000L + NavVisibility.AUTO_HIDE_MS, timer.autoHideAt());

        assertFalse(timer.isDue(8999L));
        assertTrue(timer.isDue(9000L));
        assertFalse(timer.consume(8999L));
        assertTrue(timer.consume(9000L));
        // 消费后停止计时，不会重复触发。
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.consume(20000L));
    }

    @Test
    public void noteKeyResetsOnlyWhenArmed() {
        NavVisibility timer = new NavVisibility();
        timer.noteKey(0L);
        assertEquals(-1L, timer.autoHideAt());

        timer.arm(0L);
        timer.noteKey(5000L);
        assertEquals(13000L, timer.autoHideAt());
        assertFalse(timer.consume(12999L));
        assertTrue(timer.consume(13000L));
    }

    @Test
    public void blockingStopsTimerAndRestartsOnRelease() {
        NavVisibility timer = new NavVisibility();
        timer.arm(0L);
        timer.setBlocked(true, 1000L);
        assertTrue(timer.isBlocked());
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.isDue(999999L));

        // 阻塞期间按键不开始计时。
        timer.noteKey(2000L);
        assertEquals(-1L, timer.autoHideAt());

        timer.setBlocked(false, 20000L);
        assertEquals(28000L, timer.autoHideAt());
        assertFalse(timer.consume(27999L));
        assertTrue(timer.consume(28000L));
    }

    @Test
    public void armedWhileBlockedStartsOnRelease() {
        NavVisibility timer = new NavVisibility();
        timer.setBlocked(true, 0L);
        timer.arm(100L);
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.consume(100000L));

        timer.setBlocked(false, 100000L);
        assertTrue(timer.consume(108000L));
    }

    @Test
    public void cancelStopsTimer() {
        NavVisibility timer = new NavVisibility();
        timer.arm(0L);
        timer.cancel();
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.consume(8000L));
    }

    /** 批次 18：自动收起时长可配置为 5/8/15 秒。 */
    @Test
    public void configuredDurationsArmAtFiveEightFifteenSeconds() {
        long[] durations = {5000L, 8000L, 15000L};
        for (long duration : durations) {
            NavVisibility timer = new NavVisibility();
            timer.setAutoHideMs(duration);
            assertEquals(duration, timer.autoHideMs());
            assertTrue(timer.isAutoHideEnabled());

            timer.arm(1000L);
            assertEquals(1000L + duration, timer.autoHideAt());
            assertFalse("未到点不收起", timer.consume(999L + duration));
            assertTrue("到点收起", timer.consume(1000L + duration));
        }
    }

    /** 0 表示不自动收起：永不到期，Back/OK 仍由调用方手动收起。 */
    @Test
    public void zeroDurationNeverAutoHides() {
        NavVisibility timer = new NavVisibility();
        timer.setAutoHideMs(0L);
        assertFalse(timer.isAutoHideEnabled());

        timer.arm(0L);
        assertEquals(-1L, timer.autoHideAt());
        assertFalse(timer.isDue(24L * 60L * 60L * 1000L));
        assertFalse(timer.consume(24L * 60L * 60L * 1000L));

        timer.noteKey(5000L);
        assertEquals(-1L, timer.autoHideAt());
        timer.setBlocked(false, 6000L);
        assertEquals(-1L, timer.autoHideAt());
    }

    @Test
    public void changingDurationWhileArmedStopsZeroTimer() {
        NavVisibility timer = new NavVisibility();
        timer.arm(0L);
        assertEquals(NavVisibility.AUTO_HIDE_MS, timer.autoHideAt());

        timer.setAutoHideMs(0L);
        assertEquals("改成不收起后立即取消截止时间", -1L, timer.autoHideAt());
        assertFalse(timer.consume(999999L));
    }

    @Test
    public void repeatedBlockChangesAreNoOps() {
        NavVisibility timer = new NavVisibility();
        timer.arm(0L);
        timer.setBlocked(false, 1000L);
        // 状态没变，不应重置计时。
        assertEquals(NavVisibility.AUTO_HIDE_MS, timer.autoHideAt());
        timer.setBlocked(true, 2000L);
        timer.setBlocked(true, 3000L);
        assertEquals(-1L, timer.autoHideAt());
    }
}
