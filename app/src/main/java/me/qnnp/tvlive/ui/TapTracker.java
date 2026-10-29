package me.qnnp.tvlive.ui;

import android.view.MotionEvent;

/**
 * 单点轻触跟踪（批次 5b 修复）。
 *
 * <p>全屏时视频区铺满，触摸可能落在 {@code SurfaceView} 上、也可能被父容器的
 * click 抢走。这里把「按下 → 抬起且位移 ≤ touchSlop」判成一次点按，供
 * {@link VideoTapTouch}（挂在 SurfaceView 上）和布局的 {@code dispatchTouchEvent}
 * （父层兜底）共用同一套判定，避免两处规则不一致。</p>
 */
final class TapTracker {

    private final int touchSlop;

    private float downX;
    private float downY;
    private boolean tracking;

    TapTracker(int touchSlop) {
        this.touchSlop = touchSlop;
    }

    /**
     * 喂入一个事件。
     *
     * @return true 表示这次 {@code ACTION_UP} 构成一次点按（只会在抬起时返回一次）
     */
    boolean track(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                tracking = true;
                return false;
            case MotionEvent.ACTION_UP: {
                boolean tap = tracking
                        && VideoTapTouch.isTap(event.getX() - downX, event.getY() - downY,
                                touchSlop);
                tracking = false;
                return tap;
            }
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            default:
                return false;
        }
    }

    void reset() {
        tracking = false;
    }
}
