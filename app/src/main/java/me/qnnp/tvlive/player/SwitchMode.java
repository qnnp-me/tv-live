package me.qnnp.tvlive.player;

/**
 * 切台方式（设置页「切台方式」）。
 *
 * <p>{@link #KEEP} 是 1.0.6 的行为：同类型引擎复用实例，旧画面保留到新台首帧
 * （批次 10 起在 TextureView 上保留，SurfaceTexture 不会像 SurfaceView 那样被清掉）。
 * {@link #DIRECT} 是 A 之前的行为：切台时销毁并重建引擎，用于对比。
 * {@link #DUAL} 用两个 TextureView + 两个播放引擎，首帧后 alpha 交换（见
 * {@code DualPlayback}）；单控制器层面仍按 {@link #KEEP} 处理。</p>
 */
public enum SwitchMode {

    KEEP,
    DIRECT,
    DUAL;

    /** 设置页 OK 循环：保留 → 极速 → 双画面 → 保留。 */
    public SwitchMode next() {
        SwitchMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** 从持久化 key 解析；未知值回退默认的 {@link #KEEP}。 */
    public static SwitchMode fromKey(String key) {
        if (key != null) {
            for (SwitchMode mode : values()) {
                if (mode.name().equalsIgnoreCase(key)) {
                    return mode;
                }
            }
        }
        return KEEP;
    }

    public String key() {
        return name();
    }
}
