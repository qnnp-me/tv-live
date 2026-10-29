package me.qnnp.tvlive.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.qnnp.tvlive.R;

/**
 * 导航内常驻的频道操作列（组栏 24% + 列表 52% + 操作列 24%）。
 *
 * <p>顶部是焦点台名称（小字、可省略），下面 4 项：收藏/取消收藏、切换线路、
 * 频道信息、全屏播放。作用于焦点台，不播放、不改预览。操作列没有焦点时仍然显示，
 * 只是不高亮；焦点项用 full-bleed 背景 + 左侧 tally 条。</p>
 */
public final class ActionColumn extends LinearLayout {

    public static final int ITEM_FAVORITE = 0;
    public static final int ITEM_VARIANTS = 1;
    public static final int ITEM_INFO = 2;
    public static final int ITEM_FULLSCREEN = 3;
    public static final int ITEM_COUNT = 4;

    /** 触摸：点操作项（手机横屏复用电视布局时）。遥控仍走 Activity 的焦点高亮。 */
    public interface Listener {

        void onActionSelected(int index);
    }

    private final TextView title;
    private final List<Item> items = new ArrayList<Item>();
    private final int colorText;
    private final int colorMuted;
    private final int colorRowActive;
    private final int itemHeight;
    private final int accentWidth;

    private boolean focused;
    private int focusIndex = ITEM_FAVORITE;
    private boolean variantsEnabled = true;
    private boolean favorite;
    private Listener listener;

    public ActionColumn(Context context) {
        super(context);
        setOrientation(VERTICAL);

        colorText = Res.color(context, R.color.text);
        colorMuted = Res.color(context, R.color.muted);
        colorRowActive = Res.color(context, R.color.row_active);
        itemHeight = Res.dimen(context, R.dimen.action_item_height);
        accentWidth = Res.dimen(context, R.dimen.action_accent_width);

        int padStart = Res.dimen(context, R.dimen.action_pad_start);
        int padEnd = Res.dimen(context, R.dimen.action_pad_end)
                + Res.dimen(context, R.dimen.nav_grad_width);

        title = new TextView(context);
        title.setTextColor(colorMuted);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Res.dimen(context, R.dimen.action_title_text));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(padStart, Res.dimen(context, R.dimen.list_margin_top),
                padEnd, Res.dimen(context, R.dimen.action_title_gap));
        addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addItem(ITEM_FAVORITE, context.getString(R.string.menu_favorite),
                padStart, padEnd);
        addItem(ITEM_VARIANTS, context.getString(R.string.menu_switch_source),
                padStart, padEnd);
        addItem(ITEM_INFO, context.getString(R.string.menu_channel_info),
                padStart, padEnd);
        addItem(ITEM_FULLSCREEN, context.getString(R.string.menu_fullscreen),
                padStart, padEnd);
    }

    /**
     * 更新内容与焦点。name 为焦点台名称（空则标题留空）；favorite 决定第一项文案；
     * variantCount<=1 时「切换线路」不可用。
     */
    public void update(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex) {
        title.setText(name == null ? "" : name);
        this.favorite = favorite;
        this.variantsEnabled = variantCount > 1;
        items.get(ITEM_FAVORITE).label.setText(favorite
                ? getContext().getString(R.string.menu_unfavorite)
                : getContext().getString(R.string.menu_favorite));
        items.get(ITEM_VARIANTS).label.setAlpha(variantsEnabled ? 1f : 0.4f);
        this.focused = focused;
        this.focusIndex = normalize(focusIndex);
        applyState();
    }

    /** 只移动/清空操作列焦点，不重设内容。 */
    public void updateFocus(boolean focused, int focusIndex) {
        this.focused = focused;
        this.focusIndex = normalize(focusIndex);
        applyState();
    }

    public boolean isEnabled(int index) {
        return index != ITEM_VARIANTS || variantsEnabled;
    }

    /** 当前显示的焦点台名称（诊断/测试用）。 */
    public CharSequence channelName() {
        return title.getText();
    }

    public boolean isFavorite() {
        return favorite;
    }

    /** 触摸点按操作项（手机横屏）；null 时操作列只响应遥控。 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private int normalize(int index) {
        if (index < 0 || index >= ITEM_COUNT) {
            return ITEM_FAVORITE;
        }
        return index;
    }

    private void addItem(int index, String label, int padStart, int padEnd) {
        final int itemIndex = index;
        Item item = new Item();

        item.focus = new FocusRow(getContext(), padStart, padEnd, accentWidth,
                FocusRow.inset(itemHeight));

        item.label = new TextView(getContext());
        item.label.setText(label);
        item.label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Res.dimen(getContext(), R.dimen.action_item_text));
        item.label.setTextColor(colorMuted);
        item.label.setSingleLine(true);
        item.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.focus.content.addView(item.label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        item.focus.root.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Listener target = listener;
                if (target != null) {
                    target.onActionSelected(itemIndex);
                }
            }
        });

        items.add(index, item);
        addView(item.focus.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemHeight));
        applyState();
    }

    private void applyState() {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean active = focused && i == focusIndex;
            item.focus.setActiveBackground(active, colorRowActive);
            item.label.setTextColor(active ? colorText : colorMuted);
            item.focus.setAccentVisible(active);
        }
    }

    private static final class Item {

        FocusRow focus;
        TextView label;
    }
}
