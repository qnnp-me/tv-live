package me.qnnp.tvlive.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 核心单测：解析 / 分组 / 编号 / 变体合并 / 显示名清理 / 源存储。
 * 覆盖 docs/specs/batch-1.md 的 15 类用例，以及批次 2b 的 6 组归一、tvg-id @ 后缀、
 * 名称清理、更新时间元数据过滤。
 */
public class PlaylistCoreTest {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    // ---------------------------------------------------------------- 1. BOM + UTF-8 + EXTINF

    @Test
    public void parsesUtf8WithBomAndBasicExtinf() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv1\" group-title=\"央视\",CCTV-1 综合\n"
                + "http://example.com/cctv1.m3u8\n"
                + "#EXTINF:-1,湖南卫视\n"
                + "http://example.com/hunan.m3u8\n"
                + "#EXTINF:-1,\u3000CCTV\u30001\u3000\n"
                + "http://example.com/cctv1x.m3u8\n";

        Playlist playlist = PlaylistParser.parse(withBom(utf8(text)), null);

        assertEquals(3, playlist.channelCount());
        assertEquals(0, playlist.skipped());
        assertNull(playlist.error());

        Channel first = playlist.channels().get(0);
        assertEquals("cctv1", first.id());
        assertEquals("CCTV-1 综合", first.name());
        assertEquals("央视", first.group());
        assertEquals(1, first.variants().size());
        assertEquals("http://example.com/cctv1.m3u8", first.variants().get(0).url());

        Channel second = playlist.channels().get(1);
        assertEquals("湖南卫视", second.name());
        assertEquals("http://example.com/hunan.m3u8", second.variants().get(0).url());

        // 全角空格归一 + 首尾 trim
        assertEquals("CCTV 1", playlist.channels().get(2).name());
    }

    // ---------------------------------------------------------------- 2. GB18030

    @Test
    public void parsesGb18030ChineseNames() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"地方\",浙江卫视\n"
                + "http://example.com/zj.m3u8\n"
                + "#EXTINF:-1,CCTV-13 新闻\n"
                + "http://example.com/c13.m3u8\n";
        byte[] data = text.getBytes(Charset.forName("GB18030"));

        Playlist playlist = PlaylistParser.parse(data, null);

        assertEquals(2, playlist.channelCount());
        assertEquals("浙江卫视", playlist.channels().get(0).name());
        // 解析阶段保留 group-title 原文，6 组归一见 ChannelSet 测试
        assertEquals("地方", playlist.channels().get(0).group());
        assertEquals("CCTV-13 新闻", playlist.channels().get(1).name());
    }

    // ---------------------------------------------------------------- 3. attrs

    @Test
    public void parsesKnownAttributesAndIgnoresTvgChno() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"cctv5\" tvg-name=\"体育\" tvg-logo=\"http://logo.example/c5.png\""
                + " group-title=\"央视\" tvg-chno=\"5\",CCTV-5 体育\n"
                + "http://example.com/c5.m3u8\n"
                + "#EXTINF:-1 tvg-name=\"CCTV-6 电影\" tvg-logo=\"http://logo.example/c6.png\""
                + " group-title=\"央视\",\n"
                + "http://example.com/c6.m3u8\n";

        Playlist playlist = ChannelSet.organize(PlaylistParser.parse(utf8(text), null).channels());

        Channel cctv5 = playlist.channels().get(0);
        assertEquals("cctv5", cctv5.id());
        assertEquals("CCTV-5 体育", cctv5.name());
        assertEquals("央视", cctv5.group());
        assertEquals("http://logo.example/c5.png", cctv5.logoUrl());
        // 标题为空时回退 tvg-name
        assertEquals("CCTV-6 电影", playlist.channels().get(1).name());
        // tvg-chno 不参与编号
        assertEquals(1, cctv5.number());
        assertEquals(2, playlist.channels().get(1).number());
    }

    // ---------------------------------------------------------------- 4. EXTGRP

    @Test
    public void extgrpFallsBackWhenGroupTitleMissing() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTGRP:地方\n"
                + "#EXTINF:-1 group-title=\"卫视\",A台\n"
                + "http://example.com/a.m3u8\n"
                + "#EXTINF:-1,B台\n"
                + "http://example.com/b.m3u8\n"
                + "#EXTGRP:港澳台\n"
                + "#EXTINF:-1,C台\n"
                + "http://example.com/c.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视\",D台\n"
                + "http://example.com/d.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(4, playlist.channelCount());
        // group-title 优先
        assertEquals("卫视", playlist.channels().get(0).group());
        // 无 group-title 时用最近一次 #EXTGRP
        assertEquals("地方", playlist.channels().get(1).group());
        assertEquals("港澳台", playlist.channels().get(2).group());
        // EXTGRP 粘性，但 group-title 仍然优先
        assertEquals("央视", playlist.channels().get(3).group());
    }

    // ---------------------------------------------------------------- 5. EXTVLCOPT

    @Test
    public void extvlcoptUserAgentAndRefererFollowUrl() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,频道A\n"
                + "#EXTVLCOPT:http-user-agent=TvLive/0.1 Test\n"
                + "#EXTVLCOPT:http-referrer=http://ref.example/\n"
                + "http://example.com/a.m3u8\n"
                + "#EXTINF:-1,频道B\n"
                + "http://example.com/b.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        Variant a = playlist.channels().get(0).variants().get(0);
        assertEquals("TvLive/0.1 Test", a.userAgent());
        assertEquals("http://ref.example/", a.referer());

        Variant b = playlist.channels().get(1).variants().get(0);
        assertNull(b.userAgent());
        assertNull(b.referer());
    }

    // ---------------------------------------------------------------- 6. KODIPROP

    @Test
    public void ignoresKodiprop() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,频道K\n"
                + "#KODIPROP:inputstream.adaptive.manifest_type=hls\n"
                + "#KODIPROP:inputstream.adaptive.license_type=clearkey\n"
                + "http://example.com/k.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(1, playlist.channelCount());
        assertEquals("http://example.com/k.m3u8", playlist.channels().get(0).variants().get(0).url());
        assertEquals(0, playlist.skipped());
    }

    // ---------------------------------------------------------------- 7. https 保留

    @Test
    public void keepsHttpsUrls() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,加密台\n"
                + "https://example.com/secure.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(1, playlist.channelCount());
        assertEquals(0, playlist.skipped());
        assertEquals("https://example.com/secure.m3u8", playlist.channels().get(0).variants().get(0).url());
    }

    // ---------------------------------------------------------------- 8. 非 http(s) 跳过计数

    @Test
    public void skipsNonHttpUrlsAndCounts() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,UDP台\n"
                + "udp://239.0.0.1:1234\n"
                + "#EXTINF:-1,RTMP台\n"
                + "rtmp://example.com/live\n"
                + "#EXTINF:-1,正常台\n"
                + "http://example.com/ok.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(1, playlist.channelCount());
        assertEquals(2, playlist.skipped());
        assertEquals("正常台", playlist.channels().get(0).name());
    }

    // ---------------------------------------------------------------- 9. 相对 URL

    @Test
    public void resolvesRelativeUrlAgainstBaseUrl() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,相对台\n"
                + "live/rel.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), "http://example.com/list/tv.m3u");

        assertEquals(1, playlist.channelCount());
        assertEquals("http://example.com/list/live/rel.m3u8",
                playlist.channels().get(0).variants().get(0).url());

        // 无 http(s) baseUrl 时相对地址跳过
        String mixed = "#EXTM3U\n"
                + "#EXTINF:-1,正常台\n"
                + "http://example.com/ok.m3u8\n"
                + "#EXTINF:-1,相对台\n"
                + "live/rel.m3u8\n";
        Playlist withoutBase = PlaylistParser.parse(utf8(mixed), null);
        assertEquals(1, withoutBase.channelCount());
        assertEquals(1, withoutBase.skipped());
    }

    // ---------------------------------------------------------------- 10. 变体合并

    @Test
    public void mergesVariantsByGroupNameAndByTvgId() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV-1\n"
                + "http://example.com/a1.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视\",cctv-1\n"
                + "http://example.com/a2.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"cnn\" group-title=\"其他\",CNN\n"
                + "http://example.com/cnn1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"cnn\" group-title=\"其他\",CNN HD\n"
                + "http://example.com/cnn2.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(2, playlist.channelCount());

        Channel cctv1 = playlist.channels().get(0);
        assertEquals("CCTV-1", cctv1.name());
        assertEquals(2, cctv1.variants().size());
        assertEquals("http://example.com/a1.m3u8", cctv1.variants().get(0).url());
        assertEquals("http://example.com/a2.m3u8", cctv1.variants().get(1).url());

        Channel cnn = playlist.channels().get(1);
        assertEquals("cnn", cnn.id());
        assertEquals(2, cnn.variants().size());
        assertEquals("http://example.com/cnn2.m3u8", cnn.variants().get(1).url());
    }

    // ---------------------------------------------------------------- 11. 空 / HLS / 无频道

    @Test
    public void throwsOnEmptyHlsOrNoChannels() {
        assertParseFails(new byte[0], null);
        assertParseFails(withBom(new byte[0]), null);
        assertParseFails(utf8("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n"
                + "http://example.com/master.m3u8\n"), null);
        assertParseFails(utf8("#EXTM3U\n#EXTGRP:央视\n"), null);
    }

    // ---------------------------------------------------------------- 12. General → 按名称分类

    @Test
    public void classifiesNeutralGroupTitleByName() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"General\",CCTV-13 新闻\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"China\",湖南卫视\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1 group-title=\"中国\",苏州城市频道\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1 group-title=\"默认\",香港翡翠台\n"
                + "http://example.com/4.m3u8\n"
                + "#EXTINF:-1 group-title=\"未分类\",普通频道\n"
                + "http://example.com/5.m3u8\n";

        Playlist playlist = organize(text);

        assertEquals(Arrays.asList("央视", "卫视", "地方", "港澳台", "其他"), groupNames(playlist));
        assertEquals("央视", channelByName(playlist, "CCTV-13 新闻").group());
        assertEquals("卫视", channelByName(playlist, "湖南卫视").group());
        assertEquals("地方", channelByName(playlist, "苏州城市频道").group());
        assertEquals("港澳台", channelByName(playlist, "香港翡翠台").group());
        assertEquals("其他", channelByName(playlist, "普通频道").group());
    }

    // ---------------------------------------------------------------- 13. 组顺序 + 连续编号

    @Test
    public void ordersGroupsAndNumbersChannelsContinuously() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"其他A\",频道1\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视\",频道2\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视\",频道3\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1 group-title=\"地方\",频道4\n"
                + "http://example.com/4.m3u8\n"
                + "#EXTINF:-1 group-title=\"港澳台\",频道5\n"
                + "http://example.com/5.m3u8\n"
                + "#EXTINF:-1 group-title=\"其他B\",频道6\n"
                + "http://example.com/6.m3u8\n"
                + "#EXTINF:-1,测试台\n"
                + "http://example.com/7.m3u8\n";

        Playlist playlist = organize(text);

        assertEquals(Arrays.asList("央视", "卫视", "地方", "港澳台", "其他"),
                groupNames(playlist));
        assertEquals(Arrays.asList("频道3", "频道2", "频道4", "频道5", "频道1", "频道6", "测试台"),
                channelNames(playlist));

        List<Integer> numbers = new ArrayList<Integer>();
        for (Channel channel : playlist.channels()) {
            numbers.add(channel.number());
        }
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7), numbers);

        // 组内保持源顺序
        Group cctv = playlist.groups().get(0);
        assertEquals("频道3", cctv.channels().get(0).name());
    }

    // ---------------------------------------------------------------- 14. 空名称回退 URL 末段

    @Test
    public void fallsBackToUrlTailForEmptyName() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,\n"
                + "http://example.com/live/chan9.m3u8\n"
                + "#EXTINF:-1,\n"
                + "http://example.com/live/chan10.m3u8?token=abc\n"
                + "#EXTINF:-1,\n"
                + "http://example.com/\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(2, playlist.channelCount());
        assertEquals(1, playlist.skipped());
        assertEquals("chan9.m3u8", playlist.channels().get(0).name());
        assertEquals("chan10.m3u8", playlist.channels().get(1).name());
        assertTrue(playlist.channels().get(0).id().contains("|"));
        assertNotEquals(playlist.channels().get(0).id(), playlist.channels().get(1).id());
    }

    // ---------------------------------------------------------------- 15. 仓库读写/删除/原子写

    @Test
    public void writeAtomicSyncFlagControlsFsync() throws Exception {
        File dir = Files.createTempDirectory("tvlive-sync").toFile();
        try {
            File file = new File(dir, "control_pin.txt");
            final int[] syncs = {0};
            PlaylistRepository.Syncer counting = new PlaylistRepository.Syncer() {
                @Override
                public void sync(java.io.FileDescriptor descriptor) {
                    syncs[0]++;
                }
            };

            byte[] pin = utf8("123456");
            PlaylistRepository.writeAtomic(file, pin, false, counting);
            assertEquals("sync=false 不得 fsync", 0, syncs[0]);
            assertArrayEquals(pin, PlaylistRepository.read(file));
            assertFalse(new File(dir, "control_pin.txt.tmp").exists());

            byte[] next = utf8("654321");
            PlaylistRepository.writeAtomic(file, next, true, counting);
            assertEquals("sync=true 必须 fsync 一次", 1, syncs[0]);
            assertArrayEquals(next, PlaylistRepository.read(file));

            // 默认重载保持旧行为（源文件写入必须 sync）。
            PlaylistRepository.writeAtomic(file, pin);
            assertArrayEquals(pin, PlaylistRepository.read(file));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void repositoryWritesReadsAndDeletesAtomically() throws Exception {
        File dir = Files.createTempDirectory("tvlive-repo").toFile();
        try {
            File file = PlaylistRepository.playlistFile(dir);
            assertEquals("playlist.m3u", file.getName());
            assertFalse(file.exists());

            byte[] first = utf8("#EXTM3U\n#EXTINF:-1,频道A\nhttp://example.com/a.m3u8\n");
            PlaylistRepository.writeAtomic(file, first);
            assertTrue(file.isFile());
            assertArrayEquals(first, PlaylistRepository.read(file));
            assertFalse("临时文件应已重命名", new File(dir, "playlist.m3u.tmp").exists());

            byte[] second = utf8("#EXTM3U\n#EXTINF:-1,频道B\nhttp://example.com/b.m3u8\n");
            PlaylistRepository.save(dir, second);
            assertArrayEquals(second, PlaylistRepository.read(file));
            assertFalse(new File(dir, "playlist.m3u.tmp").exists());

            Playlist loaded = PlaylistRepository.load(dir);
            assertEquals(1, loaded.channelCount());
            assertEquals("频道B", loaded.channels().get(0).name());
            assertEquals(1, loaded.channels().get(0).number());

            PlaylistRepository.SourceInfo info = PlaylistRepository.readInfo(dir);
            assertTrue(info.hasSource());
            assertEquals("playlist.m3u", info.label());
            assertEquals(second.length, info.size());
            assertEquals(1, info.channelCount());
            assertTrue(info.lastModified() > 0L);
            assertNotNull(PlaylistRepository.readInfo(dir).label());

            assertTrue(PlaylistRepository.delete(dir));
            assertFalse(file.exists());
            PlaylistRepository.SourceInfo cleared = PlaylistRepository.readInfo(dir);
            assertFalse(cleared.hasSource());
            assertEquals(0, cleared.channelCount());
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------------------------------------------------------------- 16. 6 组归一

    @Test
    public void normalizesGroupTitlesToSixCanonicalGroups() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视频道\",一台\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"CCTV\",二台\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视频道\",三台\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1 group-title=\"地方频道\",四台\n"
                + "http://example.com/4.m3u8\n"
                + "#EXTINF:-1 group-title=\"港澳台\",五台\n"
                + "http://example.com/5.m3u8\n"
                + "#EXTINF:-1 group-title=\"台湾香港澳门\",六台\n"
                + "http://example.com/6.m3u8\n"
                + "#EXTINF:-1 group-title=\"电影频道\",七台\n"
                + "http://example.com/7.m3u8\n"
                + "#EXTINF:-1 group-title=\"Religious\",八台\n"
                + "http://example.com/8.m3u8\n"
                + "#EXTINF:-1 group-title=\"News\",九台\n"
                + "http://example.com/9.m3u8\n";

        Playlist playlist = organize(text);

        assertEquals(Arrays.asList("央视", "卫视", "地方", "港澳台", "其他"),
                groupNames(playlist));
        assertEquals("央视", channelByName(playlist, "一台").group());
        assertEquals("央视", channelByName(playlist, "二台").group());
        assertEquals("卫视", channelByName(playlist, "三台").group());
        assertEquals("地方", channelByName(playlist, "四台").group());
        assertEquals("港澳台", channelByName(playlist, "五台").group());
        assertEquals("港澳台", channelByName(playlist, "六台").group());
        assertEquals("其他", channelByName(playlist, "七台").group());
        assertEquals("其他", channelByName(playlist, "八台").group());
        assertEquals("其他", channelByName(playlist, "九台").group());
    }

    // ---------------------------------------------------------------- 17. 名称优先

    @Test
    public void channelNameTakesPriorityOverGroupTitle() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"卫视频道\",CCTV-1 综合\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视频道\",湖南卫视\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视\",苏州城市频道\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1 group-title=\"其他\",香港翡翠台\n"
                + "http://example.com/4.m3u8\n";

        Playlist playlist = organize(text);

        assertEquals(Arrays.asList("央视", "卫视", "地方", "港澳台"),
                groupNames(playlist));
        assertEquals("央视", channelByName(playlist, "CCTV-1 综合").group());
        assertEquals("卫视", channelByName(playlist, "湖南卫视").group());
        assertEquals("地方", channelByName(playlist, "苏州城市频道").group());
        assertEquals("港澳台", channelByName(playlist, "香港翡翠台").group());
    }

    // ---------------------------------------------------------------- 18. tvg-id @ 后缀合并

    @Test
    public void stripsTvgIdSuffixAndMergesQualityVariants() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1.cn@SD\" group-title=\"央视\",CCTV-1 综合\n"
                + "http://example.com/a1.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1.cn@HD\" group-title=\"央视\",CCTV-1 综合 HD\n"
                + "http://example.com/a2.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"CCTV1.cn@4K\" group-title=\"央视\",CCTV-1 综合 (4K)\n"
                + "http://example.com/a3.m3u8\n";

        Playlist playlist = organize(text);

        assertEquals(1, playlist.channelCount());
        Channel cctv1 = playlist.channels().get(0);
        assertEquals("CCTV1.cn", cctv1.id());
        assertEquals("CCTV-1 综合", cctv1.name());
        assertEquals(3, cctv1.variants().size());
        assertEquals("http://example.com/a1.m3u8", cctv1.variants().get(0).url());
        assertEquals("http://example.com/a3.m3u8", cctv1.variants().get(2).url());
    }

    // ---------------------------------------------------------------- 19. 显示名清理

    @Test
    public void cleansQualityAndNoteSuffixesFromNames() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,CCTV-1 综合 (720p)\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1,CCTV-2 财经 HD (1080p)\n"
                + "http://example.com/2.m3u8\n"
                + "#EXTINF:-1,CCTV-3 综艺 HD\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1,CCTV-4 中文国际 [Not 24/7]\n"
                + "http://example.com/4.m3u8\n"
                + "#EXTINF:-1,CCTV-5 体育 [Geo-blocked]\n"
                + "http://example.com/5.m3u8\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(5, playlist.channelCount());
        assertEquals(Arrays.asList("CCTV-1 综合", "CCTV-2 财经", "CCTV-3 综艺",
                "CCTV-4 中文国际", "CCTV-5 体育"), channelNames(playlist));
    }

    // ---------------------------------------------------------------- 20. 更新时间元数据

    @Test
    public void filtersUpdateTimeMetadataWithoutCountingSkipped() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"更新时间\",更新时间\n"
                + "http://example.com/updated.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"更新时间\" group-title=\"直播中国\",2026-10-28 12:24:56\n"
                + "http://example.com/updated2.m3u8\n"
                + "#EXTINF:-1 group-title=\"其他\",正常台\n"
                + "http://example.com/ok.m3u8\n"
                + "#EXTINF:-1 group-title=\"其他\",非http\n"
                + "udp://239.0.0.1:1234\n";

        Playlist playlist = PlaylistParser.parse(utf8(text), null);

        assertEquals(1, playlist.channelCount());
        assertEquals("正常台", playlist.channels().get(0).name());
        assertEquals(1, playlist.skipped());

        Playlist organized = ChannelSet.organize(playlist.channels(), playlist.skipped());
        assertEquals(Arrays.asList("其他"), groupNames(organized));
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(byte[] body) {
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private static Playlist organize(String text) throws PlaylistParseException {
        Playlist parsed = PlaylistParser.parse(utf8(text), null);
        return ChannelSet.organize(parsed.channels(), parsed.skipped());
    }

    private static List<String> groupNames(Playlist playlist) {
        List<String> names = new ArrayList<String>();
        for (Group group : playlist.groups()) {
            names.add(group.name());
        }
        return names;
    }

    private static List<String> channelNames(Playlist playlist) {
        List<String> names = new ArrayList<String>();
        for (Channel channel : playlist.channels()) {
            names.add(channel.name());
        }
        return names;
    }

    private static Channel channelByName(Playlist playlist, String name) {
        for (Channel channel : playlist.channels()) {
            if (name.equals(channel.name())) {
                return channel;
            }
        }
        fail("找不到频道: " + name);
        return null;
    }

    private static void assertParseFails(byte[] data, String baseUrl) {
        try {
            PlaylistParser.parse(data, baseUrl);
            fail("应当抛出 PlaylistParseException");
        } catch (PlaylistParseException expected) {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static void deleteRecursively(File file) throws IOException {
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
