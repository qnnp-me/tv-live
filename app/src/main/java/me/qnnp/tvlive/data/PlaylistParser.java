package me.qnnp.tvlive.data;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * m3u/m3u8 频道表解析（纯 Java，无 Android 依赖）。
 *
 * <p>只处理频道表：{@code #EXTM3U}、{@code #EXTINF}、{@code #EXTGRP}、
 * {@code #EXTVLCOPT}、URL 行；{@code #KODIPROP} 忽略；出现 {@code #EXT-X-}
 * 视为 HLS 媒体列表并报错。输出保持源顺序，分组/编号交给 {@link ChannelSet}。</p>
 *
 * <p>按行流式解析：{@code InputStreamReader(BufferedReader)} 逐行读，不再
 * 「ByteBuffer → CharBuffer → String → split 行数组」一次性铺满整份文件，
 * 5MB 源的解析峰值从数十 MB 降到字节数组 + 单行缓冲。</p>
 */
public final class PlaylistParser {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 解码尝试顺序：UTF-8 严格优先，失败再退中文编码（GB18030 → GBK → GB2312）。 */
    private static final String[] CHARSETS = {"UTF-8", "GB18030", "GBK", "GB2312"};

    private static final int READ_BUFFER_CHARS = 8192;

    /** 仅 {@link #parseText(String, String)} 用（测试/已解码文本）；解析主路径不切行数组。 */
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");

    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]*))");

    /**
     * 名称尾部的质量/备注后缀：{@code (720p)}、{@code HD (1080p)}、{@code HD}、
     * {@code [Not 24/7]}、{@code [Geo-blocked]} 等。循环剥离直到不再匹配。
     */
    private static final Pattern QUALITY_SUFFIX_PATTERN = Pattern.compile(
            "\\s*(?:[（(\\[【]\\s*(?:not\\s*24\\s*/\\s*7|geo[-\\s]?blocked"
                    + "|\\d{3,4}[pi]|[248]k|fhd|uhd|hd|sd)\\s*[)）\\]】]"
                    + "|\\s+(?:\\d{3,4}[pi]|[248]k|fhd|uhd|hd|sd))\\s*$",
            Pattern.CASE_INSENSITIVE);


    private PlaylistParser() {
    }

    /**
     * 解析字节内容。
     *
     * @param data    源文件/响应原始字节
     * @param baseUrl 源 URL，用于解析相对地址；本地源可为 null
     * @return 解析结果（channels 保持源顺序，number 未分配）
     * @throws PlaylistParseException 编码不可识别、HLS 媒体列表、空文件或无频道
     */
    public static Playlist parse(byte[] data, String baseUrl) throws PlaylistParseException {
        if (data == null || data.length == 0) {
            throw new PlaylistParseException("播放列表为空");
        }
        int offset = bomOffset(data);
        if (offset >= data.length) {
            throw new PlaylistParseException("播放列表为空");
        }
        return parseStreamed(data, offset, baseUrl);
    }

    /** 已解码文本的解析入口（便于测试/复用）。走旧的整串切行，用作流式解析的回归对照。 */
    static Playlist parseText(String text, String baseUrl) throws PlaylistParseException {
        if (text == null) {
            throw new PlaylistParseException("播放列表为空");
        }
        Builder builder = new Builder(baseUrl);
        for (String rawLine : LINE_BREAK.split(text, -1)) {
            builder.line(rawLine);
        }
        return builder.finish();
    }

    /**
     * 逐行流式解析：先按 UTF-8 严格解码，任一处解码失败（含中途乱码）就换下一个
     * 编码重来，直到整份内容解码成功。解析错误（HLS/无频道）不重试。
     */
    private static Playlist parseStreamed(byte[] data, int offset, String baseUrl)
            throws PlaylistParseException {
        for (String charsetName : CHARSETS) {
            Builder builder = new Builder(baseUrl);
            try {
                readLines(new ByteArrayInputStream(data, offset, data.length - offset),
                        charsetName, builder);
                return builder.finish();
            } catch (CharacterCodingException ignored) {
                // 该编码解码失败：继续下一个候选
            } catch (UnsupportedCharsetException ignored) {
                // 设备不支持该字符集，继续下一个
            } catch (IOException e) {
                throw new PlaylistParseException(messageOf(e));
            }
        }
        throw new PlaylistParseException("无法识别播放列表编码（UTF-8 / GB18030 均失败）");
    }

    /** 用指定编码逐行喂给 builder；解码失败抛 {@link CharacterCodingException}。 */
    private static void readLines(InputStream raw, String charsetName, Builder builder)
            throws IOException, PlaylistParseException {
        CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        Reader reader = new InputStreamReader(raw, decoder);
        BufferedReader buffered = new BufferedReader(reader, READ_BUFFER_CHARS);
        try {
            String line;
            while ((line = buffered.readLine()) != null) {
                builder.line(line);
            }
        } finally {
            buffered.close();
        }
    }

    private static int bomOffset(byte[] data) {
        if (data.length >= 3
                && data[0] == UTF8_BOM[0] && data[1] == UTF8_BOM[1] && data[2] == UTF8_BOM[2]) {
            return 3;
        }
        return 0;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? "播放列表读取失败" : "播放列表读取失败: " + message;
    }

    /**
     * 字节解码：识别 UTF-8 BOM；UTF-8 严格解码失败转 GB18030（再退 GBK/GB2312）；
     * 全部失败抛错。
     */
    public static String decode(byte[] data) throws PlaylistParseException {
        if (data == null) {
            throw new PlaylistParseException("播放列表为空");
        }
        int offset = bomOffset(data);
        if (offset >= data.length) {
            throw new PlaylistParseException("播放列表为空");
        }
        for (String charsetName : CHARSETS) {
            try {
                return readText(new ByteArrayInputStream(data, offset, data.length - offset),
                        charsetName);
            } catch (CharacterCodingException ignored) {
                // 非该编码，继续下一个候选
            } catch (UnsupportedCharsetException ignored) {
                // 设备不支持该字符集，继续下一个
            } catch (IOException e) {
                throw new PlaylistParseException(messageOf(e));
            }
        }
        throw new PlaylistParseException("无法识别播放列表编码（UTF-8 / GB18030 均失败）");
    }

    private static String readText(InputStream raw, String charsetName) throws IOException {
        CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        Reader reader = new InputStreamReader(raw, decoder);
        try {
            StringBuilder out = new StringBuilder(READ_BUFFER_CHARS);
            char[] buffer = new char[READ_BUFFER_CHARS];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, read);
            }
            return out.toString();
        } finally {
            reader.close();
        }
    }

    /** 逐行解析的状态机；行内容语义与旧实现一致（trim 后按前缀分派）。 */
    private static final class Builder {

        private final String baseUrl;
        private final List<Channel> channels = new ArrayList<Channel>();
        private final Map<String, Integer> indices = new LinkedHashMap<String, Integer>();
        private int skipped;
        private boolean sawHeader;
        /** 是否出现过非空行；等价于旧实现的「整串 trim 后非空」。 */
        private boolean sawContent;
        private Pending pending;
        private String extGroup;
        private String pendingUserAgent;
        private String pendingReferer;

        Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        void line(String rawLine) throws PlaylistParseException {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) {
                return;
            }
            sawContent = true;
            String upper = line.toUpperCase(Locale.US);

            if (upper.startsWith("#EXT-X-")) {
                throw new PlaylistParseException("不是频道列表（HLS 媒体列表）");
            }
            if (upper.startsWith("#EXTM3U")) {
                sawHeader = true;
                return;
            }
            if (upper.startsWith("#EXTINF")) {
                pending = parseExtInf(line);
                return;
            }
            if (upper.startsWith("#EXTGRP:")) {
                // 粘性兜底：后续条目没有 group-title 时使用，直到下一个 #EXTGRP
                extGroup = cleanText(line.substring("#EXTGRP:".length()));
                return;
            }
            if (upper.startsWith("#EXTVLCOPT:")) {
                String option = line.substring("#EXTVLCOPT:".length()).trim();
                int eq = option.indexOf('=');
                if (eq > 0) {
                    String key = option.substring(0, eq).trim().toLowerCase(Locale.US);
                    String value = cleanText(option.substring(eq + 1));
                    if (value != null) {
                        if ("http-user-agent".equals(key)) {
                            pendingUserAgent = value;
                        } else if ("http-referrer".equals(key) || "http-referer".equals(key)) {
                            pendingReferer = value;
                        }
                    }
                }
                return;
            }
            if (upper.startsWith("#KODIPROP:")) {
                return;
            }
            if (line.startsWith("#")) {
                return;
            }

            String url = resolveUrl(line, baseUrl);

            Pending entry = pending;
            pending = null;

            String entryGroup = entry == null ? null : cleanText(entry.group);
            String entryName = entry == null ? null : cleanText(entry.name);
            if (isMetadataEntry(entryGroup, entryName, entry == null ? null : entry.tvgId)) {
                // 更新时间之类的源元数据不是频道；不计入 skipped（skipped 只统计非 http 源）。
                pendingUserAgent = null;
                pendingReferer = null;
                return;
            }

            if (url == null) {
                skipped++;
                return;
            }

            String name = entryName;
            if (name == null) {
                name = nameFromUrl(url);
            }
            if (name == null) {
                skipped++;
                pendingUserAgent = null;
                pendingReferer = null;
                return;
            }

            // tvg-id 已在 parseExtInf 里去掉 @SD/@HD 后缀
            String tvgId = entry == null ? null : entry.tvgId;
            // group-title 优先，EXTGRP 兜底；两者交给 ChannelSet 归一为 6 组
            String group = entryGroup != null ? entryGroup : extGroup;
            String logo = entry == null ? null : cleanText(entry.logoUrl);

            Variant variant = new Variant(url, pendingUserAgent, pendingReferer);
            pendingUserAgent = null;
            pendingReferer = null;

            // 同 tvg-id 优先合并；否则同 group + 规范化名称合并且为变体
            String key = tvgId != null
                    ? "id:" + tvgId
                    : "name:" + normalizeKey(group) + "|" + normalizeKey(name);
            Integer index = indices.get(key);
            if (index != null) {
                channels.get(index).addVariant(variant);
            } else {
                String id = tvgId != null ? tvgId : normalizeKey(group) + "|" + normalizeKey(name);
                Channel channel = new Channel(id, name, group, logo);
                channel.addVariant(variant);
                channels.add(channel);
                indices.put(key, channels.size() - 1);
            }
        }

        Playlist finish() throws PlaylistParseException {
            if (channels.isEmpty()) {
                if (!sawContent) {
                    throw new PlaylistParseException("播放列表为空");
                }
                throw new PlaylistParseException(sawHeader
                        ? "播放列表中没有可用频道"
                        : "不是有效的 m3u/m3u8 频道列表");
            }
            return new Playlist(channels, buildGroups(channels), skipped, null);
        }
    }

    private static Pending parseExtInf(String line) {
        String rest = line.substring("#EXTINF".length());
        if (rest.startsWith(":")) {
            rest = rest.substring(1);
        }
        int comma = firstTopLevelComma(rest);
        String head = comma >= 0 ? rest.substring(0, comma) : rest;
        String title = comma >= 0 ? rest.substring(comma + 1) : null;

        Map<String, String> attrs = parseAttributes(head);
        Pending pending = new Pending();
        pending.tvgId = normalizeTvgId(attrs.get("tvg-id"));
        pending.group = attrs.get("group-title");
        pending.logoUrl = attrs.get("tvg-logo");
        String name = cleanChannelName(title);
        if (name == null) {
            name = cleanChannelName(attrs.get("tvg-name"));
        }
        pending.name = name;
        return pending;
    }

    /**
     * 显示名清理：去掉尾部质量/备注后缀（720p/1080p/HD/[Not 24/7]/[Geo-blocked] 等）。
     * 清空时保留原名，避免把频道名剥没。
     */
    static String cleanChannelName(String value) {
        String cleaned = cleanText(value);
        if (cleaned == null) {
            return null;
        }
        String previous;
        do {
            previous = cleaned;
            cleaned = QUALITY_SUFFIX_PATTERN.matcher(cleaned).replaceFirst("");
            cleaned = cleanText(cleaned);
            if (cleaned == null) {
                return previous;
            }
        } while (!cleaned.equals(previous));
        return cleaned;
    }

    /** tvg-id 去掉 @ 后缀（CCTV1.cn@SD / @HD → CCTV1.cn）；空值返回 null。 */
    static String normalizeTvgId(String value) {
        String cleaned = cleanText(value);
        if (cleaned == null) {
            return null;
        }
        int at = cleaned.indexOf('@');
        if (at >= 0) {
            cleaned = cleanText(cleaned.substring(0, at));
        }
        return cleaned;
    }

    /** 「更新时间」这类行是源元数据（group-title / name / tvg-id 任一命中），不是频道。 */
    private static boolean isMetadataEntry(String group, String name, String tvgId) {
        return isMetadataLabel(group) || isMetadataLabel(name) || isMetadataLabel(tvgId);
    }

    private static boolean isMetadataLabel(String value) {
        if (value == null) {
            return false;
        }
        return value.toLowerCase(Locale.US).startsWith("更新时间");
    }

    private static Map<String, String> parseAttributes(String head) {
        Map<String, String> attrs = new LinkedHashMap<String, String>();
        Matcher matcher = ATTR_PATTERN.matcher(head);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.US);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (value != null && !attrs.containsKey(key)) {
                attrs.put(key, value);
            }
        }
        return attrs;
    }

    /** 标题里的逗号不算分隔：跳过引号内的逗号。 */
    private static int firstTopLevelComma(String text) {
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static List<Group> buildGroups(List<Channel> channels) {
        Map<String, List<Channel>> byGroup = new LinkedHashMap<String, List<Channel>>();
        for (Channel channel : channels) {
            String group = channel.group() == null ? "" : channel.group();
            List<Channel> list = byGroup.get(group);
            if (list == null) {
                list = new ArrayList<Channel>();
                byGroup.put(group, list);
            }
            list.add(channel);
        }
        List<Group> groups = new ArrayList<Group>(byGroup.size());
        for (Map.Entry<String, List<Channel>> entry : byGroup.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            groups.add(new Group(entry.getKey(), entry.getValue()));
        }
        return groups;
    }

    private static String resolveUrl(String line, String baseUrl) {
        if (isHttpUrl(line)) {
            return line;
        }
        if (line.indexOf("://") >= 0) {
            return null;
        }
        if (line.indexOf(' ') >= 0 || line.indexOf('\t') >= 0) {
            return null;
        }
        if (baseUrl == null || !isHttpUrl(baseUrl.trim())) {
            return null;
        }
        try {
            return new URL(new URL(baseUrl.trim()), line).toExternalForm();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** 无名称时取 URL 路径末段（去掉 query/fragment）；取不到返回 null。 */
    private static String nameFromUrl(String url) {
        if (url == null) {
            return null;
        }
        String path = url;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            path = path.substring(scheme + 3);
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        return cleanText(path.substring(slash + 1));
    }

    /**
     * 文本归一：全角空格/制表/换行转空格，连续空格折叠，去首尾空白；
     * 结果为空返回 null。
     */
    static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        boolean lastSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char normalized = (c == '\u3000' || c == '\t' || c == '\r' || c == '\n') ? ' ' : c;
            if (normalized == ' ') {
                if (!lastSpace) {
                    builder.append(' ');
                }
                lastSpace = true;
            } else {
                builder.append(normalized);
                lastSpace = false;
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** 名称/分组的比较键：cleanText + 大小写归一；null 归一为空串。 */
    static String normalizeKey(String value) {
        String cleaned = cleanText(value);
        return cleaned == null ? "" : cleaned.toLowerCase(Locale.US);
    }

    /** 一条 #EXTINF 解析结果，等待后面的 URL 行落地为 Channel/Variant。 */
    private static final class Pending {
        String name;
        String tvgId;
        String group;
        String logoUrl;
    }
}
