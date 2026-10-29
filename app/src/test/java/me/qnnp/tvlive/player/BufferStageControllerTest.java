package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/** 缓冲档位计时：READY 后 3 秒切稳定档、再次切台重置、关闭开关不切换。 */
public class BufferStageControllerTest {

    private FakeScheduler scheduler;

    @Before
    public void setUp() {
        scheduler = new FakeScheduler();
    }

    @Test
    public void readySchedulesStableAfterThreeSeconds() {
        AdaptiveLoadControl loadControl = new AdaptiveLoadControl(true);
        BufferStageController stage = new BufferStageController(loadControl, scheduler);

        stage.onPrepare();
        stage.onReady();

        assertEquals(1, scheduler.delays.size());
        assertEquals(3000L, scheduler.delays.get(0).longValue());
        assertSameStartup(loadControl);

        scheduler.runLast();

        assertTrue(stage.isStable());
        assertSame(BufferProfile.STABLE, loadControl.currentProfile());
    }

    @Test
    public void repeatedReadyDoesNotRestartTimerOrDropBack() {
        AdaptiveLoadControl loadControl = new AdaptiveLoadControl(true);
        BufferStageController stage = new BufferStageController(loadControl, scheduler);

        stage.onPrepare();
        stage.onReady();
        scheduler.runLast();
        stage.onReady(); // 重缓冲又回到 READY：保持稳定档，不再计时

        assertEquals("只应排一次 3 秒计时", 1, scheduler.delays.size());
        assertTrue(stage.isStable());
        assertSame(BufferProfile.STABLE, loadControl.currentProfile());
    }

    @Test
    public void nextPrepareResetsToStartupAndRestartsTimer() {
        AdaptiveLoadControl loadControl = new AdaptiveLoadControl(true);
        BufferStageController stage = new BufferStageController(loadControl, scheduler);

        stage.onPrepare();
        stage.onReady();
        scheduler.runLast();
        assertTrue(stage.isStable());

        // 再次切台：prepare 回到起播档，并取消/重置旧状态。
        stage.onPrepare();
        assertFalse(stage.isStable());
        assertSameStartup(loadControl);

        stage.onReady();
        assertEquals(2, scheduler.delays.size());
        assertEquals(3000L, scheduler.delays.get(1).longValue());
    }

    @Test
    public void stopCancelsPendingTimerAndResets() {
        AdaptiveLoadControl loadControl = new AdaptiveLoadControl(true);
        BufferStageController stage = new BufferStageController(loadControl, scheduler);

        stage.onPrepare();
        stage.onReady();
        FakeScheduler.Task pending = scheduler.tasks.get(0);

        stage.onStop();

        assertTrue(pending.cancelled);
        assertEquals(0, scheduler.activeCount());
        assertFalse(stage.isStable());
        assertSameStartup(loadControl);
    }

    @Test
    public void disabledNeverSchedulesOrSwitches() {
        AdaptiveLoadControl loadControl = new AdaptiveLoadControl(false);
        BufferStageController stage = new BufferStageController(loadControl, scheduler);

        stage.onPrepare();
        stage.onReady();

        assertEquals(0, scheduler.delays.size());
        assertFalse(stage.isStable());
        assertSame(BufferProfile.ORIGINAL, loadControl.currentProfile());
    }

    private static void assertSameStartup(AdaptiveLoadControl loadControl) {
        assertEquals(BufferProfile.STARTUP, loadControl.currentProfile());
    }

    /** 假调度器：记录延迟，由测试手动执行；cancel 可断言。 */
    static final class FakeScheduler implements Scheduler {

        final class Task implements Cancelable {
            final Runnable action;
            final long delayMs;
            boolean cancelled;

            Task(Runnable action, long delayMs) {
                this.action = action;
                this.delayMs = delayMs;
            }

            @Override
            public void cancel() {
                cancelled = true;
                tasks.remove(this);
            }
        }

        final List<Task> tasks = new ArrayList<Task>();
        final List<Long> delays = new ArrayList<Long>();

        @Override
        public Cancelable schedule(Runnable action, long delayMs) {
            Task task = new Task(action, delayMs);
            tasks.add(task);
            delays.add(delayMs);
            return task;
        }

        void runLast() {
            Task task = tasks.remove(tasks.size() - 1);
            assertFalse("已取消的任务不应被执行", task.cancelled);
            task.action.run();
        }

        int activeCount() {
            return tasks.size();
        }
    }
}
