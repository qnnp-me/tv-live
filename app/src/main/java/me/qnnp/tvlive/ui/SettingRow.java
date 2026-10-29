package me.qnnp.tvlive.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.qnnp.tvlive.R;

/**
 * 设置页 / 首启页共用行：满宽焦点背景 + 左侧 tally 条 + 左标签 + 右值。
 *
 * <p>单行行（操作行）与两行行（源行：名称 + 台数/状态）只有内容列不同：两行行多一行
 * 详情形，标签右侧留出不压住右值的间距。焦点绑定统一在这里：底色、标签色、tally、
 * 详情弱化，六处设置行不再各写一份。</p>
 */
final class SettingRow {

    final FocusRow focus;
    /** 行根视图（满宽 FrameLayout）；等价于 {@code focus.root}。 */
    final FrameLayout root;
    final TextView label;
    /** 两行行的第二行；单行行为 null。 */
    final TextView detail;
    final TextView value;

    private final int activeBackground;
    private final int activeLabelColor;
    private final int idleLabelColor;

    private SettingRow(Context context, FocusRow focus, int idleLabelColor,
            int labelTextRes, boolean twoLine) {
        this.focus = focus;
        this.root = focus.root;
        this.idleLabelColor = idleLabelColor;
        activeBackground = Res.color(context, R.color.menu_active_bg);
        activeLabelColor = Res.color(context, R.color.text);

        label = new TextView(context);
        label.setTextColor(idleLabelColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, Res.dimen(context, labelTextRes));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);

        value = new TextView(context);
        value.setTextColor(Res.color(context, R.color.muted));
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Res.dimen(context, R.dimen.settings_value_text));
        value.setSingleLine(true);

        detail = twoLine ? new TextView(context) : null;
        if (detail != null) {
            detail.setTextColor(Res.color(context, R.color.muted));
            detail.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Res.dimen(context, R.dimen.settings_source_detail_text));
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    /** 单行操作行：左标签 + 右值，高度/字号与菜单行同一套设置页 token。 */
    static SettingRow menuRow(Context context, CharSequence text) {
        int padSide = Res.dimen(context, R.dimen.settings_row_pad_side);
        FocusRow focus = new FocusRow(context, padSide, 0,
                Res.dimen(context, R.dimen.settings_row_accent_width),
                FocusRow.inset(Res.dimen(context, R.dimen.settings_row_height)));
        SettingRow row = new SettingRow(context, focus, Res.color(context, R.color.menu_item),
                R.dimen.settings_row_label_text, false);
        row.label.setText(text);
        focus.content.addView(row.label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.rightMargin = padSide;
        focus.content.addView(row.value, valueLp);
        return row;
    }

    /** 两行源行：名称 + 台数/状态；右值叠在行右侧（不参与文本列宽度）。 */
    static SettingRow sourceRow(Context context, CharSequence name, CharSequence detail,
            int detailColor) {
        return twoLineRow(context, name, detail, detailColor,
                R.dimen.settings_source_name_text, R.dimen.settings_source_detail_text);
    }

    /**
     * 两行设置行（播放分组）：标签 + 说明，右侧当前值。说明由调用方按焦点显示/隐藏，
     * 行高固定，避免焦点移动时列表跳动。
     */
    static SettingRow playRow(Context context, CharSequence label, CharSequence detail) {
        return twoLineRow(context, label, detail,
                Res.color(context, R.color.muted),
                R.dimen.settings_row_label_text, R.dimen.settings_info_text);
    }

    private static SettingRow twoLineRow(Context context, CharSequence name, CharSequence detail,
            int detailColor, int labelTextRes, int detailTextRes) {
        int padSide = Res.dimen(context, R.dimen.settings_row_pad_side);
        FocusRow focus = new FocusRow(context, 0, 0,
                Res.dimen(context, R.dimen.settings_row_accent_width),
                Res.dimen(context, R.dimen.settings_section_gap_bottom));
        SettingRow row = new SettingRow(context, focus, Res.color(context, R.color.text),
                labelTextRes, true);
        row.label.setText(name);
        row.detail.setText(detail);
        row.detail.setTextColor(detailColor);
        row.detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, Res.dimen(context, detailTextRes));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        textColumn.setPadding(padSide, 0, padSide, 0);
        focus.content.addView(textColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int textRightMargin = padSide + Res.dimen(context, R.dimen.settings_row_value_gap);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.rightMargin = textRightMargin;
        textColumn.addView(row.label, labelLp);

        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.rightMargin = textRightMargin;
        textColumn.addView(row.detail, detailLp);

        FrameLayout.LayoutParams valueLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        valueLp.rightMargin = padSide;
        focus.root.addView(row.value, valueLp);
        return row;
    }

    /** 焦点/激活态：底色 + 标签色 + tally + 详情弱化。 */
    void setFocused(boolean active) {
        focus.setActiveBackground(active, activeBackground);
        label.setTextColor(active ? activeLabelColor : idleLabelColor);
        if (detail != null) {
            detail.setAlpha(active ? 1f : 0.85f);
        }
        focus.setAccentVisible(active);
    }
}
