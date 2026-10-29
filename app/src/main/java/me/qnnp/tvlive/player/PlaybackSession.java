package me.qnnp.tvlive.player;

/**
 * 引擎会话令牌：后台 prepare 与 UI 线程的 stop/release 之间的唯一同步点。
 *
 * <p>每次 {@link #begin()} 开启新会话；{@link #stop()} / {@link #release()} 立即作废
 * 之前所有会话。后台任务与 MediaPlayer 回调在真正执行前用 {@link #isCurrent(int)}
 * 检查，迟到的旧会话直接丢弃。纯 Java，单测可直接覆盖。</p>
 */
final class PlaybackSession {

    private int session;
    private boolean stopped;
    private boolean released;

    /** 开启一次 prepare 会话；已 release 的引擎不允许再次 prepare。 */
    synchronized int begin() {
        if (released) {
            throw new IllegalStateException("engine 已 release");
        }
        stopped = false;
        return ++session;
    }

    /** 指定会话是否仍是当前有效会话。 */
    synchronized boolean isCurrent(int token) {
        return !released && !stopped && token == session;
    }

    /** 是否已停止/释放（任何旧会话都失效）。 */
    synchronized boolean isDead() {
        return released || stopped;
    }

    synchronized boolean isReleased() {
        return released;
    }

    /** 停止：作废当前会话，之后仍可再次 begin。 */
    synchronized void stop() {
        if (!released) {
            stopped = true;
        }
        session++;
    }

    /** 释放：永久作废。 */
    synchronized void release() {
        released = true;
        stopped = true;
        session++;
    }
}
