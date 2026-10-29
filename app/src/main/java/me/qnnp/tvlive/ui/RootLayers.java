package me.qnnp.tvlive.ui;

/**
 * 根层叠放顺序与覆盖层色调（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 8 修正：TCL 4.4 只保证 root 的「最后一个直接子」压过全宽 SurfaceView，
 * 多个 UI 各自做 root 子视图时，时钟/OSD 一刷新就会把导航重新压到视频下面。因此 root
 * 只有两层：视频布局 → uiLayer；导航/首启/设置/浮层都是 uiLayer 的子视图，顺序固定为
 * {@link #NAV} → {@link #PAGE} → {@link #OVERLAYS}。uiLayer 整体压住 SurfaceView，
 * 内部再保证「浮层盖设置、设置盖导航、导航盖视频」。</p>
 *
 * <p>覆盖层统一用 90% 黑（{@link #SCRIM_ALPHA} = 0xE6），视频始终全宽
 * （{@link #videoWidth(int)} 不随导航显隐变化）。</p>
 */
public final class RootLayers {

    /** 视频布局（TvLayout / PhoneLayout）。 */
    public static final int VIDEO = 0;
    /** 导航覆盖层（NavView），压在视频上但不挤压视频。 */
    public static final int NAV = 1;
    /** 首启 / 设置页覆盖层；同样压在视频上。 */
    public static final int PAGE = 2;
    /** OSD / 菜单 / 角标 / 时钟；始终最高。 */
    public static final int OVERLAYS = 3;

    /** 90% 不透明（0xE6 / 255 ≈ 90%）。 */
    public static final int SCRIM_ALPHA = 0xE6;
    /** 覆盖层底色 RGB：0B1012（与 nav_bg 一致）。 */
    public static final int SCRIM_RGB = 0x0B1012;

    private RootLayers() {
    }

    /** 覆盖层颜色：90% 黑。 */
    public static int scrimColor() {
        return (SCRIM_ALPHA << 24) | SCRIM_RGB;
    }

    /** layer 是否画在 other 之上（下标越大越靠上）。 */
    public static boolean isAbove(int layer, int other) {
        return layer > other;
    }

    public static boolean navAboveVideo() {
        return isAbove(NAV, VIDEO);
    }

    public static boolean pageAboveVideo() {
        return isAbove(PAGE, VIDEO);
    }

    public static boolean overlaysAboveNav() {
        return isAbove(OVERLAYS, NAV);
    }

    /**
     * 视频可见宽度：导航/设置页显示或隐藏都不改变它，恒为整屏宽。
     *
     * @param totalWidth 根布局宽；<=0 返回 0
     */
    public static int videoWidth(int totalWidth) {
        return totalWidth > 0 ? totalWidth : 0;
    }

    /** alpha 的百分比（用于校验 90% 覆盖层与「半透明」）。 */
    public static int alphaPercent(int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        return Math.round(clamped * 100f / 255f);
    }

    /** 半透明：不是全透明，也不是全不透明。 */
    public static boolean isTranslucent(int alpha) {
        return alpha > 0 && alpha < 0xFF;
    }
}
