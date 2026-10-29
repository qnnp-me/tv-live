package me.qnnp.tvlive.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;

/**
 * 批次 2e：「其他」子组聚合 / 原始 sourceGroup 保留 / 子组显示名。
 *
 * <p>用 iptv4、cn、domestic 三类真实源的 group-title 形态做内联样本，不依赖外部文件。</p>
 */
public class ChannelSetTest {

    // ---------------------------------------------------- iptv4：中文子组、按首现顺序

    @Test
    public void otherSubGroupsFollowFirstAppearanceForIptv4Shape() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"地方频道\",北京新闻\n"
                + "http://example.com/bj.m3u8\n"
                + "#EXTINF:-1 group-title=\"纪录频道\",纪录一\n"
                + "http://example.com/doc1.m3u8\n"
                + "#EXTINF:-1 group-title=\"电影频道\",电影一\n"
                + "http://example.com/movie1.m3u8\n"
                + "#EXTINF:-1 group-title=\"直播中国\",直播一\n"
                + "http://example.com/live1.m3u8\n"
                + "#EXTINF:-1 group-title=\"音乐频道\",音乐一\n"
                + "http://example.com/music1.m3u8\n"
                + "#EXTINF:-1 group-title=\"春晚频道\",春晚一\n"
                + "http://example.com/spring1.m3u8\n"
                + "#EXTINF:-1 group-title=\"纪录频道\",纪录二\n"
                + "http://example.com/doc2.m3u8\n"
                + "#EXTINF:-1 group-title=\"儿童频道\",儿童一\n"
                + "http://example.com/kid1.m3u8\n";

        Playlist playlist = organize(text);
        List<ChannelSet.SubGroup> subGroups = ChannelSet.subGroups(
                playlist.channels(), ChannelSet.GROUP_OTHER);

        // 地方频道归「地方」，其余进入「其他」；顺序按源中首次出现。
        assertEquals(Arrays.asList("纪录频道", "电影频道", "直播中国", "音乐频道",
                "春晚频道", "儿童频道"), names(subGroups));
        assertEquals(Arrays.asList(2, 1, 1, 1, 1, 1), counts(subGroups));
        assertEquals(7, otherCount(playlist));
        assertTrue(subGroups.size() >= 5);
    }

    // ---------------------------------------------------- cn：英文子组保留原文

    @Test
    public void otherSubGroupsKeepEnglishGroupTitlesForCnShape() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"Religious\",Alpha TV\n"
                + "http://example.com/a.m3u8\n"
                + "#EXTINF:-1 group-title=\"News\",Beta TV\n"
                + "http://example.com/b.m3u8\n"
                + "#EXTINF:-1 group-title=\"General\",Gamma TV\n"
                + "http://example.com/c.m3u8\n"
                + "#EXTINF:-1 group-title=\"Movies\",Delta TV\n"
                + "http://example.com/d.m3u8\n"
                + "#EXTINF:-1 group-title=\"News\",Epsilon TV\n"
                + "http://example.com/e.m3u8\n";

        Playlist playlist = organize(text);
        List<ChannelSet.SubGroup> subGroups = ChannelSet.subGroups(
                playlist.channels(), ChannelSet.GROUP_OTHER);

        assertEquals(Arrays.asList("Religious", "News", "General", "Movies"),
                names(subGroups));
        assertEquals(Arrays.asList(1, 2, 1, 1), counts(subGroups));
    }

    // ---------------------------------------------------- domestic：无组名 → 仅未分组

    @Test
    public void domesticWithoutGroupTitleHasOnlyUngrouped() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1,频道一\n"
                + "http://example.com/1.m3u8\n"
                + "#EXTINF:-1,频道二\n"
                + "http://example.com/2.m3u8\n"
                // group-title 本身就是「其他」：在「其他」里再套一个「其他」没有意义。
                + "#EXTINF:-1 group-title=\"其他\",重温经典\n"
                + "http://example.com/3.m3u8\n"
                + "#EXTINF:-1 group-title=\"其他\",五星体育\n"
                + "http://example.com/4.m3u8\n";

        Playlist playlist = organize(text);
        List<ChannelSet.SubGroup> subGroups = ChannelSet.subGroups(
                playlist.channels(), ChannelSet.GROUP_OTHER);

        assertEquals(1, subGroups.size());
        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, subGroups.get(0).name());
        assertEquals(4, subGroups.get(0).count());
    }

    // ---------------------------------------------------- sourceGroup 保留

    @Test
    public void organizeKeepsSourceGroupAfterGrouping() throws Exception {
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"纪录频道\",纪录一\n"
                + "http://example.com/doc1.m3u8\n"
                + "#EXTINF:-1 group-title=\"央视频道\",CCTV-1 综合\n"
                + "http://example.com/cctv1.m3u8\n";

        Playlist playlist = organize(text);
        Channel documentary = byName(playlist, "纪录一");
        Channel cctv = byName(playlist, "CCTV-1 综合");

        assertEquals(ChannelSet.GROUP_OTHER, documentary.group());
        assertEquals("纪录频道", documentary.sourceGroup());
        assertEquals(ChannelSet.GROUP_CCTV, cctv.group());
        assertEquals("央视频道", cctv.sourceGroup());
    }

    @Test
    public void channelCopyKeepsSourceGroupForVariantSwitch() throws Exception {
        Playlist playlist = organize("#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"纪录频道\",纪录一\n"
                + "http://example.com/doc1.m3u8\n");
        Channel documentary = byName(playlist, "纪录一");

        // 切换线路时 MainActivity 会复制频道：子组来源不能丢。
        Channel copy = new Channel(documentary);
        assertEquals(documentary.id(), copy.id());
        assertEquals(documentary.name(), copy.name());
        assertEquals(documentary.group(), copy.group());
        assertEquals("纪录频道", copy.sourceGroup());
        assertEquals(documentary.number(), copy.number());
    }

    @Test
    public void subGroupNameFallsBackToUngrouped() {
        Channel empty = new Channel("a", "频道", "其他", "", null);
        Channel other = new Channel("b", "频道", "其他", "其他", null);
        Channel named = new Channel("c", "频道", "其他", " 纪录频道 ", null);

        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, ChannelSet.subGroupName(empty));
        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, ChannelSet.subGroupName(other));
        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, ChannelSet.subGroupName(null));
        assertEquals("纪录频道", ChannelSet.subGroupName(named));
    }

    @Test
    public void onlyOtherGroupHasSubGroups() throws Exception {
        Playlist playlist = organize("#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视频道\",CCTV-1 综合\n"
                + "http://example.com/cctv1.m3u8\n");

        assertTrue(ChannelSet.subGroups(playlist.channels(), ChannelSet.GROUP_CCTV).isEmpty());
        assertTrue(ChannelSet.subGroups(playlist.channels(), null).isEmpty());
        assertTrue(ChannelSet.subGroups(null, ChannelSet.GROUP_OTHER).isEmpty());
        assertNull(playlist.error());
    }

    @Test
    public void longSubGroupLabelIsEllipsized() {
        assertEquals("直播中国", ChannelSet.subGroupLabel("直播中国"));
        assertEquals("Document…", ChannelSet.subGroupLabel("Documentary"));
        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, ChannelSet.subGroupLabel("  "));
        assertEquals(ChannelSet.SUBGROUP_UNGROUPED, ChannelSet.subGroupLabel(null));
        // 8 字正好不截断。
        assertEquals("12345678", ChannelSet.subGroupLabel("12345678"));
    }

    // ---------------------------------------------------- helpers

    private static Playlist organize(String text) throws PlaylistParseException {
        Playlist parsed = PlaylistParser.parse(
                text.getBytes(StandardCharsets.UTF_8), null);
        return ChannelSet.organize(parsed.channels(), parsed.skipped());
    }

    private static int otherCount(Playlist playlist) {
        int count = 0;
        for (Channel channel : playlist.channels()) {
            if (ChannelSet.GROUP_OTHER.equals(channel.group())) {
                count++;
            }
        }
        return count;
    }

    private static Channel byName(Playlist playlist, String name) {
        for (Channel channel : playlist.channels()) {
            if (name.equals(channel.name())) {
                return channel;
            }
        }
        throw new AssertionError("找不到频道 " + name);
    }

    private static List<String> names(List<ChannelSet.SubGroup> subGroups) {
        List<String> names = new ArrayList<String>();
        for (ChannelSet.SubGroup subGroup : subGroups) {
            names.add(subGroup.name());
        }
        return names;
    }

    private static List<Integer> counts(List<ChannelSet.SubGroup> subGroups) {
        List<Integer> counts = new ArrayList<Integer>();
        for (ChannelSet.SubGroup subGroup : subGroups) {
            counts.add(Integer.valueOf(subGroup.count()));
        }
        return counts;
    }
}
