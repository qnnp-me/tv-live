package me.qnnp.tvlive.ui;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 视频区点按识别（批次 5b 修复）。
 *
 * <p>模拟器实测：全屏后直接点画面收不到 {@code videoStage} 的 click（触摸落到了
 * {@code SurfaceView} 上）。这里给 {@code SurfaceView} 自己挂一个触摸监听，
 * 把「按下 → 抬起且位移小于 touchSlop」判成一次点按并回调；同时消费整段手势，
 * 避免同一次点按再冒泡成父容器的 click（不会重复触发）。</p>
 *
 * <p>只识别单点轻触，不做滑动/长按：全屏手势交给系统（返回手势）与布局层。</p>
 */
final class VideoTapTouch implements View.OnTouchListener {

    private final TapTracker tracker;
    private final Runnable action;

    VideoTapTouch(Context context, Runnable action) {
        this.tracker = new TapTracker(ViewConfiguration.get(context).getScaledTouchSlop());
        this.action = action;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        // 无论是否点按都消费：同一次手势不能又被父容器算成 click。
        if (tracker.track(event) && action != null) {
            action.run();
        }
        return true;
    }

    /** 位移不超过 touchSlop 即点按（纯 Java，可在 JVM 单测）。 */
    static boolean isTap(float dx, float dy, int touchSlop) {
        int slop = Math.max(0, touchSlop);
        return Math.abs(dx) <= slop && Math.abs(dy) <= slop;
    }
}
