package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 网速采样：1 秒滑动窗口 + 指数平滑（批次 15）。
 *
 * <p>用 100ms × 10 桶构造 1 秒窗口，按固定节奏喂字节，断言输出落在合理区间、
 * 升速时不会一步跳到新值、断流后归零。</p>
 */
public class SpeedMeterTest {

    private static final long BUCKET_MS = 100L;
    private static final int BUCKETS = 10;
    private static final long WINDOW_MS = BUCKET_MS * BUCKETS;

    private static final long SAMPLE_MS = 500L;

    @Test
    public void noBytesReturnsZero() {
        SpeedMeter meter = new SpeedMeter();
        assertEquals(0L, meter.sample(0L));
        assertEquals(0L, meter.sample(1500L));
    }

    @Test
    public void constantRateIsCloseToTrueValue() {
        SpeedMeter meter = new SpeedMeter(BUCKET_MS, BUCKETS);
        // 2560 B / 100ms = 25600 B/s
        long perTick = 2560L;
        long trueRate = perTick * 1000L / BUCKET_MS;

        long now = 0L;
        // 喂 3 秒，稳定后取样
        long lastSample = 0L;
        for (long tick = 1L; tick <= 30L; tick++) {
            now = tick * BUCKET_MS;
            meter.addBytes(perTick, now);
            if (now % SAMPLE_MS == 0L) {
                lastSample = meter.sample(now);
                if (now >= 2000L) {
                    assertTrue("稳定后应接近真实速率: " + lastSample,
                            Math.abs(lastSample - trueRate) < trueRate / 10);
                }
            }
        }
        assertTrue(lastSample > 0L);
    }

    @Test
    public void smoothingDoesNotJumpToNewRate() {
        SpeedMeter meter = new SpeedMeter(BUCKET_MS, BUCKETS);
        long now = 0L;
        // 先跑 3 秒 10000 B/s
        for (long tick = 1L; tick <= 30L; tick++) {
            now = tick * BUCKET_MS;
            meter.addBytes(1000L, now);
            if (now % SAMPLE_MS == 0L) {
                meter.sample(now);
            }
        }
        long before = meter.sample(now);

        // 突然翻倍到 20000 B/s：下一个采样必须仍在两者之间（平滑 0.35），不能直接跳到新值。
        now += BUCKET_MS;
        meter.addBytes(4000L, now); // 20000 B/s 的 100ms
        long after = meter.sample(now);

        assertTrue("输出应升高: before=" + before + " after=" + after, after >= before);
        assertTrue("不能一步跳到新速率: after=" + after,
                after < 20000L);
    }

    @Test
    public void stalledStreamDropsToZeroWithinOneWindow() {
        SpeedMeter meter = new SpeedMeter(BUCKET_MS, BUCKETS);
        long now = 0L;
        for (long tick = 1L; tick <= 20L; tick++) {
            now = tick * BUCKET_MS;
            meter.addBytes(2048L, now);
        }
        assertTrue(meter.sample(now) > 0L);

        // 一个完整窗口没有新字节：视为断流，回到 0（UI 显示「—」）。
        assertEquals(0L, meter.sample(now + WINDOW_MS + BUCKET_MS));
    }

    @Test
    public void resetDropsOldChannelData() {
        SpeedMeter meter = new SpeedMeter(BUCKET_MS, BUCKETS);
        long now = 0L;
        for (long tick = 1L; tick <= 20L; tick++) {
            now = tick * BUCKET_MS;
            meter.addBytes(4096L, now);
        }
        assertTrue(meter.sample(now) > 0L);

        meter.reset();
        assertEquals(0L, meter.sample(now));

        // 换台后重新统计：先按低速喂一小段，输出不能还带着旧台的高速。
        long restart = now + BUCKET_MS;
        for (int i = 0; i < 5; i++) {
            restart += BUCKET_MS;
            meter.addBytes(256L, restart);
            meter.sample(restart);
        }
        long afterReset = meter.sample(restart + BUCKET_MS);
        assertTrue("换台后应明显低于旧台速率: " + afterReset, afterReset < 10000L);
    }

    @Test
    public void samplesStayWithinWindowAverage() {
        SpeedMeter meter = new SpeedMeter(BUCKET_MS, BUCKETS);
        // 每个 100ms 只在最后 20ms 下载一个大分片：窗口平均约 1/5 的瞬时值。
        long now = 0L;
        long total = 0L;
        for (long tick = 1L; tick <= 40L; tick++) {
            now = tick * BUCKET_MS;
            if (tick % 5L == 0L) {
                meter.addBytes(5000L, now);
                total += 5000L;
            }
        }
        long bps = meter.sample(now);
        long windowBytes = 5000L * 2L; // 最近 1 秒里最多包含 2 个分片
        long upper = windowBytes * 1000L / WINDOW_MS * 2L;
        assertTrue("不应超过窗口平均的合理上限: " + bps, bps <= upper);
        assertTrue(total > 0L);
    }
}
