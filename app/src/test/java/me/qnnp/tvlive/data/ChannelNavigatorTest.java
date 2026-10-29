package me.qnnp.tvlive.data;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ChannelNavigator：跨组上一/下一、全局号查找、组筛选、收藏虚拟组。
 */
public class ChannelNavigatorTest {

    private ChannelNavigator navigator;
    private Channel cctv1;
    private Channel cctv2;
    private Channel hunan;
    private Channel other1;
    private Channel other2;

    @Before
    public void setUp() {
        cctv1 = channel("cctv1", "CCTV-1 综合", ChannelSet.GROUP_CCTV, 1);
        cctv2 = channel("cctv2", "CCTV-2 财经", ChannelSet.GROUP_CCTV, 2);
        hunan = channel("hunan", "湖南卫视", ChannelSet.GROUP_SATELLITE, 3);
        other1 = channel("other1", "测试频道一", ChannelSet.GROUP_OTHER, 4);
        other2 = channel("other2", "测试频道二", ChannelSet.GROUP_OTHER, 5);

        List<Group> groups = new ArrayList<Group>();
        groups.add(new Group(ChannelSet.GROUP_CCTV, Arrays.asList(cctv1, cctv2)));
        groups.add(new Group(ChannelSet.GROUP_SATELLITE, Collections.singletonList(hunan)));
        groups.add(new Group(ChannelSet.GROUP_OTHER, Arrays.asList(other1, other2)));
        navigator = new ChannelNavigator(
                Arrays.asList(cctv1, cctv2, hunan, other1, other2), groups);
    }

    @Test
    public void byNumberUsesGlobalOrder() {
        assertEquals(cctv1, navigator.byNumber(1));
        assertEquals(hunan, navigator.byNumber(3));
        assertEquals(other2, navigator.byNumber(5));
        assertNull(navigator.byNumber(0));
        assertNull(navigator.byNumber(6));
        assertNull(navigator.byNumber(-1));
    }

    @Test
    public void byNumberFallsBackWhenNumbersAreNotContiguous() {
        PlaylistModel.Channel sparse = channel("sparse", "稀疏", ChannelSet.GROUP_OTHER, 9);
        ChannelNavigator sparseNavigator = new ChannelNavigator(
                Collections.singletonList(sparse),
                Collections.singletonList(new Group(ChannelSet.GROUP_OTHER,
                        Collections.singletonList(sparse))));
        assertEquals(sparse, sparseNavigator.byNumber(9));
        assertNull(sparseNavigator.byNumber(1));
    }

    @Test
    public void byIdFindsChannel() {
        assertEquals(cctv2, navigator.byId("cctv2"));
        assertNull(navigator.byId("missing"));
        assertNull(navigator.byId(null));
    }

    @Test
    public void stepMovesAcrossGroupsWithoutWrapping() {
        assertEquals(cctv2, navigator.step(cctv1, 1));
        assertEquals(hunan, navigator.step(cctv2, 1));
        assertEquals(other1, navigator.step(hunan, 1));
        assertEquals(hunan, navigator.step(other1, -1));
        assertEquals(cctv1, navigator.step(cctv2, -1));
    }

    @Test
    public void stepDoesNotWrapAtEnds() {
        assertNull(navigator.step(cctv1, -1));
        assertNull(navigator.step(other2, 1));
        assertNull(navigator.step(other2, 2));
    }

    @Test
    public void stepWrappingKeepsFullscreenBehaviour() {
        assertEquals(other2, navigator.stepWrapping(cctv1, -1));
        assertEquals(cctv1, navigator.stepWrapping(other2, 1));
        assertEquals(other2, navigator.stepWrapping(other2, 5));
    }

    @Test
    public void stepFromNullTakesFirstOrLast() {
        assertEquals(cctv1, navigator.step(null, 1));
        assertEquals(other2, navigator.step(null, -1));
        assertEquals(cctv1, navigator.stepWrapping(null, 1));
        assertEquals(other2, navigator.stepWrapping(null, -1));
        assertNull(navigator.step(cctv1, 0));
    }

    @Test
    public void groupFilterKeepsSourceOrder() {
        assertEquals(Arrays.asList(cctv1, cctv2),
                navigator.group(ChannelSet.GROUP_CCTV));
        assertEquals(Collections.singletonList(hunan),
                navigator.group(ChannelSet.GROUP_SATELLITE));
        assertEquals(Arrays.asList(other1, other2), navigator.group(ChannelSet.GROUP_OTHER));
        assertTrue(navigator.group(ChannelSet.GROUP_LOCAL).isEmpty());
        assertTrue(navigator.group(null).isEmpty());
        assertEquals(2, navigator.groupCount(ChannelSet.GROUP_CCTV));
        assertEquals(0, navigator.groupCount(ChannelSet.GROUP_LOCAL));
    }

    @Test
    public void favoritesFollowGlobalOrderAndSkipUnknownIds() {
        List<Channel> favorites = navigator.favorites(
                new HashSet<String>(Arrays.asList("other2", "cctv1", "gone")));
        assertEquals(Arrays.asList(cctv1, other2), favorites);
        assertTrue(navigator.favorites(Collections.<String>emptySet()).isEmpty());
        assertTrue(navigator.favorites(null).isEmpty());
    }

    @Test
    public void groupIndexOfChannel() {
        assertEquals(0, navigator.groupIndexOf(cctv1));
        assertEquals(1, navigator.groupIndexOf(hunan));
        assertEquals(2, navigator.groupIndexOf(other1));
        assertEquals(-1, navigator.groupIndexOf(null));
        assertEquals(-1, navigator.groupIndexOf(
                channel("x", "未知组", "不存在", 6)));
    }

    @Test
    public void groupNamesExcludeFavoritesVirtualGroup() {
        assertEquals(Arrays.asList(ChannelSet.GROUP_CCTV, ChannelSet.GROUP_SATELLITE,
                ChannelSet.GROUP_OTHER), navigator.groups());
        assertFalse(navigator.groups().contains("收藏"));
    }

    @Test
    public void emptyNavigatorIsSafe() {
        ChannelNavigator empty = new ChannelNavigator(null, null);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.count());
        assertNull(empty.first());
        assertNull(empty.last());
        assertNull(empty.byNumber(1));
        assertNull(empty.step(null, 1));
        assertNull(empty.stepWrapping(null, 1));
        assertTrue(empty.group(ChannelSet.GROUP_CCTV).isEmpty());
        assertEquals(-1, empty.indexOf("x"));
    }

    @Test
    public void playlistConstructorShapeStaysUsable() {
        // organize() 的结果可直接交给 navigator（UI 实际用法）。
        List<Channel> raw = new ArrayList<Channel>();
        raw.add(channel("a", "CCTV-1 综合", "央视", 0));
        raw.add(channel("b", "湖南卫视", "卫视", 0));
        Playlist organized = ChannelSet.organize(raw);
        ChannelNavigator nav = new ChannelNavigator(
                organized.channels(), organized.groups());
        assertEquals(ChannelSet.GROUP_CCTV, nav.first().group());
        assertEquals(2, nav.count());
        assertEquals(nav.first(), nav.byNumber(1));
    }

    private static Channel channel(String id, String name, String group, int number) {
        Channel channel = new Channel(id, name, group, null);
        channel.setNumber(number);
        return channel;
    }
}
