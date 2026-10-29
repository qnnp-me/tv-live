package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import me.qnnp.tvlive.R;

/**
 * 首启页动作按钮（「在本机输入地址」）：透明底 tally 描边；焦点时 tally 底 + ink 文字。
 *
 * <p>首启页只有这一个可操作项，焦点态由 Activity 直接驱动（{@link #setFocused}）。</p>
 */
final class ActionButton extends TextView {

    ActionButton(Context context) {
        super(context);
        setTextColor(Res.color(context, R.color.text));
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Res.dimen(context, R.dimen.setup_action_text));
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        int padH = Res.dimen(context, R.dimen.setup_action_pad_h);
        int padV = Res.dimen(context, R.dimen.setup_action_pad_v);
        setPadding(padH, padV, padH, padV);
        setBackground(box(false));
        setClickable(true);
    }

    void setFocused(boolean focused) {
        setBackground(box(focused));
        setTextColor(Res.color(getContext(), focused ? R.color.ink : R.color.text));
    }

    private GradientDrawable box(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Res.color(getContext(), R.color.tally) : Color.TRANSPARENT);
        drawable.setCornerRadius(Res.dimen(getContext(), R.dimen.setup_action_radius));
        drawable.setStroke(1, Res.color(getContext(), R.color.tally));
        return drawable;
    }
}
