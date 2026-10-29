package me.qnnp.tvlive.player;

import android.view.Surface;

import java.util.List;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 播放控制器：切台、同频道多变体轮换、Exo/MediaPlayer 引擎切换。
 *
 * <p>不做 UI，不自己起线程：全部方法必须在 main thread 调用，引擎回调也在 main thread。</p>
 *
 * <p>每个 variant 的尝试顺序：先 Exo，再 MediaPlayer。</p>
 * <ul>
 *   <li>网络类错误（NETWORK/TIMEOUT/UNKNOWN）→ 原 variant 原引擎延迟重试，最多 2 次
 *       （间隔 1s、3s）；期间 onError(error, true) 但不换 variant。</li>
 *   <li>重试耗尽 → HTTP/TLS 错误同现有逻辑：不换引擎，下一个 variant。</li>
 *   <li>Exo 解码器/容器错误 → 不重试，同一 variant 换 MediaPlayer。</li>
 *   <li>MediaPlayer 任何错误 → 下一个 variant，回到 Exo。</li>
 *   <li>全部 variant 失败 → onAllVariantsFailed(channel, lastError)，只发一次；不自动切频道。</li>
 * </ul>
 *
 * <p>切台复用：目标引擎类型与当前相同时（Exo→Exo、MediaPlayer→MediaPlayer）不销毁实例，
 * 直接 {@code prepare()} 换源，SurfaceView 上的最后一帧保留到新台首帧；类型切换
 * （含 Exo→MediaPlayer 回退）才 stop+release 重建。网络重试同样复用实例。</p>
 *
 * <p>取消/重入：每次 play/stop/release 递增 generation 并取消挂起重试，每次尝试另有
 * attempt token；旧引擎/旧尝试的迟到回调被丢弃，同时校验引擎实例身份，三重保险。</p>
 */
public final class PlaybackController {

    /** 控制器对外状态。 */
    public enum PlaybackState {
        /** 未播放或已停止。 */
        IDLE,
        /** 正在准备/换 variant。 */
        PREPARING,
        /** 已就绪并开始播放。 */
        PLAYING,
        /** 所有 variant 都失败。 */
        ERROR
    }

    /** 控制器事件；全部在 main thread 回调。 */
    public interface Listener {

        /** 引擎就绪（onPrepared）。一次 play 中可能因换 variant 多次触发。 */
        void onChannelStarted(Channel channel, Variant variant);

        /** 第一帧渲染。 */
        void onFirstFrame(Channel channel);

        /** 中间失败，willRetry=true 表示还会继续尝试其他引擎/variant。 */
        void onError(PlaybackError error, boolean willRetry);

        /** 所有 variant 都失败；此后控制器进入 ERROR，等下一次 play/stop。 */
        void onAllVariantsFailed(Channel channel, PlaybackError lastError);

        /** 自然播放结束。 */
        void onEnded(Channel channel);
    }

    /** 未配置地址时的错误。 */
    private static final PlaybackError NO_VARIANT_ERROR =
            PlaybackError.of(PlaybackError.Kind.UNKNOWN, "该频道没有可用地址");

    /** 网络类错误在同一 variant 上的最大重试次数。 */
    private static final int MAX_NETWORK_RETRIES = 2;

    /** 第 1/2 次重试的延迟：1s、3s。 */
    private static final long[] RETRY_DELAYS_MS = {1000L, 3000L};

    private final EngineFactory factory;
    private final Scheduler scheduler;

    private SwitchMode switchMode = SwitchMode.KEEP;
    private boolean firstFrameBoost;
    /** 首帧加速切换后，下一次切台重建引擎让新缓冲档位生效。 */
    private boolean rebuildOnNextPlay;

    private Listener listener;
    private Surface surface;
    /** 实时网速回调；引擎复用/重建后都要重新套用（见 startAttempt）。 */
    private PlaybackEngine.BandwidthListener bandwidthListener;
    /** 目标音量；每次尝试套用到当前引擎，standby 恒为 0。 */
    private float volume = 1f;
    /** 是否申请音频焦点；双画面 standby 为 false，新建引擎时自动套用。 */
    private boolean handleAudioFocus = true;

    private Channel channel;
    private int variantIndex;
    private boolean usingMediaPlayer;
    private PlaybackEngine engine;
    /** 当前 engine 的类型；engine == null 时无意义。 */
    private boolean engineIsMediaPlayer;
    private PlaybackState state = PlaybackState.IDLE;
    private PlaybackError lastError;
    private int generation;
    /** 每次 startAttempt 递增；用于丢弃复用实例后上一次准备的回调。 */
    private int attemptSeq;

    /** 当前 variant 已经发起的网络重试次数；play/换 variant/换引擎时归零。 */
    private int retryCount;
    private Scheduler.Cancelable pendingRetry;

    public PlaybackController(EngineFactory factory) {
        this(factory, new HandlerScheduler());
    }

    public PlaybackController(EngineFactory factory, Scheduler scheduler) {
        if (factory == null) {
            throw new IllegalArgumentException("factory == null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler == null");
        }
        this.factory = factory;
        this.scheduler = scheduler;
    }

    /** 切台方式；{@link SwitchMode#DUAL} 由 {@code DualPlayback} 编排双引擎，控制器本身按
     *  {@link SwitchMode#KEEP} 处理（复用实例、不 release）。 */
    public void setSwitchMode(SwitchMode mode) {
        this.switchMode = mode == null || mode == SwitchMode.DUAL ? SwitchMode.KEEP : mode;
    }

    public SwitchMode getSwitchMode() {
        return switchMode;
    }

    /**
     * 下一次 {@link #play(Channel)} 前强制销毁并重建引擎。
     *
     * <p>批次 28：起播超时/厂商解码器卡住时，KEEP 复用会把坏的 player 状态带过去；
     * 这里只标记，真正释放发生在 play 里，避免在回调线程上直接 release。</p>
     */
    public void rebuildOnNextPlay() {
        rebuildOnNextPlay = true;
    }

    /** 首帧加速：影响下一次新建 Exo 引擎的缓冲档位；已建引擎会在下一台重建。 */
    public void setFirstFrameBoost(boolean enabled) {
        if (firstFrameBoost != enabled) {
            firstFrameBoost = enabled;
            rebuildOnNextPlay = true;
        }
    }

    public boolean isFirstFrameBoost() {
        return firstFrameBoost;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 实时网速回调（批次 15）：立即套用到当前引擎；之后复用/重建引擎时在
     * {@link #startAttempt()} 里重新套用。{@code null} 表示不再需要回调。
     */
    public void setBandwidthListener(PlaybackEngine.BandwidthListener listener) {
        this.bandwidthListener = listener;
        PlaybackEngine current = engine;
        if (current != null) {
            try {
                current.setBandwidthListener(listener);
            } catch (RuntimeException e) {
                // 引擎已坏也不能挡住设置变更；下一次 startAttempt 会再套用。
            }
        }
    }

    /** 可早于 play；新建引擎时自动套用；null 表示解绑。 */
    public void setSurface(Surface surface) {
        this.surface = surface;
        PlaybackEngine current = engine;
        if (current != null) {
            current.setSurface(surface);
        }
    }

    /**
     * 设置音量（0–1）：透传给当前引擎；引擎尚未创建或随后重建时在 {@link #startAttempt()}
     * 里自动套用。双画面 standby 先 {@code setVolume(0)} 再 play，首帧后才切 1。
     */
    /** 连续换源后视频不出帧时的兜底：不销毁 player，只重配渲染器。 */
    public void forceReconfigureVideo() {
        PlaybackEngine current = engine;
        if (current == null) {
            return;
        }
        try {
            current.forceReconfigureVideo();
        } catch (RuntimeException e) {
            // 引擎已坏时不能让兜底本身崩掉。
        }
    }

    public void setVolume(float volume) {
        this.volume = volume < 0f ? 0f : (volume > 1f ? 1f : volume);
        PlaybackEngine current = engine;
        if (current != null) {
            current.setVolume(this.volume);
        }
    }

    public float getVolume() {
        return volume;
    }

    /**
     * 是否申请音频焦点：双画面 standby 传 {@code false}，避免两路播放器互抢把前台 pause。
     * 已建引擎立即透传，之后新建的引擎在 {@link #startAttempt()} 里自动套用。
     */
    public void setHandleAudioFocus(boolean handleAudioFocus) {
        this.handleAudioFocus = handleAudioFocus;
        PlaybackEngine current = engine;
        if (current != null) {
            try {
                current.setHandleAudioFocus(handleAudioFocus);
            } catch (RuntimeException e) {
                // 引擎已坏也不能挡住角色切换；新引擎会再套用一次。
            }
        }
    }

    public boolean isHandleAudioFocus() {
        return handleAudioFocus;
    }

    /**
     * 从 variant 0 开始播放；取消挂起重试。
     *
     * <p>不在这里 stop+release：同类型引擎由 {@link #startAttempt()} 复用，
     * 避免切台瞬间清空 Surface。类型不同或尚未创建时才重建；「极速直切」模式下
     * 每次切台都先销毁旧引擎，回到 A 之前的行为。</p>
     */
    public void play(Channel channel) {
        generation++;
        cancelPendingRetry();
        // DIRECT 也复用引擎：每次切台都 release + new ExoPlayer 会反复创建/销毁
        // MediaCodec，TCL 上连续切台可能把解码器资源耗尽，之后所有台都起不来。
        // DIRECT 与 KEEP 的差别只在渲染目标（SurfaceView vs TextureView）。
        if (rebuildOnNextPlay) {
            stopCurrentEngine();
        }
        rebuildOnNextPlay = false;

        this.channel = channel;
        this.variantIndex = 0;
        this.usingMediaPlayer = false;
        this.retryCount = 0;
        this.lastError = null;
        this.state = PlaybackState.PREPARING;

        if (channel == null || channel.variants().isEmpty()) {
            failAll(channel, NO_VARIANT_ERROR);
            return;
        }
        startAttempt();
    }

    /** 停止并释放当前引擎；状态 IDLE。重复调用安全。 */
    public void stop() {
        generation++;
        cancelPendingRetry();
        stopCurrentEngine();
        state = PlaybackState.IDLE;
        channel = null;
        retryCount = 0;
        lastError = null;
    }

    /** 彻底释放；之后仍可再次 play（会新建引擎）。重复调用安全。 */
    public void release() {
        generation++;
        cancelPendingRetry();
        stopCurrentEngine();
        state = PlaybackState.IDLE;
        channel = null;
        retryCount = 0;
        lastError = null;
        listener = null;
        surface = null;
    }

    public boolean isPlaying() {
        return state == PlaybackState.PLAYING;
    }

    public long getCurrentPosition() {
        PlaybackEngine current = engine;
        if (current == null) {
            return 0;
        }
        try {
            return current.getCurrentPosition();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 最近一帧视频渲染时刻；引擎不支持时 -1。 */
    public long lastVideoFrameAtMs() {
        PlaybackEngine current = engine;
        if (current == null) {
            return -1L;
        }
        try {
            return current.lastVideoFrameAtMs();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    public PlaybackState getState() {
        return state;
    }

    /** 是否有挂起的网络重试；此时 state 为 PREPARING，引擎实例保留等待复用。 */
    public boolean isRetrying() {
        return pendingRetry != null;
    }

    /** 当前 variant 已发起的网络重试次数（0–2），主要用于日志与测试。 */
    public int getRetryCount() {
        return retryCount;
    }

    public Channel getCurrentChannel() {
        return channel;
    }

    /** 当前正在尝试（或已成功）的 variant；未播放时为 null。 */
    public Variant getCurrentVariant() {
        Channel current = channel;
        if (current == null) {
            return null;
        }
        List<Variant> variants = current.variants();
        if (variantIndex < 0 || variantIndex >= variants.size()) {
            return null;
        }
        return variants.get(variantIndex);
    }

    // ---------------------------------------------------------------- 尝试调度

    private void startAttempt() {
        final int attemptGeneration = generation;
        final Channel activeChannel = channel;
        if (activeChannel == null) {
            failAll(null, lastError != null ? lastError : NO_VARIANT_ERROR);
            return;
        }
        final List<Variant> variants = activeChannel.variants();
        if (variantIndex < 0 || variantIndex >= variants.size()) {
            failAll(activeChannel, lastError != null ? lastError : NO_VARIANT_ERROR);
            return;
        }
        final Variant variant = variants.get(variantIndex);

        // 同类型复用实例；类型切换才真正 stop+release 旧引擎。
        final PlaybackEngine attemptEngine = obtainEngine(usingMediaPlayer);
        final int attemptToken = ++attemptSeq;
        attemptEngine.setSurface(surface);
        attemptEngine.setVolume(volume);
        attemptEngine.setHandleAudioFocus(handleAudioFocus);
        attemptEngine.setBandwidthListener(bandwidthListener);
        attemptEngine.prepare(variant, new PlaybackEngine.Callback() {
            @Override
            public void onPrepared() {
                if (!isCurrent(attemptGeneration, attemptToken, attemptEngine)) {
                    return;
                }
                state = PlaybackState.PLAYING;
                Listener l = listener;
                if (l != null) {
                    l.onChannelStarted(activeChannel, variant);
                }
            }

            @Override
            public void onFirstFrame() {
                if (!isCurrent(attemptGeneration, attemptToken, attemptEngine)) {
                    return;
                }
                Listener l = listener;
                if (l != null) {
                    l.onFirstFrame(activeChannel);
                }
            }

            @Override
            public void onError(PlaybackError error) {
                if (!isCurrent(attemptGeneration, attemptToken, attemptEngine)) {
                    return;
                }
                handleError(error);
            }

            @Override
            public void onEnded() {
                if (!isCurrent(attemptGeneration, attemptToken, attemptEngine)) {
                    return;
                }
                state = PlaybackState.IDLE;
                Listener l = listener;
                if (l != null) {
                    l.onEnded(activeChannel);
                }
            }
        });
    }

    /** 引擎回调只认当前 generation + 当前尝试 + 当前引擎实例。 */
    private boolean isCurrent(int attemptGeneration, int attemptToken, PlaybackEngine attemptEngine) {
        return attemptGeneration == generation
                && attemptToken == attemptSeq
                && engine == attemptEngine;
    }

    /**
     * 取得本次尝试要用的引擎：类型相同就复用，否则销毁重建。
     * 复用是保留 Surface 最后一帧的关键——不 stop、不 release、不换 Surface。
     */
    private PlaybackEngine obtainEngine(boolean mediaPlayer) {
        PlaybackEngine current = engine;
        if (current != null && engineIsMediaPlayer == mediaPlayer) {
            return current;
        }
        stopCurrentEngine();
        PlaybackEngine created = mediaPlayer
                ? factory.createMediaPlayer()
                : factory.createExo(firstFrameBoost);
        engine = created;
        engineIsMediaPlayer = mediaPlayer;
        return created;
    }

    private void handleError(PlaybackError error) {
        PlaybackError failure = error != null
                ? error
                : PlaybackError.of(PlaybackError.Kind.UNKNOWN, "播放失败");
        lastError = failure;
        state = PlaybackState.PREPARING;
        // 本次尝试已失败：即使引擎实例被复用，旧回调也不能再改状态。
        attemptSeq++;

        Channel activeChannel = channel;
        Listener l = listener;

        // 网络抖动：优先在原 variant/原引擎上延迟重试，不消耗线路。
        // 引擎实例保留（不 stop/release），重试时只换回首帧仍在 Surface 上。
        if (failure.isRetryableNetworkError() && retryCount < MAX_NETWORK_RETRIES) {
            long delayMs = RETRY_DELAYS_MS[retryCount];
            retryCount++;
            scheduleRetry(delayMs);
            if (l != null) {
                // UI 通过 isRetrying() 区分「重试」与「切换线路」。
                l.onError(failure, true);
            }
            return;
        }

        // 重试耗尽或不可重试：进入换引擎/换线路，新尝试重新计数。
        retryCount = 0;

        if (!usingMediaPlayer && failure.isDecoderOrContainer()) {
            // Exo 解码器/容器失败：同一 variant 换 MediaPlayer（类型切换，startAttempt 会重建）。
            if (l != null) {
                l.onError(failure, true);
            }
            usingMediaPlayer = true;
            startAttempt();
            return;
        }

        // 其他失败（含 MediaPlayer 的任何失败）：下一个 variant，回到 Exo。
        // Exo→Exo 时 startAttempt 复用实例；MediaPlayer→Exo 才重建。
        int nextIndex = variantIndex + 1;
        usingMediaPlayer = false;
        if (activeChannel == null || nextIndex >= activeChannel.variants().size()) {
            // 最终失败只发 onAllVariantsFailed，不再发 onError(false)。
            failAll(activeChannel, failure);
            return;
        }
        if (l != null) {
            l.onError(failure, true);
        }
        variantIndex = nextIndex;
        startAttempt();
    }

    private void scheduleRetry(long delayMs) {
        cancelPendingRetry();
        final int retryGeneration = generation;
        pendingRetry = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                pendingRetry = null;
                if (retryGeneration != generation) {
                    return;
                }
                startAttempt();
            }
        }, delayMs);
    }

    private void cancelPendingRetry() {
        Scheduler.Cancelable pending = pendingRetry;
        pendingRetry = null;
        if (pending != null) {
            pending.cancel();
        }
    }

    private void failAll(Channel failedChannel, PlaybackError error) {
        lastError = error;
        state = PlaybackState.ERROR;
        cancelPendingRetry();
        retryCount = 0;
        stopCurrentEngine();
        Listener l = listener;
        if (l != null) {
            l.onAllVariantsFailed(failedChannel, error);
        }
    }

    private void stopCurrentEngine() {
        PlaybackEngine current = engine;
        engine = null;
        engineIsMediaPlayer = false;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException e) {
            // 引擎已坏也不能挡住切台。
        }
        try {
            current.release();
        } catch (RuntimeException e) {
            // 同上。
        }
    }
}
