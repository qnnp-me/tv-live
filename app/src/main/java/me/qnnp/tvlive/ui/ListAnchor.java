package me.qnnp.tvlive.ui;

/**
 * 列表滚动锚点（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5b：竖屏↔横屏只切布局，列表要停在原来的位置（同一台行仍在原处），
 * 不能因为重建列表就跳回顶部。锚点保存「首个可见行 + 它距列表顶部的偏移」，
 * 新列表用 {@code setSelectionFromTop} 还原。</p>
 *
 * <p>还原前用 {@link #clampTo(int)} 兜住频道数量变化（收藏组增删、重载后变短），
 * 避免还原到不存在的行。</p>
 */
public final class ListAnchor {

    /** 没有可用锚点（列表为空 / 还没布局）。 */
    public static final ListAnchor NONE = new ListAnchor(-1, 0);

    private final int position;
    private final int offset;

    private ListAnchor(int position, int offset) {
        this.position = position;
        this.offset = offset;
    }

    /** firstVisiblePosition 从 0 开始；<0 视为无锚点。offset 负数按 0。 */
    public static ListAnchor of(int firstVisiblePosition, int topOffset) {
        if (firstVisiblePosition < 0) {
            return NONE;
        }
        return new ListAnchor(firstVisiblePosition, topOffset > 0 ? topOffset : 0);
    }

    public static ListAnchor none() {
        return NONE;
    }

    public int position() {
        return position;
    }

    public int offset() {
        return offset;
    }

    public boolean valid() {
        return position >= 0;
    }

    /** 频道数量变化后把锚点夹进 [0, count-1]；空列表返回 {@link #NONE}。 */
    public ListAnchor clampTo(int count) {
        if (!valid() || count <= 0) {
            return NONE;
        }
        if (position < count) {
            return this;
        }
        return new ListAnchor(count - 1, offset);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ListAnchor)) {
            return false;
        }
        ListAnchor that = (ListAnchor) other;
        return position == that.position && offset == that.offset;
    }

    @Override
    public int hashCode() {
        return 31 * position + offset;
    }

    @Override
    public String toString() {
        return "ListAnchor{" + position + "," + offset + "}";
    }
}
