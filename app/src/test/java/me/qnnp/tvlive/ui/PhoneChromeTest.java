package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5b：手机主界面触摸「设置」入口与全屏返回按钮的可见性。
 *
 * <p>设置入口：手机主界面才有；全屏/设置页/首启页/电视都不显示。
 * 全屏返回按钮：只有手机全屏且 OSD 可见时才有（电视保持遥控路径，没有触摸按钮）。</p>
 */
public class PhoneChromeTest {

    @Test
    public void settingsEntryShowsOnPhoneMainScreen() {
        assertTrue(PhoneChrome.settingsEntryVisible(true, false, false, false));
    }

    @Test
    public void settingsEntryHiddenInFullscreenSettingsSetupAndOnTv() {
        assertFalse(PhoneChrome.settingsEntryVisible(true, true, false, false));
        assertFalse(PhoneChrome.settingsEntryVisible(true, false, true, false));
        assertFalse(PhoneChrome.settingsEntryVisible(true, false, false, true));
        // 电视：底栏没有触摸按钮（遥控 MENU 照旧）。
        assertFalse(PhoneChrome.settingsEntryVisible(false, false, false, false));
    }

    @Test
    public void backChromeOnlyOnPhoneFullscreenWithOsd() {
        assertTrue(PhoneChrome.backChromeVisible(true, true, true));
        assertFalse(PhoneChrome.backChromeVisible(true, true, false));
        assertFalse(PhoneChrome.backChromeVisible(true, false, true));
        assertFalse(PhoneChrome.backChromeVisible(false, true, true));
    }
}
