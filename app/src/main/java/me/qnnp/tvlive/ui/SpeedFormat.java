package me.qnnp.tvlive.ui;

import java.util.Locale;

/**
 * 网速文案换算（批次 15，纯 Java，可在 JVM 单测）。
 *
 * <p>输入字节/秒，输出 {@code 320 KB/s} / {@code 1.2 MB/s}；小于 1 KB/s 保留字节单位
 * （{@code 999 B/s}），0 或未知显示 {@code —}。数值最多保留 1 位小数，整数不带
 * {@code .0}。</p>
 *
 * <p>慢速分级：低于 {@link #VERY_SLOW_BPS} 前缀「网络很慢 · 」，低于 {@link #SLOW_BPS}
 * 前缀「网络较慢 · 」，正常只显示数字。</p>
 */
public final class SpeedFormat {

    public static final long KB = 1024L;
    public static final long MB = 1024L * 1024L;

    /** 无数据（MediaPlayer 兜底、首包到达前）。 */
    public static final String NO_DATA = "—";

    /** {@code < 200 KB/s} 视为「很慢」。 */
    public static final long VERY_SLOW_BPS = 200L * KB;
    /** {@code < 500 KB/s} 视为「较慢」。 */
    public static final long SLOW_BPS = 500L * KB;

    public static final String VERY_SLOW_PREFIX = "网络很慢 · ";
    public static final String SLOW_PREFIX = "网络较慢 · ";

    private SpeedFormat() {
    }

    /** 字节/秒 → 展示用单位（不含慢速前缀）。 */
    public static String format(long bytesPerSecond) {
        if (bytesPerSecond <= 0L) {
            return NO_DATA;
        }
        if (bytesPerSecond < KB) {
            return bytesPerSecond + " B/s";
        }
        if (bytesPerSecond < MB) {
            return oneDecimal(bytesPerSecond / (double) KB) + " KB/s";
        }
        return oneDecimal(bytesPerSecond / (double) MB) + " MB/s";
    }

    /** 台名卡上的完整文案：慢速时带分级前缀。 */
    public static String formatWithWarning(long bytesPerSecond) {
        if (bytesPerSecond <= 0L) {
            return NO_DATA;
        }
        String speed = format(bytesPerSecond);
        if (bytesPerSecond < VERY_SLOW_BPS) {
            return VERY_SLOW_PREFIX + speed;
        }
        if (bytesPerSecond < SLOW_BPS) {
            return SLOW_PREFIX + speed;
        }
        return speed;
    }

    /** 慢速等级：0 正常，1 较慢，2 很慢（便于 UI 换色/单测）。 */
    public static int level(long bytesPerSecond) {
        if (bytesPerSecond <= 0L) {
            return 0;
        }
        if (bytesPerSecond < VERY_SLOW_BPS) {
            return 2;
        }
        return bytesPerSecond < SLOW_BPS ? 1 : 0;
    }

    private static String oneDecimal(double value) {
        String text = String.format(Locale.US, "%.1f", value);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }
}
