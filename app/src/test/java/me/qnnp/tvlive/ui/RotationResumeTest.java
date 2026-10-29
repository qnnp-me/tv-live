package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 批次 5b：竖屏↔横屏只切布局。
 *
 * <p>播放器在线时绝不重新起播（否则重新握手、进度归零、黑一下），只把 Surface
 * 从旧布局解绑、在新布局 surfaceCreated 时重绑；只有播放器已被释放才回退到重起播。</p>
 */
public class RotationResumeTest {

    @Test
    public void playerAliveOnlyRebindsSurface() {
        assertEquals(RotationResume.Action.REBIND_SURFACE, RotationResume.plan(true));
    }

    @Test
    public void releasedPlayerFallsBackToRestart() {
        assertEquals(RotationResume.Action.RESTART, RotationResume.plan(false));
    }
}
