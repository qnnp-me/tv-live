package me.qnnp.tvlive.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放列表模型：Channel / Variant / Group / Playlist。
 *
 * <p>纯 Java，不 import Android，可在 JVM 单测中直接构造。</p>
 */
public final class PlaylistModel {

    private PlaylistModel() {
    }

    /** 一个频道的单个播放地址，UA/Referer 来自该 URL 行前的 #EXTVLCOPT。 */
    public static final class Variant {

        private final String url;
        private final String userAgent;
        private final String referer;
        /** 该线路来自哪个源（多源合并时写入）；单源/测试构造时为 null。 */
        private final String sourceName;

        public Variant(String url) {
            this(url, null, null);
        }

        public Variant(String url, String userAgent, String referer) {
            this(url, userAgent, referer, null);
        }

        public Variant(String url, String userAgent, String referer, String sourceName) {
            if (url == null || url.isEmpty()) {
                throw new IllegalArgumentException("url 不能为空");
            }
            this.url = url;
            this.userAgent = emptyToNull(userAgent);
            this.referer = emptyToNull(referer);
            this.sourceName = emptyToNull(sourceName);
        }

        public String url() {
            return url;
        }

        public String userAgent() {
            return userAgent;
        }

        public String referer() {
            return referer;
        }

        /** 线路所属源名；未合并/测试构造时为 null。 */
        public String sourceName() {
            return sourceName;
        }

        @Override
        public String toString() {
            return "Variant{" + url + "}";
        }
    }

    /**
     * 频道。id 在解析期确定（tvg-id 优先，否则 group|normalizedName）；
     * group/number 由 {@link ChannelSet#organize} 归一化后写入。
     */
    public static final class Channel {

        private final String id;
        private String name;
        private final String logoUrl;
        private final List<Variant> variants = new ArrayList<Variant>(1);
        /** 解析期原始 group-title/EXTGRP；organize 不改它，用于「其他」子组聚合。 */
        private final String sourceGroup;
        private String group;
        private int number;
        /** 首个来源的源名（多源合并时写入）；单源/测试构造时为 null。 */
        private String sourceName;

        public Channel(String id, String name, String group, String logoUrl) {
            this(id, name, group, group, logoUrl);
        }

        /**
         * @param group       organize 后的最终分组（构造时可先放原始 group-title，之后会被改写）
         * @param sourceGroup 原始 group-title/EXTGRP，空为 ""；不随 organize 变化
         */
        public Channel(String id, String name, String group, String sourceGroup, String logoUrl) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("id 不能为空");
            }
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            this.id = id;
            this.name = name;
            this.group = group == null ? "" : group;
            this.sourceGroup = sourceGroup == null ? "" : sourceGroup;
            this.logoUrl = logoUrl;
        }

        /** 复制频道元信息（不含 variants），variant 顺序由调用方重新决定。 */
        public Channel(Channel source) {
            this(source.id, source.name, source.group, source.sourceGroup, source.logoUrl);
            this.number = source.number;
            this.sourceName = source.sourceName;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        /**
         * 多源合并时改用更可读的显示名（英文名在前、中文名在后时替换）；
         * 只改显示名，id/分组沿用条目第一个频道。
         */
        public void setName(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name 不能为空");
            }
            this.name = name;
        }

        /** 解析期为原始 group-title/EXTGRP；organize 后为最终分组名。 */
        public String group() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group == null ? "" : group;
        }

        /** 原始 group-title/EXTGRP（trim 后）；「其他」子组按它聚合。 */
        public String sourceGroup() {
            return sourceGroup;
        }

        /** 台标地址：解析保留，UI 不加载（省内存）。 */
        public String logoUrl() {
            return logoUrl;
        }

        /** 首个来源的源名；单源/测试构造时为 null。 */
        public String sourceName() {
            return sourceName;
        }

        public void setSourceName(String sourceName) {
            this.sourceName = emptyToNull(sourceName);
        }

        /** 全局显示序号，1..N；organize 前为 0。 */
        public int number() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }

        public List<Variant> variants() {
            return Collections.unmodifiableList(variants);
        }

        public void addVariant(Variant variant) {
            if (variant == null) {
                throw new IllegalArgumentException("variant == null");
            }
            variants.add(variant);
        }

        @Override
        public String toString() {
            return "Channel{" + number + " " + name + " [" + group + "] variants=" + variants.size() + "}";
        }
    }

    /** 分组：name 为最终显示名，channels 为组内源顺序。 */
    public static final class Group {

        private final String name;
        private final List<Channel> channels;

        public Group(String name, List<Channel> channels) {
            if (name == null) {
                throw new IllegalArgumentException("name == null");
            }
            this.name = name;
            this.channels = Collections.unmodifiableList(new ArrayList<Channel>(
                    channels == null ? Collections.<Channel>emptyList() : channels));
        }

        public String name() {
            return name;
        }

        public List<Channel> channels() {
            return channels;
        }

        @Override
        public String toString() {
            return "Group{" + name + " channels=" + channels.size() + "}";
        }
    }

    /** 解析/组织结果。成功时 error 为 null；失败走 PlaylistParseException。 */
    public static final class Playlist {

        private final List<Channel> channels;
        private final List<Group> groups;
        private final int skipped;
        private final String error;

        public Playlist(List<Channel> channels, List<Group> groups, int skipped, String error) {
            this.channels = Collections.unmodifiableList(new ArrayList<Channel>(
                    channels == null ? Collections.<Channel>emptyList() : channels));
            this.groups = Collections.unmodifiableList(new ArrayList<Group>(
                    groups == null ? Collections.<Group>emptyList() : groups));
            this.skipped = skipped;
            this.error = error;
        }

        /** 最终显示顺序（与 number 一致）。 */
        public List<Channel> channels() {
            return channels;
        }

        public List<Group> groups() {
            return groups;
        }

        /** 被跳过的 URL 数（非 http/https、无法解析的相对地址、无名称）。 */
        public int skipped() {
            return skipped;
        }

        public String error() {
            return error;
        }

        public int channelCount() {
            return channels.size();
        }
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
