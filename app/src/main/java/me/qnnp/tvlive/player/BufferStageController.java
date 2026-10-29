package me.qnnp.tvlive.player;

/**
 * 缓冲档位计时：首次 READY 后 3 秒从起播档切稳定档；每次 prepare/stop 回到起播档。
 *
 * <p>纯 Java（注入 {@link Scheduler}），ExoPlaybackEngine 只负责在对应回调里调用，
 * 单测可用假调度器验证「READY 后 3 秒」「再次切台重置」「关闭开关不切换」。</p>
 *
 * <p>卡顿/重缓冲会再次收到 READY，但切换只做一次；stop/release 才重置，
 * 因此抖动期间不会退回浅缓冲。</p>
 */
public final class BufferStageController {

    /** 规格：首次 READY 后延迟 3 秒进入稳定档。 */
    public static final long STABLE_DELAY_MS = 3000L;

    private final AdaptiveLoadControl loadControl;
    private final Scheduler scheduler;
    private final boolean adaptive;

    private Scheduler.Cancelable pending;
    /** 本轮播放是否已经排过/完成过稳定档切换，避免重缓冲 READY 重复计时。 */
    private boolean stableScheduled;

    public BufferStageController(AdaptiveLoadControl loadControl, Scheduler scheduler) {
        if (loadControl == null) {
            throw new IllegalArgumentException("loadControl == null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler == null");
        }
        this.loadControl = loadControl;
        this.scheduler = scheduler;
        this.adaptive = loadControl.isAdaptive();
    }

    /** prepare 之前调用：取消计时并回到起播档。 */
    public void onPrepare() {
        cancel();
        loadControl.resetToStartup();
        stableScheduled = false;
    }

    /** Exo 第一次或后续 STATE_READY 都可调用；只有首次会排 3 秒计时。 */
    public void onReady() {
        if (!adaptive || stableScheduled) {
            return;
        }
        stableScheduled = true;
        pending = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                pending = null;
                loadControl.setStable(true);
            }
        }, STABLE_DELAY_MS);
    }

    /** stop/release：取消计时并重置；卡顿不会走到这里（卡顿仍停在稳定档）。 */
    public void onStop() {
        cancel();
        loadControl.resetToStartup();
        stableScheduled = false;
    }

    public boolean isStable() {
        return loadControl.isStable();
    }

    public boolean isAdaptive() {
        return adaptive;
    }

    private void cancel() {
        Scheduler.Cancelable current = pending;
        pending = null;
        if (current != null) {
            current.cancel();
        }
    }
}
