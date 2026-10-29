package me.qnnp.tvlive.ui;

/**
 * 窗口系统栏/手势条 inset（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5b：手机竖屏、横屏都按沉浸式处理，但状态栏/刘海与手势条仍要避让
 * ——视频不压状态栏、底栏不压手势条。inset 值由 {@code WindowInsets} 读出来，
 * 这里的职责只有两条：</p>
 * <ul>
 *   <li>负数/未上报一律按 0（部分 4.4 盒子会返回 -1）；</li>
 *   <li>电视不参与（{@code phone=false} 恒为 {@link #NONE}），保证 TV 布局与 5a 一致。</li>
 * </ul>
 *
 * <p>方向不在这里判断：竖屏时 inset 落在 top/bottom，横屏手势条/三键栏在侧边时
 * 落在 left/right，系统已经按当前方向给值，直接透传即可。</p>
 */
public final class SystemInsets {

    public static final SystemInsets NONE = new SystemInsets(0, 0, 0, 0);

    private final int top;
    private final int bottom;
    private final int left;
    private final int right;

    private SystemInsets(int top, int bottom, int left, int right) {
        this.top = top;
        this.bottom = bottom;
        this.left = left;
        this.right = right;
    }

    /** 直接给四个方向（负数按 0）。 */
    public static SystemInsets of(int top, int bottom, int left, int right) {
        return new SystemInsets(clamp(top), clamp(bottom), clamp(left), clamp(right));
    }

    /**
     * 手机：透传系统 inset；电视：恒 {@link #NONE}。
     *
     * <p>横屏手机（复用 TV 布局）同样要避让，所以这里不区分布局类型，只看设备。</p>
     */
    public static SystemInsets forWindow(boolean phone, int top, int bottom, int left, int right) {
        return phone ? of(top, bottom, left, right) : NONE;
    }

    /**
     * 合并手势导航区（API 29+ 的 {@code getSystemGestureInsets()}）：沉浸式隐藏导航栏后
     * {@code systemWindowInsetBottom} 常为 0，但底部手势条仍占一块系统手势区，
     * 底栏必须避开。逐边取大，负值仍按 0。
     */
    public static SystemInsets forWindow(boolean phone, int top, int bottom, int left, int right,
            int gestureTop, int gestureBottom, int gestureLeft, int gestureRight) {
        if (!phone) {
            return NONE;
        }
        return new SystemInsets(
                Math.max(clamp(top), clamp(gestureTop)),
                Math.max(clamp(bottom), clamp(gestureBottom)),
                Math.max(clamp(left), clamp(gestureLeft)),
                Math.max(clamp(right), clamp(gestureRight)));
    }

    private static int clamp(int value) {
        return value > 0 ? value : 0;
    }

    public int top() {
        return top;
    }

    public int bottom() {
        return bottom;
    }

    public int left() {
        return left;
    }

    public int right() {
        return right;
    }

    /**
     * 合并显示挖孔安全区（API 28+ 的 {@code DisplayCutout.getSafeInset*()}）：
     * 沉浸式让窗口铺到屏幕边缘时，刘海/打孔区仍要让开（视频不压状态栏区域）。
     * 逐边取大，负值按 0。
     */
    public SystemInsets merge(int top, int bottom, int left, int right) {
        return new SystemInsets(
                Math.max(this.top, clamp(top)),
                Math.max(this.bottom, clamp(bottom)),
                Math.max(this.left, clamp(left)),
                Math.max(this.right, clamp(right)));
    }

    /** 四边都为 0：不需要改 padding（避免每次 inset 回调都触发布局）。 */
    public boolean isNone() {
        return top == 0 && bottom == 0 && left == 0 && right == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SystemInsets)) {
            return false;
        }
        SystemInsets that = (SystemInsets) other;
        return top == that.top && bottom == that.bottom
                && left == that.left && right == that.right;
    }

    @Override
    public int hashCode() {
        int result = top;
        result = 31 * result + bottom;
        result = 31 * result + left;
        result = 31 * result + right;
        return result;
    }

    @Override
    public String toString() {
        return "SystemInsets{" + top + "," + bottom + "," + left + "," + right + "}";
    }
}
