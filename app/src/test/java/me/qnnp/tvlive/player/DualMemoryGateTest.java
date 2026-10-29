package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 14：DUAL 内存门槛阈值（纯 JVM，不碰 ActivityManager）。
 *
 * <p>口径：堆上限 ≥96MB 且非低内存设备才允许；堆上限未知时退回 memoryClass；
 * 预算完全未知按不允许处理。</p>
 */
public class DualMemoryGateTest {

    private static final long MB = 1024L * 1024L;

    @Test
    public void rejectsHeapBelowMinimum() {
        assertFalse(DualMemoryGate.allows(95L * MB, 0, false));
        assertFalse(DualMemoryGate.allows(48L * MB, 48, false));
    }

    @Test
    public void allowsHeapAtOrAboveMinimum() {
        assertTrue(DualMemoryGate.allows(96L * MB, 0, false));
        assertTrue(DualMemoryGate.allows(192L * MB, 192, false));
    }

    @Test
    public void rejectsLowRamDeviceEvenWithLargeHeap() {
        assertFalse(DualMemoryGate.allows(512L * MB, 256, true));
    }

    @Test
    public void fallsBackToMemoryClassWhenHeapUnknown() {
        assertFalse("64MB class 不足 96MB", DualMemoryGate.allows(0L, 64, false));
        assertTrue("96MB class 达标", DualMemoryGate.allows(0L, 96, false));
        assertFalse("class 也未知时保守拒绝", DualMemoryGate.allows(0L, 0, false));
    }

    @Test
    public void budgetOverloadsAgreeWithPrimitiveCheck() {
        assertTrue(DualMemoryGate.allows(DualMemoryGate.Budget.of(128L * MB, 128, false)));
        assertFalse(DualMemoryGate.allows(DualMemoryGate.Budget.of(64L * MB, 64, false)));
        assertFalse("null 预算按不允许", DualMemoryGate.allows((DualMemoryGate.Budget) null));
    }

    @Test
    public void minimumConstantIs96Mb() {
        assertEquals(96L * MB, DualMemoryGate.MIN_MAX_MEMORY_BYTES);
    }

    @Test
    public void systemBudgetUsesRuntimeHeap() {
        DualMemoryGate.Budget budget = DualMemoryGate.systemBudget();
        assertEquals(Runtime.getRuntime().maxMemory(), budget.maxMemoryBytes);
        assertFalse(budget.lowRamDevice);
    }
}
