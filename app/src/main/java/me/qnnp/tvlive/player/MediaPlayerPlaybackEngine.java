package me.qnnp.tvlive.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * MediaPlayer 兜底引擎：只在 Exo 出现解码器/容器类失败时由控制器选用。
 *
 * <p>与 Exo 引擎一样按「实例生命周期 + 一次播放」拆分：prepare 可以多次调用，
 * 内部 MediaPlayer 用 reset() 复用，Surface 不主动清空；release 才彻底释放。
 * 每次 prepare 递增 session，旧会话迟到的回调直接丢弃。</p>
 *
 * <p><b>线程模型</b>：API 19 的 {@code setDataSource(context, uri, headers)} 会同步做
 * DNS 解析 + connect，因此所有 MediaPlayer 操作（reset/setDataSource/prepareAsync/
 * stop/release）都串行在引擎自己的单线程池里；回调经 {@link Handler} 投递回主线程。
 * stop/release 只作废会话令牌并把清理任务排到同一线程，主线程不等网络。</p>
 */
final class MediaPlayerPlaybackEngine implements PlaybackEngine {

    private static final String TAG = "TvLive";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;

    /** 只在 worker 线程创建/替换/释放；主线程只读。 */
    private volatile MediaPlayer player;
    private volatile PlaybackEngine.Callback callback;
    private volatile Surface surface;
    /** 目标音量；reset/重建后重新套用（MediaPlayer.reset 会回默认音量）。 */
    private volatile float volume = 1f;
    private final PlaybackSession session = new PlaybackSession();
    /** release 只调度一次；主线程访问。 */
    private boolean releaseSubmitted;
    private boolean errorReported;

    MediaPlayerPlaybackEngine(Context context) {
        this.context = context;
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "TvLive-mediaplayer-prepare");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    @Override
    public void setSurface(Surface surface) {
        this.surface = surface;
        MediaPlayer current = player;
        if (current == null || surface == null || session.isDead()) {
            // null 只表示当前没有可用 Surface，不主动 setSurface(null) 清屏。
            return;
        }
        try {
            current.setSurface(surface);
        } catch (IllegalStateException e) {
            // worker 正在 reset/prepare：Surface 会在 prepare 任务里重新套用。
            Log.w(TAG, "MediaPlayer 设置 Surface 失败", e);
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = clampVolume(volume);
        applyVolume();
    }

    private static float clampVolume(float value) {
        if (value < 0f) {
            return 0f;
        }
        return value > 1f ? 1f : value;
    }

    private void applyVolume() {
        MediaPlayer current = player;
        if (current == null || session.isDead()) {
            return;
        }
        try {
            current.setVolume(volume, volume);
        } catch (IllegalStateException e) {
            Log.w(TAG, "设置 MediaPlayer 音量失败", e);
        }
    }

    @Override
    public void prepare(Variant variant, PlaybackEngine.Callback callback) {
        if (variant == null) {
            throw new IllegalArgumentException("variant == null");
        }
        if (session.isReleased()) {
            throw new IllegalStateException("engine 已 release");
        }
        this.callback = callback;
        this.errorReported = false;
        final int activeSession = session.begin();
        final Variant target = variant;
        submit(new Runnable() {
            @Override
            public void run() {
                prepareInternal(target, activeSession);
            }
        });
    }

    /** worker 线程：复用/重建 MediaPlayer，然后 setDataSource + prepareAsync。 */
    private void prepareInternal(Variant variant, int activeSession) {
        if (!session.isCurrent(activeSession)) {
            return;
        }
        MediaPlayer mp = player;
        if (mp == null) {
            mp = new MediaPlayer();
            player = mp;
        } else {
            try {
                mp.reset();
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaPlayer reset 异常，重建实例", e);
                releasePlayer(mp);
                if (!session.isCurrent(activeSession)) {
                    return;
                }
                mp = new MediaPlayer();
                player = mp;
            }
        }
        if (!session.isCurrent(activeSession)) {
            return;
        }
        registerListeners(mp, activeSession);
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setVolume(volume, volume);
        Surface target = surface;
        if (target != null) {
            try {
                mp.setSurface(target);
            } catch (RuntimeException e) {
                // Surface 可能在排队期间已被 UI 释放；prepare 仍可继续，首帧前会重新绑定。
                Log.w(TAG, "MediaPlayer 设置 Surface 失败", e);
            }
        }

        try {
            mp.setDataSource(context, Uri.parse(variant.url()), buildHeaders(variant));
            // setDataSource 可能耗时数秒；期间 stop/release 后不得再开始播放。
            if (!session.isCurrent(activeSession)) {
                return;
            }
            mp.prepareAsync();
        } catch (IOException e) {
            Log.w(TAG, "MediaPlayer setDataSource 失败", e);
            failAsync(PlaybackError.of(PlaybackError.Kind.NETWORK, "网络连接失败", e),
                    activeSession);
        } catch (IllegalStateException e) {
            Log.w(TAG, "MediaPlayer 状态异常", e);
            failAsync(PlaybackError.of(PlaybackError.Kind.UNKNOWN, "播放失败", e), activeSession);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "MediaPlayer 地址非法", e);
            failAsync(PlaybackError.of(PlaybackError.Kind.CONTAINER, "无法识别的视频格式", e),
                    activeSession);
        }
    }

    @Override
    public void stop() {
        session.stop();
        submit(new Runnable() {
            @Override
            public void run() {
                MediaPlayer current = player;
                if (current == null) {
                    return;
                }
                try {
                    current.stop();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayer stop 异常", e);
                }
            }
        });
    }

    @Override
    public void release() {
        synchronized (this) {
            if (releaseSubmitted) {
                return;
            }
            releaseSubmitted = true;
        }
        callback = null;
        session.release();
        surface = null;
        submit(new Runnable() {
            @Override
            public void run() {
                releaseInternalPlayer();
            }
        });
        // 已排入的 prepare/stop/release 任务照常执行，之后线程退出。
        worker.shutdown();
    }

    @Override
    public long getCurrentPosition() {
        MediaPlayer current = player;
        if (current == null || session.isDead()) {
            return 0;
        }
        try {
            return current.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    @Override
    public boolean isPlaying() {
        MediaPlayer current = player;
        if (current == null || session.isDead()) {
            return false;
        }
        try {
            return current.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 提交到引擎线程；release 后不再接受新任务（迟到的 prepare/stop 直接丢弃）。 */
    private void submit(Runnable action) {
        try {
            worker.execute(action);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "MediaPlayer 引擎线程已关闭，丢弃任务");
        }
    }

    /**
     * 每次 prepare 重新注册一份 listener，闭包绑定本会话；旧会话的事件一并丢弃。
     * MediaPlayer 的事件再统一 post 回主线程，保证控制器/UI 只看到主线程回调。
     */
    private void registerListeners(MediaPlayer mp, final int activeSession) {
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(final MediaPlayer mediaPlayer) {
                onMain(activeSession, new Runnable() {
                    @Override
                    public void run() {
                        mediaPlayer.start();
                        PlaybackEngine.Callback cb = callback;
                        if (cb != null) {
                            cb.onPrepared();
                        }
                    }
                });
            }
        });
        mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mediaPlayer, int what, int extra) {
                if (what != MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    return false;
                }
                onMain(activeSession, new Runnable() {
                    @Override
                    public void run() {
                        PlaybackEngine.Callback cb = callback;
                        if (cb != null) {
                            cb.onFirstFrame();
                        }
                    }
                });
                return false;
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                onMain(activeSession, new Runnable() {
                    @Override
                    public void run() {
                        PlaybackEngine.Callback cb = callback;
                        if (cb != null) {
                            cb.onEnded();
                        }
                    }
                });
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, final int what, final int extra) {
                onMain(activeSession, new Runnable() {
                    @Override
                    public void run() {
                        if (errorReported) {
                            return;
                        }
                        errorReported = true;
                        Log.w(TAG, "MediaPlayer 播放失败 what=" + what + " extra=" + extra);
                        PlaybackEngine.Callback cb = callback;
                        if (cb != null) {
                            cb.onError(classify(what, extra));
                        }
                    }
                });
                return true;
            }
        });
    }

    /** 仅当前会话的动作投递到主线程；stop/release/新 prepare 之后自动丢弃。 */
    private void onMain(final int activeSession, final Runnable action) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!session.isCurrent(activeSession)) {
                    return;
                }
                action.run();
            }
        });
    }

    /** worker 线程的错误同样回到主线程再上报，并再次校验会话。 */
    private void failAsync(final PlaybackError error, final int activeSession) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                fail(error, activeSession);
            }
        });
    }

    private void fail(PlaybackError error, int activeSession) {
        if (!session.isCurrent(activeSession) || errorReported) {
            return;
        }
        errorReported = true;
        PlaybackEngine.Callback cb = callback;
        if (cb != null) {
            cb.onError(error);
        }
    }

    private void releaseInternalPlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) {
            return;
        }
        releasePlayer(mp);
    }

    private static void releasePlayer(MediaPlayer mp) {
        try {
            mp.setOnPreparedListener(null);
            mp.setOnInfoListener(null);
            mp.setOnCompletionListener(null);
            mp.setOnErrorListener(null);
            mp.reset();
        } catch (IllegalStateException e) {
            Log.w(TAG, "MediaPlayer reset 异常", e);
        } finally {
            try {
                mp.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "MediaPlayer release 异常", e);
            }
        }
    }

    private static Map<String, String> buildHeaders(Variant variant) {
        Map<String, String> headers = new HashMap<String, String>(2);
        if (variant.userAgent() != null) {
            headers.put("User-Agent", variant.userAgent());
        }
        if (variant.referer() != null) {
            headers.put("Referer", variant.referer());
        }
        return headers;
    }

    /**
     * MediaPlayer 错误码分类。MALFORMED / NOT_VALID_FOR_PROGRESSIVE_PLAYBACK 归容器，
     * UNSUPPORTED 归解码器（详情见批次报告里的分类口径）。
     */
    static PlaybackError classify(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            return PlaybackError.of(PlaybackError.Kind.TIMEOUT, "连接超时");
        }
        if (what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            return PlaybackError.of(PlaybackError.Kind.DECODER, "本机无法解码该视频");
        }
        if (what == MediaPlayer.MEDIA_ERROR_MALFORMED
                || what == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
            return PlaybackError.of(PlaybackError.Kind.CONTAINER, "无法识别的视频格式");
        }
        if (what == MediaPlayer.MEDIA_ERROR_IO) {
            return PlaybackError.of(PlaybackError.Kind.NETWORK, "网络连接失败");
        }
        if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
            // 解码器进程退出：按解码器失败处理（同 variant 可换 Exo 再试）。
            return PlaybackError.of(PlaybackError.Kind.DECODER, "解码器进程退出");
        }
        return PlaybackError.of(PlaybackError.Kind.UNKNOWN, "播放失败");
    }
}
