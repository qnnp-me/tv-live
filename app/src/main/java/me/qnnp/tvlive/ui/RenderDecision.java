package me.qnnp.tvlive.ui;

import me.qnnp.tvlive.player.SwitchMode;
import me.qnnp.tvlive.prefs.RenderMode;

/**
 * 渲染方式 × 切台方式的组合规则（纯逻辑，无 Android 依赖，可单测）。
 *
 * <p>批次 17：渲染方式从切台方式里拆出来（设置页「视频渲染」）。{@link RenderMode#AUTO}
 * 沿用旧行为：KEEP/DUAL 用 TextureView（保留上一画面需要纹理层），DIRECT 用 SurfaceView。
 * 强制 SurfaceView 时双画面不可靠（两个 SurfaceView 的层级由合成器决定，无法保证
 * 首帧后交换），因此请求 DUAL 时降级为 KEEP，并在 {@link #degraded} 里报告给调用方提示。</p>
 */
public final class RenderDecision {

    private RenderDecision() {
    }

    /** 该组合是否使用纹理层（TextureView）；false 表示用共享 SurfaceView。 */
    public static boolean useTexture(RenderMode render, SwitchMode mode) {
        RenderMode target = render == null ? RenderMode.AUTO : render;
        SwitchMode switchMode = mode == null ? SwitchMode.KEEP : mode;
        switch (target) {
            case SURFACE:
                return false;
            case TEXTURE:
                return true;
            case AUTO:
            default:
                return switchMode != SwitchMode.DIRECT;
        }
    }

    /**
     * 实际可用的切台方式：SurfaceView + DUAL 不可靠，降级为 KEEP（单引擎保留画面）；
     * 其余组合原样返回。
     */
    public static SwitchMode effectiveSwitch(RenderMode render, SwitchMode mode) {
        SwitchMode switchMode = mode == null ? SwitchMode.KEEP : mode;
        if (render == RenderMode.SURFACE && switchMode == SwitchMode.DUAL) {
            return SwitchMode.KEEP;
        }
        return switchMode;
    }

    /** 请求的切台方式因渲染方式被降级（仅 SurfaceView + DUAL）：调用方应提示并落库 KEEP。 */
    public static boolean degraded(RenderMode render, SwitchMode mode) {
        return render == RenderMode.SURFACE && mode == SwitchMode.DUAL;
    }
}
