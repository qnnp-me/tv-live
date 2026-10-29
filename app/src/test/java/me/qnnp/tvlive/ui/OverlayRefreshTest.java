package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 12：浮层刷新不再重排父层、OSD 倒计时只换文字。
 *
 * <p>{@code ensureOnTop()} 只 {@code invalidate()}：这是安全的，因为 uiLayer 构造顺序
 * 固定为 setup → settings → overlays，浮层恒为最后一个子视图（本测试锁定这个前提）。
 * 文本更新只有长度变化才需要 requestLayout，倒计时 9→8 这类等长替换只需重绘。</p>
 */
public class OverlayRefreshTest {

    @Test
    public void overlaysAreLastChildSoShowNeedsNoParentRestack() {
        assertTrue("浮层必须最后 add，父层顺序已保证压在最上层",
                OverlayRefresh.overlaysLast());
    }

    @Test
    public void osdTextUpdateSkipsLayoutWhenLengthUnchanged() {
        assertFalse("倒计时 3→2 秒等长替换只需重绘",
                OverlayRefresh.needsLayout("3 秒后自动跳台", "2 秒后自动跳台"));
        assertTrue("9→10 秒位数变化需要重新测量",
                OverlayRefresh.needsLayout("9 秒后自动跳台", "10 秒后自动跳台"));
    }

    @Test
    public void osdTextUpdateHandlesNullAsEmpty() {
        assertFalse(OverlayRefresh.needsLayout(null, null));
        assertFalse(OverlayRefresh.needsLayout(null, ""));
        assertTrue(OverlayRefresh.needsLayout(null, "正在连接"));
        assertTrue(OverlayRefresh.needsLayout("正在连接", null));
    }
}
