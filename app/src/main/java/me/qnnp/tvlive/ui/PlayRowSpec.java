package me.qnnp.tvlive.ui;

import me.qnnp.tvlive.R;

/**
 * 设置页一级「播放」分组的行定义：顺序与初始文案。
 *
 * <p>批次 18 起一级只放常用项：切台方式 → 视频渲染 → 画面比例 → 切台提示 → 显示网速；
 * 首帧加速移到可折叠的「高级」分组（见 {@link AdvancedRowSpec}）。纯数据（只依赖 R 的
 * int 常量），{@link SettingsView} 按此顺序建行，单测据此防止行序/文案回归。</p>
 */
public final class PlayRowSpec {

    public static final int ACTION_SWITCH_MODE = 20;
    public static final int ACTION_CARD = 22;
    public static final int ACTION_SPEED = 23;
    public static final int ACTION_RENDER = 24;
    public static final int ACTION_ASPECT = 25;

    /** 一行播放设置：动作 id + 标签 + 默认（未选中时也用作初始值）说明。 */
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
            new Row(ACTION_SWITCH_MODE, R.string.settings_play_switch_mode,
                    R.string.settings_play_switch_keep_detail),
            new Row(ACTION_RENDER, R.string.settings_play_render,
                    R.string.settings_play_render_auto_detail),
            new Row(ACTION_ASPECT, R.string.settings_play_aspect,
                    R.string.settings_play_aspect_fit_detail),
            new Row(ACTION_CARD, R.string.settings_play_card,
                    R.string.settings_play_card_detail),
            new Row(ACTION_SPEED, R.string.settings_play_speed,
                    R.string.settings_play_speed_detail),
    };

    private PlayRowSpec() {
    }

    /** 按显示顺序返回行定义（副本，调用方不可改静态表）。 */
    public static Row[] rows() {
        return ROWS.clone();
    }

    /** 视频渲染的三档右值标签：自动 / SurfaceView / TextureView。 */
    public static int renderLabelRes(int renderMode) {
        switch (renderMode) {
            case 1:
                return R.string.settings_play_render_surface;
            case 2:
                return R.string.settings_play_render_texture;
            default:
                return R.string.settings_play_render_auto;
        }
    }

    /** 视频渲染三档的说明行。 */
    public static int renderDetailRes(int renderMode) {
        switch (renderMode) {
            case 1:
                return R.string.settings_play_render_surface_detail;
            case 2:
                return R.string.settings_play_render_texture_detail;
            default:
                return R.string.settings_play_render_auto_detail;
        }
    }

    /** 画面比例三档右值标签：适应 / 拉伸 / 裁剪。 */
    public static int aspectLabelRes(int aspect) {
        switch (aspect) {
            case 1:
                return R.string.settings_play_aspect_stretch;
            case 2:
                return R.string.settings_play_aspect_crop;
            default:
                return R.string.settings_play_aspect_fit;
        }
    }

    /** 画面比例三档的说明行。 */
    public static int aspectDetailRes(int aspect) {
        switch (aspect) {
            case 1:
                return R.string.settings_play_aspect_stretch_detail;
            case 2:
                return R.string.settings_play_aspect_crop_detail;
            default:
                return R.string.settings_play_aspect_fit_detail;
        }
    }
}
