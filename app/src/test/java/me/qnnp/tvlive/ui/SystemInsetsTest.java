package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5b：系统栏/手势条 inset 避让规则。
 *
 * <p>手机竖屏：状态栏/刘海在 top、手势条在三键栏 bottom；横屏手机手势条可能在侧边，
 * 系统已经按当前方向给值，这里只负责夹负数与「电视恒 0」。</p>
 */
public class SystemInsetsTest {

    @Test
    public void portraitStatusBarAndGestureBarAreApplied() {
        SystemInsets insets = SystemInsets.forWindow(true, 96, 132, 0, 0);
        assertEquals(96, insets.top());
        assertEquals(132, insets.bottom());
        assertEquals(0, insets.left());
        assertEquals(0, insets.right());
        assertFalse(insets.isNone());
    }

    @Test
    public void landscapeSideInsetIsApplied() {
        // 横屏三键栏在右侧：视频/底栏都不压到导航栏。
        SystemInsets insets = SystemInsets.forWindow(true, 0, 0, 0, 126);
        assertEquals(126, insets.right());
        assertEquals(0, insets.top());
        assertEquals(0, insets.bottom());
    }

    @Test
    public void negativeOrMissingInsetsAreTreatedAsZero() {
        SystemInsets insets = SystemInsets.forWindow(true, -1, -1080, -1, 0);
        assertEquals(0, insets.top());
        assertEquals(0, insets.bottom());
        assertEquals(0, insets.left());
        assertEquals(0, insets.right());
        assertTrue(insets.isNone());
    }

    @Test
    public void tvAlwaysKeepsZeroInsets() {
        // 电视（sw≥600dp）不装 inset 逻辑：布局/遥控与 4b 完全一致。
        assertSame(SystemInsets.NONE, SystemInsets.forWindow(false, 96, 132, 24, 24));
    }

    @Test
    public void equalityIsByEdges() {
        assertEquals(SystemInsets.of(0, 48, 0, 0), SystemInsets.of(0, 48, 0, 0));
        assertEquals(SystemInsets.of(0, 48, 0, 0).hashCode(),
                SystemInsets.of(0, 48, 0, 0).hashCode());
    }

    @Test
    public void gestureInsetIsMergedWhenSystemInsetIsZero() {
        // API 29+ 手势导航：沉浸式下 systemWindowInsetBottom=0，手势区仍有 60px。
        SystemInsets insets = SystemInsets.forWindow(true, 0, 0, 0, 0, 0, 60, 0, 0);
        assertEquals(60, insets.bottom());
        assertEquals(0, insets.top());
    }

    @Test
    public void largerEdgeWinsWhenBothReported() {
        SystemInsets insets = SystemInsets.forWindow(true, 40, 90, 0, 0, 100, 60, 0, 30);
        assertEquals(100, insets.top());
        assertEquals(90, insets.bottom());
        assertEquals(30, insets.right());
    }

    @Test
    public void gestureInsetsAlsoIgnoredOnTv() {
        assertSame(SystemInsets.NONE, SystemInsets.forWindow(false, 0, 0, 0, 0, 0, 60, 0, 0));
    }

    @Test
    public void cutoutSafeAreaIsMergedPerEdge() {
        // 刘海在顶部：视频从刘海下缘开始，其余方向不被缩小。
        SystemInsets base = SystemInsets.forWindow(true, 0, 60, 0, 0);
        SystemInsets merged = base.merge(96, 0, 0, 0);
        assertEquals(96, merged.top());
        assertEquals(60, merged.bottom());
        assertEquals(0, merged.left());
        assertEquals(0, merged.right());
    }

    @Test
    public void mergeKeepsLargerSideAndIgnoresNegative() {
        SystemInsets merged = SystemInsets.of(0, 0, 0, 30).merge(-8, -8, -8, 12);
        assertEquals(0, merged.top());
        assertEquals(30, merged.right());
    }
}
