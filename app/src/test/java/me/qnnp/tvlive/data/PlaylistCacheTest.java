package me.qnnp.tvlive.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;
import me.qnnp.tvlive.data.SourceRecord.Kind;

/**
 * 批次 13：解析结果缓存 + 流式解析。
 *
 * <p>缓存断言用 {@link PlaylistRepository#cacheStats()} 的计数（命中不再 read()+parse），
 * 流式解析用旧的整串切行实现（{@code parseText}）作回归对照，逐字段比较。</p>
 */
public class PlaylistCacheTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Before
    public void resetCache() {
        PlaylistRepository.resetParsedCacheForTests();
    }

    // ---------------------------------------------------------------- 缓存命中

    @Test
    public void cacheHitSkipsReadAndParseWhenSourceUnchanged() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(fileSource("s1", "源A", 0));
            SourceStore.save(dir, records);
            SourceStore.writeCacheFile(dir, records.get(0), utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\nhttp://a/cctv1\n"
                    + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/hunan\n"));

            PlaylistRepository.MultiPlaylist first = PlaylistRepository.loadAll(dir);
            assertEquals(2, first.playlist().channelCount());
            assertEquals(1, PlaylistRepository.cacheStats().parses());
            assertEquals(0, PlaylistRepository.cacheStats().hits());

            // 模拟切后台再回前台：源文件没变，不应再解析。
            PlaylistRepository.MultiPlaylist second = PlaylistRepository.loadAll(dir);
            assertEquals(2, second.playlist().channelCount());
            assertEquals("命中后不得重复解析", 1, PlaylistRepository.cacheStats().parses());
            assertEquals(1, PlaylistRepository.cacheStats().hits());

            PlaylistRepository.MultiPlaylist third = PlaylistRepository.loadAll(dir);
            assertEquals(2, third.playlist().channelCount());
            assertEquals(1, PlaylistRepository.cacheStats().parses());
            assertEquals(2, PlaylistRepository.cacheStats().hits());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void unchangedSourceStillHitAfterIndexWriteBack() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache-index").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(fileSource("s1", "源A", 0));
            SourceStore.save(dir, records);
            SourceStore.writeCacheFile(dir, records.get(0), utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道A\nhttp://a/1\n"));

            // 首次加载会把频道数写回 sources.json；索引变化不该让解析缓存失效。
            PlaylistRepository.loadAll(dir);
            PlaylistRepository.loadAll(dir);
            assertEquals(1, PlaylistRepository.cacheStats().parses());
            assertEquals(1, PlaylistRepository.cacheStats().hits());
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- 失效

    @Test
    public void rewriteThroughRepositoryInvalidatesCache() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache-rewrite").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(fileSource("s1", "源A", 0));
            SourceStore.save(dir, records);
            SourceRecord record = records.get(0);

            SourceStore.writeCacheFile(dir, record, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道A\nhttp://a/1\n"));
            assertEquals("频道A",
                    PlaylistRepository.loadAll(dir).playlist().channels().get(0).name());

            // 同长度、可能同毫秒的新内容：原子写挂钩了显式失效，不能返回旧快照。
            SourceStore.writeCacheFile(dir, record, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道B\nhttp://a/2\n"));
            assertEquals("频道B",
                    PlaylistRepository.loadAll(dir).playlist().channels().get(0).name());
            assertEquals(2, PlaylistRepository.cacheStats().parses());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void lengthAndMtimeChangesInvalidateCache() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache-stat").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(fileSource("s1", "源A", 0));
            SourceStore.save(dir, records);
            SourceRecord record = records.get(0);
            File cache = SourceStore.cacheFile(dir, record);

            SourceStore.writeCacheFile(dir, record, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道A\nhttp://a/1\n"));
            assertEquals("频道A",
                    PlaylistRepository.loadAll(dir).playlist().channels().get(0).name());

            // 绕过仓库直接改文件（模拟外部写入）：长度变化 → 立刻失效。
            writeRaw(cache, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道BB\nhttp://a/2\n"));
            Playlist parsed = PlaylistRepository.loadAll(dir).playlist();
            assertEquals(1, parsed.channelCount());
            assertEquals("频道BB", parsed.channels().get(0).name());

            // 长度不变、只把 mtime 往后拨（内容同长度） → 也要重新解析。
            writeRaw(cache, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道CC\nhttp://a/3\n"));
            assertTrue(cache.setLastModified(cache.lastModified() + 5000L));
            Playlist bumped = PlaylistRepository.loadAll(dir).playlist();
            assertEquals("频道CC", bumped.channels().get(0).name());
            assertEquals(3, PlaylistRepository.cacheStats().parses());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void deletedSourceDisappearsAndOtherSourcesKeepCache() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache-delete").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(
                    fileSource("s1", "源A", 0),
                    fileSource("s2", "源B", 1));
            SourceStore.save(dir, records);
            SourceStore.writeCacheFile(dir, records.get(0), utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道A\nhttp://a/1\n"));
            SourceStore.writeCacheFile(dir, records.get(1), utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道B\nhttp://b/1\n"));
            assertEquals(2, PlaylistRepository.loadAll(dir).playlist().channelCount());
            assertEquals(2, PlaylistRepository.cacheStats().parses());

            SourceStore.deleteSource(dir, "s1");

            PlaylistRepository.MultiPlaylist after = PlaylistRepository.loadAll(dir);
            assertEquals(1, after.playlist().channelCount());
            assertEquals("频道B", after.playlist().channels().get(0).name());
            // 只剩源 B 仍在缓存里：不解析、只命中。
            assertEquals(2, PlaylistRepository.cacheStats().parses());
            assertEquals(1, PlaylistRepository.cacheStats().hits());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void sourceLocationChangeReresolvesRelativeUrls() throws Exception {
        File dir = Files.createTempDirectory("tvlive-cache-base").toFile();
        try {
            SourceRecord record = new SourceRecord("s1", "远程源", Kind.URL,
                    "http://example.com/list/tv.m3u", true, 0, 0L, 0, null,
                    "sources/s1.m3u");
            SourceStore.save(dir, Arrays.asList(record));
            SourceStore.writeCacheFile(dir, record, utf8("#EXTM3U\n"
                    + "#EXTINF:-1,频道A\nstream/1.m3u8\n"));

            Playlist first = PlaylistRepository.loadAll(dir).playlist();
            assertEquals("http://example.com/list/stream/1.m3u8",
                    first.channels().get(0).variants().get(0).url());

            // 源地址变了：相对地址要按新 base 重新解析（缓存 key 含 baseUrl）。
            SourceRecord moved = record.withLocation(Kind.URL, "http://cdn.example.org/x/tv.m3u",
                    System.currentTimeMillis(), 0);
            SourceStore.save(dir, Arrays.asList(moved));

            Playlist second = PlaylistRepository.loadAll(dir).playlist();
            assertEquals("http://cdn.example.org/x/stream/1.m3u8",
                    second.channels().get(0).variants().get(0).url());
            assertEquals(2, PlaylistRepository.cacheStats().parses());
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- 流式解析回归

    @Test
    public void streamingParseMatchesLegacySplitParse() throws Exception {
        String text = "#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1.cn@HD\" tvg-name=\"一套\" tvg-logo=\"http://l/1.png\""
                + " group-title=\"央视\",CCTV-1 综合 (1080p)\r\n"
                + "#EXTVLCOPT:http-user-agent=MyUA\r\n"
                + "#EXTVLCOPT:http-referrer=http://ref/x\r\n"
                + "http://example.com/1.m3u8\r\n"
                + "\r\n"
                + "#EXTGRP:地方\r\n"
                + "#EXTINF:-1,\u3000湖南\u3000卫视\u3000\r\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视\",CCTV-1 综合\r\n"
                + "http://example.com/1b.m3u8\r"
                + "#KODIPROP:inputstream=application/x-mpegURL\n"
                + "#EXTINF:-1 group-title=\"更新时间\",更新时间 20240101\n"
                + "http://example.com/updated\n"
                + "#EXTINF:-1,\n"
                + "http://example.com/chan9.m3u8\n"
                + "#EXTINF:-1,rtmp 频道\n"
                + "rtmp://example.com/live\n"
                + "#EXTINF:-1,相对地址\n"
                + "rel/3.m3u8\n"
                + "#COMMENT 忽略\n";

        Playlist streamed = PlaylistParser.parse(utf8(text), "http://example.com/list/tv.m3u");
        Playlist legacy = PlaylistParser.parseText(text, "http://example.com/list/tv.m3u");

        assertSamePlaylist(legacy, streamed);
        assertEquals(5, streamed.channelCount());
        assertEquals(1, streamed.skipped());
        // 相对地址按 baseUrl 解析
        assertEquals("http://example.com/list/rel/3.m3u8",
                streamed.channels().get(4).variants().get(0).url());
    }

    @Test
    public void streamingParseMatchesLegacyForBomAndGb18030() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"地方\",浙江卫视\n"
                + "http://example.com/zj.m3u8\n"
                + "#EXTINF:-1,CCTV-13 新闻\n"
                + "http://example.com/c13.m3u8\n";

        byte[] bom = withBom(utf8(text));
        assertSamePlaylist(PlaylistParser.parseText(text, null), PlaylistParser.parse(bom, null));

        byte[] gb = text.getBytes(Charset.forName("GB18030"));
        Playlist parsed = PlaylistParser.parse(gb, null);
        assertSamePlaylist(PlaylistParser.parseText(text, null), parsed);
        assertEquals("浙江卫视", parsed.channels().get(0).name());
    }

    @Test
    public void malformedUtf8FallsBackMidStreamWithoutLosingEarlierChannels() throws Exception {
        // 前几条是 ASCII（UTF-8 能解），中文出现在后面 → 编码回退发生在解析中途，
        // 必须整份按 GB18030 重解析，而不是保留半截结果。
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,ASCII 频道\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1,中文频道\n"
                + "http://example.com/2.m3u8\n";
        byte[] gb = text.getBytes(Charset.forName("GB18030"));

        Playlist parsed = PlaylistParser.parse(gb, null);

        assertSamePlaylist(PlaylistParser.parseText(text, null), parsed);
        assertEquals(2, parsed.channelCount());
        assertEquals("中文频道", parsed.channels().get(1).name());
    }

    @Test
    public void parseErrorsKeepSameMessagesAsBefore() {
        assertMessage("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttp://e/m.m3u8\n",
                "不是频道列表（HLS 媒体列表）");
        assertMessage("#EXTM3U\n#EXTGRP:央视\n", "播放列表中没有可用频道");
        assertMessage("#EXTGRP:央视\n", "不是有效的 m3u/m3u8 频道列表");
        assertMessage("\n\r\n   \n", "播放列表为空");
    }

    /** 5MB 量级：逐行流式解析，行数/频道数正确（峰值内存由实现保证，不做 JVM 堆断言）。 */
    @Test
    public void parsesFiveMegabyteSourceLineByLine() throws Exception {
        final int channels = 58000;
        StringBuilder text = new StringBuilder(channels * 96);
        text.append("#EXTM3U\n");
        for (int i = 1; i <= channels; i++) {
            text.append("#EXTINF:-1 tvg-id=\"c").append(i)
                    .append("\" group-title=\"央视\",CCTV-").append(i).append('\n')
                    .append("http://example.com/live/").append(i).append(".m3u8\n");
        }
        byte[] data = utf8(text.toString());
        assertTrue("用例源应达到 5MB 量级，实际 " + data.length,
                data.length >= 5L * 1024L * 1024L);

        Playlist parsed = PlaylistParser.parse(data, null);

        assertEquals(channels, parsed.channelCount());
        assertEquals(0, parsed.skipped());
        assertEquals("c1", parsed.channels().get(0).id());
        assertEquals("CCTV-" + channels, parsed.channels().get(channels - 1).name());
        assertEquals("http://example.com/live/" + channels + ".m3u8",
                parsed.channels().get(channels - 1).variants().get(0).url());
    }

    // ---------------------------------------------------------------- helpers

    private static void assertSamePlaylist(Playlist expected, Playlist actual) {
        assertEquals(expected.channelCount(), actual.channelCount());
        assertEquals(expected.skipped(), actual.skipped());
        assertEquals(expected.error(), actual.error());
        for (int i = 0; i < expected.channelCount(); i++) {
            Channel left = expected.channels().get(i);
            Channel right = actual.channels().get(i);
            assertEquals(left.id(), right.id());
            assertEquals(left.name(), right.name());
            assertEquals(left.group(), right.group());
            assertEquals(left.logoUrl(), right.logoUrl());
            assertEquals(left.variants().size(), right.variants().size());
            for (int v = 0; v < left.variants().size(); v++) {
                Variant lv = left.variants().get(v);
                Variant rv = right.variants().get(v);
                assertEquals(lv.url(), rv.url());
                assertEquals(lv.userAgent(), rv.userAgent());
                assertEquals(lv.referer(), rv.referer());
            }
        }
        assertEquals(expected.groups().size(), actual.groups().size());
    }

    private static void assertMessage(String text, String expected) {
        try {
            PlaylistParser.parse(utf8(text), null);
            org.junit.Assert.fail("应当抛出 PlaylistParseException: " + expected);
        } catch (PlaylistParseException e) {
            assertEquals(expected, e.getMessage());
        }
    }

    private static SourceRecord fileSource(String id, String name, int order) {
        return new SourceRecord(id, name, Kind.FILE, "/tmp/" + id + ".m3u",
                true, order, 0L, 0, null, null);
    }

    /** 绕过仓库直接写文件，模拟外部改写（不触发显式失效）。 */
    private static void writeRaw(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        RandomAccessFile out = new RandomAccessFile(file, "rw");
        try {
            out.setLength(0L);
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(byte[] body) {
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
