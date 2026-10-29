package me.qnnp.tvlive.player;

import android.content.Context;

/**
 * 引擎工厂：控制器只依赖这个接口，单测替换成假工厂即可覆盖全部策略。
 */
public interface EngineFactory {

    /** 新建一个 ExoPlayer 引擎实例（未 prepare）。 */
    PlaybackEngine createExo();

    /**
     * 按「首帧加速」设置新建 Exo 引擎。默认实现忽略该参数，方便只关心策略的假工厂；
     * 生产实现用它决定两档自适应缓冲。
     */
    default PlaybackEngine createExo(boolean firstFrameBoost) {
        return createExo();
    }

    /** 新建一个 MediaPlayer 兜底引擎实例（未 prepare）。 */
    PlaybackEngine createMediaPlayer();

    /** 生产实现：用 application context 创建真实引擎。 */
    final class Default implements EngineFactory {

        private final Context context;

        public Default(Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context == null");
            }
            this.context = context.getApplicationContext() != null
                    ? context.getApplicationContext()
                    : context;
        }

        @Override
        public PlaybackEngine createExo() {
            return new ExoPlaybackEngine(context, false);
        }

        @Override
        public PlaybackEngine createExo(boolean firstFrameBoost) {
            return new ExoPlaybackEngine(context, firstFrameBoost);
        }

        @Override
        public PlaybackEngine createMediaPlayer() {
            return new MediaPlayerPlaybackEngine(context);
        }
    }
}
