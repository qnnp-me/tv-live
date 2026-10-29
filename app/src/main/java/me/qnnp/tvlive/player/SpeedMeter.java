package me.qnnp.tvlive.player;

/**
 * 实时网速采样（批次 15，纯 Java，可在 JVM 单测）。
 *
 * <p>网络线程只调用 {@link #addBytes(long, long)} 累加计数（方法同步，内部无锁竞争，
 * 一次只加一个 long）；采样线程用 {@link #sample(long)} 取平滑后的字节/秒。</p>
 *
 * <p>窗口：4 个 250ms 桶组成 1 秒滑动窗口，桶随采样时间滚动，因此每次取到的都是
 * 「最近 1 秒」的平均值；再按 {@link #SMOOTHING} 做指数平滑，避免 HLS 分片下载的
 * 锯齿（一段时间只下分片、其余时间空闲）直接跳到界面上。</p>
 *
 * <p>最近 1 秒一个字节都没收到（起播前 / 断流 / 重新缓冲）直接返回 0，不再让平滑值
 * 慢慢衰减：那会在卡住时一直显示一个并不存在的慢速数字。</p>
 */
public final class SpeedMeter {

    /** 指数平滑系数：新样本占 35%，旧值 65%。 */
    public static final double SMOOTHING = 0.35;

    /** 滑动窗口长度。 */
    public static final long WINDOW_MS = 1000L;

    static final long DEFAULT_BUCKET_MS = 250L;
    static final int DEFAULT_BUCKET_COUNT = 4;

    private final long bucketMs;
    private final long[] buckets;
    private int cursor;
    private boolean started;
    private long bucketStartMs;
    private long trackStartMs;
    private double smoothed;
    private boolean hasSample;

    public SpeedMeter() {
        this(DEFAULT_BUCKET_MS, DEFAULT_BUCKET_COUNT);
    }

    /** 测试用：自定义桶粒度与桶数量（窗口 = bucketMs × bucketCount）。 */
    SpeedMeter(long bucketMs, int bucketCount) {
        if (bucketMs <= 0L || bucketCount <= 0) {
            throw new IllegalArgumentException("bucketMs/bucketCount 必须为正");
        }
        this.bucketMs = bucketMs;
        this.buckets = new long[bucketCount];
    }

    /**
     * 累加一次网络读取的字节数。
     *
     * @param bytes 本次读到的字节；<=0 忽略
     * @param nowMs 单调时钟（毫秒），与 {@link #sample(long)} 同一时间基准
     */
    public synchronized void addBytes(long bytes, long nowMs) {
        if (bytes <= 0L) {
            return;
        }
        advance(nowMs);
        buckets[cursor] += bytes;
    }

    /**
     * 采样并返回平滑后的字节/秒；调用方负责节流（本类不做通知）。
     *
     * @param nowMs 单调时钟（毫秒）
     */
    public synchronized long sample(long nowMs) {
        advance(nowMs);
        long total = 0L;
        for (int i = 0; i < buckets.length; i++) {
            total += buckets[i];
        }
        if (total <= 0L) {
            smoothed = 0d;
            // 不标记 hasSample：起播后第一个真正有数据的采样直接落在真实速率上，
            // 不用从 0 慢慢爬（台名卡上的第一眼数字就是当前网速）。
            return 0L;
        }
        // 起播后不足一个窗口时按已覆盖的时长折算，否则前 1 秒会显示成一半速度。
        long coveredMs = nowMs - trackStartMs;
        long windowMs = windowMs();
        double denominator = coveredMs > 0L
                ? Math.min(windowMs, Math.max(bucketMs, coveredMs))
                : bucketMs;
        double instant = total * 1000d / denominator;
        if (!hasSample) {
            smoothed = instant;
            hasSample = true;
        } else {
            smoothed += SMOOTHING * (instant - smoothed);
        }
        return Math.max(0L, Math.round(smoothed));
    }

    /** 换台/重新起播：丢弃旧台的数据，下一次采样重新起步。 */
    public synchronized void reset() {
        java.util.Arrays.fill(buckets, 0L);
        cursor = 0;
        started = false;
        bucketStartMs = 0L;
        trackStartMs = 0L;
        smoothed = 0d;
        hasSample = false;
    }

    private long windowMs() {
        return bucketMs * buckets.length;
    }

    /** 把窗口滚到 {@code nowMs}，清掉过期桶。 */
    private void advance(long nowMs) {
        if (!started) {
            started = true;
            bucketStartMs = nowMs;
            trackStartMs = nowMs;
            java.util.Arrays.fill(buckets, 0L);
            cursor = 0;
            return;
        }
        if (nowMs <= bucketStartMs) {
            return;
        }
        long steps = (nowMs - bucketStartMs) / bucketMs;
        if (steps <= 0L) {
            return;
        }
        if (steps >= buckets.length) {
            // 超过整个窗口没动过：全部过期，从当前时刻重新分段。
            java.util.Arrays.fill(buckets, 0L);
            cursor = 0;
            bucketStartMs = nowMs;
            return;
        }
        for (long i = 0L; i < steps; i++) {
            cursor = (cursor + 1) % buckets.length;
            buckets[cursor] = 0L;
        }
        bucketStartMs += steps * bucketMs;
    }
}
