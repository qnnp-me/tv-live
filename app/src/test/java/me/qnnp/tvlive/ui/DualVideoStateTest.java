package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 10：两个 TextureView 的 alpha 状态机（纯 Java）。
 *
 * <p>KEEP 恒为 A；DUAL 首帧后交换；旋转/重建布局时用 setActiveA 把 Activity 记住的
 * 槽位套回新 View，不能因为新建 View 就默认回 A（否则会显示隐藏那一路）。</p>
 */
public class DualVideoStateTest {

    @Test
    public void startsOnAWithSingleVisibleLayer() {
        DualVideoState state = new DualVideoState();
        assertTrue(state.isActiveA());
        assertEquals(1f, state.alphaA(), 0f);
        assertEquals(0f, state.alphaB(), 0f);
    }

    @Test
    public void swapMovesFrontAndAlphaToBThenBack() {
        DualVideoState state = new DualVideoState();
        state.setDual(true);

        assertFalse(state.swap());
        assertFalse(state.isActiveA());
        assertEquals(0f, state.alphaA(), 0f);
        assertEquals(1f, state.alphaB(), 0f);

        assertTrue(state.swap());
        assertTrue(state.isActiveA());
        assertEquals(1f, state.alphaA(), 0f);
        assertEquals(0f, state.alphaB(), 0f);
    }

    @Test
    public void resetAlwaysReturnsToA() {
        DualVideoState state = new DualVideoState();
        state.setDual(true);
        state.swap();
        state.reset();
        assertTrue(state.isActiveA());
        assertEquals(1f, state.alphaA(), 0f);
        assertEquals(0f, state.alphaB(), 0f);
    }

    @Test
    public void singleModeNeverActivatesB() {
        DualVideoState state = new DualVideoState();
        assertFalse("默认 KEEP：B 不 attach", state.isDual());

        state.setActiveA(false);
        assertTrue("单画面没有 B 的 Surface，不能把前台切到 B", state.isActiveA());
        assertTrue(state.swap());
        assertTrue(state.isActiveA());
    }

    @Test
    public void disablingDualReturnsFrontToA() {
        DualVideoState state = new DualVideoState();
        state.setDual(true);
        state.swap();
        assertFalse(state.isActiveA());

        state.setDual(false);
        assertFalse(state.isDual());
        assertTrue("关闭双画面前必须归位 A，否则 detach B 后是黑屏", state.isActiveA());
        assertEquals(1f, state.alphaA(), 0f);
        assertEquals(0f, state.alphaB(), 0f);
    }

    @Test
    public void setActiveAIsIdempotentAndRestoresSlotOnNewView() {
        DualVideoState state = new DualVideoState();
        state.setDual(true);
        state.swap();
        // 旋转重建了 TextureVideoLayer：Activity 把「前台在 B」重新套回来。
        DualVideoState rebuilt = new DualVideoState();
        rebuilt.setDual(true);
        rebuilt.setActiveA(state.isActiveA());
        assertFalse(rebuilt.isActiveA());
        rebuilt.setActiveA(false);
        assertFalse(rebuilt.isActiveA());
        assertEquals(1f, rebuilt.alphaB(), 0f);
    }
}
