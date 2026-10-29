package me.qnnp.tvlive.ui;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.prefs.AutoHide;

/**
 * 设置页可折叠「高级」分组的行定义与折叠标题。
 *
 * <p>批次 18：一级只放常用项，调一次就不动的项收进这里——首帧加速、导航宽度、自动收起、
 * 时钟角标。{@link #rows()} 的顺序就是展开后的显示/焦点顺序；折叠标题的项数由它动态
 * 计算，之后往这里继续加行不需要改标题逻辑。</p>
 *
 * <p>纯数据，只依赖 R 的 int 常量，单测据此防止行序/文案回归。</p>
 */
public final class AdvancedRowSpec {

    /** 首帧加速（批次 9 的一级项，批次 18 移入高级）。 */
    public static final int ACTION_BOOST = 21;
    /** 折叠标题行：OK 展开/收起。 */
    public static final int ACTION_TOGGLE = 26;
    public static final int ACTION_NAV_WIDTH = 27;
    public static final int ACTION_AUTO_HIDE = 28;
    public static final int ACTION_CLOCK = 29;

    /** 一行高级设置：动作 id + 标签 + 初始说明（当前值说明由页面按值刷新）。 */
    public static final class Row {

        public final int action;
        public final int labelRes;
        public final int detailRes;

        Row(int action, int labelRes, int detailRes) {
            this.action = action;
            this.labelRes = labelRes;
            this.detailRes = detailRes;
        }
    }

    private static final Row[] ROWS = {
            new Row(ACTION_BOOST, R.string.settings_play_boost,
                    R.string.settings_play_boost_detail),
            new Row(ACTION_NAV_WIDTH, R.string.settings_adv_nav_width,
                    R.string.settings_adv_nav_width_standard_detail),
            new Row(ACTION_AUTO_HIDE, R.string.settings_adv_auto_hide,
                    R.string.settings_adv_auto_hide_detail),
            new Row(ACTION_CLOCK, R.string.settings_adv_clock,
                    R.string.settings_adv_clock_detail),
    };

    private AdvancedRowSpec() {
    }

    /** 按展开后的显示顺序返回行定义（副本，调用方不可改静态表）。 */
    public static Row[] rows() {
        return ROWS.clone();
    }

    /** 高级分组当前项数（折叠标题的「N 项」）。 */
    public static int itemCount() {
        return ROWS.length;
    }

    /** 折叠标题文案：收起「▸ 高级设置 · N 项」/ 展开「▾ 高级设置 · N 项」。 */
    public static int titleRes(boolean expanded) {
        return expanded ? R.string.settings_advanced_title_expanded
                : R.string.settings_advanced_title_collapsed;
    }

    /** 导航宽度三档右值标签：窄 38% / 标准 44% / 宽 50%。 */
    public static int navWidthLabelRes(int navWidth) {
        switch (navWidth) {
            case 0:
                return R.string.settings_adv_nav_width_narrow;
            case 2:
                return R.string.settings_adv_nav_width_wide;
            default:
                return R.string.settings_adv_nav_width_standard;
        }
    }

    /** 导航宽度三档说明。 */
    public static int navWidthDetailRes(int navWidth) {
        switch (navWidth) {
            case 0:
                return R.string.settings_adv_nav_width_narrow_detail;
            case 2:
                return R.string.settings_adv_nav_width_wide_detail;
            default:
                return R.string.settings_adv_nav_width_standard_detail;
        }
    }

    /** 自动收起右值标签：5 秒 / 8 秒 / 15 秒 / 不收起。 */
    public static int autoHideLabelRes(int seconds) {
        switch (seconds) {
            case 5:
                return R.string.settings_adv_auto_hide_5;
            case 15:
                return R.string.settings_adv_auto_hide_15;
            case 0:
                return R.string.settings_adv_auto_hide_off;
            default:
                return R.string.settings_adv_auto_hide_8;
        }
    }

    /** 自动收起按秒数归一：只接受 0/5/8/15，其余回退 8 秒。 */
    public static int normalizeAutoHideSeconds(int seconds) {
        return AutoHide.fromSeconds(seconds).seconds();
    }

    /** 设置页 OK 的下一档：5 → 8 → 15 → 不收起 → 5。 */
    public static int nextAutoHideSeconds(int seconds) {
        return AutoHide.fromSeconds(seconds).next().seconds();
    }
}
