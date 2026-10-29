package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 两档缓冲参数映射：起播档 / 稳定档 / 关闭开关保持原参数。 */
public class AdaptiveLoadControlTest {

    @Test
    public void enabledStartsAtStartupProfile() {
        AdaptiveLoadControl control = new AdaptiveLoadControl(true);

        assertTrue(control.isAdaptive());
        assertFalse(control.isStable());
        assertSame(BufferProfile.STARTUP, control.currentProfile());
        assertEquals(1200, control.currentProfile().minBufferMs());
        assertEquals(8000, control.currentProfile().maxBufferMs());
        assertEquals(400, control.currentProfile().bufferForPlaybackMs());
        assertEquals(800, control.currentProfile().bufferForPlaybackAfterRebufferMs());
    }

    @Test
    public void stableSwitchUsesWiderProfile() {
        AdaptiveLoadControl control = new AdaptiveLoadControl(true);

        control.setStable(true);

        assertTrue(control.isStable());
        assertSame(BufferProfile.STABLE, control.currentProfile());
        assertEquals(4000, control.currentProfile().minBufferMs());
        assertEquals(15000, control.currentProfile().maxBufferMs());
        assertEquals(1200, control.currentProfile().bufferForPlaybackMs());
        assertEquals(1500, control.currentProfile().bufferForPlaybackAfterRebufferMs());
    }

    @Test
    public void resetReturnsToStartupForNextChannel() {
        AdaptiveLoadControl control = new AdaptiveLoadControl(true);
        control.setStable(true);

        control.resetToStartup();

        assertFalse(control.isStable());
        assertSame(BufferProfile.STARTUP, control.currentProfile());
    }

    @Test
    public void disabledKeepsOriginalProfileAndIgnoresStable() {
        AdaptiveLoadControl control = new AdaptiveLoadControl(false);

        assertFalse(control.isAdaptive());
        assertSame(BufferProfile.ORIGINAL, control.currentProfile());
        assertEquals(3000, control.currentProfile().minBufferMs());
        assertEquals(12000, control.currentProfile().maxBufferMs());
        assertEquals(1000, control.currentProfile().bufferForPlaybackMs());
        assertEquals(2000, control.currentProfile().bufferForPlaybackAfterRebufferMs());

        control.setStable(true);

        assertFalse("关闭首帧加速时不得切换档位", control.isStable());
        assertSame(BufferProfile.ORIGINAL, control.currentProfile());
    }

    @Test
    public void longContinueLoadingStillAccessibleAfterProfileSwitch() {
        AdaptiveLoadControl control = new AdaptiveLoadControl(true);

        // 两种档位下都能正常回答加载策略（使用共享 allocator，不因切换失效）。
        assertTrue(control.shouldContinueLoading(0L, 0L, 1f));
        control.setStable(true);
        assertFalse(control.shouldContinueLoading(0L, 50_000_000L, 1f));
        assertSame(BufferProfile.STABLE, control.currentProfile());
    }
}
