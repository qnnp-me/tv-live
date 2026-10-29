package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import me.qnnp.tvlive.prefs.Aspect;

/**
 * 批次 18：画面比例三档的尺寸计算。
 *
 * <p>覆盖 1920×1080（16:9）、1440×1080（4:3 容器）、2340×1080（19.5:9 容器）：
 * fit 不越界且等比；stretch 等于容器；crop 完全覆盖容器且保持 16:9、居中裁边。</p>
 */
public class VideoGeometryTest {

    /** crop 用向上取整，宽×9 与高×16 最多差十几像素。 */
    private static final int RATIO_TOLERANCE = 20;

    @Test
    public void fitSixteenByNineContainerFillsExactly() {
        VideoGeometry.Size size = VideoGeometry.fit(1920, 1080);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
    }

    @Test
    public void fitFourByThreeContainerLetterboxesVertically() {
        // 1440×1080（4:3 容器）：宽受限，16:9 高度 = 810，上下各留 135 黑边。
        VideoGeometry.Size size = VideoGeometry.fit(1440, 1080);
        assertEquals(1440, size.width);
        assertEquals(810, size.height);
        assertEquals(135, VideoGeometry.centerOffset(1080, size.height));
    }

    @Test
    public void fitWidePhoneContainerPillarboxes() {
        // 2340×1080（19.5:9 容器）：高受限，16:9 宽度 = 1920，左右各留 210 黑边。
        VideoGeometry.Size size = VideoGeometry.fit(2340, 1080);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
        assertEquals(210, VideoGeometry.centerOffset(2340, size.width));
    }

    @Test
    public void stretchAlwaysEqualsContainer() {
        int[][] containers = {{1920, 1080}, {1440, 1080}, {2340, 1080}, {1080, 1920}};
        for (int[] c : containers) {
            VideoGeometry.Size size = VideoGeometry.stretch(c[0], c[1]);
            assertEquals("stretch width " + c[0] + "x" + c[1], c[0], size.width);
            assertEquals("stretch height " + c[0] + "x" + c[1], c[1], size.height);
        }
    }

    @Test
    public void cropSixteenByNineContainerIsUnchanged() {
        VideoGeometry.Size size = VideoGeometry.crop(1920, 1080);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
        assertEquals(0, VideoGeometry.centerOffset(1920, size.width));
        assertEquals(0, VideoGeometry.centerOffset(1080, size.height));
    }

    @Test
    public void cropFourByThreeContainerCropsSides() {
        // 4:3 容器：放大到 1920×1080 才能盖满，左右各裁 240。
        VideoGeometry.Size size = VideoGeometry.crop(1440, 1080);
        assertEquals(1920, size.width);
        assertEquals(1080, size.height);
        assertEquals(-240, VideoGeometry.centerOffset(1440, size.width));
        assertEquals(0, VideoGeometry.centerOffset(1080, size.height));
        assertSixteenByNine(size);
    }

    @Test
    public void cropWidePhoneContainerCropsTopAndBottom() {
        // 19.5:9 容器：宽先贴边（2340），高度放大到 1317，上下共裁 237。
        VideoGeometry.Size size = VideoGeometry.crop(2340, 1080);
        assertEquals(2340, size.width);
        assertTrue("crop 高度必须盖满容器", size.height >= 1080);
        assertEquals(0, VideoGeometry.centerOffset(2340, size.width));
        assertTrue("crop 顶部偏移为负", VideoGeometry.centerOffset(1080, size.height) < 0);
        assertSixteenByNine(size);
    }

    @Test
    public void cropAlwaysCoversContainerAndKeepsRatio() {
        int[][] containers = {
                {1920, 1080}, {1440, 1080}, {2340, 1080}, {1280, 720},
                {1080, 1920}, {3840, 2160}, {720, 1008},
        };
        for (int[] c : containers) {
            VideoGeometry.Size size = VideoGeometry.crop(c[0], c[1]);
            assertTrue("crop width covers " + c[0] + "x" + c[1], size.width >= c[0]);
            assertTrue("crop height covers " + c[0] + "x" + c[1], size.height >= c[1]);
            assertTrue("crop visible " + c[0] + "x" + c[1], !size.isEmpty());
            assertSixteenByNine(size);
            assertTrue("crop 居中裁边", VideoGeometry.centerOffset(c[0], size.width) <= 0);
            assertTrue("crop 居中裁边", VideoGeometry.centerOffset(c[1], size.height) <= 0);
        }
    }

    @Test
    public void invalidContainerReturnsEmpty() {
        assertTrue(VideoGeometry.fit(0, 1080).isEmpty());
        assertTrue(VideoGeometry.fit(1920, 0).isEmpty());
        assertTrue(VideoGeometry.stretch(-1, 1080).isEmpty());
        assertTrue(VideoGeometry.crop(1920, -1).isEmpty());
    }

    @Test
    public void sizeFollowsAspectSetting() {
        assertEquals(1920, VideoGeometry.size(Aspect.FIT, 2340, 1080).width);
        assertEquals(2340, VideoGeometry.size(Aspect.STRETCH, 2340, 1080).width);
        assertEquals(2340, VideoGeometry.size(Aspect.CROP, 2340, 1080).width);
        assertEquals("null 按默认 FIT", 1920,
                VideoGeometry.size(null, 2340, 1080).width);
    }

    @Test
    public void centerOffsetRoundsDownForOddSlack() {
        assertEquals(2, VideoGeometry.centerOffset(5, 1));
        assertEquals(-1, VideoGeometry.centerOffset(4, 5));
        assertEquals(0, VideoGeometry.centerOffset(4, 4));
    }

    private static void assertSixteenByNine(VideoGeometry.Size size) {
        int diff = Math.abs(size.width * VideoGeometry.VIDEO_HEIGHT
                - size.height * VideoGeometry.VIDEO_WIDTH);
        assertTrue("expected 16:9 for " + size + " (diff=" + diff + ")",
                diff <= RATIO_TOLERANCE);
    }
}
