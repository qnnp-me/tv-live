package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 8：导航宽度 + 全宽视频的 16:9 等比 fit。
 *
 * <p>导航是根层覆盖层，宽 44%，但不再占用/挤压视频：视频容器始终全宽，画面在容器内
 * 按 16:9 等比缩放并居中，不拉伸。批次 6 的「导航并排 / 视频缩到右侧 56%」已撤销。</p>
 */
public class TvNavGeometryTest {

    /** 16:9 的比例误差容忍：四舍五入后宽×9 与高×16 最多差几像素。 */
    private static final int RATIO_TOLERANCE = 8;

    @Test
    public void navIs44PercentOfWidth() {
        // 1920×0.44 = 844.8 → 845（向上取整，不窄于 44%）。
        assertEquals(845, TvNavGeometry.navWidth(1920));
        assertEquals(476, TvNavGeometry.navWidth(1080));
        assertEquals(317, TvNavGeometry.navWidth(720));
        assertEquals(564, TvNavGeometry.navWidth(1280));
    }

    @Test
    public void navNeverWiderThanScreen() {
        for (int width = 320; width <= 4096; width++) {
            int nav = TvNavGeometry.navWidth(width);
            assertTrue("nav must be positive at " + width, nav > 0);
            assertTrue("nav must stay inside screen at " + width, nav <= width);
        }
    }

    @Test
    public void unknownWidthDoesNotProduceNegativeValues() {
        assertEquals(0, TvNavGeometry.navWidth(0));
        assertEquals(0, TvNavGeometry.navWidth(-1920));
    }

    /** 批次 18：「导航宽度」三档（38% / 44% / 50%），向上取整。 */
    @Test
    public void navWidthSupportsThreeRatios() {
        assertEquals(730, TvNavGeometry.navWidth(1920, 0.38f));
        assertEquals(845, TvNavGeometry.navWidth(1920, 0.44f));
        assertEquals(960, TvNavGeometry.navWidth(1920, 0.50f));

        assertEquals(890, TvNavGeometry.navWidth(2340, 0.38f));
        assertEquals(1030, TvNavGeometry.navWidth(2340, 0.44f));
        assertEquals(1170, TvNavGeometry.navWidth(2340, 0.50f));

        assertEquals(411, TvNavGeometry.navWidth(1080, 0.38f));
        assertEquals(476, TvNavGeometry.navWidth(1080, 0.44f));
        assertEquals(540, TvNavGeometry.navWidth(1080, 0.50f));

        assertEquals("默认重载仍等于标准 44%", TvNavGeometry.navWidth(1920),
                TvNavGeometry.navWidth(1920, TvNavGeometry.NAV_WIDTH_RATIO));
    }

    @Test
    public void navWidthRatioIsClampedToScreen() {
        assertEquals(0, TvNavGeometry.navWidth(1920, 0f));
        assertEquals(0, TvNavGeometry.navWidth(1920, -0.5f));
        assertEquals("比例 >1 按整屏宽", 1920, TvNavGeometry.navWidth(1920, 1.5f));
    }

    // ------------------------------------------------------------ 16:9 等比 fit

    @Test
    public void fullWidthSixteenByNineFillsTheScreen() {
        // 1920×1080 整屏（1080p TV）：16:9 正好铺满，无黑边。
        assertEquals(1920, fitW(1920, 1080));
        assertEquals(1080, fitH(1920, 1080));
        assertSixteenByNine(1920, 1080);
    }

    @Test
    public void videoStaysFullWidthWithNavShown() {
        // 导航不再缩视频：同样的全宽容器，无论导航显隐画面尺寸一致。
        int nav = TvNavGeometry.navWidth(1920);
        assertEquals(845, nav);
        assertEquals(1920, fitW(1920, 1080));
        assertEquals(1080, fitH(1920, 1080));
        assertCentered(1920, 1080, 1920, 1080);
    }

    @Test
    public void narrowPhoneLandscapeKeepsFullWidthAndLosesHeight() {
        // 320 宽的小屏：宽受限，高度按比例缩到 180。
        assertEquals(320, fitW(320, 1080));
        assertEquals(180, fitH(320, 1080));
        assertSixteenByNine(320, 180);
        assertCentered(320, 1080, 320, 180);
    }

    @Test
    public void heightLimitedContainerLetterboxes() {
        // 容器比 16:9 更矮/更宽：取高度换算宽度（例如 1280×600），左右留黑边。
        assertEquals(1066, fitW(1280, 600));
        assertEquals(600, fitH(1280, 600));
        assertSixteenByNine(1066, 600);
        assertCentered(1280, 600, 1066, 600);
    }

    @Test
    public void fitNeverExceedsContainerAndKeepsRatio() {
        int[][] containers = {
                {1920, 1080}, {1075, 1080}, {320, 1080}, {1280, 600}, {2340, 1080},
                {720, 1008}, {1075, 1008}, {1920, 1008}, {405, 608},
        };
        for (int[] c : containers) {
            int w = fitW(c[0], c[1]);
            int h = fitH(c[0], c[1]);
            assertTrue("fit width <= container at " + c[0] + "x" + c[1], w <= c[0]);
            assertTrue("fit height <= container at " + c[0] + "x" + c[1], h <= c[1]);
            assertTrue("fit must be visible at " + c[0] + "x" + c[1], w > 0 && h > 0);
            assertSixteenByNine(w, h);
            assertCentered(c[0], c[1], w, h);
        }
    }

    @Test
    public void unknownContainerDoesNotProduceNegativeValues() {
        assertEquals(0, fitW(0, 1080));
        assertEquals(0, fitW(1075, 0));
        assertEquals(0, fitW(-1075, 1080));
        assertEquals(0, fitH(0, 1080));
        assertEquals(0, fitH(1075, 0));
        assertEquals(0, fitH(-1075, 1080));
    }

    // ------------------------------------------------------------------ 工具

    private static int fitW(int containerWidth, int containerHeight) {
        return TvNavGeometry.fitWidth(containerWidth, containerHeight);
    }

    private static int fitH(int containerWidth, int containerHeight) {
        return TvNavGeometry.fitHeight(fitW(containerWidth, containerHeight), containerHeight);
    }

    /** 宽高比必须是 16:9（允许取整误差），不允许出现接近 1:1 的拉伸。 */
    private static void assertSixteenByNine(int width, int height) {
        int diff = Math.abs(width * TvNavGeometry.VIDEO_HEIGHT
                - height * TvNavGeometry.VIDEO_WIDTH);
        assertTrue("expected 16:9 for " + width + "x" + height + " (diff=" + diff + ")",
                diff <= RATIO_TOLERANCE);
    }

    /**
     * 等比 fit 必须「尽量大且不越界」：至少一边贴边（宽或高用满），另一边留黑边；
     * 居中由 {@code FrameLayout.LayoutParams.gravity = CENTER} 完成，左右/上下
     * 黑边相差不超过 1px（取整）。
     */
    private static void assertCentered(int containerWidth, int containerHeight,
            int width, int height) {
        int slackX = containerWidth - width;
        int slackY = containerHeight - height;
        assertTrue("horizontal slack must be >= 0", slackX >= 0);
        assertTrue("vertical slack must be >= 0", slackY >= 0);
        assertTrue("fit must fill at least one axis", slackX == 0 || slackY == 0);
        int leftBar = slackX / 2;
        int rightBar = slackX - leftBar;
        int topBar = slackY / 2;
        int bottomBar = slackY - topBar;
        assertTrue("left/right bars must be equal", Math.abs(leftBar - rightBar) <= 1);
        assertTrue("top/bottom bars must be equal", Math.abs(topBar - bottomBar) <= 1);
    }
}
