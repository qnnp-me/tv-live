package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import me.qnnp.tvlive.player.SwitchMode;
import me.qnnp.tvlive.prefs.RenderMode;

/** 批次 17：渲染方式 × 切台方式的九种组合（3 渲染 × 3 切台）。 */
public class RenderDecisionTest {

    @Test
    public void autoFollowsSwitchMode() {
        assertTrue("自动 + 保留 → 纹理层", RenderDecision.useTexture(RenderMode.AUTO, SwitchMode.KEEP));
        assertFalse("自动 + 极速直切 → SurfaceView",
                RenderDecision.useTexture(RenderMode.AUTO, SwitchMode.DIRECT));
        assertTrue("自动 + 双画面 → 纹理层", RenderDecision.useTexture(RenderMode.AUTO, SwitchMode.DUAL));
    }

    @Test
    public void surfaceAlwaysUsesSurfaceView() {
        assertFalse(RenderDecision.useTexture(RenderMode.SURFACE, SwitchMode.KEEP));
        assertFalse(RenderDecision.useTexture(RenderMode.SURFACE, SwitchMode.DIRECT));
        assertFalse(RenderDecision.useTexture(RenderMode.SURFACE, SwitchMode.DUAL));
    }

    @Test
    public void textureAlwaysUsesTextureView() {
        assertTrue(RenderDecision.useTexture(RenderMode.TEXTURE, SwitchMode.KEEP));
        assertTrue(RenderDecision.useTexture(RenderMode.TEXTURE, SwitchMode.DIRECT));
        assertTrue(RenderDecision.useTexture(RenderMode.TEXTURE, SwitchMode.DUAL));
    }

    @Test
    public void onlySurfaceDualDegradesToKeep() {
        RenderMode[] renders = RenderMode.values();
        SwitchMode[] modes = SwitchMode.values();
        for (RenderMode render : renders) {
            for (SwitchMode mode : modes) {
                boolean degrade = render == RenderMode.SURFACE && mode == SwitchMode.DUAL;
                assertEquals("degraded(" + render + ", " + mode + ")",
                        degrade, RenderDecision.degraded(render, mode));
                assertEquals("effectiveSwitch(" + render + ", " + mode + ")",
                        degrade ? SwitchMode.KEEP : mode,
                        RenderDecision.effectiveSwitch(render, mode));
            }
        }
    }

    @Test
    public void nullsFallBackToAutoAndKeep() {
        assertTrue("null 渲染按自动、null 切台按保留",
                RenderDecision.useTexture(null, null));
        assertEquals(SwitchMode.KEEP, RenderDecision.effectiveSwitch(null, null));
        assertFalse(RenderDecision.degraded(null, SwitchMode.DUAL));
        assertEquals("自动 + 直切仍走 SurfaceView",
                false, RenderDecision.useTexture(null, SwitchMode.DIRECT));
    }

    /** 降级后必须仍然使用 SurfaceView（否则会出现「提示降级却切了纹理层」）。 */
    @Test
    public void degradedCombinationStillUsesSurfaceView() {
        assertFalse(RenderDecision.useTexture(RenderMode.SURFACE,
                RenderDecision.effectiveSwitch(RenderMode.SURFACE, SwitchMode.DUAL)));
    }
}
