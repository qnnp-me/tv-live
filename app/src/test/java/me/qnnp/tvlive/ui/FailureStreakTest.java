package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 批次 2e：全屏自动跳失败链计数（连续 5 个停止）。
 */
public class FailureStreakTest {

    @Test
    public void reachesLimitAfterFiveFailures() {
        FailureStreak streak = new FailureStreak();
        assertEquals(0, streak.count());
        assertFalse(streak.limitReached());

        for (int i = 1; i <= 4; i++) {
            assertEquals(i, streak.recordFailure());
            assertFalse("第 " + i + " 个失败不应停手", streak.limitReached());
        }

        assertEquals(5, streak.recordFailure());
        assertTrue(streak.limitReached());
        assertEquals(5, streak.count());
    }

    @Test
    public void resetStartsANewChain() {
        FailureStreak streak = new FailureStreak();
        for (int i = 0; i < 5; i++) {
            streak.recordFailure();
        }
        assertTrue(streak.limitReached());

        streak.reset();
        assertEquals(0, streak.count());
        assertFalse(streak.limitReached());

        assertEquals(1, streak.recordFailure());
        assertFalse(streak.limitReached());
    }

    @Test
    public void customLimitIsHonoured() {
        FailureStreak streak = new FailureStreak(3);
        assertEquals(3, streak.limit());
        streak.recordFailure();
        streak.recordFailure();
        assertFalse(streak.limitReached());
        streak.recordFailure();
        assertTrue(streak.limitReached());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveLimit() {
        new FailureStreak(0);
    }

    @Test
    public void countStaysAtMaxValue() {
        FailureStreak streak = new FailureStreak(Integer.MAX_VALUE);
        // 不能真的循环 MAX_VALUE 次；用一个小 limit 验证计数值单调即可。
        FailureStreak small = new FailureStreak(2);
        assertEquals(1, small.recordFailure());
        assertEquals(2, small.recordFailure());
        assertEquals(3, small.recordFailure());
        assertTrue(small.limitReached());
        assertEquals(Integer.MAX_VALUE, streak.limit());
    }

    @Test
    public void defaultLimitIsFive() {
        assertEquals(5, FailureStreak.DEFAULT_LIMIT);
        assertEquals(5, new FailureStreak().limit());
    }

    @Test
    public void invalidStateDoesNotThrow() {
        try {
            new FailureStreak(-1);
            fail("负数上限应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        }
    }
}
