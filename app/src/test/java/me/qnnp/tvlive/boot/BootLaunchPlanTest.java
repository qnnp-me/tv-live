package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 开机拉起后的缓起播与「已在前台不重复拉起」。 */
public class BootLaunchPlanTest {

    @Test
    public void bootLaunchDefersFirstPlayback() {
        assertTrue(BootLaunchPlan.shouldDeferPlayback(true));
        assertEquals(3_000L, BootLaunchPlan.BOOT_PLAYBACK_DELAY_MS);
    }

    @Test
    public void normalLaunchPlaysImmediately() {
        assertFalse(BootLaunchPlan.shouldDeferPlayback(false));
    }

    @Test
    public void skipsLaunchWhenAlreadyForeground() {
        assertFalse(BootLaunchPlan.shouldLaunch(true));
        assertTrue(BootLaunchPlan.shouldLaunch(false));
    }

    @Test
    public void directLaunchSkipsHomeWaitAndUsesOneSecondDelay() {
        assertTrue("批次 23 默认直接拉起", BootLaunchPlan.DIRECT_LAUNCH);
        assertEquals(1_000L, BootLaunchPlan.BOOT_DIRECT_DELAY_MS);
        assertEquals(BootLaunchPlan.BOOT_DIRECT_DELAY_MS,
                BootLaunchPlan.directLaunchDelayMs());
        assertTrue("直接拉起延迟必须小于等桌面的兜底窗口",
                BootLaunchPlan.BOOT_DIRECT_DELAY_MS < BootWaitPlan.FALLBACK_MS);
    }

    @Test
    public void waitPathStillReachableWhenSwitchOff() {
        assertTrue(BootLaunchPlan.directLaunchDelayMs(true) >= 0L);
        assertEquals("开关关闭必须回到等桌面路径",
                BootLaunchPlan.NO_DIRECT_DELAY,
                BootLaunchPlan.directLaunchDelayMs(false));
    }

    @Test
    public void tclHomeSwitchDelaysLaunchByTwoAndAHalfSeconds() {
        assertTrue("切源成功才等", BootLaunchPlan.shouldDelayAfterHomeSwitch(true));
        assertEquals("第一次开机 sourcemananger 起得晚，1.5 秒常常不够",
                2_500L, BootLaunchPlan.BOOT_HOME_SWITCH_DELAY_MS);
        assertEquals(BootLaunchPlan.BOOT_HOME_SWITCH_DELAY_MS,
                BootLaunchPlan.launchDelayAfterHomeSwitch(true));
    }

    @Test
    public void nonTclLaunchesWithoutHomeSwitchDelay() {
        assertFalse("非 TCL 发不出切源广播，不用等", BootLaunchPlan.shouldDelayAfterHomeSwitch(false));
        assertEquals(0L, BootLaunchPlan.launchDelayAfterHomeSwitch(false));
    }
}
