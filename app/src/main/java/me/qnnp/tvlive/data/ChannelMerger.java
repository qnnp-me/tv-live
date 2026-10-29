package me.qnnp.tvlive.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 多源合并：同名频道合并线路、按源 order 决定线路优先级、URL 去重。
 *
 * <p>合并键：</p>
 * <ol>
 *   <li>有 tvg-id（{@link Channel#id()} 不含 {@code |}，解析期已去 {@code @} 后缀）时按它匹配；</li>
 *   <li>同时登记 {@code 归一化分组|规范化名称}，让「有 tvg-id 的源」与「只有名字的源」也能合并；</li>
 *   <li>名称先查 {@link ChannelAlias#canonical}，命中用标准名（如
 *       {@code Hunan TV → 湖南卫视}），否则用 {@link ChannelAlias#canonicalName}；</li>
 *   <li>仍只做精确匹配，不做模糊匹配。</li>
 * </ol>
 *
 * <p>线路顺序：源 order 小的在前；同源内保持 URL 顺序；同一 URL 只保留第一次出现。
 * 频道元信息（台号/分组/台标/源名）取第一个来源；显示名优先中文：条目当前名是
 * 纯英文/拉丁字符、新来的是中文时替换显示名，反之保留。分组仍交给
 * {@link ChannelSet#organize}。</p>
 */
public final class ChannelMerger {

    private ChannelMerger() {
    }

    /** 一个源解析出的频道（保持源内 URL 顺序）。 */
    public static final class SourcePlaylist {

        private final SourceRecord source;
        private final List<Channel> channels;

        public SourcePlaylist(SourceRecord source, List<Channel> channels) {
            if (source == null) {
                throw new IllegalArgumentException("source == null");
            }
            this.source = source;
            this.channels = channels == null
                    ? Collections.<Channel>emptyList() : channels;
        }

        public SourceRecord source() {
            return source;
        }

        public List<Channel> channels() {
            return channels;
        }
    }

    /** 合并结果。 */
    public static final class Result {

        private final Playlist playlist;
        private final List<String> warnings;
        private final int mergedChannels;
        private final int totalParsed;
        private final int skipped;

        Result(Playlist playlist, List<String> warnings, int mergedChannels,
                int totalParsed, int skipped) {
            this.playlist = playlist;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
            this.mergedChannels = mergedChannels;
            this.totalParsed = totalParsed;
            this.skipped = skipped;
        }

        /** 已 organize（分组/编号）的合并结果。 */
        public Playlist playlist() {
            return playlist;
        }

        /** 截断/跳过源等中文提示；正常为空。 */
        public List<String> warnings() {
            return warnings;
        }

        public int mergedChannels() {
            return mergedChannels;
        }

        /** 所有源解析出的频道数之和（去重前）。 */
        public int totalParsed() {
            return totalParsed;
        }

        public int skipped() {
            return skipped;
        }
    }

    /** 合并入口；inputs 内部的顺序无关，结果按 source.order() 升序。 */
    public static Result merge(List<SourcePlaylist> inputs) {
        List<String> warnings = new ArrayList<String>();
        List<SourcePlaylist> ordered = new ArrayList<SourcePlaylist>(
                inputs == null ? Collections.<SourcePlaylist>emptyList() : inputs);
        Collections.sort(ordered, new java.util.Comparator<SourcePlaylist>() {
            @Override
            public int compare(SourcePlaylist left, SourcePlaylist right) {
                int lo = left.source().order();
                int ro = right.source().order();
                return lo < ro ? -1 : (lo > ro ? 1 : 0);
            }
        });

        Map<String, Entry> byKey = new LinkedHashMap<String, Entry>();
        List<Entry> entries = new ArrayList<Entry>();
        int totalParsed = 0;
        int skipped = 0;
        boolean totalLimitHit = false;

        for (SourcePlaylist input : ordered) {
            List<Channel> channels = input.channels();
            int remaining = SourceStore.MAX_TOTAL_CHANNELS - totalParsed;
            if (remaining <= 0) {
                totalLimitHit = true;
                break;
            }
            int take = Math.min(channels.size(), remaining);
            if (take < channels.size()) {
                totalLimitHit = true;
            }
            totalParsed += take;
            for (int i = 0; i < take; i++) {
                Channel channel = channels.get(i);
                if (channel == null || channel.variants().isEmpty()) {
                    continue;
                }
                mergeChannel(channel, input.source().name(), input.source().order(),
                        byKey, entries);
            }
            if (take < channels.size()) {
                break;
            }
        }
        if (totalLimitHit) {
            warnings.add("源频道总数超过 " + SourceStore.MAX_TOTAL_CHANNELS + "，已忽略部分频道");
        }

        List<Channel> merged = new ArrayList<Channel>(entries.size());
        for (Entry entry : entries) {
            entry.finish();
            if (!entry.channel.variants().isEmpty()) {
                merged.add(entry.channel);
            }
        }
        if (merged.size() > SourceStore.MAX_MERGED_CHANNELS) {
            merged = new ArrayList<Channel>(
                    merged.subList(0, SourceStore.MAX_MERGED_CHANNELS));
            warnings.add("合并后频道超过 " + SourceStore.MAX_MERGED_CHANNELS + "，已截断");
        }

        Playlist playlist = ChannelSet.organize(merged, skipped);
        return new Result(playlist, warnings, playlist.channelCount(), totalParsed, skipped);
    }

    /** 合并单条频道进已有条目或新建条目。 */
    private static void mergeChannel(Channel channel, String sourceName, int sourceOrder,
            Map<String, Entry> byKey, List<Entry> entries) {
        String idKey = tvgKey(channel);
        String nameKey = nameKey(channel);

        Entry target = idKey == null ? null : byKey.get(idKey);
        Entry nameTarget = byKey.get(nameKey);
        if (target == null) {
            target = nameTarget;
        } else if (nameTarget != null && nameTarget != target) {
            // 两个已有条目指向同一频道（一个带 tvg-id、一个只有名字）：合并成一条。
            mergeEntries(target, nameTarget, byKey, entries);
        }

        if (target == null) {
            Channel copy = new Channel(channel);
            copy.setSourceName(sourceName);
            target = new Entry(copy);
            entries.add(target);
        } else {
            target.preferChineseName(channel.name());
        }

        if (idKey != null) {
            byKey.put(idKey, target);
        }
        byKey.put(nameKey, target);

        for (Variant variant : channel.variants()) {
            if (variant == null || variant.url().isEmpty()) {
                continue;
            }
            target.add(variant, sourceName, sourceOrder);
        }
    }

    /** 把 other 的线路并入 target，并把 other 的键全部改指 target。 */
    private static void mergeEntries(Entry target, Entry other, Map<String, Entry> byKey,
            List<Entry> entries) {
        target.preferChineseName(other.channel.name());
        for (int i = 0; i < other.variants.size(); i++) {
            target.add(other.variants.get(i), other.sourceNames.get(i), other.orders.get(i));
        }
        for (Map.Entry<String, Entry> entry : byKey.entrySet()) {
            if (entry.getValue() == other) {
                entry.setValue(target);
            }
        }
        entries.remove(other);
    }

    /** tvg-id 键：Channel.id 不含 '|' 视为 tvg-id；去掉 @SD/@HD 之类的后缀。 */
    private static String tvgKey(Channel channel) {
        String id = channel.id();
        if (id == null || id.indexOf('|') >= 0) {
            return null;
        }
        String trimmed = PlaylistParser.cleanText(id);
        if (trimmed == null) {
            return null;
        }
        int at = trimmed.indexOf('@');
        if (at > 0) {
            trimmed = trimmed.substring(0, at);
        }
        return trimmed.isEmpty() ? null : "id:" + trimmed;
    }

    /** 名称键：归一化分组 + 别名/规范化名称（精确匹配，不跨组合并）。 */
    private static String nameKey(Channel channel) {
        String group = ChannelSet.resolveGroup(channel.group(), channel.name());
        return "name:" + group + "|" + ChannelAlias.mergeKey(channel.name());
    }

    private static final class Entry {

        final Channel channel;
        final List<Variant> variants = new ArrayList<Variant>();
        final List<String> sourceNames = new ArrayList<String>();
        final List<Integer> orders = new ArrayList<Integer>();
        final Set<String> urls = new LinkedHashSet<String>();

        Entry(Channel channel) {
            this.channel = channel;
        }

        /** 显示名优先中文：仅当当前名是纯英文/拉丁且新名含中文时替换。 */
        void preferChineseName(String incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return;
            }
            if (ChannelAlias.isLatinName(channel.name())
                    && ChannelAlias.containsChinese(incoming)) {
                channel.setName(incoming);
            }
        }

        /** 去重后暂存线路，最后按源 order 稳定排序再写入 Channel。 */
        void add(Variant variant, String sourceName, int order) {
            if (variant == null || variant.url().isEmpty() || !urls.add(variant.url())) {
                return;
            }
            variants.add(new Variant(variant.url(), variant.userAgent(), variant.referer(),
                    sourceName));
            sourceNames.add(sourceName);
            orders.add(Integer.valueOf(order));
        }

        /** 源 order 小的在前；同源保持加入顺序（稳定排序）。 */
        void finish() {
            Integer[] index = new Integer[variants.size()];
            for (int i = 0; i < index.length; i++) {
                index[i] = Integer.valueOf(i);
            }
            java.util.Arrays.sort(index, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer left, Integer right) {
                    int lo = orders.get(left.intValue());
                    int ro = orders.get(right.intValue());
                    return lo < ro ? -1 : (lo > ro ? 1 : 0);
                }
            });
            for (Integer i : index) {
                channel.addVariant(variants.get(i.intValue()));
            }
        }
    }
}
