package me.qnnp.tvlive.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.data.PlaylistModel.Channel;

/**
 * 频道列表行：台号 / 名称 / 播放点 / 收藏星，无台标（省内存）。
 *
 * <p>只渲染 Activity 过滤好的当前分组；焦点态由 Activity 驱动（电视上列表本身不拿焦点，
 * 方向键由 Activity 统一处理），所以行不设置 focusable。焦点条与播放点相互独立，
 * 同一行可以同时有 tally 条和 {@code ●}。</p>
 *
 * <p>大列表（871 条）性能：行视图完整复用；颜色/尺寸在构造时缓存；台号走静态表；
 * 只有频道 id、焦点态、播放态、收藏态变化时才改 TextView，滚动/切台不产生逐行文本分配。</p>
 *
 * <p>批次 12：焦点/播放点变化不再 {@code notifyDataSetChanged}，只重绑列表中可见的旧/新
 * 两行（{@link #attachList} + {@link ListRowRefresh}）；不可见的行滚入时由
 * {@link #getView} 按最新状态绑定。数据变化（换组/换源）仍整体刷新。</p>
 */
public final class ChannelListAdapter extends BaseAdapter {

    /** 台号 1..999 预格式化，避免每次 bind 都 String.format。 */
    private static final String[] NUMBER_LABELS = new String[1000];

    static {
        for (int i = 1; i < NUMBER_LABELS.length; i++) {
            NUMBER_LABELS[i] = i < 10 ? "0" + i : String.valueOf(i);
        }
    }

    private final Context context;
    private final boolean phone;
    private final List<Channel> channels = new ArrayList<Channel>();
    private final Set<String> favorites = new HashSet<String>();
    private int focusIndex = -1;
    /** 焦点是否在列表（组栏拿焦点时仍保留 focusIndex，但不绘制行焦点）。 */
    private boolean focusVisible = true;
    private String playingId;
    /** 当前挂载的列表；焦点/播放点变化时只重绑它的可见行（null = 尚未挂载）。 */
    private AbsListView list;

    private final int colorRowActive;
    private final int colorRowNum;
    private final int colorTally;
    private final int colorText;
    private final int colorMuted;
    private final int rowHeight;
    private final int rowNumWidth;
    private final int rowNumText;
    private final int rowNameText;
    private final int rowStarText;
    private final int rowStarWidth;
    private final int rowDotWidth;
    private final int rowPadStart;
    private final int rowPadEnd;
    private final int rowAccentWidth;

    public ChannelListAdapter(Context context) {
        this(context, false);
    }

    /**
     * @param phone true 时用手机尺寸（56dp 行、17sp 名称，批次 5a）并关闭跑马灯：
     *              触摸列表里焦点行只换底色，不滚动文字。
     */
    public ChannelListAdapter(Context context, boolean phone) {
        this.context = context;
        this.phone = phone;
        colorRowActive = Res.color(context, R.color.row_active);
        colorRowNum = Res.color(context, R.color.row_num);
        colorTally = Res.color(context, R.color.tally);
        colorText = Res.color(context, R.color.text);
        colorMuted = Res.color(context, R.color.muted);
        rowHeight = Res.dimen(context, phone ? R.dimen.phone_row_height : R.dimen.row_height);
        rowNumWidth = Res.dimen(context,
                phone ? R.dimen.phone_row_num_width : R.dimen.row_num_width);
        rowNumText = Res.dimen(context,
                phone ? R.dimen.phone_row_num_text : R.dimen.row_num_text);
        rowNameText = Res.dimen(context,
                phone ? R.dimen.phone_row_name_text : R.dimen.row_name_text);
        rowStarText = Res.dimen(context,
                phone ? R.dimen.phone_row_star_text : R.dimen.row_star_text);
        rowStarWidth = Res.dimen(context,
                phone ? R.dimen.phone_row_star_width : R.dimen.row_star_width);
        rowDotWidth = Res.dimen(context,
                phone ? R.dimen.phone_row_dot_width : R.dimen.row_dot_width);
        rowPadStart = Res.dimen(context,
                phone ? R.dimen.phone_row_pad_start : R.dimen.row_pad_start);
        rowPadEnd = Res.dimen(context,
                phone ? R.dimen.phone_row_pad_end : R.dimen.row_pad_end);
        rowAccentWidth = Res.dimen(context,
                phone ? R.dimen.phone_row_accent_width : R.dimen.row_accent_width);
    }

    /** 绑定当前显示列表：焦点/播放点变化时用它找可见行，避免全量 notifyDataSetChanged。 */
    public void attachList(AbsListView listView) {
        this.list = listView;
    }

    /** 一次替换列表与收藏快照，只触发一次刷新。 */
    public void setData(List<Channel> source, Set<String> favoriteIds) {
        channels.clear();
        if (source != null) {
            channels.addAll(source);
        }
        favorites.clear();
        if (favoriteIds != null) {
            favorites.addAll(favoriteIds);
        }
        notifyDataSetChanged();
    }

    public void setFavorites(Set<String> favoriteIds) {
        favorites.clear();
        if (favoriteIds != null) {
            favorites.addAll(favoriteIds);
        }
        notifyDataSetChanged();
    }

    /** 焦点行（tally 条）；播放台不在这里，见 {@link #setPlayingId}。 */
    public void setFocusIndex(int index) {
        if (focusIndex == index) {
            return;
        }
        int previous = focusIndex;
        focusIndex = index;
        refreshVisibleRows(previous, index, true);
    }

    /**
     * 焦点是否在频道列表。焦点切到组栏时保留 {@code focusIndex}（返回列表时落到原行），
     * 但不再绘制列表行焦点（背景/tally/亮色/跑马灯），避免同时出现两个焦点。
     * 操作列仍作用于焦点台，所以只有组栏拿焦点时调用方应传 false。
     */
    public void setFocusVisible(boolean visible) {
        if (focusVisible == visible) {
            return;
        }
        focusVisible = visible;
        // focusIndex 不变：只需重绑当前焦点行，去掉/恢复它的焦点样式。
        refreshVisibleRows(focusIndex, focusIndex, true);
    }

    /** 正在播放/预览的台：行尾弱 {@code ●}，可与焦点条同时存在。 */
    public void setPlayingId(String channelId) {
        if (playingId == null ? channelId == null : playingId.equals(channelId)) {
            return;
        }
        int previous = indexOfChannel(playingId);
        playingId = channelId;
        refreshVisibleRows(previous, indexOfChannel(channelId), false);
    }

    /**
     * 只重绑可见的受影响行；不可见的行滚入时由 {@link #getView} 按最新状态绑定。
     * 列表无 header/footer，{@code getFirstVisiblePosition() + i} 即第 i 个 child 的
     * adapter position（ListView 的行顺序与可见 child 顺序一致）。
     */
    private void refreshVisibleRows(int oldPosition, int newPosition, boolean focusChange) {
        AbsListView listView = list;
        if (listView == null) {
            return;
        }
        int first = listView.getFirstVisiblePosition();
        int children = listView.getChildCount();
        for (int i = 0; i < children; i++) {
            int position = first + i;
            boolean affected = focusChange
                    ? ListRowRefresh.focusAffects(position, oldPosition, newPosition)
                    : ListRowRefresh.playingAffects(position, oldPosition, newPosition);
            if (!affected) {
                continue;
            }
            View child = listView.getChildAt(i);
            Object tag = child == null ? null : child.getTag();
            if (tag instanceof RowHolder) {
                bindRow((RowHolder) tag, position);
            }
        }
    }

    private int indexOfChannel(String channelId) {
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

    public Channel channelAt(int index) {
        if (index < 0 || index >= channels.size()) {
            return null;
        }
        return channels.get(index);
    }

    @Override
    public int getCount() {
        return channels.size();
    }

    @Override
    public Channel getItem(int position) {
        return channelAt(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RowHolder holder;
        if (convertView == null) {
            holder = createRow();
            convertView = holder.focus.root;
            convertView.setTag(holder);
        } else {
            holder = (RowHolder) convertView.getTag();
        }
        bindRow(holder, position);
        return convertView;
    }

    private void bindRow(RowHolder holder, int position) {
        Channel channel = channelAt(position);
        holder.bind(channel,
                ListRowRefresh.focusVisible(focusVisible, position, focusIndex),
                channel != null && playingId != null && playingId.equals(channel.id()),
                channel != null && favorites.contains(channel.id()));
    }

    /** 行结构：左侧 tally 条 + 台号 + 名称 + 播放点 + 行尾星。 */
    private RowHolder createRow() {
        RowHolder holder = new RowHolder();

        FocusRow focus = new FocusRow(context, rowPadStart, rowPadEnd, rowAccentWidth,
                FocusRow.inset(rowHeight));
        holder.focus = focus;
        focus.root.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        LinearLayout content = focus.content;

        holder.number = new TextView(context);
        holder.number.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowNumText);
        holder.number.setTextColor(colorRowNum);
        holder.number.setSingleLine(true);
        content.addView(holder.number, new LinearLayout.LayoutParams(
                rowNumWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder.name = new TextView(context);
        holder.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowNameText);
        holder.name.setTextColor(colorMuted);
        holder.name.setSingleLine(true);
        holder.name.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(holder.name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        holder.dot = FocusRow.playingDot(context, R.dimen.row_dot_text);
        holder.dot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        content.addView(holder.dot, new LinearLayout.LayoutParams(
                rowDotWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder.star = new TextView(context);
        holder.star.setText("★");
        holder.star.setTextSize(TypedValue.COMPLEX_UNIT_PX, rowStarText);
        holder.star.setTextColor(colorTally);
        holder.star.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        holder.star.setSingleLine(true);
        content.addView(holder.star, new LinearLayout.LayoutParams(
                rowStarWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        return holder;
    }

    /** 焦点行：MARQUEE + selected（短名不滚，超长才动）；非焦点行：END 省略并取消选中。 */
    private void applyEllipsize(TextView name, boolean focused) {
        if (focused && !phone) {
            name.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            name.setMarqueeRepeatLimit(-1);
            name.setHorizontallyScrolling(true);
            name.setSelected(true);
        } else {
            name.setSelected(false);
            name.setHorizontallyScrolling(false);
            name.setMarqueeRepeatLimit(0);
            name.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    static String formatNumber(int number) {
        if (number > 0 && number < NUMBER_LABELS.length) {
            return NUMBER_LABELS[number];
        }
        return number <= 0 ? "" : String.valueOf(number);
    }

    private final class RowHolder {

        FocusRow focus;
        TextView number;
        TextView name;
        TextView dot;
        TextView star;

        private String boundId;
        private String boundName;
        private int boundNumber;
        private boolean boundFocused;
        private boolean boundPlaying;
        private boolean boundFavorite;

        void bind(Channel channel, boolean focused, boolean playing, boolean favorite) {
            if (channel == null) {
                if (boundId != null) {
                    boundId = null;
                    boundName = null;
                    boundNumber = 0;
                    number.setText("");
                    name.setText("");
                    star.setVisibility(View.INVISIBLE);
                    dot.setVisibility(View.INVISIBLE);
                    focus.setAccentVisible(false);
                    focus.setActiveBackground(false, colorRowActive);
                    number.setTextColor(colorRowNum);
                    name.setTextColor(colorMuted);
                    applyEllipsize(name, false);
                }
                boundFocused = focused;
                boundPlaying = playing;
                boundFavorite = favorite;
                return;
            }

            boolean contentChanged = !channel.id().equals(boundId)
                    || !channel.name().equals(boundName)
                    || channel.number() != boundNumber;
            boolean focusChanged = focused != boundFocused;
            boolean playingChanged = (playing != boundPlaying)
                    || (playing && contentChanged);
            boolean favoriteChanged = favorite != boundFavorite;
            if (!contentChanged && !focusChanged && !playingChanged && !favoriteChanged) {
                return;
            }

            if (contentChanged) {
                number.setText(formatNumber(channel.number()));
                name.setText(channel.name());
            }
            if (contentChanged || focusChanged) {
                focus.setActiveBackground(focused, colorRowActive);
                number.setTextColor(focused ? colorTally : colorRowNum);
                name.setTextColor(focused ? colorText : colorMuted);
                focus.setAccentVisible(focused);
                // 焦点行跑马灯无限循环；非焦点行 END 省略。复用行必须显式复位，
                // 否则 4.4 上滚出视野的行会带着 selected 继续滚动。
                applyEllipsize(name, focused);
            }
            if (contentChanged || playingChanged) {
                dot.setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
            }
            if (contentChanged || favoriteChanged) {
                star.setVisibility(favorite ? View.VISIBLE : View.INVISIBLE);
            }

            boundId = channel.id();
            boundName = channel.name();
            boundNumber = channel.number();
            boundFocused = focused;
            boundPlaying = playing;
            boundFavorite = favorite;
        }
    }
}
