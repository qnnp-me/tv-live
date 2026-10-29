package me.qnnp.tvlive.player;

import android.view.Surface;

import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 统一播放引擎接口：ExoPlayer 与 MediaPlayer 两种实现，对控制器暴露同一套语义。
 *
 * <p>线程：全部回调都在 main thread 上发生。实现不得自己起线程回调。</p>
 *
 * <p>生命周期：{@code setSurface} → {@code prepare} → （成功）回调 onPrepared/onFirstFrame；
 * 失败回调 onError；播完回调 onEnded。{@code prepare} 可以多次调用以复用同一个引擎实例换台，
 * 实现需自行丢弃上一次没播完的回调。{@code stop}/{@code release} 之后不再回调；
 * {@code release} 之后不可再 prepare。</p>
 */
public interface PlaybackEngine {

    /**
     * 绑定渲染表面；{@code null} 表示当前没有可用 Surface（例如 Activity 进后台或旋转中）。
     * 实现不得因为 {@code null} 主动清屏或 detach SurfaceView。
     */
    void setSurface(Surface surface);

    /**
     * 设置音量（0–1，线性）。双画面 standby 恒为 0，首帧后瞬时切到 1，
     * 保证任何时刻最多只有一路出声。可在 {@code prepare} 前后调用；实现需记住并在
     * 引擎实例真正创建/复用时套用。
     */
    void setVolume(float volume);

    /**
     * 是否申请音频焦点。双画面里只有前台引擎 {@code true}，standby 传 {@code false}：
     * 两路都申请焦点会被系统当成两个播放器互抢，可能把前台 pause 掉。
     *
     * <p>默认空实现：不涉及焦点的引擎（MediaPlayer 兜底）保持原行为。</p>
     */
    default void setHandleAudioFocus(boolean handleAudioFocus) {
    }

    /**
     * 实时网速回调（批次 15）：切台/缓冲期间最多每 500ms 一次，主线程。
     *
     * <p>不产生网速的引擎（MediaPlayer 兜底）不回调，界面显示「—」。</p>
     */
    interface BandwidthListener {

        /** @param bytesPerSecond 最近 1 秒窗口平滑后的字节/秒；0 表示当前没有数据 */
        void onBandwidth(long bytesPerSecond);
    }

    /**
     * 绑定网速回调；可早于 {@code prepare} 调用，实现需记住并在内部 player 复用时继续生效。
     *
     * <p>默认空实现：不统计网速的引擎（MediaPlayer）不需要改。</p>
     */
    default void setBandwidthListener(BandwidthListener listener) {
    }

    /** 开始加载并播放；同一实例可多次调用（换地址/重试），实现需复用内部 player。 */
    void prepare(Variant variant, Callback callback);

    /** 停止播放但保留实例，便于随后 prepare 复用；重复调用安全。 */
    void stop();

    /** 释放全部资源；之后该实例不可再用，重复调用安全。 */
    void release();

    /**
     * 强制视频渲染器重新配置：不销毁 player，只把 VideoSurface 解绑再绑回去。
     * 默认空实现；Exo 在连续换源后可能不再出帧，用这个救回画面。
     */
    default void forceReconfigureVideo() {
    }

    /** 当前播放位置（毫秒）；不可用时返回 0。 */
    long getCurrentPosition();

    /** 引擎自认为正在播放；控制器以自己的状态为准。 */
    boolean isPlaying();

    /** 最近一帧视频真正渲染的 elapsedRealtime；不支持/未知返回 -1。 */
    default long lastVideoFrameAtMs() {
        return -1L;
    }

    /** 引擎事件回调。 */
    interface Callback {

        /** 资源就绪、可以出画面了。 */
        void onPrepared();

        /** 第一帧真正渲染完成（用于隐藏加载 OSD）。 */
        void onFirstFrame();

        /** 播放失败，已分类。 */
        void onError(PlaybackError error);

        /** 自然播放结束。 */
        void onEnded();
    }
}
