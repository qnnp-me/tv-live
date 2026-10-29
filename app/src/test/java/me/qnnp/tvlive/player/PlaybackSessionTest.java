package me.qnnp.tvlive.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 引擎会话令牌：后台 prepare 与 stop/release 的竞态只靠这一处判定。
 *
 * <p>覆盖「迟到回调必须丢弃」：旧 prepare/旧 MediaPlayer 事件、stop 后与 release 后
 * 的所有回调都不再是当前会话。</p>
 */
public class PlaybackSessionTest {

    @Test
    public void currentSessionStaysValidUntilNextBegin() {
        PlaybackSession session = new PlaybackSession();
        int first = session.begin();
        assertTrue(session.isCurrent(first));
        assertFalse(session.isDead());

        int second = session.begin();
        assertFalse("上一次 prepare 的迟到回调必须丢弃", session.isCurrent(first));
        assertTrue(session.isCurrent(second));
    }

    @Test
    public void stopInvalidatesInflightWorkButAllowsNextBegin() {
        PlaybackSession session = new PlaybackSession();
        int active = session.begin();
        session.stop();

        assertTrue(session.isDead());
        assertFalse("后台 setDataSource/prepareAsync 之后不得再上屏",
                session.isCurrent(active));

        int next = session.begin();
        assertTrue("stop 后复用实例再次 prepare 必须生效", session.isCurrent(next));
        assertFalse(session.isCurrent(active));
        assertFalse(session.isDead());
    }

    @Test
    public void releaseInvalidatesEverythingAndRejectsBegin() {
        PlaybackSession session = new PlaybackSession();
        int active = session.begin();
        session.release();

        assertTrue(session.isReleased());
        assertTrue(session.isDead());
        assertFalse(session.isCurrent(active));

        try {
            session.begin();
            fail("release 后不得再 prepare");
        } catch (IllegalStateException expected) {
            // 引擎已释放。
        }
    }
}
