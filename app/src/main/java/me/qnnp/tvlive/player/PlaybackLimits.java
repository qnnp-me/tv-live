package me.qnnp.tvlive.player;

import java.util.Locale;

/**
 * 最后一道 URL 兜底（纯 Java）。
 *
 * <p>正常路径由 {@link StreamProbe} 按真实 Format 判断，不再按频道名屏蔽。
 * 只有探测全部失败、且是 API 19 legacy 设备时，才用 URL 里的 4K/已知 4K 源特征
 * 做保守跳过；普通 URL 不拦。</p>
 */
public final class PlaybackLimits {

    /** 已知会把 MT5507 卡死的 4K 源特征（东方卫视/东方卫视4K 共用）。 */
    private static final String[] KNOWN_4K_URL_MARKERS = {
            "bestv.cn/148/3/",
            "cctv4k",
            "cctv8k",
            "suzhou4k",
            "csztv4k",
    };

    /**
     * 已在实机把 OMX/mediaserver 卡死过的源。不是按频道名，而是按具体 URL；
     * 如果探针误判为 1080p，这里仍然先拦一次，避免重启后再点一次又卡死。
     */
    private static final String[] KNOWN_BAD_URL_MARKERS = {
            "bestv.cn/148/3/",
    };

    private PlaybackLimits() {
    }

    /** 实机已确认会卡死 OMX 的源（负缓存种子）。 */
    public static boolean isKnownBadUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String marker : KNOWN_BAD_URL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 探测失败后的最后兜底：URL 明显是 4K/已知 4K 源。 */
    public static boolean is4kUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (contains4k(url)) {
            return true;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String marker : KNOWN_4K_URL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 文本里的 4K/UHD/2160 标记（大小写不敏感）。 */
    static boolean contains4k(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("4k") || lower.contains("uhd") || lower.contains("2160");
    }
}
