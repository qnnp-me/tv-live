package me.qnnp.tvlive.ui;

/**
 * 列表行局部刷新判定（批次 12，纯 Java，可在 JVM 单测）。
 *
 * <p>焦点/播放点变化只影响两行：旧行要清掉高亮/播放点，新行要加上。列表里只有可见行
 * 需要立刻重绑，其余行滚入时由 {@code getView} 按最新状态绑定——不需要
 * {@code notifyDataSetChanged()} 重建所有可见行。</p>
 */
public final class ListRowRefresh {

    private ListRowRefresh() {
    }

    /** 第 position 个可见行是否受焦点从 oldFocus 移到 newFocus 影响。 */
    public static boolean focusAffects(int position, int oldFocus, int newFocus) {
        return position >= 0 && (position == oldFocus || position == newFocus);
    }

    /**
     * 行是否绘制焦点样式：焦点索引匹配，且焦点确实在列表（组栏拿焦点时不算）。
     * 焦点离开列表时 {@code focusIndex} 仍保留，用于返回列表时恢复落点。
     */
    public static boolean focusVisible(boolean listFocused, int position, int focusIndex) {
        return listFocused && position >= 0 && position == focusIndex;
    }

    /** 第 position 个可见行是否受播放点从 oldPosition 换到 newPosition 影响。 */
    public static boolean playingAffects(int position, int oldPosition, int newPosition) {
        return position >= 0 && (position == oldPosition || position == newPosition);
    }
}
