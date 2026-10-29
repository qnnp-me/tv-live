package me.qnnp.tvlive.player;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsMultivariantPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser;
import com.google.android.exoplayer2.upstream.DataReader;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableByteArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 起播前流探测：按成本从低到高，取第一个有效结果。
 *
 * <ol>
 *   <li>HLS master playlist 的 {@code RESOLUTION} / {@code CODECS}：一次 HTTP GET，最便宜；</li>
 *   <li>HLS media playlist 的第一个分片：下载前几 MB，先 {@link MediaExtractor}，再
 *       Exo {@link TsExtractor}/{@link FragmentedMp4Extractor}，不创建厂商解码器；</li>
 *   <li>非 HLS/无法解析：直接抓 URL 前几 MB 走同样的 Extractor；</li>
 *   <li>全部失败：返回 UNKNOWN，由调用方按设备策略决定（legacy 且 URL 命中已知 4K 时保守跳过）。</li>
 * </ol>
 *
 * <p>结果按 URL 缓存在进程内，避免切台重复下载。探测线程由调用方提供，本类阻塞执行。</p>
 */
public final class StreamProbe {

    private static final String TAG = "TvLive";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_PLAYLIST_BYTES = 1024 * 1024;
    private static final int MAX_SEGMENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_INIT_BYTES = 512 * 1024;
    private static final long CACHE_TTL_MS = 10L * 60L * 1000L;
    private static final long CACHE_TTL_UNKNOWN_MS = 60L * 1000L;

    private static final Map<String, Cached> CACHE =
            new ConcurrentHashMap<String, Cached>();

    private final Context context;
    private final VideoProfile profile;
    private final ProbeStore store;

    public StreamProbe(Context context, VideoProfile profile) {
        this.context = context;
        this.profile = profile;
        this.store = ProbeStore.get(context);
    }

    /**
     * 播放成功后把真实选中的 Format 记入白名单（即使预探测当时是 UNKNOWN）。
     * 下次同 URL 直接走白名单，不再下载分片。
     */
    public void remember(Variant variant, int width, int height, String mime) {
        if (variant == null || width <= 0 || height <= 0) {
            return;
        }
        if (!profile.withinLimit(width, height) || !profile.supportsMime(mime)) {
            return;
        }
        store.put(variant.url(), profile.label(),
                ProbeResult.supported(width, height, mime, "playback"));
        store.touch(variant.url(), profile.label());
    }

    /** 播放失败/看门狗超时：清掉该 URL 的白名单，下次重新探测。 */
    public static void invalidate(Context context, String url) {
        if (url == null) {
            return;
        }
        CACHE.remove(url);
        if (context != null) {
            ProbeStore.get(context).remove(url);
        }
    }

    public ProbeResult probe(Variant variant) {
        if (variant == null) {
            return ProbeResult.unknown("no-variant");
        }
        String url = variant.url();
        Cached cached = CACHE.get(url);
        long now = System.currentTimeMillis();
        if (cached != null) {
            long ttl = cached.result.isUnknown() ? CACHE_TTL_UNKNOWN_MS : CACHE_TTL_MS;
            if (now - cached.atMs <= ttl) {
                return cached.result;
            }
        }
        ProbeResult remembered = store.get(url, profile.label());
        if (remembered != null) {
            CACHE.put(url, new Cached(now, remembered));
            Log.i(TAG, "probe " + safeUrl(url) + " -> " + remembered
                    + " (whitelist) profile=" + profile.label());
            return remembered;
        }
        if (profile.legacy() && PlaybackLimits.isKnownBadUrl(url)) {
            ProbeResult knownBad = ProbeResult.unsupported(
                    "该源已在本机卡死过解码器，已跳过", "known-bad");
            CACHE.put(url, new Cached(now, knownBad));
            Log.w(TAG, "probe " + safeUrl(url) + " -> " + knownBad
                    + " profile=" + profile.label());
            return knownBad;
        }
        ProbeResult result;
        try {
            result = probeInner(variant);
        } catch (Throwable t) {
            Log.w(TAG, "probe failed " + safeUrl(url) + ": " + t);
            result = ProbeResult.unknown("error");
        }
        if (result.isUnknown() && profile.legacy() && PlaybackLimits.is4kUrl(url)) {
            // 只对“看起来像 4K”的未知源保守跳过；普通频道的 TS 分片可能
            // 过期/404/TLS 失败，探测失败不能因此把正常频道也拦掉。
            result = ProbeResult.unsupported("无法探测清晰度，按 4K 源保守跳过", "legacy-4k-unknown");
        }
        CACHE.put(url, new Cached(now, result));
        if (result.status() == ProbeResult.Status.SUPPORTED) {
            store.put(url, profile.label(), result);
        }
        Log.i(TAG, "probe " + safeUrl(url) + " -> " + result + " profile=" + profile.label());
        return result;
    }

    private ProbeResult probeInner(Variant variant) throws IOException {
        String url = variant.url();
        if (isPlaylistUrl(url)) {
            String text = fetchText(url, variant, MAX_PLAYLIST_BYTES);
            if (text != null && text.startsWith("#EXTM3U")) {
                HlsPlaylist playlist = new HlsPlaylistParser().parse(
                        Uri.parse(url), new ByteArrayInputStream(text.getBytes(UTF_8)));
                if (playlist instanceof HlsMasterPlaylist) {
                    ProbeResult r = probeMaster((HlsMasterPlaylist) playlist, variant);
                    if (r != null) {
                        return r;
                    }
                } else if (playlist instanceof HlsMediaPlaylist) {
                    ProbeResult r = probeMedia((HlsMediaPlaylist) playlist, url, variant);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        // 非 HLS / playlist 解析失败：抓 URL 头部按媒体字节探测。
        byte[] bytes = fetchBytes(url, variant, 0, -1, MAX_SEGMENT_BYTES);
        return parseBytes(bytes, "raw");
    }

    private ProbeResult probeMaster(HlsMasterPlaylist master, Variant variant) throws IOException {
        if (master.variants == null || master.variants.isEmpty()) {
            return ProbeResult.unknown("master-empty");
        }
        HlsMultivariantPlaylist.Variant best = null;
        HlsMultivariantPlaylist.Variant unknown = null;
        boolean allKnownTooLarge = true;
        String bestMime = null;
        for (HlsMultivariantPlaylist.Variant v : master.variants) {
            if (v == null || v.format == null) {
                continue;
            }
            int w = v.format.width;
            int h = v.format.height;
            if (w <= 0 || h <= 0) {
                if (unknown == null) {
                    unknown = v;
                }
                allKnownTooLarge = false;
                continue;
            }
            if (profile.withinLimit(w, h) && profile.supportsMime(mimeFromCodecs(v.format.codecs))) {
                best = v;
                bestMime = mimeFromCodecs(v.format.codecs);
                allKnownTooLarge = false;
            } else if (profile.withinLimit(w, h)) {
                // 尺寸可以但编码不支持：继续找其他 variant。
                allKnownTooLarge = false;
            }
        }
        if (best != null) {
            return ProbeResult.supported(best.format.width, best.format.height,
                    bestMime, "master-playlist");
        }
        if (unknown == null && allKnownTooLarge) {
            return ProbeResult.unsupported(
                    "本机最高支持 " + profile.maxWidth() + "x" + profile.maxHeight(), "master-playlist");
        }
        // master 没有可用的已知档（或全部未知）：挑最低码率/第一个 variant 的 media playlist 再探。
        HlsMultivariantPlaylist.Variant target = unknown != null ? unknown : master.variants.get(0);
        if (target.url == null) {
            return ProbeResult.unknown("master-no-url");
        }
        String childUrl = target.url.toString();
        String text = fetchText(childUrl, variant, MAX_PLAYLIST_BYTES);
        if (text != null && text.startsWith("#EXTM3U")) {
            HlsPlaylist child = new HlsPlaylistParser().parse(
                    Uri.parse(childUrl), new ByteArrayInputStream(text.getBytes(UTF_8)));
            if (child instanceof HlsMediaPlaylist) {
                ProbeResult r = probeMedia((HlsMediaPlaylist) child, childUrl, variant);
                if (r != null) {
                    return r;
                }
            }
        }
        return ProbeResult.unknown("master-unknown");
    }

    private ProbeResult probeMedia(HlsMediaPlaylist media, String playlistUrl, Variant variant)
            throws IOException {
        if (media.segments == null || media.segments.isEmpty()) {
            return ProbeResult.unknown("media-empty");
        }
        // 先探最新分片（live playlist 的第一个分片可能已过期/404），
        // 再探最老分片；两边都看，覆盖中途切 4K 的情况。
        ProbeResult last = probeSegment(media.segments.get(media.segments.size() - 1),
                playlistUrl, variant);
        if (last != null && last.isUnsupported()) {
            return last;
        }
        ProbeResult first = probeSegment(media.segments.get(0), playlistUrl, variant);
        if (first != null && first.isUnsupported()) {
            return first;
        }
        if (last == null && first == null) {
            return ProbeResult.unknown("media-segments");
        }
        if (last == null) {
            return first;
        }
        if (first == null) {
            return last;
        }
        // 两个分片都有效：按较大的分辨率返回，避免 1080p -> 4K 中途切换漏判。
        if (first.width() > last.width() || first.height() > last.height()) {
            return first;
        }
        return last;
    }

    private ProbeResult probeSegment(HlsMediaPlaylist.Segment segment, String playlistUrl,
                                     Variant variant) throws IOException {
        if (segment == null || segment.url == null) {
            return null;
        }
        if (segment.fullSegmentEncryptionKeyUri != null || segment.drmInitData != null) {
            return ProbeResult.unknown("segment-encrypted");
        }
        java.net.URI base;
        try {
            base = java.net.URI.create(playlistUrl);
        } catch (IllegalArgumentException e) {
            return ProbeResult.unknown("media-bad-url");
        }
        java.net.URI segmentUri = base.resolve(segment.url);
        if (segmentUri == null) {
            return ProbeResult.unknown("media-bad-url");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (segment.initializationSegment != null
                && segment.initializationSegment.url != null) {
            java.net.URI initUri = base.resolve(segment.initializationSegment.url);
            byte[] init = fetchBytes(initUri == null ? segment.initializationSegment.url
                            : initUri.toString(), variant, 0, -1, MAX_INIT_BYTES);
            if (init == null) {
                return null;
            }
            out.write(init);
        }
        long rangeStart = segment.byteRangeOffset;
        long rangeEnd = segment.byteRangeLength > 0
                ? rangeStart + segment.byteRangeLength - 1L : -1L;
        byte[] bytes = fetchBytes(segmentUri.toString(), variant, rangeStart, rangeEnd,
                MAX_SEGMENT_BYTES);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        out.write(bytes);
        return parseBytes(out.toByteArray(), "hls-segment");
    }

    private ProbeResult parseBytes(byte[] bytes, String method) {
        if (bytes == null || bytes.length == 0) {
            return ProbeResult.unknown(method + "-empty");
        }
        File file = null;
        try {
            file = File.createTempFile("tvlive-probe-", ".bin", context.getCacheDir());
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            ProbeResult mediaExtractor = parseWithMediaExtractor(file, method + "-mediaextractor");
            if (mediaExtractor != null) {
                return mediaExtractor;
            }
            ProbeResult exo = parseWithExo(file, method + "-exo");
            if (exo != null) {
                return exo;
            }
            return ProbeResult.unknown(method);
        } catch (Throwable t) {
            Log.w(TAG, "parse bytes failed: " + t);
            return ProbeResult.unknown(method);
        } finally {
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private ProbeResult parseWithMediaExtractor(File file, String method) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                int w = format.containsKey(MediaFormat.KEY_WIDTH)
                        ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
                int h = format.containsKey(MediaFormat.KEY_HEIGHT)
                        ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
                ProbeResult result = decide(w, h, mime, method);
                if (result != null) {
                    return result;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "MediaExtractor probe failed: " + t);
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
        return null;
    }

    private ProbeResult parseWithExo(File file, String method) {
        FormatCaptureOutput output = new FormatCaptureOutput();
        Extractor extractor = sniffExtractor(file);
        if (extractor == null) {
            return null;
        }
        FileDataSource dataSource = new FileDataSource();
        try {
            dataSource.open(new DataSpec(Uri.fromFile(file)));
            DefaultExtractorInput input = new DefaultExtractorInput(
                    dataSource, 0, file.length());
            extractor.init(output);
            PositionHolder position = new PositionHolder();
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                int result = extractor.read(input, position);
                if (result == Extractor.RESULT_END_OF_INPUT) {
                    break;
                }
                if (result == Extractor.RESULT_SEEK) {
                    input.resetPeekPosition();
                }
                Format format = output.videoFormat;
                if (format != null) {
                    ProbeResult r = decide(format.width, format.height,
                            format.sampleMimeType, method);
                    if (r != null) {
                        return r;
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "Exo extractor probe failed: " + t);
        } finally {
            try {
                dataSource.close();
            } catch (IOException ignored) {
                // ignore
            }
            extractor.release();
        }
        return null;
    }

    private Extractor sniffExtractor(File file) {
        byte[] head = new byte[16];
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            int read = in.read(head);
            if (read >= 4) {
                if (head[0] == 0x47 || (head.length > 188 && head[188] == 0x47)) {
                    return new TsExtractor(TsExtractor.MODE_HLS);
                }
                if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                    return new FragmentedMp4Extractor();
                }
            }
        } catch (Throwable ignored) {
            // fall through
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        // 最后尝试按 fMP4（HLS init+segment 常见）
        return new FragmentedMp4Extractor();
    }

    private ProbeResult decide(int width, int height, String mime, String method) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        if (!profile.withinLimit(width, height)) {
            return ProbeResult.unsupported("本机最高支持 "
                    + profile.maxWidth() + "x" + profile.maxHeight()
                    + "，源为 " + width + "x" + height, method);
        }
        if (mime != null && !profile.supportsMime(mime)) {
            return ProbeResult.unsupported("本机不支持该视频编码: " + mime, method);
        }
        return ProbeResult.supported(width, height, mime, method);
    }

    private static String mimeFromCodecs(String codecs) {
        if (codecs == null || codecs.isEmpty()) {
            return null;
        }
        for (String codec : codecs.split(",")) {
            String c = codec.trim().toLowerCase(Locale.US);
            if (c.startsWith("avc1") || c.startsWith("avc3")) {
                return MimeTypes.VIDEO_H264;
            }
            if (c.startsWith("hvc1") || c.startsWith("hev1")) {
                return MimeTypes.VIDEO_H265;
            }
            if (c.startsWith("av01")) {
                return MimeTypes.VIDEO_AV1;
            }
            if (c.startsWith("vp09") || c.startsWith("vp08")) {
                return MimeTypes.VIDEO_VP9;
            }
        }
        return null;
    }

    private String fetchText(String url, Variant variant, int maxBytes) throws IOException {
        byte[] bytes = fetchBytes(url, variant, 0, -1, maxBytes);
        return bytes == null ? null : new String(bytes, UTF_8);
    }

    /**
     * @param rangeStart -1 表示不带 Range；rangeEnd >= 0 时带 Range
     */
    private byte[] fetchBytes(String url, Variant variant, long rangeStart, long rangeEnd,
                              int maxBytes) throws IOException {
        return fetchBytes(url, variant, rangeStart, rangeEnd, maxBytes, 0);
    }

    private byte[] fetchBytes(String url, Variant variant, long rangeStart, long rangeEnd,
                              int maxBytes, int redirects) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "*/*");
            String ua = variant.userAgent();
            if (ua != null) {
                connection.setRequestProperty("User-Agent", ua);
            }
            String referer = variant.referer();
            if (referer != null) {
                connection.setRequestProperty("Referer", referer);
            }
            if (rangeStart >= 0 && rangeEnd >= 0) {
                connection.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd);
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400 && redirects < 3) {
                String location = connection.getHeaderField("Location");
                if (location != null && !location.isEmpty()) {
                    String next = new URL(new URL(url), location).toString();
                    return fetchBytes(next, variant, rangeStart, rangeEnd, maxBytes,
                            redirects + 1);
                }
            }
            if (code < 200 || code >= 300) {
                Log.i(TAG, "probe http " + code + " " + safeUrl(url));
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            while (total < maxBytes) {
                int read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                total += read;
            }
            in.close();
            return out.toByteArray();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String safeUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            return uri.getHost() + uri.getPath();
        } catch (Throwable t) {
            return "<url>";
        }
    }

    private static boolean isPlaylistUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private static final class Cached {
        final long atMs;
        final ProbeResult result;

        Cached(long atMs, ProbeResult result) {
            this.atMs = atMs;
            this.result = result;
        }
    }

    /** 只抓 Format，不消费真实样本；Extractor 需要 sampleData 时直接读掉。 */
    private static final class FormatCaptureOutput implements ExtractorOutput {

        Format videoFormat;

        @Override
        public TrackOutput track(int id, int type) {
            return new CapturingTrackOutput(this);
        }

        @Override
        public void endTracks() {
            // no-op
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            // no-op
        }
    }

    private static final class CapturingTrackOutput implements TrackOutput {

        private final FormatCaptureOutput owner;

        CapturingTrackOutput(FormatCaptureOutput owner) {
            this.owner = owner;
        }

        @Override
        public void format(Format format) {
            if (format != null && format.sampleMimeType != null
                    && format.sampleMimeType.startsWith("video/")
                    && owner.videoFormat == null) {
                owner.videoFormat = format;
            }
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput,
                              int sampleDataPart) throws IOException {
            byte[] buffer = new byte[Math.min(length, 16 * 1024)];
            int total = 0;
            while (total < length) {
                int read = input.read(buffer, 0, Math.min(buffer.length, length - total));
                if (read == C.RESULT_END_OF_INPUT) {
                    return total == 0 ? C.RESULT_END_OF_INPUT : total;
                }
                total += read;
            }
            return total;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            data.skipBytes(length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                   CryptoData cryptoData) {
            // no-op
        }
    }
}
