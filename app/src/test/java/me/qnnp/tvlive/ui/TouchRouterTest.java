package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * 批次 5a：触摸路由——点行预览、同一行再点/双击全屏、长按清掉点按链。
 */
public class TouchRouterTest {

    private static final long DOUBLE_TAP_MS = 300L;

    private TouchRouter router;

    @Before
    public void setUp() {
        router = new TouchRouter(DOUBLE_TAP_MS);
    }

    @Test
    public void firstTapPreviewsWhenChannelIsNotPlaying() {
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(3, false, 1000L));
        assertEquals(3, router.lastPosition());
    }

    @Test
    public void tapOnPlayingRowGoesFullscreen() {
        // 「同一行再点」：该行已经在播，第一下点就全屏（不要求双击间隔）。
        assertEquals(TouchRouter.Tap.FULLSCREEN, router.tap(3, true, 1000L));
    }

    @Test
    public void secondTapOnSameRowWithinWindowIsFullscreen() {
        // 第一下切预览（播放状态还没变化），第二下在窗口内 → 全屏。
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(5, false, 1000L));
        assertEquals(TouchRouter.Tap.FULLSCREEN, router.tap(5, false, 1000L + DOUBLE_TAP_MS));
    }

    @Test
    public void secondTapAfterWindowIsANewSingleTap() {
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(5, false, 1000L));
        assertEquals(TouchRouter.Tap.PREVIEW,
                router.tap(5, false, 1000L + DOUBLE_TAP_MS + 1));
    }

    @Test
    public void tapOnAnotherRowDoesNotCountAsDoubleTap() {
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(1, false, 1000L));
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(2, false, 1050L));
        assertEquals(2, router.lastPosition());
    }

    @Test
    public void longPressClearsTapChain() {
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(4, false, 1000L));
        assertTrue(router.longPress(4));
        // 长按后紧接着点同一行：不能误判成双击。
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(4, false, 1100L));
    }

    @Test
    public void longPressOnEmptyPositionIsIgnored() {
        assertFalse(router.longPress(-1));
    }

    @Test
    public void resetBreaksDoubleTap() {
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(2, false, 1000L));
        router.reset();
        assertEquals(-1, router.lastPosition());
        assertEquals(TouchRouter.Tap.PREVIEW, router.tap(2, false, 1010L));
    }

    @Test
    public void doubleTapWindowIsConfigurable() {
        assertEquals(DOUBLE_TAP_MS, router.doubleTapMs());
    }
}
