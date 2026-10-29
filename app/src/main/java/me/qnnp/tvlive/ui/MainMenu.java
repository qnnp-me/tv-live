package me.qnnp.tvlive.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.qnnp.tvlive.data.PlaylistModel.Channel;

/**
 * MENU 主菜单模型（批次 6）：当前播放频道操作 + 全局项。
 *
 * <p>纯 Java（不 import Android），可在 JVM 单测中验证「有/无播放台」「线路数」
 * 与动作目标。文案由 {@link Labels} 注入：Activity 用资源实现，测试用固定串。</p>
 *
 * <p>频道项固定作用于当前播放台（{@link #target}），与导航操作列作用于焦点台
 * 互不混用；没有播放台时只保留全局项，标题退回「直播 / 菜单」。</p>
 */
public final class MainMenu {

    /** 菜单文案；Activity 用 getString 实现（全屏项文案随设备/模式变化）。 */
    public interface Labels {

        String favorite(boolean favorite);

        String switchSource();

        String channelInfo();

        String reload();

        String settings();

        String exit();

        /** 没有播放台时的标题（「直播」）。 */
        String globalTitle();

        /** 没有播放台时的副标题（「菜单」）。 */
        String globalSub();

        /** 播放台副标题：分组 + 台号，由 Activity 决定拼接与空值回退。 */
        String channelSub(String group, String number);
    }

    /** 一条主菜单项；section 表示在它前面画一条分组分隔（频道组 / 全局组）。 */
    public static final class Item {

        private final int action;
        private final String label;
        private final boolean enabled;
        private final boolean section;

        Item(int action, String label, boolean enabled, boolean section) {
            this.action = action;
            this.label = label;
            this.enabled = enabled;
            this.section = section;
        }

        public int action() {
            return action;
        }

        public String label() {
            return label;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean section() {
            return section;
        }
    }

    /** 构建结果：标题 + 副标题 + 有序菜单项（最后一项固定是退出）。 */
    public static final class Model {

        private final String kicker;
        private final String sub;
        private final List<Item> items;

        Model(String kicker, String sub, List<Item> items) {
            this.kicker = kicker;
            this.sub = sub;
            this.items = Collections.unmodifiableList(items);
        }

        public String kicker() {
            return kicker;
        }

        public String sub() {
            return sub;
        }

        public List<Item> items() {
            return items;
        }
    }

    private MainMenu() {
    }

    /**
     * @param playing        当前播放台；null 时只出全局项
     * @param numberText     播放台台号文案（Activity 用列表同一套格式化）
     * @param favorite       播放台是否已收藏（决定第一项文案）
     * @param fullscreenLabel 「全屏播放 / 收起导航 / 退出全屏」文案（按设备与模式）
     * @param labels         文案注入
     */
    public static Model build(Channel playing, String numberText, boolean favorite,
            String fullscreenLabel, Labels labels) {
        List<Item> items = new ArrayList<Item>();
        if (playing != null) {
            int variants = playing.variants().size();
            items.add(new Item(Overlays.ACTION_FAVORITE,
                    labels.favorite(favorite), true, false));
            // 单线路时保留不可用项（与操作列一致），不隐藏，避免菜单项跳动。
            items.add(new Item(Overlays.ACTION_VARIANTS,
                    labels.switchSource(), variants > 1, false));
            items.add(new Item(Overlays.ACTION_INFO,
                    labels.channelInfo(), true, false));
            items.add(new Item(Overlays.ACTION_FULLSCREEN,
                    fullscreenLabel, true, false));
        }
        // 分隔线之前是频道组，之后是全局组。
        boolean section = !items.isEmpty();
        items.add(new Item(Overlays.ACTION_RELOAD, labels.reload(), true, section));
        items.add(new Item(Overlays.ACTION_SETTINGS, labels.settings(), true, false));
        items.add(new Item(Overlays.ACTION_EXIT, labels.exit(), true, false));

        if (playing == null) {
            return new Model(labels.globalTitle(), labels.globalSub(), items);
        }
        return new Model(playing.name(),
                labels.channelSub(playing.group(), numberText), items);
    }

    /** 频道项固定作用于播放台；全局项没有台目标。 */
    public static Channel target(int action, Channel playing) {
        return isChannelAction(action) ? playing : null;
    }

    public static boolean isChannelAction(int action) {
        return action == Overlays.ACTION_FAVORITE
                || action == Overlays.ACTION_VARIANTS
                || action == Overlays.ACTION_INFO
                || action == Overlays.ACTION_FULLSCREEN;
    }
}
