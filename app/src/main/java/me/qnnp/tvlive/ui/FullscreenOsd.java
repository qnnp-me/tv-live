package me.qnnp.tvlive.ui;

/**
 * 手机全屏 OSD 计时（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5b：全屏点画面出 2.5s OSD + 半透明返回按钮；OSD 期间再点一次收起。
 * 计时放这里，{@code MainActivity} 只按 {@link #isVisible(long)} 的结果驱动
 * {@code Overlays}（OSD 文字与返回按钮共用同一个可见窗口）。</p>
 *
 * <p>与 {@link NavVisibility} 的分工：那个是电视导航的 8 秒自动收起，本类只管
 * 手机全屏 OSD 的 2.5 秒，互不影响。</p>
 */
public final class FullscreenOsd {

    /** 规格：2.5 秒。 */
    public static final long DEFAULT_SHOW_MS = 2500L;

    private final long showMs;

    private boolean visible;
    private long hideAt = Long.MIN_VALUE;

    public FullscreenOsd() {
        this(DEFAULT_SHOW_MS);
    }

    public FullscreenOsd(long showMs) {
        if (showMs <= 0L) {
            throw new IllegalArgumentException("showMs <= 0");
        }
        this.showMs = showMs;
    }

    /** 显示 OSD（并重开计时）；进入全屏、点画面、旋转后重新给满。 */
    public void show(long now) {
        visible = true;
        hideAt = now + showMs;
    }

    public void hide() {
        visible = false;
        hideAt = Long.MIN_VALUE;
    }

    /** 到点自动隐藏，返回当前是否可见（只读语义，不改调用方状态）。 */
    public boolean isVisible(long now) {
        if (visible && now >= hideAt) {
            hide();
        }
        return visible;
    }

    /** OSD 计划收起时刻；{@link #hide()} 后为 {@link Long#MIN_VALUE}（诊断/测试用）。 */
    public long hideAt() {
        return hideAt;
    }

    public long showMs() {
        return showMs;
    }

    /** 切布局 / 进设置页时复位。 */
    public void reset() {
        hide();
    }
}
