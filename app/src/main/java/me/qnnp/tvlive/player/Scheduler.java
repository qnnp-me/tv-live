package me.qnnp.tvlive.player;

/**
 * 延迟调度抽象：控制器用它安排网络错误后的重试，测试替换成假实现即可免线程/免 Android。
 *
 * <p>生产实现见 {@link HandlerScheduler}；回调在 main thread 执行。</p>
 */
public interface Scheduler {

    /** 可取消的挂起任务。 */
    interface Cancelable {
        /** 取消；已执行或已取消时无副作用。 */
        void cancel();
    }

    /**
     * 延迟 {@code delayMs} 毫秒后在 main thread 执行 {@code action}。
     *
     * @return 用于取消的句柄，不会为 null
     */
    Cancelable schedule(Runnable action, long delayMs);
}
