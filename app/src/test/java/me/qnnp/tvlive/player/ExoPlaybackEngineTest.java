package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.google.android.exoplayer2.source.MediaSource;

/**
 * 批次 14：切台换源调用序列（假 player，不实例化真实 ExoPlayer）。
 *
 * <p>{@code setMediaSource} 会先停掉当前源，因此 prepare 复用实例时不能再显式
 * {@code stop()}（多一次 flush）。这里断言顺序与「没有 stop」。</p>
 */
public class ExoPlaybackEngineTest {

    @Test
    public void switchSourceDoesNotCallStopBeforePreparing() {
        RecordingOps ops = new RecordingOps();

        ExoPlaybackEngine.switchSource(ops, (MediaSource) null);

        assertEquals(Arrays.asList("setMediaSource", "playWhenReady:true", "prepare"),
                ops.calls);
        assertFalse("prepare 换源不得显式 stop", ops.calls.contains("stop"));
    }

    @Test
    public void switchSourcePreparesExactlyOnce() {
        RecordingOps ops = new RecordingOps();

        ExoPlaybackEngine.switchSource(ops, (MediaSource) null);

        int prepares = 0;
        for (String call : ops.calls) {
            if ("prepare".equals(call)) {
                prepares++;
            }
        }
        assertEquals(1, prepares);
    }

    /** 记录调用顺序的假 player。 */
    static final class RecordingOps implements ExoPlaybackEngine.PlayerOps {

        final List<String> calls = new ArrayList<String>();

        @Override
        public void setMediaSource(MediaSource mediaSource) {
            calls.add("setMediaSource");
        }

        @Override
        public void setPlayWhenReady(boolean playWhenReady) {
            calls.add("playWhenReady:" + playWhenReady);
        }

        @Override
        public void prepare() {
            calls.add("prepare");
        }
    }
}
