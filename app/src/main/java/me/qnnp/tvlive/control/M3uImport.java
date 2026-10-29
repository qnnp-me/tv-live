package me.qnnp.tvlive.control;

import java.util.Locale;

import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistParseException;
import me.qnnp.tvlive.data.PlaylistParser;
import me.qnnp.tvlive.data.SourceStore;

/**
 * 手机页导入校验：只接受 m3u/m3u8 文本，≤5MB，且至少解析出一个频道。
 *
 * <p>纯 Java，单测直接传字节数组。错误消息面向用户（中文）。</p>
 */
public final class M3uImport {

    /** 单个源文件上限，与 {@link SourceStore#MAX_SOURCE_BYTES} 一致。 */
    public static final long MAX_BYTES = SourceStore.MAX_SOURCE_BYTES;

    private static final String[] EXTENSIONS = {".m3u", ".m3u8"};

    private M3uImport() {
    }

    /** 校验内容并返回解析出的频道数；失败抛中文原因。 */
    public static int validate(byte[] data, String baseUrl) throws ImportException {
        if (data == null || data.length == 0) {
            throw new ImportException("文件为空");
        }
        if (data.length > MAX_BYTES) {
            throw new ImportException("文件超过 5MB");
        }
        if (!looksLikeText(data)) {
            throw new ImportException("不是 m3u 文本（只接受 m3u/m3u8）");
        }
        try {
            Playlist parsed = PlaylistParser.parse(data, baseUrl);
            int count = parsed.channelCount();
            if (count <= 0) {
                throw new ImportException("没有可用频道");
            }
            return count;
        } catch (PlaylistParseException e) {
            String message = e.getMessage();
            throw new ImportException(message == null || message.isEmpty()
                    ? "不是 m3u 文本（只接受 m3u/m3u8）" : message);
        }
    }

    /** 文件名是否 .m3u/.m3u8（大小写不敏感）。 */
    public static boolean hasM3uExtension(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.trim().toLowerCase(Locale.US);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 由 URL 或文件名推一个可读源名：去掉目录、查询串与扩展名，空则用兜底。
     */
    public static String suggestName(String source, String fallback) {
        String value = source == null ? "" : source.trim();
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            value = value.substring(0, dot);
        }
        value = value.replace("%20", " ").trim();
        if (value.isEmpty()) {
            value = fallback;
        }
        if (value.length() > 24) {
            value = value.substring(0, 24);
        }
        return value;
    }

    /** 粗筛：NUL/控制字符视为二进制，避免把图片/压缩包当 m3u 存下来。 */
    private static boolean looksLikeText(byte[] data) {
        int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xff;
            if (b == 0) {
                return false;
            }
            if (b < 0x09 || (b > 0x0d && b < 0x20)) {
                return false;
            }
        }
        return true;
    }

    /** 导入失败（中文原因）。 */
    public static final class ImportException extends Exception {

        private static final long serialVersionUID = 1L;

        public ImportException(String message) {
            super(message);
        }
    }
}
