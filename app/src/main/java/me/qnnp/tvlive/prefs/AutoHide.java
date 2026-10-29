package me.qnnp.tvlive.prefs;

/**
 * 导航自动收起时长（设置页「高级 → 自动收起」）。
 *
 * <p>声明顺序即设置页 OK 的循环顺序：5 秒 → 8 秒 → 15 秒 → 不收起 → 5 秒。
 * {@link #OFF} 只关闭计时，Back/OK 仍可手动收起导航。</p>
 */
public enum AutoHide {

    FIVE(5),
    EIGHT(8),
    FIFTEEN(15),
    OFF(0);

    private final int seconds;

    AutoHide(int seconds) {
        this.seconds = seconds;
    }

    /** 自动收起秒数；0 表示不自动收起。 */
    public int seconds() {
        return seconds;
    }

    public long millis() {
        return seconds * 1000L;
    }

    /** 是否启用自动收起（{@link #OFF} 为 false，仅手动 Back/OK 收起）。 */
    public boolean isEnabled() {
        return seconds > 0;
    }

    /** 设置页 OK 循环：5 → 8 → 15 → 不收起 → 5。 */
    public AutoHide next() {
        AutoHide[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 按秒数解析；未知值回退默认的 {@link #EIGHT}。 */
    public static AutoHide fromSeconds(int seconds) {
        for (AutoHide option : values()) {
            if (option.seconds == seconds) {
                return option;
            }
        }
        return EIGHT;
    }

    /** 从持久化 key（秒数）解析；未知值回退默认的 {@link #EIGHT}。 */
    public static AutoHide fromKey(String key) {
        if (key != null) {
            try {
                return fromSeconds(Integer.parseInt(key.trim()));
            } catch (NumberFormatException ignored) {
                // 落回默认值。
            }
        }
        return EIGHT;
    }

    public String key() {
        return Integer.toString(seconds);
    }
}
