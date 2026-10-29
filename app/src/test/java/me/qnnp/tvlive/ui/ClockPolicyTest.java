package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 批次 18：时钟角标开关与页面/导航状态的组合。 */
public class ClockPolicyTest {

    @Test
    public void fullscreenWithoutOverlaysShowsClock() {
        assertTrue(ClockPolicy.allowed(true, false, false, false, true, false));
    }

    @Test
    public void hiddenNavOnTvShowsClock() {
        assertTrue("电视导航收起时显示整/半点角标",
                ClockPolicy.allowed(true, false, false, false, false, false));
    }

    @Test
    public void shownNavHidesClock() {
        assertFalse(ClockPolicy.allowed(true, false, false, false, false, true));
    }

    @Test
    public void menuSettingsAndSetupHideClock() {
        assertFalse(ClockPolicy.allowed(true, true, false, false, true, false));
        assertFalse(ClockPolicy.allowed(true, false, true, false, true, false));
        assertFalse(ClockPolicy.allowed(true, false, false, true, true, false));
    }

    @Test
    public void disabledSettingAlwaysHidesClock() {
        assertFalse(ClockPolicy.allowed(false, false, false, false, true, false));
        assertFalse(ClockPolicy.allowed(false, false, false, false, false, false));
    }
}
