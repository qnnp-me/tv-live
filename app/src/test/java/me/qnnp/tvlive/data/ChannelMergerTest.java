package me.qnnp.tvlive.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import me.qnnp.tvlive.data.ChannelMerger.SourcePlaylist;
import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;
import me.qnnp.tvlive.data.SourceRecord.Kind;

/**
 * 批次 2f：多源合并（同名合并 / tvg-id 优先 / 源 order 优先级 / URL 去重 / 坏源隔离）。
 */
public class ChannelMergerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    // -------------------------------------------------------- 合并键

    @Test
    public void mergesTvgIdChannelWithNameOnlyChannel() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n"
                        + "#EXTINF:-1 tvg-id=\"CCTV1.cn\" group-title=\"央视\",CCTV-1 综合\n"
                        + "http://a.example.com/1.m3u8\n"
                        + "#EXTINF:-1 tvg-id=\"CCTV1.cn\" group-title=\"央视\",CCTV-1 综合 HD\n"
                        + "http://a.example.com/2.m3u8\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"央视频道\",CCTV-1 综合\n"
                        + "http://b.example.com/1.m3u8\n");

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(a, b));
        Playlist playlist = result.playlist();

        assertEquals(1, playlist.channelCount());
        Channel channel = playlist.channels().get(0);
        assertEquals("CCTV-1 综合", channel.name());
        assertEquals("源A", channel.sourceName());
        assertEquals(ChannelSet.GROUP_CCTV, channel.group());
        assertEquals("央视", channel.sourceGroup());
        assertEquals(3, channel.variants().size());
        assertEquals("http://a.example.com/1.m3u8", channel.variants().get(0).url());
        assertEquals("http://a.example.com/2.m3u8", channel.variants().get(1).url());
        assertEquals("http://b.example.com/1.m3u8", channel.variants().get(2).url());
        assertEquals("源A", channel.variants().get(0).sourceName());
        assertEquals("源B", channel.variants().get(2).sourceName());
    }

    @Test
    public void foldsConflictingKeysIntoOneChannelKeepingSourceOrder() throws Exception {
        SourcePlaylist s1 = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"自定义\",频道一\nhttp://a/1.m3u8\n");
        SourcePlaylist s2 = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"X\" group-title=\"自定义\",频道二\n"
                        + "http://b/1.m3u8\n");
        SourcePlaylist s3 = source("s3", "源C", 2,
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"X\" group-title=\"自定义\",频道一\n"
                        + "http://c/1.m3u8\n");

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(s1, s2, s3));

        assertEquals(1, result.playlist().channelCount());
        List<Variant> variants = result.playlist().channels().get(0).variants();
        assertEquals(3, variants.size());
        assertEquals("http://a/1.m3u8", variants.get(0).url());
        assertEquals("http://b/1.m3u8", variants.get(1).url());
        assertEquals("http://c/1.m3u8", variants.get(2).url());
        assertEquals("源A", variants.get(0).sourceName());
    }

    @Test
    public void sourceOrderDecidesLinePriorityEvenWhenInputsUnsorted() throws Exception {
        SourcePlaylist later = source("s2", "二源", 5,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://b.example.com/hunan\n");
        SourcePlaylist earlier = source("s1", "一源", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a.example.com/hunan\n");

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(later, earlier));
        Channel channel = result.playlist().channels().get(0);

        assertEquals(2, channel.variants().size());
        assertEquals("http://a.example.com/hunan", channel.variants().get(0).url());
        assertEquals("http://b.example.com/hunan", channel.variants().get(1).url());
        assertEquals("一源", channel.sourceName());
    }

    @Test
    public void dedupesSameUrlAcrossSourcesKeepingFirstOccurrence() throws Exception {
        String url = "http://same.example.com/cctv1.m3u8";
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n" + url + "\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n" + url + "\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n"
                        + "http://b.example.com/2.m3u8\n");

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(a, b));

        assertEquals(1, result.playlist().channelCount());
        List<Variant> variants = result.playlist().channels().get(0).variants();
        assertEquals(2, variants.size());
        assertEquals(url, variants.get(0).url());
        assertEquals("源A", variants.get(0).sourceName());
    }

    @Test
    public void differentChannelsStaySeparateAndKeepSourceOrder() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"地方\",北京新闻\nhttp://a/bj\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV-5 体育\nhttp://a/cctv5\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"地方\",北京新闻\nhttp://b/bj\n"
                        + "#EXTINF:-1 group-title=\"地方\",上海新闻\nhttp://b/sh\n");

        Playlist playlist = ChannelMerger.merge(Arrays.asList(a, b)).playlist();

        assertEquals(3, playlist.channelCount());
        // 固定组顺序：央视在前，地方在后；组内保持源顺序。
        assertEquals("CCTV-5 体育", playlist.channels().get(0).name());
        assertEquals("北京新闻", playlist.channels().get(1).name());
        assertEquals("上海新闻", playlist.channels().get(2).name());
        assertEquals(2, playlist.channels().get(1).variants().size());
        assertEquals(1, playlist.channels().get(2).variants().size());
    }

    @Test
    public void mergesOtherSubgroupsByFirstSourceGroupTitle() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"纪录频道\",纪录一\nhttp://a/doc\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"纪录片\",纪录一\nhttp://b/doc\n");

        Channel channel = ChannelMerger.merge(Arrays.asList(a, b)).playlist().channels().get(0);

        assertEquals(ChannelSet.GROUP_OTHER, channel.group());
        assertEquals("纪录频道", channel.sourceGroup());
        assertEquals(2, channel.variants().size());
    }

    // -------------------------------------------------------- 名称归一/别名（批次 16）

    @Test
    public void mergesSpellingAndQualityVariantsWithinAndAcrossSources() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV5\nhttp://a/c5\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV-5\nhttp://a/c5b\n"
                        + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/hunan\n"
                        + "#EXTINF:-1 group-title=\"卫视\",湖南卫视HD\nhttp://a/hunanhd\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"央视\",CCTV 5\nhttp://b/c5\n"
                        + "#EXTINF:-1 group-title=\"卫视\",Hunan TV HD\nhttp://b/hunan\n");

        Playlist playlist = ChannelMerger.merge(Arrays.asList(a, b)).playlist();

        assertEquals(2, playlist.channelCount());
        Channel cctv5 = playlist.channels().get(0);
        assertEquals("CCTV5", cctv5.name());
        assertEquals(1, cctv5.number());
        assertEquals(3, cctv5.variants().size());
        assertEquals("http://a/c5", cctv5.variants().get(0).url());
        assertEquals("http://a/c5b", cctv5.variants().get(1).url());
        assertEquals("http://b/c5", cctv5.variants().get(2).url());

        Channel hunan = playlist.channels().get(1);
        assertEquals("湖南卫视", hunan.name());
        assertEquals(2, hunan.number());
        assertEquals(3, hunan.variants().size());
        assertEquals("http://a/hunan", hunan.variants().get(0).url());
        assertEquals("http://a/hunanhd", hunan.variants().get(1).url());
        assertEquals("http://b/hunan", hunan.variants().get(2).url());
    }

    @Test
    public void prefersChineseDisplayNameWhenEnglishComesFirst() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",Hunan TV\nhttp://a/hunan\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://b/hunan\n");

        Channel channel = ChannelMerger.merge(Arrays.asList(a, b)).playlist().channels().get(0);

        assertEquals("湖南卫视", channel.name());
        // 源名/台号仍取条目第一个频道
        assertEquals("源A", channel.sourceName());
        assertEquals(2, channel.variants().size());
        assertEquals("http://a/hunan", channel.variants().get(0).url());
        assertEquals("http://b/hunan", channel.variants().get(1).url());
    }

    @Test
    public void keepsChineseDisplayNameWhenEnglishComesLater() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/hunan\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"卫视\",Hunan TV\nhttp://b/hunan\n");

        Channel channel = ChannelMerger.merge(Arrays.asList(a, b)).playlist().channels().get(0);

        assertEquals("湖南卫视", channel.name());
        assertEquals(2, channel.variants().size());
    }

    @Test
    public void doesNotMergeCctv5PlusWithCctv5() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV5\nhttp://a/5\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV5+\nhttp://b/5p\n");

        Playlist playlist = ChannelMerger.merge(Arrays.asList(a, b)).playlist();

        assertEquals(2, playlist.channelCount());
        assertEquals("CCTV5", playlist.channels().get(0).name());
        assertEquals("CCTV5+", playlist.channels().get(1).name());
        assertEquals(1, playlist.channels().get(0).variants().size());
        assertEquals(1, playlist.channels().get(1).variants().size());
    }

    @Test
    public void doesNotMergeSimilarLocalChannelNames() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n#EXTINF:-1 group-title=\"地方\",安顺新闻综合频道\nhttp://a/as\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n#EXTINF:-1 group-title=\"地方\",安庆新闻综合频道\nhttp://b/aq\n");

        Playlist playlist = ChannelMerger.merge(Arrays.asList(a, b)).playlist();

        assertEquals(2, playlist.channelCount());
        assertEquals("安顺新闻综合频道", playlist.channels().get(0).name());
        assertEquals("安庆新闻综合频道", playlist.channels().get(1).name());
    }

    @Test
    public void doesNotMergeSameNameAcrossFinalGroups() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"港澳台\",凤凰卫视\nhttp://a/phoenix\n"
                        + "#EXTINF:-1 group-title=\"港澳台\",翡翠台\nhttp://a/jade\n");
        SourcePlaylist b = source("s2", "源B", 1,
                "#EXTM3U\n"
                        + "#EXTINF:-1 group-title=\"其他\",Phoenix TV\nhttp://b/phoenix\n"
                        + "#EXTINF:-1 group-title=\"地方\",Jade\nhttp://b/jade\n");

        Playlist playlist = ChannelMerger.merge(Arrays.asList(a, b)).playlist();

        assertEquals(4, playlist.channelCount());
        for (Channel channel : playlist.channels()) {
            assertEquals(1, channel.variants().size());
        }
    }

    @Test
    public void truncatesMergedChannelsAtLimit() throws Exception {
        StringBuilder text = new StringBuilder("#EXTM3U\n");
        for (int i = 1; i <= SourceStore.MAX_MERGED_CHANNELS + 1; i++) {
            text.append("#EXTINF:-1 group-title=\"自定义\",频道").append(i).append('\n')
                    .append("http://example.com/").append(i).append(".m3u8\n");
        }
        SourcePlaylist a = source("s1", "源A", 0, text.toString());

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(a));

        assertEquals(SourceStore.MAX_MERGED_CHANNELS, result.mergedChannels());
        assertEquals(SourceStore.MAX_MERGED_CHANNELS, result.playlist().channelCount());
        assertFalse(result.warnings().isEmpty());
        assertTrue(result.warnings().get(0).contains("5000"));
    }

    @Test
    public void stopsParsingBeyondTotalChannelLimit() throws Exception {
        SourcePlaylist a = source("s1", "源A", 0, channels("A", 4500));
        SourcePlaylist b = source("s2", "源B", 1, channels("B", 4500));

        ChannelMerger.Result result = ChannelMerger.merge(Arrays.asList(a, b));

        assertEquals(SourceStore.MAX_TOTAL_CHANNELS, result.totalParsed());
        assertEquals(SourceStore.MAX_MERGED_CHANNELS, result.mergedChannels());
        boolean totalWarning = false;
        for (String warning : result.warnings()) {
            if (warning.contains(String.valueOf(SourceStore.MAX_TOTAL_CHANNELS))) {
                totalWarning = true;
            }
        }
        assertTrue(totalWarning);
    }

    // -------------------------------------------------------- 坏源隔离

    @Test
    public void badAndDisabledSourcesDoNotBreakGoodOnes() throws Exception {
        File dir = Files.createTempDirectory("tvlive-merge").toFile();
        try {
            List<SourceRecord> records = new ArrayList<SourceRecord>();
            records.add(fileSource("s1", "好源", 0));
            records.add(fileSource("s2", "坏源", 1));
            records.add(fileSource("s3", "停用源", 2).withEnabled(false));
            SourceStore.save(dir, records);

            SourceStore.writeCacheFile(dir, records.get(0), utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\nhttp://a/cctv1\n"
                    + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/hunan\n"));
            // HLS 媒体列表 → PlaylistParseException，只应影响该源。
            SourceStore.writeCacheFile(dir, records.get(1), utf8(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttp://bad/live.m3u8\n"));
            SourceStore.writeCacheFile(dir, records.get(2), utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-2 财经\nhttp://c/cctv2\n"));

            PlaylistRepository.MultiPlaylist result = PlaylistRepository.loadAll(dir);

            assertTrue(result.hasSources());
            assertEquals(3, result.sources().size());
            assertEquals(2, result.playlist().channelCount());
            assertEquals(1, result.failedCount());

            PlaylistRepository.SourceState good = state(result, "s1");
            assertNull(good.error());
            assertEquals(2, good.channelCount());
            PlaylistRepository.SourceState bad = state(result, "s2");
            assertNotNull(bad.error());
            assertFalse(bad.error().isEmpty());
            PlaylistRepository.SourceState off = state(result, "s3");
            assertNull(off.error());
            assertEquals(0, off.channelCount());

            // 坏源只标记 lastError，好源计数写回索引，停用源保持不变。
            List<SourceRecord> saved = SourceStore.load(dir);
            assertNotNull(byId(saved, "s2").lastError());
            assertEquals(2, byId(saved, "s1").channelCount());
            assertNull(byId(saved, "s1").lastError());
            assertFalse(byId(saved, "s3").enabled());
            assertNull(byId(saved, "s3").lastError());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void disabledSourceChannelsDisappearAndDeletedIdIsIgnored() throws Exception {
        File dir = Files.createTempDirectory("tvlive-disable").toFile();
        try {
            List<SourceRecord> records = Arrays.asList(
                    fileSource("s1", "源A", 0),
                    fileSource("s2", "源B", 1));
            SourceStore.save(dir, records);
            SourceStore.writeCacheFile(dir, records.get(0), utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\nhttp://a/cctv1\n"
                    + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\nhttp://a/hunan\n"));
            SourceStore.writeCacheFile(dir, records.get(1), utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\nhttp://b/cctv1\n"
                    + "#EXTINF:-1 group-title=\"地方\",北京新闻\nhttp://b/bj\n"));

            PlaylistRepository.MultiPlaylist both = PlaylistRepository.loadAll(dir);
            assertEquals(3, both.playlist().channelCount());
            assertEquals(2, navigator(both).byId(channelId(both.playlist(), "CCTV-1 综合"))
                    .variants().size());

            // 停用源 B：只剩源 A 的频道。
            SourceStore.save(dir, Arrays.asList(records.get(0),
                    records.get(1).withEnabled(false)));
            PlaylistRepository.MultiPlaylist onlyA = PlaylistRepository.loadAll(dir);
            assertEquals(2, onlyA.playlist().channelCount());
            assertEquals(1, navigator(onlyA).byId(channelId(onlyA.playlist(), "CCTV-1 综合"))
                    .variants().size());

            // 删除源 A（含正在播/收藏的 id）后，失效 id 只是查不到，不崩。
            SourceStore.deleteSource(dir, "s1");
            PlaylistRepository.MultiPlaylist none = PlaylistRepository.loadAll(dir);
            assertNull(none.playlist());
            assertTrue(none.hasSources());
            assertEquals(0, none.enabledCount());

            ChannelNavigator empty = new ChannelNavigator(
                    new ArrayList<Channel>(), new ArrayList<PlaylistModel.Group>());
            assertNull(empty.byId("CCTV-1 综合"));
            assertNull(empty.byId("gone"));
            assertNull(empty.first());
        } finally {
            deleteRecursively(dir);
        }
    }

    // -------------------------------------------------------- helpers

    private static ChannelNavigator navigator(PlaylistRepository.MultiPlaylist loaded) {
        return new ChannelNavigator(loaded.playlist().channels(), loaded.playlist().groups());
    }

    private static String channelId(Playlist playlist, String name) {
        for (Channel channel : playlist.channels()) {
            if (channel.name().equals(name)) {
                return channel.id();
            }
        }
        throw new AssertionError("找不到频道: " + name);
    }

    private static PlaylistRepository.SourceState state(
            PlaylistRepository.MultiPlaylist loaded, String id) {
        for (PlaylistRepository.SourceState state : loaded.sources()) {
            if (state.record().id().equals(id)) {
                return state;
            }
        }
        throw new AssertionError("找不到源状态: " + id);
    }

    private static SourceRecord byId(List<SourceRecord> records, String id) {
        for (SourceRecord record : records) {
            if (record.id().equals(id)) {
                return record;
            }
        }
        throw new AssertionError("找不到源: " + id);
    }

    private static SourceRecord fileSource(String id, String name, int order) {
        return new SourceRecord(id, name, Kind.FILE, "/tmp/" + id + ".m3u",
                true, order, 0L, 0, null, null);
    }

    private static SourcePlaylist source(String id, String name, int order, String text)
            throws PlaylistParseException {
        SourceRecord record = new SourceRecord(id, name, Kind.FILE, "", true, order, 0L, 0,
                null, null);
        Playlist parsed = PlaylistParser.parse(utf8(text), null);
        return new SourcePlaylist(record, parsed.channels());
    }

    /** 生成 count 个唯一频道（名称带前缀，避免跨源误合并）。 */
    private static String channels(String prefix, int count) {
        StringBuilder text = new StringBuilder("#EXTM3U\n");
        for (int i = 1; i <= count; i++) {
            text.append("#EXTINF:-1 group-title=\"自定义\",").append(prefix)
                    .append("频道").append(i).append('\n')
                    .append("http://example.com/").append(prefix).append('/')
                    .append(i).append(".m3u8\n");
        }
        return text.toString();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(UTF_8);
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
