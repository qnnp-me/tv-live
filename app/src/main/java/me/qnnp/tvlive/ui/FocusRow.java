package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.qnnp.tvlive.R;

/**
 * 共用行骨架：满宽 root + 左侧 tally 条 + 横排内容列（垂直居中、左右内边距固定）。
 *
 * <p>组栏、频道列表、操作列、右侧菜单、设置页的行都是同一形态，差异只是尺寸参数：
 * 内边距、tally 宽度、tally 上下内缩。tally 常驻占位，只切换显隐，避免焦点移动时
 * 行内文字横移；激活底色由调用方给（各层的「选中」色不完全相同）。</p>
 */
final class FocusRow {

    /** 频道列表 / 操作列 / 菜单 / 设置行的 tally 上下内缩：行高的 18%。 */
    private static final float INSET_RATIO = 0.18f;

    final FrameLayout root;
    final LinearLayout content;
    final View accent;

    /**
     * @param padStart          内容列左内边距
     * @param padEnd            内容列右内边距
     * @param accentWidth       tally 条宽度
     * @param accentInset       tally 条上下内缩（像素；组栏与菜单/列表不同）
     */
    FocusRow(Context context, int padStart, int padEnd, int accentWidth, int accentInset) {
        root = new FrameLayout(context);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(padStart, 0, padEnd, 0);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        accent = new View(context);
        accent.setBackgroundColor(Res.color(context, R.color.tally));
        accent.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams accentLp = new FrameLayout.LayoutParams(
                accentWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        accentLp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        accentLp.topMargin = accentInset;
        accentLp.bottomMargin = accentInset;
        root.addView(accent, accentLp);
    }

    /** 常见内缩：行高的 18%。 */
    static int inset(int itemHeight) {
        return Math.round(itemHeight * INSET_RATIO);
    }

    /** 行尾播放点 {@code ●}：弱化 tally 色，常驻占位（只切显隐）。 */
    static TextView playingDot(Context context, int textSizeRes) {
        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_PX, Res.dimen(context, textSizeRes));
        dot.setTextColor(Res.color(context, R.color.tally));
        dot.setAlpha(0.6f);
        dot.setSingleLine(true);
        dot.setVisibility(View.INVISIBLE);
        return dot;
    }

    /** 焦点背景：active 时填底色，否则透明。 */
    void setActiveBackground(boolean active, int backgroundColor) {
        root.setBackgroundColor(active ? backgroundColor : Color.TRANSPARENT);
    }

    /** tally 条显隐（激活/焦点标记）。 */
    void setAccentVisible(boolean visible) {
        accent.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }
}
