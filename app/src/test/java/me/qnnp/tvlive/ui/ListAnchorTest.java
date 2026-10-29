package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 5b：旋转切布局时列表位置不跳。
 *
 * <p>锚点 = 首个可见行 + 它相对列表顶部内边距的偏移；新布局用
 * {@code setSelectionFromTop(position, paddingTop + offset)} 还原。</p>
 */
public class ListAnchorTest {

    @Test
    public void anchorRemembersFirstVisibleRowAndOffset() {
        ListAnchor anchor = ListAnchor.of(37, 24);
        assertTrue(anchor.valid());
        assertEquals(37, anchor.position());
        assertEquals(24, anchor.offset());
        assertEquals(anchor, ListAnchor.of(37, 24));
    }

    @Test
    public void negativePositionMeansNoAnchor() {
        assertSame(ListAnchor.NONE, ListAnchor.of(-1, 0));
        assertFalse(ListAnchor.of(-1, 40).valid());
        assertSame(ListAnchor.NONE, ListAnchor.none());
    }

    @Test
    public void negativeOffsetIsTreatedAsZero() {
        assertEquals(0, ListAnchor.of(3, -48).offset());
    }

    @Test
    public void clampKeepsAnchorInsideNewChannelCount() {
        // 重载/收藏增删后列表变短：还原到能显示的最后一行，而不是空位置。
        assertEquals(ListAnchor.of(9, 12), ListAnchor.of(37, 12).clampTo(10));
        assertEquals(ListAnchor.of(37, 12), ListAnchor.of(37, 12).clampTo(120));
        assertSame(ListAnchor.NONE, ListAnchor.of(37, 12).clampTo(0));
        assertSame(ListAnchor.NONE, ListAnchor.NONE.clampTo(120));
    }
}
