package me.qnnp.tvlive.ui;

/**
 * 把 {@link UiState} 的当前模式单向同步到布局（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5c：{@link UiState} 是唯一状态源，{@code TvLayout}/{@code PhoneLayout}
 * 只跟随、不再自己决定模式。每次模式迁移（OK 弹导航、Back 收起、进出全屏、旋转换布局）
 * 后由 {@code MainActivity.applyModeToLayout()} 调用这里，保证两边的全屏/导航状态一致。</p>
 *
 * <p>容错：如果 {@code ui} 已是非全屏但布局还卡在全屏（5c 前的历史 bug：布局
 * {@code showNav()} 因内部 {@code fullscreen} 直接 return，导航永远弹不出来），
 * 这里先 {@link Target#exitFullscreen()} 再显示导航，把状态拉回来。</p>
 */
public final class LayoutModeSync {

    /** 布局侧需要的最小模式接口；{@link LiveLayout} 实现，单测用记录型 fake。 */
    public interface Target {
        boolean isFullscreen();

        void enterFullscreen();

        void exitFullscreen();

        void showNav(boolean animate);

        void hideNav(boolean animate);
    }

    private LayoutModeSync() {
    }

    /**
     * 按 {@code ui.mode()} 同步布局。
     *
     * @param animateNav 导航显隐是否走动画（OK 弹导航/Back 收起为 true；全屏、换布局为 false）
     */
    public static void apply(UiState ui, Target layout, boolean animateNav) {
        if (layout == null) {
            return;
        }
        if (ui.isFullscreen()) {
            // 全屏是互斥态：布局进入全屏即可，导航由布局自己收起。
            if (!layout.isFullscreen()) {
                layout.enterFullscreen();
            }
            return;
        }
        // 非全屏却卡在全屏：先退全屏，否则布局 showNav() 会被 fullscreen 兜底挡掉。
        if (layout.isFullscreen()) {
            layout.exitFullscreen();
        }
        if (ui.isNavShown()) {
            layout.showNav(animateNav);
        } else {
            layout.hideNav(animateNav);
        }
    }
}
