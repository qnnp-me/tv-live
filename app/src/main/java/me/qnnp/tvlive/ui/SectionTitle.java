package me.qnnp.tvlive.ui;

import android.content.Context;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.qnnp.tvlive.R;

/**
 * 设置页小节标题（源 / 系统 / 关于）：小字、弱色，上下留白由 section gap token 决定。
 */
final class SectionTitle {

    private SectionTitle() {
    }

    static void addTo(LinearLayout parent, int titleRes) {
        Context context = parent.getContext();
        TextView section = new TextView(context);
        section.setText(titleRes);
        section.setTextColor(Res.color(context, R.color.muted));
        section.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Res.dimen(context, R.dimen.settings_section_text));
        section.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Res.dimen(context, R.dimen.settings_section_gap_top);
        lp.bottomMargin = Res.dimen(context, R.dimen.settings_section_gap_bottom);
        parent.addView(section, lp);
    }
}
