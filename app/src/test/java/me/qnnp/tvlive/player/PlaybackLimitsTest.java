package me.qnnp.tvlive.player;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 探测失败后的 URL 兜底（正常路径由 StreamProbe 按真实 Format 判断）。 */
public class PlaybackLimitsTest {

    @Test
    public void detects4kMarkerInUrl() {
        assertTrue(PlaybackLimits.is4kUrl("http://example.com/live/cctv4k.m3u8"));
        assertTrue(PlaybackLimits.is4kUrl("http://example.com/uhd/video.m3u8"));
        assertTrue(PlaybackLimits.is4kUrl("http://example.com/2160/video.m3u8"));
    }

    @Test
    public void detectsKnown4kSourceWithout4kInUrl() {
        // 东方卫视/东方卫视4K 共用这个 4K-only 地址。
        assertTrue(PlaybackLimits.is4kUrl(
                "http://bp-resource-dfl.bestv.cn/148/3/video.m3u8"));
    }

    @Test
    public void knownBadSourceIsGuardedByUrlOnly() {
        assertTrue(PlaybackLimits.isKnownBadUrl(
                "http://bp-resource-dfl.bestv.cn/148/3/video.m3u8"));
        assertFalse(PlaybackLimits.isKnownBadUrl("http://example.com/live/cctv4k.m3u8"));
    }

    @Test
    public void normalChannelIsNotBlocked() {
        assertFalse(PlaybackLimits.is4kUrl(
                "http://74.91.26.218:82/live/cctv13hd.m3u8"));
        assertFalse(PlaybackLimits.is4kUrl(null));
        assertFalse(PlaybackLimits.is4kUrl(""));
    }

    @Test
    public void unsupportedErrorDoesNotFallBackToMediaPlayer() {
        PlaybackError error = PlaybackError.of(PlaybackError.Kind.UNSUPPORTED, "4K");
        assertFalse(error.isDecoderOrContainer());
        assertFalse(error.isNetworkFamily());
        assertFalse(error.isRetryableNetworkError());
    }
}
