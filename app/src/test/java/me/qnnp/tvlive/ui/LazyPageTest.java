package me.qnnp.tvlive.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LazyPageTest {

    /** 计数的假页面。 */
    private static final class Page {

        final int index;

        Page(int index) {
            this.index = index;
        }
    }

    private static final class Builder implements LazyPage.Builder<Page> {

        int builds;

        @Override
        public Page build() {
            builds++;
            return new Page(builds);
        }
    }

    @Test
    public void doesNotBuildUntilFirstGet() {
        Builder builder = new Builder();
        LazyPage<Page> page = new LazyPage<Page>(builder);

        assertFalse("冷启动不应构建设置页/首启页", page.isBuilt());
        assertEquals(0, builder.builds);
        assertNull(page.peek());

        Page built = page.get();
        assertTrue(page.isBuilt());
        assertEquals(1, builder.builds);
        assertEquals(1, page.buildCount());
        assertSame(built, page.peek());
    }

    @Test
    public void reusesSameInstanceAfterExitAndReenter() {
        Builder builder = new Builder();
        LazyPage<Page> page = new LazyPage<Page>(builder);

        Page first = page.get();   // 首次进设置页
        Page second = page.get();  // 退出再进
        Page third = page.get();

        assertSame(first, second);
        assertSame(first, third);
        assertEquals("退出再进不重建", 1, builder.builds);
    }

    @Test
    public void peekNeverBuilds() {
        Builder builder = new Builder();
        LazyPage<Page> page = new LazyPage<Page>(builder);

        for (int i = 0; i < 5; i++) {
            assertNull(page.peek());
        }
        assertFalse(page.isBuilt());
        assertEquals(0, builder.builds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullBuilder() {
        new LazyPage<Page>(null);
    }
}
