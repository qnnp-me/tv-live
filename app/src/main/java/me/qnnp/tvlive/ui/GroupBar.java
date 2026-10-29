package me.qnnp.tvlive.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.qnnp.tvlive.R;

/**
 * 左栏竖排分组栏：收藏 / 央视 / 卫视 / 地方 / 港澳台 / 其他。
 *
 * <p>每项两段字号（组名 + 数量）；active 组左侧 tally 条 + 组名亮色；焦点项 full-bleed
 * 背景；组内含当前播放台时行尾弱 {@code ●}。批次 2e 起「其他」下可展开子组：
 * 子组缩进、字号小一级、数量弱化；项数超过组栏高度时整栏滚动（焦点项自动滚进视野）。</p>
 */
public final class GroupBar extends LinearLayout {

    /** 组栏 tally 上下内缩：行高的 20%（列表/菜单/设置行为 18%）。 */
    private static final float ACCENT_INSET_RATIO = 0.2f;

    /** 一个组项的展示数据：名称 + 频道数 + 是否为「其他」子组。 */
    public static final class Item {

        private final String name;
        private final int count;
        private final boolean subGroup;

        public Item(String name, int count) {
            this(name, count, false);
        }

        public Item(String name, int count, boolean subGroup) {
            this.name = name == null ? "" : name;
            this.count = count;
            this.subGroup = subGroup;
        }

        public String name() {
            return name;
        }

        public int count() {
            return count;
        }

        public boolean subGroup() {
            return subGroup;
        }
    }

    private final ScrollView scroll;
    private final LinearLayout rowsBox;
    private final List<Row> rows = new ArrayList<Row>();
    private final int colorText;
    private final int colorMuted;
    private final int colorRowActive;
    private final int itemHeight;
    private final int padStart;
    private final int padEnd;
    private final int nameText;
    private final int subNameText;
    private final int countText;
    private final int subCountText;
    private final int subIndent;
    private final int gap;
    private final int accentWidth;

    public GroupBar(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Res.color(context, R.color.surface));

        colorText = Res.color(context, R.color.text);
        colorMuted = Res.color(context, R.color.muted);
        colorRowActive = Res.color(context, R.color.row_active);
        itemHeight = Res.dimen(context, R.dimen.group_item_height);
        padStart = Res.dimen(context, R.dimen.group_item_pad_start);
        padEnd = Res.dimen(context, R.dimen.group_item_pad_end);
        nameText = Res.dimen(context, R.dimen.group_item_name_text);
        subNameText = Res.dimen(context, R.dimen.group_item_sub_name_text);
        countText = Res.dimen(context, R.dimen.group_item_count_text);
        subCountText = Res.dimen(context, R.dimen.group_item_sub_count_text);
        subIndent = Res.dimen(context, R.dimen.group_item_sub_indent);
        gap = Res.dimen(context, R.dimen.group_item_gap);
        accentWidth = Res.dimen(context, R.dimen.group_accent_width);

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);

        rowsBox = new LinearLayout(context);
        rowsBox.setOrientation(VERTICAL);
        scroll.addView(rowsBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 重建组项（一级组 + 「其他」展开时的子组）；数量变化、源重载、收藏变化时调用。 */
    public void setItems(List<Item> items) {
        rowsBox.removeAllViews();
        rows.clear();
        if (items == null) {
            return;
        }
        for (Item item : items) {
            Row row = createRow(item);
            rows.add(row);
            rowsBox.addView(row.focus.root, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemHeight));
        }
    }

    /**
     * 更新状态：active 组（列表当前筛选）、焦点项（焦点在组栏时）、组内播放点。
     * 索引与 {@link #setItems} 的顺序一致；-1 表示没有。播放台可能同时属于收藏组和
     * 它的数据组，所以播放点可以有多个。
     */
    public void updateState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean active = i == activeIndex;
            boolean focused = groupFocus && i == focusIndex;
            // 组栏的 tally 表示「当前筛选组」，与焦点无关；焦点只换底色 + 亮组名。
            row.focus.setAccentVisible(active);
            row.name.setTextColor(active || focused ? colorText : colorMuted);
            row.focus.setActiveBackground(focused, colorRowActive);
            row.dot.setVisibility(isPlaying(i, playingIndexes) ? View.VISIBLE : View.INVISIBLE);
        }
        if (groupFocus) {
            ensureVisible(focusIndex);
            // 刚重建的行还没完成布局（getTop/getBottom 为 0）：布局后再滚一次。
            final int target = focusIndex;
            post(new Runnable() {
                @Override
                public void run() {
                    ensureVisible(target);
                }
            });
        }
    }

    /** 组栏滚动：焦点项被上下边缘裁掉时滚到完整可见。 */
    private void ensureVisible(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        View row = rows.get(index).focus.root;
        int top = row.getTop();
        int bottom = row.getBottom();
        int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
        if (viewport <= 0) {
            return;
        }
        int scrollY = scroll.getScrollY();
        if (top < scrollY) {
            scroll.scrollTo(0, top);
        } else if (bottom > scrollY + viewport) {
            scroll.scrollTo(0, bottom - viewport);
        }
    }

    private static boolean isPlaying(int index, int[] playingIndexes) {
        if (playingIndexes == null) {
            return false;
        }
        for (int playing : playingIndexes) {
            if (playing == index) {
                return true;
            }
        }
        return false;
    }

    private Row createRow(Item item) {
        Row row = new Row();
        boolean sub = item.subGroup();

        // 组栏行高只有列表行的一半多，tally 内缩比例更大（20% vs 18%）。
        row.focus = new FocusRow(getContext(),
                padStart + (sub ? subIndent : 0), padEnd, accentWidth,
                Math.round(itemHeight * ACCENT_INSET_RATIO));
        LinearLayout content = row.focus.content;

        row.name = new TextView(getContext());
        row.name.setText(item.name());
        row.name.setTextColor(colorMuted);
        row.name.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub ? subNameText : nameText);
        row.name.setSingleLine(true);
        row.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(row.name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.count = new TextView(getContext());
        row.count.setText(String.valueOf(item.count()));
        row.count.setTextColor(colorMuted);
        row.count.setTextSize(TypedValue.COMPLEX_UNIT_PX, sub ? subCountText : countText);
        row.count.setSingleLine(true);
        if (sub) {
            // 子组数量弱化：仍然可读，但不和一级组争注意力。
            row.count.setAlpha(0.65f);
        }
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = gap;
        content.addView(row.count, countLp);

        row.dot = FocusRow.playingDot(getContext(), R.dimen.group_dot_text);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotLp.leftMargin = gap;
        content.addView(row.dot, dotLp);

        return row;
    }

    private static final class Row {

        FocusRow focus;
        TextView name;
        TextView count;
        TextView dot;
    }
}
