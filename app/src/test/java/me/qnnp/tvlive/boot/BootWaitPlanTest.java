package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 开机等待计划：桌面就绪、连续确认、超时保底、查询失败兜底与信号去重。
 */
public class BootWaitPlanTest {

    private static final String HOME = "com.example.launcher";
    private static final String APP = "me.qnnp.tvlive";

    @Test
    public void waitsWhenLauncherIsNotOnTopYet() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 0, 0L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals(0, step.streak());
        assertEquals(BootWaitPlan.POLL_MS, step.delayMs());
        assertEquals(BootWaitPlan.Mode.TASK, step.mode());
    }

    @Test
    public void firstLauncherSightingNeedsAnotherPoll() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 0, 2_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals(1, step.streak());
        assertEquals(BootWaitPlan.POLL_MS, step.delayMs());
    }

    @Test
    public void twoConsecutiveLauncherSightingsSettleThenLaunch() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 1, 4_000L);
        assertEquals(BootWaitPlan.Action.SETTLE, step.action());
        assertEquals(2, step.streak());
        assertEquals(BootWaitPlan.SETTLE_MS, step.delayMs());
        assertEquals(BootWaitPlan.Mode.TASK, step.mode());
    }

    @Test
    public void settleAlarmLaunchesWhenLauncherStillOnTop() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 2, 6_000L);
        assertEquals("settle 醒来后不再无限重排", BootWaitPlan.Action.LAUNCH, step.action());
        assertEquals(BootWaitPlan.Mode.TASK, step.mode());
    }

    @Test
    public void settleAlarmRepollsWhenAnotherAppTookOver() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 2, 6_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals(0, step.streak());
    }

    @Test
    public void anotherAppResetsTheLauncherStreak() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 1, 4_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals("桌面被别的窗口盖住后必须重新确认两次", 0, step.streak());
    }

    @Test
    public void launchesWhenSettleWouldExceedMaxWait() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 1,
                BootWaitPlan.MAX_WAIT_MS - 500L);
        assertEquals(BootWaitPlan.Action.LAUNCH, step.action());
        assertEquals(0L, step.delayMs());
        assertEquals(BootWaitPlan.Mode.MAX, step.mode());
    }

    @Test
    public void launchesAtMaxWaitWhenLauncherNeverAppears() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 0, BootWaitPlan.MAX_WAIT_MS);
        assertEquals(BootWaitPlan.Action.LAUNCH, step.action());
        assertEquals(BootWaitPlan.Mode.MAX, step.mode());
    }

    @Test
    public void capsNextPollAtMaxWait() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 0,
                BootWaitPlan.MAX_WAIT_MS - 1_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals("不越过 20 秒上限", 1_000L, step.delayMs());
    }

    @Test
    public void fallsBackWhenHomeCannotBeResolved() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, null, 0, 2_000L);
        assertEquals(BootWaitPlan.Action.FALLBACK, step.action());
        assertEquals(BootWaitPlan.FALLBACK_MS - 2_000L, step.delayMs());
        assertEquals(BootWaitPlan.Mode.ELAPSED, step.mode());
    }

    @Test
    public void elapsedFallbackLaunchesAfterTwelveSeconds() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, null, 0, BootWaitPlan.FALLBACK_MS);
        assertEquals(BootWaitPlan.Action.LAUNCH, step.action());
        assertEquals(BootWaitPlan.Mode.ELAPSED, step.mode());
    }

    @Test
    public void fallsBackWhenTopActivityIsUnavailable() {
        // getRunningTasks 无权限/被 ROM 屏蔽：top=null，即便知道桌面包名也不猜。
        BootWaitPlan.Step step = BootWaitPlan.next(null, HOME, 0, 5_000L);
        assertEquals(BootWaitPlan.Action.FALLBACK, step.action());
        assertEquals(7_000L, step.delayMs());
        assertEquals(BootWaitPlan.Mode.ELAPSED, step.mode());
    }

    @Test
    public void repeatedBootSignalDoesNotRestartTheWait() {
        assertFalse(BootWaitPlan.shouldStartWait(3_000L, 1_000L));
        assertTrue(BootWaitPlan.shouldStartWait(1_000L + BootWaitPlan.MAX_WAIT_MS, 1_000L));
    }

    @Test
    public void rebootOrFirstSignalStartsTheWait() {
        assertTrue(BootWaitPlan.shouldStartWait(500L, BootWaitPlan.NO_WAIT));
        // 重启后 elapsedRealtime 归零，旧记录不能挡住新计划。
        assertTrue(BootWaitPlan.shouldStartWait(10L, 90_000L));
    }

    @Test
    public void clampsNegativeWaitToZero() {
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 0, -5_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals(BootWaitPlan.POLL_MS, step.delayMs());
    }

    @Test
    public void checksTopActivityEverySecond() {
        assertEquals("批次 21：1 秒轮询，更早起拉", 1_000L, BootWaitPlan.POLL_MS);
        BootWaitPlan.Step step = BootWaitPlan.next(APP, HOME, 0, 3_000L);
        assertEquals(BootWaitPlan.Action.POLL, step.action());
        assertEquals(1_000L, step.delayMs());
    }

    @Test
    public void settlesForThreeSecondsBeforeLaunch() {
        assertEquals("批次 21：留给桌面 AllApps 稳定 3 秒", 3_000L, BootWaitPlan.SETTLE_MS);
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 1, 1_000L);
        assertEquals(BootWaitPlan.Action.SETTLE, step.action());
        assertEquals(3_000L, step.delayMs());
    }

    @Test
    public void keepsThreeSecondSettleRightUpToMaxWait() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 1,
                BootWaitPlan.MAX_WAIT_MS - BootWaitPlan.SETTLE_MS);
        assertEquals("17s + 3s 刚好等于 20s 上限，仍可 settle", BootWaitPlan.Action.SETTLE,
                step.action());
        assertEquals(BootWaitPlan.SETTLE_MS, step.delayMs());
    }

    @Test
    public void launchesWhenThreeSecondSettleExceedsMaxWait() {
        BootWaitPlan.Step step = BootWaitPlan.next(HOME, HOME, 1,
                BootWaitPlan.MAX_WAIT_MS - BootWaitPlan.SETTLE_MS + 1L);
        assertEquals(BootWaitPlan.Action.LAUNCH, step.action());
        assertEquals(BootWaitPlan.Mode.MAX, step.mode());
    }
}
