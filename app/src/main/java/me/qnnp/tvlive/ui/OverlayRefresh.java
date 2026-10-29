package me.qnnp.tvlive.ui;

/**
 * 浮层刷新策略（批次 12，纯 Java，可在 JVM 单测）。
 *
 * <p>{@code MainActivity} 构造 uiLayer 时按 {@link #SETUP} → {@link #SETTINGS} →
 * {@link #OVERLAYS} 的顺序 add，浮层恒为最后一个子视图，父容器顺序已经保证它压在设置/
 * 首启页之上。因此显示浮层只需要重绘，不能再 {@code bringChildToFront}（那会让整个
 * FrameLayout {@code requestLayout}，长按音量时每秒 20–30 次全树布局）。</p>
 *
 * <p>OSD 倒计时只换文字：长度不变时 {@code setText} 自带重绘，长度变化才需要重新测量。</p>
 */
public final class OverlayRefresh {

    /** uiLayer 子视图顺序：首启页。 */
    public static final int SETUP = 0;
    /** uiLayer 子视图顺序：设置页。 */
    public static final int SETTINGS = 1;
    /** uiLayer 子视图顺序：OSD/菜单/角标/音量/返回 chrome；必须最后 add。 */
    public static final int OVERLAYS = 2;

    private OverlayRefresh() {
    }

    /** 浮层是 uiLayer 的最后一个子视图（顺序已由构造保证，显示时无需重排父层）。 */
    public static boolean overlaysLast() {
        return OVERLAYS > SETTINGS && SETTINGS > SETUP;
    }

    /** OSD 只换文字时是否需要重新测量/布局：只有文本长度变化才需要。 */
    public static boolean needsLayout(CharSequence oldText, CharSequence newText) {
        return lengthOf(oldText) != lengthOf(newText);
    }

    private static int lengthOf(CharSequence text) {
        return text == null ? 0 : text.length();
    }
}
