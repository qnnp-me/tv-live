package me.qnnp.tvlive.ui;

/**
 * 电视主布局的横向几何（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 8：导航是根层覆盖层，压在全宽视频上；视频不再缩到右侧。<b>导航宽度</b>默认
 * 为整屏宽的 44%（1080p ≈845px，720p 564px），批次 18 起可在 38%/44%/50% 间切换。
 * 画面几何（fit/stretch/crop）由 {@link VideoGeometry} 计算，容器始终全宽
 * （{@link TvLayout} 的 preview 是 MATCH_PARENT）。</p>
 *
 * <p>这里只算宽度，不动 View：{@link NavView} 在自己的 {@code onMeasure} 里套用
 * {@link #navWidth(int, float)}；{@link #fitWidth}/{@link #fitHeight} 保留给批次 8 的
 * 单测与旧调用方，实际布局已改走 {@link VideoGeometry}。</p>
 */
public final class TvNavGeometry {

    /** 导航默认占整屏宽度的比例（1080p ≈845px，720p 564px）。 */
    public static final float NAV_WIDTH_RATIO = 0.44f;

    /** 视频宽高比 16:9：画面只按比例缩放，绝不拉伸变形。 */
    public static final int VIDEO_WIDTH = 16;
    public static final int VIDEO_HEIGHT = 9;

    private TvNavGeometry() {
    }

    /**
     * 导航宽度：整屏宽的默认 44%，向上取整（保证导航永不窄于 44%）。
     *
     * @param totalWidth 布局宽度；<=0（尚未测量）返回 0
     */
    public static int navWidth(int totalWidth) {
        return navWidth(totalWidth, NAV_WIDTH_RATIO);
    }

    /**
     * 导航宽度：整屏宽 × ratio，向上取整（批次 18：窄 38% / 标准 44% / 宽 50%）。
     *
     * @param totalWidth 布局宽度；<=0（尚未测量）返回 0
     * @param ratio      占整屏宽度的比例；<=0 返回 0，>1 按 1 处理
     */
    public static int navWidth(int totalWidth, float ratio) {
        if (totalWidth <= 0 || ratio <= 0f) {
            return 0;
        }
        float safeRatio = Math.min(ratio, 1f);
        return (int) Math.ceil(totalWidth * safeRatio);
    }

    /**
     * 画面宽度：在容器内按 16:9 等比 fit（不裁切、不拉伸），黑边由容器背景色填充。
     *
     * <p>{@code width = min(容器宽, 容器高 × 16/9)}：容器比 16:9 更宽/更高时，
     * 取容器高换算出的宽度，画面上下留黑边；容器比 16:9 更窄时取容器宽，上下留黑边。
     * 1920×1080（整屏）→ 1920；1280×1080 → 1920 被容器宽限制为 1280。容器始终全宽，
     * 导航显示与否不改变这里。</p>
     *
     * @param containerWidth  容器宽（preview 宽）；<=0 返回 0
     * @param containerHeight 容器高（preview 高）；<=0 返回 0
     */
    public static int fitWidth(int containerWidth, int containerHeight) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return 0;
        }
        long maxByHeight = (long) containerHeight * VIDEO_WIDTH / VIDEO_HEIGHT;
        return (int) Math.min(containerWidth, maxByHeight);
    }

    /**
     * 画面高度：由 {@link #fitWidth} 反算，保持 16:9 且不超过容器高。
     *
     * @param fitWidth        画面宽度（{@link #fitWidth} 的结果）；<=0 返回 0
     * @param containerHeight 容器高，用于兜底钳制；<=0 返回 0
     */
    public static int fitHeight(int fitWidth, int containerHeight) {
        if (fitWidth <= 0 || containerHeight <= 0) {
            return 0;
        }
        long height = (fitWidth * (long) VIDEO_HEIGHT + VIDEO_WIDTH / 2) / VIDEO_WIDTH;
        return (int) Math.min(height, containerHeight);
    }
}
