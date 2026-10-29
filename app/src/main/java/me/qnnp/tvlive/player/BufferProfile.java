package me.qnnp.tvlive.player;

/**
 * 一套 ExoPlayer 缓冲档位参数（毫秒）：最小/最大缓冲、起播所需缓冲、重缓冲后起播所需缓冲。
 *
 * <p>纯数据类，方便单测直接断言参数映射；真正生效的是
 * {@link AdaptiveLoadControl} 里的两个 {@code DefaultLoadControl} 委托。</p>
 */
public final class BufferProfile {

    /** 起播档：切台/首次 prepare 用。400ms 即可出画，后续再加深。 */
    public static final BufferProfile STARTUP = new BufferProfile(1200, 8000, 400, 800);

    /** 稳定档：首帧之后延迟 3 秒切换，放宽缓冲抗网络抖动。 */
    public static final BufferProfile STABLE = new BufferProfile(4000, 15000, 1200, 1500);

    /** 首帧加速关闭时的原参数（与 1.0.6 一致）。 */
    public static final BufferProfile ORIGINAL = new BufferProfile(3000, 12000, 1000, 2000);

    private final int minBufferMs;
    private final int maxBufferMs;
    private final int bufferForPlaybackMs;
    private final int bufferForPlaybackAfterRebufferMs;

    public BufferProfile(int minBufferMs, int maxBufferMs,
            int bufferForPlaybackMs, int bufferForPlaybackAfterRebufferMs) {
        this.minBufferMs = minBufferMs;
        this.maxBufferMs = maxBufferMs;
        this.bufferForPlaybackMs = bufferForPlaybackMs;
        this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs;
    }

    public int minBufferMs() {
        return minBufferMs;
    }

    public int maxBufferMs() {
        return maxBufferMs;
    }

    public int bufferForPlaybackMs() {
        return bufferForPlaybackMs;
    }

    public int bufferForPlaybackAfterRebufferMs() {
        return bufferForPlaybackAfterRebufferMs;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BufferProfile)) {
            return false;
        }
        BufferProfile that = (BufferProfile) other;
        return minBufferMs == that.minBufferMs
                && maxBufferMs == that.maxBufferMs
                && bufferForPlaybackMs == that.bufferForPlaybackMs
                && bufferForPlaybackAfterRebufferMs == that.bufferForPlaybackAfterRebufferMs;
    }

    @Override
    public int hashCode() {
        int result = minBufferMs;
        result = 31 * result + maxBufferMs;
        result = 31 * result + bufferForPlaybackMs;
        result = 31 * result + bufferForPlaybackAfterRebufferMs;
        return result;
    }

    @Override
    public String toString() {
        return "BufferProfile{" + minBufferMs + "/" + maxBufferMs + "/"
                + bufferForPlaybackMs + "/" + bufferForPlaybackAfterRebufferMs + "}";
    }
}
