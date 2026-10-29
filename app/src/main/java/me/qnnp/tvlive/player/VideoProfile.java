package me.qnnp.tvlive.player;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

/**
 * 本机视频能力档案：最大分辨率 + 是否有对应解码器。
 *
 * <p>API 21+ 用 {@code MediaCodecList}/{@code VideoCapabilities} 查 4K 硬解能力；
 * API 19（TCL L32F3800A / MT5507）没有 VideoCapabilities，固定 1080p。</p>
 *
 * <p>注意：{@code isSizeSupported} 只说明尺寸，不保证 4K 10bit/HDR 或厂商固件
 * 一定能稳定解码；预探测和看门狗仍然是必要的。</p>
 */
public final class VideoProfile {

    private static final String TAG = "TvLive";

    private final int maxWidth;
    private final int maxHeight;
    private final boolean legacy;

    private VideoProfile(int maxWidth, int maxHeight, boolean legacy) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.legacy = legacy;
    }

    /** 启动时探测一次；失败按 1080p 保守处理。 */
    public static VideoProfile detect(Context context) {
        if (Build.VERSION.SDK_INT < 21) {
            return new VideoProfile(1920, 1080, true);
        }
        try {
            if (Api21.supports4k("video/hevc") || Api21.supports4k("video/avc")) {
                return new VideoProfile(3840, 2160, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "探测 4K 解码能力失败，按 1080p 处理: " + t);
        }
        return new VideoProfile(1920, 1080, false);
    }

    /** 固定 1080p 档案（测试/降级用）。 */
    public static VideoProfile hd() {
        return new VideoProfile(1920, 1080, false);
    }

    public int maxWidth() {
        return maxWidth;
    }

    public int maxHeight() {
        return maxHeight;
    }

    /** API < 21：没有 VideoCapabilities，按 1080p 保守处理。 */
    public boolean legacy() {
        return legacy;
    }

    /** 宽高未知（0）时放行，交给后续 codec 探测。 */
    public boolean withinLimit(int width, int height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        return width <= maxWidth && height <= maxHeight;
    }

    /** 是否有该 MIME 的解码器；查询失败按不支持处理。 */
    public boolean supportsMime(String mime) {
        if (mime == null || mime.isEmpty()) {
            return true;
        }
        try {
            return !MediaCodecUtil.getDecoderInfos(mime, false, false).isEmpty();
        } catch (Throwable t) {
            Log.w(TAG, "查询解码器失败: " + mime, t);
            return false;
        }
    }

    public String label() {
        return maxWidth + "x" + maxHeight + (legacy ? " legacy" : "");
    }

    /** API 21+ 的 4K 查询隔离在这里，避免 API 19 加载 VideoCapabilities。 */
    private static final class Api21 {

        static boolean supports4k(String mime) {
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            android.media.MediaCodecInfo[] codecs = list.getCodecInfos();
            for (android.media.MediaCodecInfo codec : codecs) {
                if (codec == null || codec.isEncoder()) {
                    continue;
                }
                try {
                    android.media.MediaCodecInfo.CodecCapabilities caps =
                            codec.getCapabilitiesForType(mime);
                    if (caps == null || caps.getVideoCapabilities() == null) {
                        continue;
                    }
                    if (caps.getVideoCapabilities().isSizeSupported(3840, 2160)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    // 该组件不支持这个 MIME，继续看下一个。
                }
            }
            return false;
        }
    }
}
