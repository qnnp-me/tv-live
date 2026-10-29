package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5b：全屏 OSD + 半透明返回按钮的 2.5s 可见窗口。
 *
 * <p>点画面唤出 OSD（含返回按钮），2.5s 后一起收起；OSD 期间的再次点按立即收起，
 * 不等计时。系统返回/返回手势不走这里（KeyRouter → Activity，直接退全屏回列表）。</p>
 */
public class FullscreenOsdTest {

    @Test
    public void osdStaysForTwoAndHalfSeconds() {
        FullscreenOsd osd = new FullscreenOsd();
        assertEquals(2500L, osd.showMs());

        osd.show(1000L);
        assertTrue(osd.isVisible(1000L));
        assertTrue(osd.isVisible(3499L));
        // 到点自动收起（返回按钮与 OSD 共用这个窗口）。
        assertFalse(osd.isVisible(3500L));
        assertEquals(Long.MIN_VALUE, osd.hideAt());
    }

    @Test
    public void tappingAgainHidesImmediately() {
        FullscreenOsd osd = new FullscreenOsd();
        osd.show(0L);
        assertTrue(osd.isVisible(400L));
        osd.hide();
        assertFalse(osd.isVisible(400L));
        // 收起后再点重新给满 2.5s。
        osd.show(400L);
        assertTrue(osd.isVisible(2800L));
        assertFalse(osd.isVisible(2900L));
    }

    @Test
    public void showRestartsTheWindow() {
        FullscreenOsd osd = new FullscreenOsd();
        osd.show(0L);
        osd.show(2000L);
        assertTrue(osd.isVisible(4400L));
        assertFalse(osd.isVisible(4500L));
    }

    @Test
    public void resetClearsChrome() {
        FullscreenOsd osd = new FullscreenOsd();
        osd.show(100L);
        osd.reset();
        assertFalse(osd.isVisible(100L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroWindowIsRejected() {
        new FullscreenOsd(0L);
    }
}
