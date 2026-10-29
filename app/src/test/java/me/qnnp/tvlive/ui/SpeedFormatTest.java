package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 网速文案换算与慢速分级（批次 15）。 */
public class SpeedFormatTest {

    @Test
    public void zeroOrUnknownShowsDash() {
        assertEquals(SpeedFormat.NO_DATA, SpeedFormat.format(0L));
        assertEquals(SpeedFormat.NO_DATA, SpeedFormat.format(-1L));
        assertEquals(SpeedFormat.NO_DATA, SpeedFormat.formatWithWarning(0L));
    }

    @Test
    public void belowOneKilobyteKeepsBytesPerSecond() {
        assertEquals("999 B/s", SpeedFormat.format(999L));
        assertEquals("1 B/s", SpeedFormat.format(1L));
    }

    @Test
    public void kilobyteBoundary() {
        assertEquals("1 KB/s", SpeedFormat.format(1024L));
        assertEquals("1.5 KB/s", SpeedFormat.format(1536L));
        assertEquals("320 KB/s", SpeedFormat.format(320L * 1024L));
    }

    @Test
    public void megabyteBoundary() {
        long oneMegabyte = 1024L * 1024L;
        assertEquals("1 MB/s", SpeedFormat.format(oneMegabyte));
        // 1.2 MB/s，保留 1 位小数
        long onePointTwo = (long) (1.2d * oneMegabyte);
        assertEquals("1.2 MB/s", SpeedFormat.format(onePointTwo));
        // 差 1 字节仍是 KB 区间（1024.0 KB/s），不做四舍五入进位到 MB。
        assertEquals("1024 KB/s", SpeedFormat.format(oneMegabyte - 1L));
    }

    @Test
    public void decimalIsDroppedWhenWholeNumber() {
        assertEquals("500 KB/s", SpeedFormat.format(500L * 1024L));
        assertEquals("2 MB/s", SpeedFormat.format(2L * 1024L * 1024L));
    }

    @Test
    public void slowLevelsUseSpecThresholds() {
        // < 200 KB/s：很慢
        assertEquals(2, SpeedFormat.level(199L * 1024L));
        assertEquals(SpeedFormat.VERY_SLOW_PREFIX + "199 KB/s",
                SpeedFormat.formatWithWarning(199L * 1024L));
        // 恰好 200 KB/s：较慢
        assertEquals(1, SpeedFormat.level(200L * 1024L));
        assertEquals(SpeedFormat.SLOW_PREFIX + "200 KB/s",
                SpeedFormat.formatWithWarning(200L * 1024L));
        // < 500 KB/s：较慢
        assertEquals(1, SpeedFormat.level(499L * 1024L));
        // 恰好 500 KB/s：正常，不带前缀
        assertEquals(0, SpeedFormat.level(500L * 1024L));
        assertEquals("500 KB/s", SpeedFormat.formatWithWarning(500L * 1024L));
        assertEquals("1.2 MB/s", SpeedFormat.formatWithWarning(1258291L));
    }

    @Test
    public void warningOnVerySlowByteRange() {
        assertEquals(SpeedFormat.VERY_SLOW_PREFIX + "512 B/s",
                SpeedFormat.formatWithWarning(512L));
        assertEquals(2, SpeedFormat.level(512L));
    }
}
