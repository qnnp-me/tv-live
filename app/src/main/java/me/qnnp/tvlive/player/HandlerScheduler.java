package me.qnnp.tvlive.player;

import android.os.Handler;
import android.os.Looper;

/**
 * 生产用调度器：委托 main thread 的 Handler。
 *
 * <p>只在有 main Looper 的进程里使用；JVM 单测使用假 Scheduler，不实例化本类。</p>
 */
public final class HandlerScheduler implements Scheduler {

    private final Handler handler;

    public HandlerScheduler() {
        this(new Handler(Looper.getMainLooper()));
    }

    HandlerScheduler(Handler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    @Override
    public Cancelable schedule(final Runnable action, long delayMs) {
        if (action == null) {
            throw new IllegalArgumentException("action == null");
        }
        handler.postDelayed(action, delayMs < 0L ? 0L : delayMs);
        return new Cancelable() {
            private boolean cancelled;

            @Override
            public void cancel() {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                handler.removeCallbacks(action);
            }
        };
    }
}
