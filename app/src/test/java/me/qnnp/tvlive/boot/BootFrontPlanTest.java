package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 拉起后的前台确认计划：自己在前台、被桌面盖住、次数上限、查询失败、用户退出。
 */
public class BootFrontPlanTest {

    private static final String SELF = "me.qnnp.tvlive";
    private static final String LAUNCHER = "com.tcl.bama.launcher";

    @Test
    public void selfOnTopEndsImmediately() {
        BootFrontPlan.Step step = BootFrontPlan.next(SELF, SELF, 1, 0, false);
        assertEquals(BootFrontPlan.Action.OK, step.action());
        assertEquals(0L, step.delayMs());
    }

    @Test
    public void launcherOnTopBringsBackAndSchedulesNext() {
        BootFrontPlan.Step step = BootFrontPlan.next(LAUNCHER, SELF, 1, 0, false);
        assertEquals(BootFrontPlan.Action.BRING, step.action());
        assertEquals(2, step.attempt());
        assertEquals(BootFrontPlan.INTERVAL_MS, step.delayMs());
    }

    @Test
    public void keepsBringingUntilAttemptLimit() {
        BootFrontPlan.Step step = BootFrontPlan.next(LAUNCHER, SELF,
                BootFrontPlan.MAX_ATTEMPTS - 1, 0, false);
        assertEquals(BootFrontPlan.Action.BRING, step.action());
        assertEquals(BootFrontPlan.MAX_ATTEMPTS, step.attempt());
    }

    @Test
    public void givesUpAtAttemptLimit() {
        BootFrontPlan.Step step = BootFrontPlan.next(LAUNCHER, SELF,
                BootFrontPlan.MAX_ATTEMPTS, 0, false);
        assertEquals(BootFrontPlan.Action.GIVE_UP, step.action());
        assertEquals(0L, step.delayMs());
    }

    @Test
    public void selfOnTopWinsEvenAtAttemptLimit() {
        BootFrontPlan.Step step = BootFrontPlan.next(SELF, SELF,
                BootFrontPlan.MAX_ATTEMPTS, 0, false);
        assertEquals(BootFrontPlan.Action.OK, step.action());
    }

    @Test
    public void unknownTopRetriesWithoutBringing() {
        BootFrontPlan.Step step = BootFrontPlan.next(null, SELF, 1, 0, false);
        assertEquals(BootFrontPlan.Action.RETRY, step.action());
        assertEquals(1, step.unknown());
        assertEquals(2, step.attempt());
        assertEquals(BootFrontPlan.INTERVAL_MS, step.delayMs());
    }

    @Test
    public void unknownTopGivesUpAfterThreeTries() {
        assertEquals(BootFrontPlan.Action.RETRY,
                BootFrontPlan.next(null, SELF, 2, 1, false).action());
        BootFrontPlan.Step step = BootFrontPlan.next(null, SELF, 3, 2, false);
        assertEquals(BootFrontPlan.Action.GIVE_UP, step.action());
        assertEquals(BootFrontPlan.MAX_UNKNOWN, step.unknown());
        assertEquals(0L, step.delayMs());
    }

    @Test
    public void userExitedEndsTheChain() {
        BootFrontPlan.Step step = BootFrontPlan.next(LAUNCHER, SELF, 1, 0, true);
        assertEquals(BootFrontPlan.Action.OK, step.action());
        assertEquals(0L, step.delayMs());
    }

    @Test
    public void windowIsAboutTwentyFourSeconds() {
        assertEquals(12, BootFrontPlan.MAX_ATTEMPTS);
        assertEquals(2_000L, BootFrontPlan.INTERVAL_MS);
        assertEquals(BootFrontPlan.MAX_ATTEMPTS * BootFrontPlan.INTERVAL_MS, 24_000L);
    }

    @Test
    public void homeSwitchIsResentAcrossFrontCheckWindow() {
        assertTrue("第一次确认（App 已被盖住）就补发切源",
                BootFrontPlan.shouldSendHomeSwitch(1));
        assertTrue(BootFrontPlan.shouldSendHomeSwitch(
                BootFrontPlan.HOME_SWITCH_MAX_ATTEMPT));
        assertFalse("窗口外不再每 2 秒刷广播",
                BootFrontPlan.shouldSendHomeSwitch(BootFrontPlan.HOME_SWITCH_MAX_ATTEMPT + 1));
        assertTrue("计数异常也不能漏掉第一次", BootFrontPlan.shouldSendHomeSwitch(0));
        assertEquals("覆盖整个前台确认窗口，等 sourcemananger 起来",
                12, BootFrontPlan.HOME_SWITCH_MAX_ATTEMPT);
    }

    @Test
    public void clampsInvalidCounters() {
        BootFrontPlan.Step step = BootFrontPlan.next(LAUNCHER, SELF, 0, -5, false);
        assertEquals(BootFrontPlan.Action.BRING, step.action());
        assertEquals("attempt 至少从 1 算", 2, step.attempt());
        assertEquals(0, step.unknown());
    }
}
