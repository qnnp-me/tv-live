package me.qnnp.tvlive.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StartupResumeTest {

    @Test
    public void onStartRemembersChannelUntilReloadSettles() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");

        assertTrue("重载完成前保持待恢复台", resume.hasPending());
        assertEquals("c1", resume.pendingId());
    }

    @Test
    public void sameChannelAlreadyPlayingIsSkipped() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");

        // 重载已经按 reloadTargetId 起了同一台：不能再 force 一次。
        assertEquals(StartupResume.Action.SKIP_SAME,
                resume.settle(false, true, "c1"));
        assertFalse("消费后清空，避免下次误播", resume.hasPending());
    }

    @Test
    public void playsWhenNothingIsActive() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");

        // release/restore 之后编排层没有当前台：由这里恢复一次（force=false）。
        assertEquals(StartupResume.Action.PLAY, resume.settle(false, true, null));
    }

    @Test
    public void playsADifferentActiveChannel() {
        StartupResume resume = new StartupResume();
        resume.defer("c2");
        assertEquals(StartupResume.Action.PLAY, resume.settle(false, true, "c1"));
    }

    @Test
    public void dropsChannelGoneFromPlaylist() {
        StartupResume resume = new StartupResume();
        resume.defer("c9");
        assertEquals(StartupResume.Action.DROP, resume.settle(false, false, null));
        assertNull(resume.pendingId());
    }

    @Test
    public void dropsWhenBackInBackground() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");
        assertEquals(StartupResume.Action.DROP, resume.settle(true, true, null));
    }

    @Test
    public void settleWithoutPendingDoesNothing() {
        StartupResume resume = new StartupResume();
        assertEquals(StartupResume.Action.NONE, resume.settle(false, true, "c1"));
        assertEquals(StartupResume.Action.NONE, resume.settle(false, false, null));
    }

    @Test
    public void discardCancelsPendingResume() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");
        resume.discard();
        assertFalse(resume.hasPending());
        assertEquals(StartupResume.Action.NONE, resume.settle(false, true, null));
    }

    @Test
    public void reloadThatKeepsPlayingConsumesPendingExactlyOnce() {
        StartupResume resume = new StartupResume();
        resume.defer("c1");

        assertEquals(StartupResume.Action.SKIP_SAME, resume.settle(false, true, "c1"));
        // 同一次恢复不能在下一次加载时再起一次解码器。
        assertEquals(StartupResume.Action.NONE, resume.settle(false, true, "c1"));
    }
}
