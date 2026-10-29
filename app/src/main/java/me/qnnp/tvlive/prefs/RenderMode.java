package me.qnnp.tvlive.prefs;

/**
 * 视频渲染方式（设置页「视频渲染」）：自动 / SurfaceView / TextureView。
 *
 * <p>{@link #AUTO} 保持批次 9 以来的行为：纹理层（KEEP/DUAL）与 SurfaceView（DIRECT）
 * 由切台方式决定；{@link #SURFACE} / {@link #TEXTURE} 强制指定视频层，与切台方式解耦
 * （组合规则见 {@code ui.RenderDecision}）。</p>
 */
public enum RenderMode {

    AUTO,
    SURFACE,
    TEXTURE;

    /** 设置页 OK 循环：自动 → SurfaceView → TextureView → 自动。 */
    public RenderMode next() {
        RenderMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 从持久化 key 解析；未知值回退默认的 {@link #AUTO}。 */
    public static RenderMode fromKey(String key) {
        if (key != null) {
            for (RenderMode mode : values()) {
                if (mode.name().equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return AUTO;
    }

    public String key() {
        return name();
    }
}
