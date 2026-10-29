package me.qnnp.tvlive.player;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * DUAL（双画面）内存门槛（批次 14）。
 *
 * <p>两路 1080p 同时解码在 512MB 机器上极易 OOM，进 DUAL 前先判断堆上限。判定本身是
 * 纯逻辑（{@link #allows(Budget)}），JVM 单测直接构造 {@link Budget} 断言阈值；
 * Android 侧只由 {@link #probe(Context)} 采集 {@code ActivityManager} 与
 * {@code Runtime.maxMemory()}。</p>
 *
 * <p>阈值口径：优先用 {@code Runtime.getRuntime().maxMemory()}（真正的堆天花板，
 * largeHeap 已包含在内）；取不到（≤0）时才退回 {@code ActivityManager.getMemoryClass()} /
 * {@code getLargeMemoryClass()}。低内存设备（{@code isLowRamDevice()}，通常就是 512MB 级
 * 电视盒子）一律拒绝，即使堆上限看起来够。</p>
 */
public final class DualMemoryGate {

    /** DUAL 需要的最小堆上限：96MB。低于此值不开第二路解码。 */
    public static final long MIN_MAX_MEMORY_BYTES = 96L * 1024L * 1024L;

    private DualMemoryGate() {
    }

    /** 一次采样得到的内存信息；单测直接构造，生产由 {@link #probe(Context)} 填。 */
    public static final class Budget {

        /** {@code Runtime.getRuntime().maxMemory()}；≤0 表示未知。 */
        public final long maxMemoryBytes;
        /** {@code ActivityManager.getMemoryClass()/getLargeMemoryClass()}；≤0 表示未知。 */
        public final int memoryClassMb;
        /** {@code ActivityManager.isLowRamDevice()}。 */
        public final boolean lowRamDevice;

        public Budget(long maxMemoryBytes, int memoryClassMb, boolean lowRamDevice) {
            this.maxMemoryBytes = maxMemoryBytes;
            this.memoryClassMb = memoryClassMb;
            this.lowRamDevice = lowRamDevice;
        }

        public static Budget of(long maxMemoryBytes, int memoryClassMb, boolean lowRamDevice) {
            return new Budget(maxMemoryBytes, memoryClassMb, lowRamDevice);
        }
    }

    /** 是否允许进入 DUAL；{@code null} 预算（未知）按不允许处理。 */
    public static boolean allows(Budget budget) {
        return budget != null
                && allows(budget.maxMemoryBytes, budget.memoryClassMb, budget.lowRamDevice);
    }

    /** 阈值判定：低内存设备一律拒绝；否则按有效堆上限与 96MB 比较。 */
    public static boolean allows(long maxMemoryBytes, int memoryClassMb, boolean lowRamDevice) {
        if (lowRamDevice) {
            return false;
        }
        long limit = maxMemoryBytes > 0L
                ? maxMemoryBytes
                : (long) memoryClassMb * 1024L * 1024L;
        return limit >= MIN_MAX_MEMORY_BYTES;
    }

    /** 不依赖 Android 的默认预算：只按当前 JVM/进程堆上限判断（单测与无 Context 场景）。 */
    public static Budget systemBudget() {
        return new Budget(Runtime.getRuntime().maxMemory(), 0, false);
    }

    /** 生产采样：ActivityManager 的 memoryClass/largeMemoryClass + isLowRamDevice + 堆上限。 */
    public static Budget probe(Context context) {
        long maxMemoryBytes = Runtime.getRuntime().maxMemory();
        int memoryClassMb = 0;
        boolean lowRamDevice = false;
        if (context != null) {
            try {
                ActivityManager manager =
                        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager != null) {
                    boolean largeHeap = context.getApplicationInfo() != null
                            && (context.getApplicationInfo().flags
                            & ApplicationInfo.FLAG_LARGE_HEAP) != 0;
                    memoryClassMb = largeHeap
                            ? manager.getLargeMemoryClass()
                            : manager.getMemoryClass();
                    lowRamDevice = manager.isLowRamDevice();
                }
            } catch (RuntimeException e) {
                // 取不到系统服务时退回只用堆上限判断，不影响播放。
            }
        }
        return new Budget(maxMemoryBytes, memoryClassMb, lowRamDevice);
    }
}
