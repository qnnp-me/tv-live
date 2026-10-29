package me.qnnp.tvlive.player;

import android.content.Context;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.UnrecognizedInputFormatException;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.video.VideoFrameMetadataListener;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLException;

import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * ExoPlayer 2.18.1 实现。
 *
 * <p>实例生命周期与一次播放解耦：player 在第一次 {@link #prepare} 时创建
 * （{@link #ensurePlayer()}），listener 只注册一次；再次 prepare 只做
 * setMediaSource → prepare，不重建 ExoPlayer、不重设 Surface，
 * 因此切台/重试期间 SurfaceView 上的最后一帧不会被清掉。{@link #release()} 才彻底释放。</p>
 *
 * <p>回调前检查 {@link #released}，不做额外线程切换：Exo 的回调在创建 player 的 Looper 上，
 * 由控制器保证在主线程创建。</p>
 */
final class ExoPlaybackEngine implements PlaybackEngine {

    private static final String TAG = "TvLive";

    /** 无 UA 时的默认标识：部分源对空 UA 返回 403。 */
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 4.4.3) AppleWebKit/537.36 (KHTML, like Gecko) TvLive/1.0";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** 网速回调节流：最多每 500ms 通知一次 UI。 */
    private static final long SPEED_NOTIFY_INTERVAL_MS = 500L;

    private final Context context;
    private final AdaptiveLoadControl loadControl;
    private final BufferStageController bufferStage;
    /** 网络线程写、主线程读的网速采样（1 秒窗口 + 指数平滑）。 */
    private final SpeedMeter speedMeter = new SpeedMeter();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PlaybackEngine.BandwidthListener bandwidthListener;
    private boolean speedTickerRunning;

    /** 主线程 500ms 取样：既做节流，也保证「没新字节」时能归零。 */
    private final Runnable speedTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!speedTickerRunning) {
                return;
            }
            long bytesPerSecond = speedMeter.sample(SystemClock.elapsedRealtime());
            PlaybackEngine.BandwidthListener listener = bandwidthListener;
            if (listener != null) {
                listener.onBandwidth(bytesPerSecond);
            }
            mainHandler.postDelayed(this, SPEED_NOTIFY_INTERVAL_MS);
        }
    };
    /** 焦点/内容类型固定：媒体播放。 */
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build();

    private ExoPlayer player;
    private PlaybackEngine.Callback callback;
    private Surface surface;
    /** 目标音量；player 每次创建/复用都套用，standby 从 0 开始。 */
    private float volume = 1f;
    /** 是否申请音频焦点；双画面 standby 设 false，避免和前台互抢。 */
    private boolean handleAudioFocus = true;
    /** 当前 player 实际绑定的 Surface，用于避免重复 setVideoSurface。 */
    private Surface attachedSurface;
    private boolean released;
    private boolean readyNotified;
    private boolean tracksChecked;
    private boolean errorReported;

    /** 本机能力档案（API21+ 可到 4K；API19 TCL 固定 1080p）。 */
    private final VideoProfile videoProfile;
    /** 起播前探测；HTTP/解析在 probeExecutor 上，不阻塞主线程。 */
    private final StreamProbe streamProbe;
    private final ExecutorService probeExecutor;
    /** 每次 prepare 递增；旧的探测结果晚到直接丢弃。 */
    private int prepareGeneration;
    private Variant currentVariant;
    /** 当前选中的视频 Format；首帧成功后用它写播放白名单。 */
    private Format selectedVideoFormat;
    /** 最近一帧渲染时刻；视频卡住但位置仍在走时，主线程看门狗靠它判断。 */
    private volatile long lastVideoFrameAtMs;

    ExoPlaybackEngine(Context context) {
        this(context, false);
    }

    ExoPlaybackEngine(Context context, boolean firstFrameBoost) {
        this.context = context;
        this.loadControl = new AdaptiveLoadControl(firstFrameBoost);
        this.bufferStage = new BufferStageController(loadControl, new HandlerScheduler());
        this.videoProfile = VideoProfile.detect(context);
        this.streamProbe = new StreamProbe(context, videoProfile);
        this.probeExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "TvLive-probe");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        ExoPlayer current = player;
        if (surface == null) {
            // 关键：SurfaceView 销毁后 TCL 可能复用同一个 Surface 对象再 created。
            // 如果这里不清掉 attachedSurface，下一次 setSurface(同一个对象) 会被去重，
            // Exo 继续拿已失效的 Surface 渲染 —— 表现就是黑屏有声。
            attachedSurface = null;
            return;
        }
        if (current == null) {
            return;
        }
        if (surface == attachedSurface && surface.isValid()) {
            return;
        }
        attachedSurface = surface;
        try {
            current.setVideoSurface(surface);
        } catch (RuntimeException e) {
            Log.w(TAG, "设置 Exo Surface 失败", e);
        }
    }

    @Override
    public void forceReconfigureVideo() {
        ExoPlayer current = player;
        Surface target = surface;
        if (current == null || target == null || !target.isValid()) {
            return;
        }
        Log.i(TAG, "force reconfigure video surface");
        try {
            current.setVideoSurface(null);
            current.setVideoSurface(target);
        } catch (RuntimeException e) {
            Log.w(TAG, "force reconfigure video failed", e);
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = clampVolume(volume);
        ExoPlayer current = player;
        if (current == null) {
            return;
        }
        try {
            current.setVolume(this.volume);
        } catch (RuntimeException e) {
            Log.w(TAG, "设置 Exo 音量失败", e);
        }
    }

    private static float clampVolume(float volume) {
        if (volume < 0f) {
            return 0f;
        }
        return volume > 1f ? 1f : volume;
    }

    @Override
    public void setHandleAudioFocus(boolean handleAudioFocus) {
        if (this.handleAudioFocus == handleAudioFocus) {
            return;
        }
        this.handleAudioFocus = handleAudioFocus;
        ExoPlayer current = player;
        if (current == null) {
            // 尚未创建：ensurePlayer() 会用新的 flag 申请/不申请焦点。
            return;
        }
        try {
            // false 时 Exo 会放弃已持有的焦点，standby 不再打断前台。
            current.setAudioAttributes(audioAttributes, handleAudioFocus);
        } catch (RuntimeException e) {
            Log.w(TAG, "设置音频焦点开关失败", e);
        }
    }

    @Override
    public void setBandwidthListener(PlaybackEngine.BandwidthListener listener) {
        this.bandwidthListener = listener;
    }

    @Override
    public void prepare(Variant variant, PlaybackEngine.Callback callback) {
        if (variant == null) {
            throw new IllegalArgumentException("variant == null");
        }
        if (released) {
            throw new IllegalStateException("engine 已 release");
        }
        this.callback = callback;
        this.readyNotified = false;
        this.tracksChecked = false;
        this.errorReported = false;
        this.currentVariant = variant;
        this.selectedVideoFormat = null;
        this.lastVideoFrameAtMs = 0L;
        final int generation = ++prepareGeneration;

        // 先探测真实 Format，再创建 player/codec；探测本身不碰厂商解码器。
        try {
            probeExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final ProbeResult result = streamProbe.probe(variant);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released || generation != prepareGeneration) {
                                return;
                            }
                            if (result.isUnsupported()) {
                                Log.w(TAG, "probe unsupported: " + result);
                                fail(PlaybackError.of(
                                        PlaybackError.Kind.UNSUPPORTED, result.message()));
                                return;
                            }
                            prepareAfterProbe(variant);
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            fail(PlaybackError.of(PlaybackError.Kind.UNKNOWN, "探测线程不可用"));
        }
    }

    /** 探测通过/未知后真正建 MediaSource 和 ExoPlayer。 */
    private void prepareAfterProbe(Variant variant) {
        if (released) {
            return;
        }
        // 再次切台/重试：回到起播档并取消上一次的稳定档计时。
        bufferStage.onPrepare();
        // 新台重新统计网速，并从 500ms 取样窗口重新起步。
        speedMeter.reset();
        startSpeedTicker();

        DefaultHttpDataSource.Factory dataSourceFactory = buildDataSourceFactory(variant);
        MediaSource mediaSource = buildMediaSource(variant, dataSourceFactory);

        ensurePlayer();
        // 复用同一个 player：setMediaSource 本身会停掉当前源，不再额外 stop()
        // （那是一次多余的 flush，切台会更慢/更黑）。
        switchSource(new ExoPlayerOps(player), mediaSource);
    }

    @Override
    public void stop() {
        prepareGeneration++;
        bufferStage.onStop();
        stopSpeedTicker();
        speedMeter.reset();
        ExoPlayer current = player;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "停止 Exo 播放器时异常", e);
        }
    }

    @Override
    public void release() {
        released = true;
        prepareGeneration++;
        probeExecutor.shutdownNow();
        bufferStage.onStop();
        stopSpeedTicker();
        callback = null;
        releasePlayer();
        surface = null;
        attachedSurface = null;
    }

    @Override
    public long lastVideoFrameAtMs() {
        return lastVideoFrameAtMs;
    }

    @Override
    public long getCurrentPosition() {
        if (player == null) {
            return 0;
        }
        try {
            return player.getCurrentPosition();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean isPlaying() {
        if (player == null) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    /** prepare 换源用到的调用；单测用假实现断言调用顺序，不需要真实 ExoPlayer。 */
    interface PlayerOps {
        void setMediaSource(MediaSource mediaSource);

        void setPlayWhenReady(boolean playWhenReady);

        void prepare();
    }

    /**
     * 复用同一 player 换源。{@code setMediaSource} 内部会先停掉当前源，因此这里不再
     * 显式 {@code stop()}（那会多一次 flush）；{@code stop()} 只留给外部停止/释放。
     */
    static void switchSource(PlayerOps ops, MediaSource mediaSource) {
        ops.setMediaSource(mediaSource);
        ops.setPlayWhenReady(true);
        ops.prepare();
    }

    private static final class ExoPlayerOps implements PlayerOps {

        private final ExoPlayer player;

        ExoPlayerOps(ExoPlayer player) {
            this.player = player;
        }

        @Override
        public void setMediaSource(MediaSource mediaSource) {
            player.setMediaSource(mediaSource);
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            player.setPlayWhenReady(playWhenReady);
        }

        @Override
        public void prepare() {
            player.prepare();
        }
    }

    /** 第一次用到时创建 player 并注册唯一一份 listener；之后复用。 */
    private void ensurePlayer() {
        if (player != null) {
            return;
        }

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true);
        // 按本机能力档案限制轨道：API19 TCL 固定 1080p，API21+ 支持 4K 时才放开。
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setMaxVideoSize(videoProfile.maxWidth(), videoProfile.maxHeight()));

        ExoPlayer created = new ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build();
        created.setAudioAttributes(audioAttributes, handleAudioFocus);
        created.setVideoFrameMetadataListener(new VideoFrameMetadataListener() {
            @Override
            public void onVideoFrameAboutToBeRendered(long presentationTimeUs,
                                                      long releaseTimeNs,
                                                      Format format,
                                                      MediaFormat mediaFormat) {
                lastVideoFrameAtMs = SystemClock.elapsedRealtime();
            }
        });
        created.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (released || errorReported) {
                    return;
                }
                if (playbackState == Player.STATE_READY) {
                    // 起播档先出画；首次 READY 后 3 秒切稳定档（重缓冲 READY 不重复计时）。
                    bufferStage.onReady();
                    // 缓冲恢复也会回到 READY，onPrepared 只发一次。
                    if (!readyNotified) {
                        readyNotified = true;
                        notifyPrepared();
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // 重新缓冲：再次取样，让台名卡（若还在显示）能反映当前网速。
                    startSpeedTicker();
                } else if (playbackState == Player.STATE_ENDED) {
                    PlaybackEngine.Callback cb = callback;
                    if (cb != null) {
                        cb.onEnded();
                    }
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (released || errorReported) {
                    return;
                }
                Log.i(TAG, "first frame rendered");
                lastVideoFrameAtMs = SystemClock.elapsedRealtime();
                if (currentVariant != null && selectedVideoFormat != null) {
                    streamProbe.remember(currentVariant, selectedVideoFormat.width,
                            selectedVideoFormat.height, selectedVideoFormat.sampleMimeType);
                }
                // 起播完成：台名卡随即隐藏，网速行不再需要刷新（重缓冲会重新启动）。
                stopSpeedTicker();
                PlaybackEngine.Callback cb = callback;
                if (cb != null) {
                    cb.onFirstFrame();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Exo 播放失败: " + error.getErrorCodeName(), error);
                fail(classify(error));
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (released || errorReported) {
                    return;
                }
                checkCodecSupport(tracks);
            }
        });
        created.setPlayWhenReady(true);
        created.setVolume(volume);
        player = created;

        if (surface != null) {
            attachedSurface = surface;
            created.setVideoSurface(surface);
        }
    }

    private void releasePlayer() {
        ExoPlayer current = player;
        player = null;
        attachedSurface = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
            current.clearMediaItems();
        } catch (RuntimeException e) {
            Log.w(TAG, "停止 Exo 播放器时异常", e);
        } finally {
            current.release();
        }
    }

    private void notifyPrepared() {
        PlaybackEngine.Callback cb = callback;
        if (cb != null) {
            cb.onPrepared();
        }
    }

    /** 启动 500ms 取样：重复调用安全，已有计时时不变。 */
    private void startSpeedTicker() {
        if (speedTickerRunning || released) {
            return;
        }
        speedTickerRunning = true;
        mainHandler.post(speedTickRunnable);
    }

    private void stopSpeedTicker() {
        if (!speedTickerRunning) {
            return;
        }
        speedTickerRunning = false;
        mainHandler.removeCallbacks(speedTickRunnable);
    }

    private void fail(PlaybackError error) {
        if (errorReported || released) {
            return;
        }
        errorReported = true;
        // 白名单只跳过探测，不保证厂商解码器永远正常；一旦失败就清掉，下次重探。
        if (currentVariant != null) {
            StreamProbe.invalidate(context, currentVariant.url());
        }
        PlaybackEngine.Callback cb = callback;
        if (cb != null) {
            cb.onError(error);
        }
    }

    /**
     * prepare 之后检查选中轨道的 MIME：硬解不支持的 HEVC / AC-3 直接判为 DECODER，
     * 由控制器换 MediaPlayer 或下一个 variant，而不是让 Exo 抛一堆解码器错误。
     */
    private void checkCodecSupport(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            int type = group.getType();
            if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            if (group.length == 0) {
                continue;
            }
            boolean selected;
            try {
                selected = group.isSelected();
            } catch (RuntimeException e) {
                selected = false;
            }
            if (!selected) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                String mime = format.sampleMimeType;
                if (type == C.TRACK_TYPE_VIDEO && videoProfile.withinLimit(
                        format.width, format.height) && selectedVideoFormat == null) {
                    selectedVideoFormat = format;
                }
                if (type == C.TRACK_TYPE_VIDEO
                        && !videoProfile.withinLimit(format.width, format.height)) {
                    // 正常情况下 track selector 已经过滤掉；这里是选择后的兜底。
                    tracksChecked = true;
                    fail(PlaybackError.of(PlaybackError.Kind.UNSUPPORTED,
                            "本机最高支持 " + videoProfile.maxWidth() + "x"
                                    + videoProfile.maxHeight()));
                    return;
                }
                if (mime == null) {
                    continue;
                }
                if ((MimeTypes.VIDEO_H265.equals(mime) || MimeTypes.AUDIO_AC3.equals(mime)
                        || MimeTypes.AUDIO_E_AC3.equals(mime))
                        && !tracksChecked && !hasDecoder(mime)) {
                    tracksChecked = true;
                    String message = MimeTypes.VIDEO_H265.equals(mime)
                            ? "本机不支持 HEVC"
                            : "本机不支持 AC-3";
                    fail(PlaybackError.of(PlaybackError.Kind.DECODER, message));
                    return;
                }
            }
        }
    }

    private static boolean hasDecoder(String mime) {
        try {
            return !MediaCodecUtil.getDecoderInfos(mime, false, false).isEmpty();
        } catch (MediaCodecUtil.DecoderQueryException e) {
            Log.w(TAG, "查询解码器失败: " + mime, e);
            return false;
        }
    }

    private DefaultHttpDataSource.Factory buildDataSourceFactory(Variant variant) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory()
                .setUserAgent(variant.userAgent() != null ? variant.userAgent() : DEFAULT_UA)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(false);
        // 只统计真正的网络读取；每次 prepare 换源都会新建 factory，listener 随之复用同一个采样器。
        factory.setTransferListener(new NetworkByteCounter());
        if (variant.referer() != null) {
            Map<String, String> headers = new HashMap<String, String>(1);
            headers.put("Referer", variant.referer());
            factory.setDefaultRequestProperties(headers);
        }
        return factory;
    }

    /**
     * 从 Exo 的网络读取里累加字节数（网络线程回调）。
     *
     * <p>只认 {@code isNetwork=true}：本地文件/缓存读取不计入网速。这里只做一次
     * {@link SpeedMeter#addBytes}，格式化与 UI 更新都在主线程的 500ms 取样里完成。</p>
     */
    private final class NetworkByteCounter implements TransferListener {

        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork,
                int bytes) {
            if (!isNetwork || bytes <= 0) {
                return;
            }
            speedMeter.addBytes(bytes, SystemClock.elapsedRealtime());
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }
    }

    private static MediaSource buildMediaSource(Variant variant, DefaultHttpDataSource.Factory dataSourceFactory) {
        MediaItem item = MediaItem.fromUri(variant.url());
        if (isHls(variant.url())) {
            return new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSourceFactory, new DefaultExtractorsFactory())
                .createMediaSource(item);
    }

    private static boolean isHls(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        path = path.toLowerCase(java.util.Locale.US);
        Uri uri = Uri.parse(path);
        String last = uri.getLastPathSegment();
        return (last != null && last.endsWith(".m3u8")) || path.contains(".m3u8");
    }

    // ---------------------------------------------------------------- 错误分类

    /**
     * 按 cause 链分类。优先级：HTTP 状态码 > TLS > 超时 > 解码器/容器 > 网络 > UNKNOWN。
     * 包级可见，方便以后单独补 JVM 单测（当前批次只测控制器策略）。
     */
    static PlaybackError classify(PlaybackException error) {
        List<Throwable> chain = collectCauses(error);

        for (Throwable cause : chain) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                if (code == 401 || code == 403) {
                    return PlaybackError.of(PlaybackError.Kind.HTTP_AUTH,
                            "地址需要授权（HTTP " + code + "）", error);
                }
                if (code == 404) {
                    return PlaybackError.of(PlaybackError.Kind.HTTP_NOT_FOUND,
                            "地址不存在（HTTP 404）", error);
                }
                return PlaybackError.of(PlaybackError.Kind.HTTP, "服务器返回 HTTP " + code, error);
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof SSLException) {
                return PlaybackError.of(PlaybackError.Kind.TLS, "安全连接失败（TLS）", error);
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof SocketTimeoutException) {
                return PlaybackError.of(PlaybackError.Kind.TIMEOUT, "连接超时", error);
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof MediaCodecRenderer.DecoderInitializationException
                    || cause instanceof MediaCodecUtil.DecoderQueryException
                    || isMediaCodecException(cause)) {
                return PlaybackError.of(PlaybackError.Kind.DECODER, "本机无法解码该视频", error);
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof UnrecognizedInputFormatException
                    || cause instanceof ParserException) {
                return PlaybackError.of(PlaybackError.Kind.CONTAINER, "无法识别的视频格式", error);
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof UnknownHostException || cause instanceof ConnectException
                    || cause instanceof HttpDataSource.HttpDataSourceException
                    || cause instanceof IOException) {
                return PlaybackError.of(PlaybackError.Kind.NETWORK, "网络连接失败", error);
            }
        }
        return PlaybackError.of(PlaybackError.Kind.UNKNOWN, "播放失败", error);
    }

    /** 按类名识别 MediaCodec 的 CodecException，避开 API 21+ 类型在 API 19 上的解析风险。 */
    private static boolean isMediaCodecException(Throwable cause) {
        return cause.getClass().getName().startsWith("android.media.MediaCodec$CodecException");
    }

    private static List<Throwable> collectCauses(Throwable error) {
        List<Throwable> chain = new ArrayList<Throwable>(4);
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            chain.add(current);
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return chain;
    }
}
