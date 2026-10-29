package me.qnnp.tvlive.ui;

/**
 * 全屏自动跳台的连续失败计数（纯 Java，可在 JVM 单测）。
 *
 * <p>只有全屏自动跳失败链才记数；列表预览失败、启动恢复失败不进这里。
 * 起播成功、用户手动换台/切组、回列表、重新加载源时调用 {@link #reset()}。</p>
 */
public final class FailureStreak {

    public static final int DEFAULT_LIMIT = 5;

    private final int limit;
    private int count;

    public FailureStreak() {
        this(DEFAULT_LIMIT);
    }

    public FailureStreak(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        this.limit = limit;
    }

    /** 记录一次失败，返回累计次数。 */
    public int recordFailure() {
        if (count < Integer.MAX_VALUE) {
            count++;
        }
        return count;
    }

    public boolean limitReached() {
        return count >= limit;
    }

    public int count() {
        return count;
    }

    public int limit() {
        return limit;
    }

    public void reset() {
        count = 0;
    }
}
