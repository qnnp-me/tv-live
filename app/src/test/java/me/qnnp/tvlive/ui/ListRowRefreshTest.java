package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 12：上下键移动焦点/播放点变化只重绑可见的旧/新两行，不再全量刷新。
 */
public class ListRowRefreshTest {

    @Test
    public void focusChangeTouchesOnlyOldAndNewVisibleRows() {
        assertTrue(ListRowRefresh.focusAffects(12, 12, 13));
        assertTrue(ListRowRefresh.focusAffects(13, 12, 13));
        assertFalse("中间/其它可见行不重绑", ListRowRefresh.focusAffects(11, 12, 13));
        assertFalse(ListRowRefresh.focusAffects(14, 12, 13));
    }

    @Test
    public void emptyFocusUsesMinusOneAndNeverMatchesARow() {
        assertTrue(ListRowRefresh.focusAffects(0, -1, 0));
        assertTrue(ListRowRefresh.focusAffects(4, 4, -1));
        assertFalse(ListRowRefresh.focusAffects(-1, -1, -1));
        assertFalse(ListRowRefresh.focusAffects(3, -1, -1));
    }

    @Test
    public void playingChangeTouchesOnlyRowsThatGainedOrLostTheDot() {
        assertTrue(ListRowRefresh.playingAffects(7, 7, 20));
        assertTrue(ListRowRefresh.playingAffects(20, 7, 20));
        assertFalse(ListRowRefresh.playingAffects(8, 7, 20));

        // 频道不在当前组/列表里：只刷新旧行（清掉角标）/ 新行（加角标）。
        assertTrue(ListRowRefresh.playingAffects(20, -1, 20));
        assertTrue(ListRowRefresh.playingAffects(7, 7, -1));
        assertFalse(ListRowRefresh.playingAffects(8, 7, -1));
    }

    @Test
    public void rowFocusRequiresBothMatchingIndexAndFocusOwnedByList() {
        assertTrue(ListRowRefresh.focusVisible(true, 3, 3));
        assertFalse("组栏拿焦点时列表行不画焦点", ListRowRefresh.focusVisible(false, 3, 3));
        assertFalse(ListRowRefresh.focusVisible(true, 4, 3));
        assertFalse("无焦点行", ListRowRefresh.focusVisible(true, -1, -1));
    }
}
