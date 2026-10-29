package me.qnnp.tvlive.prefs;

/**
 * 导航宽度三档（设置页「高级 → 导航宽度」）。
 *
 * <p>{@link #ratio()} 是导航占整屏宽度的比例；实际像素由
 * {@code TvNavGeometry.navWidth(totalWidth, ratio)} 向上取整。默认 {@link #STANDARD}
 * 保持批次 8 的 44%。</p>
 */
public enum NavWidth {

    /** 窄：38%，画面露出更多。 */
    NARROW(0.38f),
    /** 标准：44%（原行为）。 */
    STANDARD(0.44f),
    /** 宽：50%，台名更长。 */
    WIDE(0.50f);

    private final float ratio;

    NavWidth(float ratio) {
        this.ratio = ratio;
    }

    public float ratio() {
        return ratio;
    }

    /** 设置页 OK 循环：窄 → 标准 → 宽 → 窄。 */
    public NavWidth next() {
        NavWidth[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 从持久化 key 解析；未知值回退默认的 {@link #STANDARD}。 */
    public static NavWidth fromKey(String key) {
        if (key != null) {
            for (NavWidth width : values()) {
                if (width.name().equalsIgnoreCase(key)) {
                    return width;
                }
            }
        }
        return STANDARD;
    }

    public String key() {
        return name();
    }
}
