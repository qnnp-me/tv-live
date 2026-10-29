package me.qnnp.tvlive.prefs;

/**
 * 画面比例（设置页「画面比例」）：适应 / 拉伸 / 裁剪。
 *
 * <p>纯枚举，只描述显示策略；实际尺寸计算在 {@code ui.VideoGeometry}。默认 {@link #FIT}
 * 保持批次 8 以来的 16:9 等比居中行为。</p>
 */
public enum Aspect {

    /** 16:9 等比缩放并居中，多余区域留黑边（默认，不变形）。 */
    FIT,
    /** 铺满容器，可能把画面拉变形。 */
    STRETCH,
    /** 等比放大到铺满容器，按容器宽高比裁掉超出部分（不变形）。 */
    CROP;

    /** 设置页 OK 循环：适应 → 拉伸 → 裁剪 → 适应。 */
    public Aspect next() {
        Aspect[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 从持久化 key 解析；未知值回退默认的 {@link #FIT}。 */
    public static Aspect fromKey(String key) {
        if (key != null) {
            for (Aspect aspect : values()) {
                if (aspect.name().equalsIgnoreCase(key)) {
                    return aspect;
                }
            }
        }
        return FIT;
    }

    public String key() {
        return name();
    }
}
