package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5a：设备判定。sw&lt;600dp 为手机；电视通常 sw720dp，不能误判成手机。
 */
public class DeviceModeTest {

    @Test
    public void narrowScreensArePhones() {
        assertTrue(DeviceMode.isPhone(320));
        assertTrue(DeviceMode.isPhone(360));
        assertTrue(DeviceMode.isPhone(411));
        assertTrue(DeviceMode.isPhone(540));
        assertTrue(DeviceMode.isPhone(DeviceMode.PHONE_MAX_SW_DP - 1));
    }

    @Test
    public void boundaryAndTvWidthsAreNotPhones() {
        assertFalse(DeviceMode.isPhone(DeviceMode.PHONE_MAX_SW_DP));
        assertFalse(DeviceMode.isPhone(720));
        assertFalse(DeviceMode.isPhone(1080));
    }

    @Test
    public void portraitFlagDecidesPhoneLayout() {
        // 手机竖屏：PhoneLayout。
        assertTrue(DeviceMode.isPhonePortrait(411, true));
        // 手机横屏：复用电视布局。
        assertFalse(DeviceMode.isPhonePortrait(411, false));
        // 电视：横竖都不进手机布局（电视本身也不旋转）。
        assertFalse(DeviceMode.isPhonePortrait(720, true));
        assertFalse(DeviceMode.isPhonePortrait(720, false));
    }

    @Test
    public void deviceClassIsIndependentOfOrientationFlag() {
        // 判定只看最小宽度：旋转不改变「是手机」这件事，只改变用哪种布局。
        assertTrue(DeviceMode.isPhone(411));
        assertTrue(DeviceMode.isPhone(411));
    }

    @Test
    public void onlyPhonePortraitUsesIndependentFullscreen() {
        // 5c：TV 与手机横屏的「全屏」= 导航隐藏，只有手机竖屏保留 FULLSCREEN。
        assertTrue(DeviceMode.usesFullscreen(true, true));
        assertFalse(DeviceMode.usesFullscreen(true, false));
        assertFalse(DeviceMode.usesFullscreen(false, true));
        assertFalse(DeviceMode.usesFullscreen(false, false));
    }
}
