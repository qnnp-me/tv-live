package me.qnnp.tvlive.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;

/**
 * 分组归一 + 组顺序 + 全局编号（纯 Java）。
 *
 * <p>最终分组只有 6 个（收藏为 UI 虚拟组，不在这里）：央视 / 卫视 / 地方 / 港澳台 / 其他。</p>
 *
 * <p>归类顺序：</p>
 * <ol>
 *   <li>名称优先：含 CCTV/央视 → 央视；含 卫视 → 卫视；含 地方/城市 → 地方；
 *       含 港澳台/香港/澳门/台湾 → 港澳台。</li>
 *   <li>名称没有命中时，按 group-title 别名：央视/CCTV → 央视；
 *       卫视/卫视频道 → 卫视；地方/地方频道 → 地方；
 *       港澳台/台湾香港澳门 → 港澳台。</li>
 *   <li>其余一律 → 其他；不再保留源自定义组名。</li>
 * </ol>
 *
 * <p>组顺序固定：央视 / 卫视 / 地方 / 港澳台 / 其他；组内保持源顺序；
 * number 从 1 连续到 N（收藏不改编号）。</p>
 */
public final class ChannelSet {

    public static final String GROUP_CCTV = "央视";
    public static final String GROUP_SATELLITE = "卫视";
    public static final String GROUP_LOCAL = "地方";
    public static final String GROUP_HMT = "港澳台";
    public static final String GROUP_OTHER = "其他";

    /** 「其他」里没有可用源分组时的子组名。 */
    public static final String SUBGROUP_UNGROUPED = "未分组";

    /** 子组显示名超过这个字数就用省略号（源分组名本身保持完整）。 */
    public static final int SUBGROUP_LABEL_MAX = 8;

    private static final String[] FIXED_ORDER = {
            GROUP_CCTV, GROUP_SATELLITE, GROUP_LOCAL, GROUP_HMT
    };

    /** 「其他」子组：名称 + 数量，按源中首次出现顺序。 */
    public static final class SubGroup {

        private final String name;
        private final int count;

        public SubGroup(String name, int count) {
            this.name = name;
            this.count = count;
        }

        public String name() {
            return name;
        }

        public int count() {
            return count;
        }

        @Override
        public String toString() {
            return "SubGroup{" + name + " x" + count + "}";
        }
    }

    private ChannelSet() {
    }

    public static Playlist organize(List<Channel> channels) {
        return organize(channels, 0);
    }

    /**
     * 归一化分组、按最终显示顺序排序并写入全局编号。
     *
     * @param channels 解析结果（保持源顺序）
     * @param skipped  解析期跳过的 URL 数，原样带到结果
     */
    public static Playlist organize(List<Channel> channels, int skipped) {
        Map<String, List<Channel>> byGroup = new LinkedHashMap<String, List<Channel>>();
        if (channels != null) {
            for (Channel channel : channels) {
                if (channel == null) {
                    continue;
                }
                String group = resolveGroup(channel.group(), channel.name());
                channel.setGroup(group);
                List<Channel> list = byGroup.get(group);
                if (list == null) {
                    list = new ArrayList<Channel>();
                    byGroup.put(group, list);
                }
                list.add(channel);
            }
        }

        List<String> groupOrder = new ArrayList<String>();
        for (String fixed : FIXED_ORDER) {
            if (byGroup.containsKey(fixed)) {
                groupOrder.add(fixed);
            }
        }
        if (byGroup.containsKey(GROUP_OTHER)) {
            groupOrder.add(GROUP_OTHER);
        }

        List<Group> groups = new ArrayList<Group>(groupOrder.size());
        List<Channel> ordered = new ArrayList<Channel>();
        int number = 1;
        for (String name : groupOrder) {
            List<Channel> members = byGroup.get(name);
            for (Channel channel : members) {
                channel.setNumber(number++);
                ordered.add(channel);
            }
            groups.add(new Group(name, members));
        }
        return new Playlist(ordered, groups, skipped, null);
    }

    /** 名称优先；名称没有命中再按 group-title 别名；未知一律「其他」。 */
    public static String resolveGroup(String rawGroup, String name) {
        String byName = classifyByNameOrNull(name);
        if (byName != null) {
            return byName;
        }
        return normalizeGroupName(rawGroup);
    }

    /** 仅按名称分类；没有命中返回「其他」。 */
    public static String classifyByName(String name) {
        String byName = classifyByNameOrNull(name);
        return byName == null ? GROUP_OTHER : byName;
    }

    /**
     * group-title 别名归一：央视/CCTV、卫视/卫视频道、地方/地方频道、
     * 港澳台/台湾香港澳门；其他一律「其他」。
     */
    public static String normalizeGroupName(String rawGroup) {
        String cleaned = PlaylistParser.cleanText(rawGroup);
        if (cleaned == null) {
            return GROUP_OTHER;
        }
        String lower = cleaned.toLowerCase(Locale.US);
        if (lower.contains("央视") || lower.contains("cctv")) {
            return GROUP_CCTV;
        }
        if (lower.contains("卫视")) {
            return GROUP_SATELLITE;
        }
        if (lower.contains("地方")) {
            return GROUP_LOCAL;
        }
        if (lower.contains("港澳台") || lower.contains("香港")
                || lower.contains("澳门") || lower.contains("台湾")) {
            return GROUP_HMT;
        }
        return GROUP_OTHER;
    }

    /**
     * 「其他」组内按原始 sourceGroup 聚合的子组；组内顺序 = 源中首次出现顺序。
     * 只对 {@link #GROUP_OTHER} 有子组，其他组返回空列表。
     *
     * <p>sourceGroup 为空、或本身就是「其他」时归入 {@link #SUBGROUP_UNGROUPED}：
     * 前者没有信息，后者在「其他」里面再出现一个「其他」没有意义（domestic 源只有未分组）。</p>
     */
    public static List<SubGroup> subGroups(List<Channel> channels, String group) {
        if (channels == null || !GROUP_OTHER.equals(group)) {
            return Collections.emptyList();
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Channel channel : channels) {
            if (channel == null || !GROUP_OTHER.equals(channel.group())) {
                continue;
            }
            String name = subGroupName(channel);
            Integer count = counts.get(name);
            counts.put(name, count == null ? 1 : count + 1);
        }
        List<SubGroup> result = new ArrayList<SubGroup>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            result.add(new SubGroup(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** 频道在「其他」里的子组名：空/「其他」→ 未分组，其余用原始 group-title。 */
    public static String subGroupName(Channel channel) {
        if (channel == null) {
            return SUBGROUP_UNGROUPED;
        }
        String cleaned = PlaylistParser.cleanText(channel.sourceGroup());
        if (cleaned == null || GROUP_OTHER.equals(cleaned)) {
            return SUBGROUP_UNGROUPED;
        }
        return cleaned;
    }

    /** 组栏显示名：超过 {@link #SUBGROUP_LABEL_MAX} 字截断加省略号；空 → 未分组。 */
    public static String subGroupLabel(String name) {
        String cleaned = PlaylistParser.cleanText(name);
        if (cleaned == null) {
            return SUBGROUP_UNGROUPED;
        }
        if (cleaned.length() <= SUBGROUP_LABEL_MAX) {
            return cleaned;
        }
        return cleaned.substring(0, SUBGROUP_LABEL_MAX) + "…";
    }

    private static String classifyByNameOrNull(String name) {
        String cleaned = PlaylistParser.cleanText(name);
        if (cleaned == null) {
            return null;
        }
        String upper = cleaned.toUpperCase(Locale.US);
        if (upper.contains("CCTV") || cleaned.contains("央视")) {
            return GROUP_CCTV;
        }
        if (cleaned.contains("卫视")) {
            return GROUP_SATELLITE;
        }
        if (cleaned.contains("地方") || cleaned.contains("城市")) {
            return GROUP_LOCAL;
        }
        if (cleaned.contains("港澳台") || cleaned.contains("香港")
                || cleaned.contains("澳门") || cleaned.contains("台湾")) {
            return GROUP_HMT;
        }
        return null;
    }
}
