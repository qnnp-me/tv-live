package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5b 修复：视频区点按判定（点按出 OSD/返回按钮）。
 *
 * <p>模拟器上全屏后点画面没有反应：触摸落到了 {@code SurfaceView} 上，
 * 父容器的 click 收不到。给 SurfaceView 挂的触摸监听用这条判定把「轻触」与滑动区分开。</p>
 */
public class VideoTapTouchTest {

    @Test
    public void stillPointIsTap() {
        assertTrue(VideoTapTouch.isTap(0f, 0f, 24));
        assertTrue(VideoTapTouch.isTap(12f, -18f, 24));
        assertTrue(VideoTapTouch.isTap(-24f, 24f, 24));
    }

    @Test
    public void movementBeyondSlopIsNotTap() {
        assertFalse(VideoTapTouch.isTap(25f, 0f, 24));
        assertFalse(VideoTapTouch.isTap(0f, -40f, 24));
    }

    @Test
    public void negativeSlopIsTreatedAsZero() {
        assertTrue(VideoTapTouch.isTap(0f, 0f, -8));
        assertFalse(VideoTapTouch.isTap(1f, 0f, -8));
    }
}
