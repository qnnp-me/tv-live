package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 批次 6：MENU 主菜单包含当前播放频道操作。
 *
 * <p>主菜单动作固定作用于播放台（与导航操作列的焦点台区分）；没有播放台时
 * 只有全局项，标题退回「直播 / 菜单」。</p>
 */
public class MainMenuTest {

    /** 固定文案：标签里带上状态，便于断言。 */
    private static final class FakeLabels implements MainMenu.Labels {

        @Override
        public String favorite(boolean favorite) {
            return favorite ? "unfavorite" : "favorite";
        }

        @Override
        public String switchSource() {
            return "switch";
        }

        @Override
        public String channelInfo() {
            return "info";
        }

        @Override
        public String reload() {
            return "reload";
        }

        @Override
        public String settings() {
            return "settings";
        }

        @Override
        public String exit() {
            return "exit";
        }

        @Override
        public String globalTitle() {
            return "brand";
        }

        @Override
        public String globalSub() {
            return "menu";
        }

        @Override
        public String channelSub(String group, String number) {
            return group + "|" + number;
        }
    }

    private static Channel channel(int variants) {
        Channel channel = new Channel("cctv1", "CCTV-1", "央视", null);
        channel.setNumber(5);
        for (int i = 0; i < variants; i++) {
            channel.addVariant(new Variant("http://example.com/" + i + ".m3u8"));
        }
        return channel;
    }

    private static MainMenu.Model build(Channel playing, boolean favorite) {
        return MainMenu.build(playing,
                playing == null ? "" : "05",
                favorite,
                "fullscreen",
                new FakeLabels());
    }

    @Test
    public void withoutPlayingChannelOnlyGlobalItems() {
        MainMenu.Model model = build(null, false);

        assertEquals("brand", model.kicker());
        assertEquals("menu", model.sub());
        List<MainMenu.Item> items = model.items();
        assertEquals(3, items.size());
        assertEquals(Overlays.ACTION_RELOAD, items.get(0).action());
        assertEquals(Overlays.ACTION_SETTINGS, items.get(1).action());
        assertEquals(Overlays.ACTION_EXIT, items.get(2).action());
        for (MainMenu.Item item : items) {
            assertTrue(item.enabled());
            assertFalse("无播放台时不需要分组分隔", item.section());
        }
    }

    @Test
    public void withPlayingChannelActionsComeFirstThenGlobals() {
        MainMenu.Model model = build(channel(2), false);

        assertEquals("CCTV-1", model.kicker());
        assertEquals("央视|05", model.sub());

        List<MainMenu.Item> items = model.items();
        assertEquals(7, items.size());
        assertEquals(Overlays.ACTION_FAVORITE, items.get(0).action());
        assertEquals(Overlays.ACTION_VARIANTS, items.get(1).action());
        assertEquals(Overlays.ACTION_INFO, items.get(2).action());
        assertEquals(Overlays.ACTION_FULLSCREEN, items.get(3).action());
        assertEquals(Overlays.ACTION_RELOAD, items.get(4).action());
        assertEquals(Overlays.ACTION_SETTINGS, items.get(5).action());
        assertEquals(Overlays.ACTION_EXIT, items.get(6).action());
        // 只有全局组首项带分组标记（退出固定底部由 Overlays 处理）。
        assertFalse(items.get(0).section());
        assertFalse(items.get(1).section());
        assertFalse(items.get(2).section());
        assertFalse(items.get(3).section());
        assertTrue(items.get(4).section());
        assertFalse(items.get(5).section());
        assertFalse(items.get(6).section());
    }

    @Test
    public void favoriteLabelFollowsPlayingChannelState() {
        assertEquals("favorite", build(channel(1), false).items().get(0).label());
        assertEquals("unfavorite", build(channel(1), true).items().get(0).label());
    }

    @Test
    public void variantsItemEnabledOnlyWithMultipleVariants() {
        MainMenu.Model single = build(channel(1), false);
        assertEquals(Overlays.ACTION_VARIANTS, single.items().get(1).action());
        assertFalse("单线路时切换线路不可用", single.items().get(1).enabled());

        MainMenu.Model multi = build(channel(3), false);
        assertTrue("多线路时切换线路可用", multi.items().get(1).enabled());
    }

    @Test
    public void channelActionsAlwaysTargetPlayingChannel() {
        Channel playing = channel(2);
        MainMenu.Model model = build(playing, false);

        for (MainMenu.Item item : model.items()) {
            Channel target = MainMenu.target(item.action(), playing);
            if (MainMenu.isChannelAction(item.action())) {
                assertSame("频道项固定作用于播放台", playing, target);
            } else {
                assertNull("全局项没有台目标", target);
            }
        }
    }

    @Test
    public void channelActionsHaveNoTargetWithoutPlayingChannel() {
        int[] channelActions = {
                Overlays.ACTION_FAVORITE, Overlays.ACTION_VARIANTS,
                Overlays.ACTION_INFO, Overlays.ACTION_FULLSCREEN,
        };
        for (int action : channelActions) {
            assertTrue(MainMenu.isChannelAction(action));
            assertNull(MainMenu.target(action, null));
        }
        assertFalse(MainMenu.isChannelAction(Overlays.ACTION_RELOAD));
        assertFalse(MainMenu.isChannelAction(Overlays.ACTION_SETTINGS));
        assertFalse(MainMenu.isChannelAction(Overlays.ACTION_EXIT));
    }

    @Test
    public void exitItemIsAlwaysLastForPinnedBottom() {
        assertEquals(Overlays.ACTION_EXIT,
                build(channel(1), false).items().get(6).action());
        assertEquals(Overlays.ACTION_EXIT,
                build(null, false).items().get(2).action());
    }
}
