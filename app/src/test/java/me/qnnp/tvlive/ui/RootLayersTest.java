package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 8：root 层叠放顺序 + 90% 半透明覆盖层 + 视频恒全宽。
 *
 * <p>导航/设置页必须在视频之上（覆盖而不是挤压视频），叠在它们上面的浮层仍最高；
 * 90% 黑底要是半透明，才能看到下面继续播放的视频。</p>
 */
public class RootLayersTest {

    @Test
    public void navAndSettingsCoverVideoButDoNotSqueezeIt() {
        assertTrue("导航必须压在视频之上", RootLayers.navAboveVideo());
        assertTrue("设置页必须压在视频之上", RootLayers.pageAboveVideo());
        assertEquals(1920, RootLayers.videoWidth(1920));
        assertEquals(1080, RootLayers.videoWidth(1080));
        // 导航/设置页显示与否不参与视频宽度计算：始终整屏宽。
        assertEquals(1920, RootLayers.videoWidth(1920));
        assertEquals(0, RootLayers.videoWidth(0));
        assertEquals(0, RootLayers.videoWidth(-1920));
    }

    @Test
    public void overlaysStayAboveNavAndPages() {
        assertTrue(RootLayers.overlaysAboveNav());
        assertTrue(RootLayers.isAbove(RootLayers.OVERLAYS, RootLayers.PAGE));
        assertTrue(RootLayers.isAbove(RootLayers.PAGE, RootLayers.VIDEO));
        assertTrue(RootLayers.isAbove(RootLayers.NAV, RootLayers.VIDEO));
        assertFalse(RootLayers.isAbove(RootLayers.NAV, RootLayers.OVERLAYS));
        assertFalse(RootLayers.isAbove(RootLayers.VIDEO, RootLayers.NAV));
    }

    @Test
    public void navAndSettingsScrimIsNinetyPercentBlack() {
        assertEquals(0xE6, RootLayers.SCRIM_ALPHA);
        assertEquals(90, RootLayers.alphaPercent(RootLayers.SCRIM_ALPHA));
        assertTrue("必须是半透明（能看到视频）",
                RootLayers.isTranslucent(RootLayers.SCRIM_ALPHA));
        assertEquals(0xE60B1012, RootLayers.scrimColor());
    }

    @Test
    public void opacityHelpersHandleEdges() {
        assertEquals(0, RootLayers.alphaPercent(0));
        assertEquals(100, RootLayers.alphaPercent(0xFF));
        assertEquals(0, RootLayers.alphaPercent(-1));
        assertEquals(100, RootLayers.alphaPercent(300));
        assertFalse(RootLayers.isTranslucent(0));
        assertFalse(RootLayers.isTranslucent(0xFF));
        assertTrue(RootLayers.isTranslucent(0x01));
        assertTrue(RootLayers.isTranslucent(0xFE));
    }
}
