package me.qnnp.tvlive.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;

/**
 * 频道导航：全局上一/下一、按全局号/ID 查找、分组筛选（纯 Java，可在 JVM 单测）。
 *
 * <p>列表模式的 CH+/− 只移焦点且到首/尾不循环（{@link #step}）；全屏保持 2b 的循环换台
 * （{@link #stepWrapping}）。收藏是 UI 虚拟组，不参与全局编号/顺序，由
 * {@link #favorites(Set)} 按全局顺序筛出。</p>
 */
public final class ChannelNavigator {

    private final List<Channel> channels;
    private final List<String> groupNames;

    public ChannelNavigator(List<Channel> channels, List<Group> groups) {
        List<Channel> copy = channels == null
                ? Collections.<Channel>emptyList() : new ArrayList<Channel>(channels);
        this.channels = Collections.unmodifiableList(copy);

        List<String> names = new ArrayList<String>();
        if (groups != null) {
            for (Group group : groups) {
                if (group == null || group.name() == null) {
                    continue;
                }
                names.add(group.name());
            }
        }
        this.groupNames = Collections.unmodifiableList(names);
    }

    /** 全局显示顺序（与编号一致）。 */
    public List<Channel> channels() {
        return channels;
    }

    public int count() {
        return channels.size();
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    /** 数据组名（不含收藏虚拟组），按最终显示顺序。 */
    public List<String> groups() {
        return groupNames;
    }

    public Channel first() {
        return channels.isEmpty() ? null : channels.get(0);
    }

    public Channel last() {
        return channels.isEmpty() ? null : channels.get(channels.size() - 1);
    }

    public Channel byId(String channelId) {
        int index = indexOf(channelId);
        return index < 0 ? null : channels.get(index);
    }

    /**
     * 按全局号查找。编号从 1 连续到 N；先按 O(1) 下标校验，编号不连续时退回线性查找。
     */
    public Channel byNumber(int number) {
        if (number <= 0) {
            return null;
        }
        int index = number - 1;
        if (index < channels.size() && channels.get(index).number() == number) {
            return channels.get(index);
        }
        for (Channel channel : channels) {
            if (channel.number() == number) {
                return channel;
            }
        }
        return null;
    }

    /**
     * 全局上一/下一台，不循环：首台再上一台、末台再下一台都返回 null。
     * from 为 null 时 delta &gt; 0 取首台，delta &lt; 0 取末台。
     */
    public Channel step(Channel from, int delta) {
        if (channels.isEmpty() || delta == 0) {
            return null;
        }
        if (from == null) {
            return delta > 0 ? first() : last();
        }
        int index = indexOf(from.id());
        if (index < 0) {
            return delta > 0 ? first() : last();
        }
        int next = index + delta;
        if (next < 0 || next >= channels.size()) {
            return null;
        }
        return channels.get(next);
    }

    /** 全局上一/下一台，循环（全屏换台沿用 2b 口径）。 */
    public Channel stepWrapping(Channel from, int delta) {
        if (channels.isEmpty() || delta == 0) {
            return null;
        }
        if (from == null) {
            return delta > 0 ? first() : last();
        }
        int index = indexOf(from.id());
        if (index < 0) {
            return delta > 0 ? first() : last();
        }
        int size = channels.size();
        return channels.get(((index + delta) % size + size) % size);
    }

    /** 某个数据组的频道，保持源顺序；未知组返回空列表。 */
    public List<Channel> group(String groupName) {
        if (groupName == null) {
            return Collections.emptyList();
        }
        List<Channel> members = new ArrayList<Channel>();
        for (Channel channel : channels) {
            if (groupName.equals(channel.group())) {
                members.add(channel);
            }
        }
        return members;
    }

    public int groupCount(String groupName) {
        int count = 0;
        if (groupName != null) {
            for (Channel channel : channels) {
                if (groupName.equals(channel.group())) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 收藏虚拟组：按全局顺序筛出已收藏频道。 */
    public List<Channel> favorites(Set<String> favoriteIds) {
        if (favoriteIds == null || favoriteIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Channel> members = new ArrayList<Channel>();
        for (Channel channel : channels) {
            if (favoriteIds.contains(channel.id())) {
                members.add(channel);
            }
        }
        return members;
    }

    /** 频道所属数据组索引（0 起）；找不到返回 -1。 */
    public int groupIndexOf(Channel channel) {
        if (channel == null) {
            return -1;
        }
        int index = groupNames.indexOf(channel.group());
        if (index >= 0) {
            return index;
        }
        return -1;
    }

    /** 频道在全局顺序中的下标；找不到返回 -1。 */
    public int indexOf(String channelId) {
        if (channelId == null) {
            return -1;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (channelId.equals(channels.get(i).id())) {
                return i;
            }
        }
        return -1;
    }
}
