package me.qnnp.tvlive.ui;

import me.qnnp.tvlive.prefs.Aspect;

/**
 * 画面在容器里的几何计算（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 18：设置页「画面比例」提供三档，视频源统一按 16:9 处理（与批次 8 的
 * preview 一致）：</p>
 * <ul>
 *   <li>{@link Aspect#FIT}：16:9 等比缩放，尽量大且不越界，居中留黑边；</li>
 *   <li>{@link Aspect#STRETCH}：直接用容器宽高，铺满但可能变形；</li>
 *   <li>{@link Aspect#CROP}：16:9 等比放大到完全覆盖容器，超出部分居中裁掉。</li>
 * </ul>
 *
 * <p>这里只算尺寸与居中偏移，不动 View：{@link TvLayout}、{@link PhoneLayout} 和
 * {@link TextureVideoLayer} 把结果套到 LayoutParams / 子视图 layout 上。</p>
 */
public final class VideoGeometry {

    /** 视频宽高比 16:9。 */
    public static final int VIDEO_WIDTH = 16;
    public static final int VIDEO_HEIGHT = 9;

    private VideoGeometry() {
    }

    /** 计算出的画面尺寸（像素）；容器无效时宽高都为 0。 */
    public static final class Size {

        public final int width;
        public final int height;

        Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    /** 按设置选择 fit / stretch / crop；null 按 {@link Aspect#FIT}。 */
    public static Size size(Aspect aspect, int containerWidth, int containerHeight) {
        Aspect target = aspect == null ? Aspect.FIT : aspect;
        switch (target) {
            case STRETCH:
                return stretch(containerWidth, containerHeight);
            case CROP:
                return crop(containerWidth, containerHeight);
            case FIT:
            default:
                return fit(containerWidth, containerHeight);
        }
    }

    /**
     * 16:9 等比 fit：{@code width = min(容器宽, 容器高 × 16/9)}，高度由宽度反算。
     * 至少一边贴边，另一边留黑边。
     */
    public static Size fit(int containerWidth, int containerHeight) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return new Size(0, 0);
        }
        long maxByHeight = (long) containerHeight * VIDEO_WIDTH / VIDEO_HEIGHT;
        int width = (int) Math.min(containerWidth, maxByHeight);
        int height = roundDiv(width * VIDEO_HEIGHT, VIDEO_WIDTH);
        if (height > containerHeight) {
            height = containerHeight;
        }
        return new Size(width, height);
    }

    /** 拉伸：宽高都等于容器（可能变形）。 */
    public static Size stretch(int containerWidth, int containerHeight) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return new Size(0, 0);
        }
        return new Size(containerWidth, containerHeight);
    }

    /**
     * 16:9 center-crop：等比放大到宽高都不小于容器，至少一边贴边、另一边超出，
     * 超出的部分由调用方按 {@link #centerOffset(int, int)} 居中裁掉。
     */
    public static Size crop(int containerWidth, int containerHeight) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            return new Size(0, 0);
        }
        // 覆盖容器高度所需的最小宽度（向上取整，保证不露边）。
        int widthForHeight = ceilDiv((long) containerHeight * VIDEO_WIDTH, VIDEO_HEIGHT);
        int width = Math.max(containerWidth, widthForHeight);
        int height = ceilDiv((long) width * VIDEO_HEIGHT, VIDEO_WIDTH);
        if (height < containerHeight) {
            height = containerHeight;
        }
        return new Size(width, height);
    }

    /**
     * 居中的起始偏移：{@code (container - content) / 2}，向下取整。
     * crop 时 content 比 container 大，返回负数（左侧/顶部被裁掉）。
     */
    public static int centerOffset(int containerSize, int contentSize) {
        int slack = containerSize - contentSize;
        if (slack >= 0) {
            return slack / 2;
        }
        return -ceilDiv(-(long) slack, 2);
    }

    private static int roundDiv(long value, int divisor) {
        return (int) ((value + divisor / 2) / divisor);
    }

    private static int ceilDiv(long value, int divisor) {
        return (int) ((value + divisor - 1) / divisor);
    }
}
