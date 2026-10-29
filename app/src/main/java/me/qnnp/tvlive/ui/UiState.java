package me.qnnp.tvlive.ui;

/**
 * 遥控 UI 状态（纯 Java，可在 JVM 单测）：当前模式 + 菜单/导航焦点/退出确认。
 *
 * <p>批次 4a 起所有模式迁移集中在本类，{@link NavVisibility} 只负责自动收起计时，
 * 不再自己保存模式；{@link TvLayout} 与浮层由 {@link MainActivity} 在调用这里的
 * enter/exit 方法后同步驱动，避免两套状态互相打架。</p>
 *
 * <p>{@link Mode#SETTINGS} 是覆盖层：打开时记住底层模式，关闭后恢复；底层模式同时
 * 决定「全屏 Back 回隐藏」等语义。{@link Mode#SETUP} 是独立页面，导航固定隐藏。</p>
 *
 * <p>批次 5c：{@link Mode#FULLSCREEN} 只属于手机竖屏（点视频进全屏 + OSD/返回按钮）。
 * 电视与手机横屏没有独立全屏态，「全屏」就是 {@link Mode#NAV_HIDDEN}（画面铺满），
 * 因此这些设备上的 OK/Back/「全屏播放」都不会产生 FULLSCREEN。</p>
 *
 * <p>{@code actionColumnVisible} 是布局状态（列表是否让出操作列宽度），
 * {@code focusInActions} 是焦点/高亮状态；2g 在数字/CH 移焦点等路径只清焦点、
 * 不收起已展开的操作列，这里保留两个字段以维持既有行为。</p>
 */
public final class UiState {

    public enum Mode {
        SETUP,
        SETTINGS,
        FULLSCREEN,
        NAV_SHOWN,
        NAV_HIDDEN
    }

    /** 退出确认（「再按一次返回退出」）状态变化；Activity 据此显示/收起提示。 */
    public interface ExitListener {
        void onExitArmedChanged(boolean armed);
    }

    private Mode mode = Mode.NAV_HIDDEN;
    /** 进入 SETTINGS 前的模式（SETUP / FULLSCREEN / NAV_SHOWN / NAV_HIDDEN）。 */
    private Mode settingsReturn = Mode.NAV_HIDDEN;

    private final NavVisibility autoHide = new NavVisibility();

    private boolean menuOpen;
    private boolean actionColumnVisible;
    private boolean focusInGroups;
    private boolean focusInActions;
    private boolean exitArmed;

    private ExitListener exitListener;

    // ------------------------------------------------------------------ 查询

    public Mode mode() {
        return mode;
    }

    /** SETTINGS 关闭后回到的模式。 */
    public Mode settingsReturn() {
        return settingsReturn;
    }

    public boolean isSetup() {
        return mode == Mode.SETUP;
    }

    public boolean isSettings() {
        return mode == Mode.SETTINGS;
    }

    /** 含 SETTINGS 覆盖层下的底层模式：设置页打开时全屏语义保持不变。 */
    public boolean isFullscreen() {
        return baseMode() == Mode.FULLSCREEN;
    }

    public boolean isNavShown() {
        return baseMode() == Mode.NAV_SHOWN;
    }

    public boolean isNavHidden() {
        return baseMode() == Mode.NAV_HIDDEN;
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public boolean isActionColumnVisible() {
        return actionColumnVisible;
    }

    public boolean isFocusInGroups() {
        return focusInGroups;
    }

    public boolean isFocusInActions() {
        return focusInActions;
    }

    public boolean isExitArmed() {
        return exitArmed;
    }

    public NavVisibility autoHide() {
        return autoHide;
    }

    /** 自动收起截止时间；-1 表示不计时。仅用于诊断/测试。 */
    public long autoHideAt() {
        return autoHide.autoHideAt();
    }

    private Mode baseMode() {
        return mode == Mode.SETTINGS ? settingsReturn : mode;
    }

    // ------------------------------------------------------------ 模式迁移

    /** 首启页：独立页面，导航固定隐藏；会话内的设置页覆盖被关闭。 */
    public void enterSetup() {
        autoHide.cancel();
        menuOpen = false;
        actionColumnVisible = false;
        focusInGroups = false;
        focusInActions = false;
        mode = Mode.SETUP;
        settingsReturn = Mode.NAV_HIDDEN;
    }

    /** 首启页被有源列表取代；若设置页正覆盖在首启页上，关闭设置后也不再回首启页。 */
    public void dismissSetup() {
        if (mode == Mode.SETUP) {
            mode = Mode.NAV_HIDDEN;
        } else if (mode == Mode.SETTINGS && settingsReturn == Mode.SETUP) {
            settingsReturn = Mode.NAV_HIDDEN;
        }
    }

    /** 打开设置页；已在设置页时返回 false。 */
    public boolean enterSettings() {
        if (mode == Mode.SETTINGS) {
            return false;
        }
        settingsReturn = mode;
        mode = Mode.SETTINGS;
        menuOpen = false;
        autoHide.cancel();
        return true;
    }

    /** 关闭设置页，返回底层模式；回到 NAV_SHOWN 时按当前时长重新计时。 */
    public Mode leaveSettings(long now) {
        if (mode != Mode.SETTINGS) {
            return mode;
        }
        mode = settingsReturn;
        settingsReturn = Mode.NAV_HIDDEN;
        if (mode == Mode.NAV_SHOWN) {
            autoHide.arm(now);
        }
        return mode;
    }

    /** 弹出导航覆盖层（NAV_HIDDEN / FULLSCREEN → NAV_SHOWN）。 */
    public void showNav(long now) {
        if (mode == Mode.SETTINGS) {
            // 设置页覆盖在导航上：只改底层模式，关闭设置后回到导航。
            settingsReturn = Mode.NAV_SHOWN;
        } else if (mode != Mode.SETUP) {
            mode = Mode.NAV_SHOWN;
        }
        actionColumnVisible = false;
        focusInActions = false;
        autoHide.arm(now);
    }

    /** 收起导航覆盖层（NAV_SHOWN / FULLSCREEN → NAV_HIDDEN）；首启页保持不变。 */
    public void hideNav() {
        autoHide.cancel();
        actionColumnVisible = false;
        focusInActions = false;
        if (mode == Mode.SETTINGS) {
            settingsReturn = Mode.NAV_HIDDEN;
        } else if (mode != Mode.SETUP) {
            mode = Mode.NAV_HIDDEN;
        }
    }

    public void enterFullscreen() {
        autoHide.cancel();
        actionColumnVisible = false;
        focusInActions = false;
        if (mode == Mode.SETTINGS) {
            settingsReturn = Mode.FULLSCREEN;
        } else {
            mode = Mode.FULLSCREEN;
        }
    }

    /** 全屏 Back → 导航隐藏态（不弹导航）。 */
    public void exitFullscreen() {
        autoHide.cancel();
        actionColumnVisible = false;
        focusInActions = false;
        if (mode == Mode.SETTINGS) {
            if (settingsReturn == Mode.FULLSCREEN) {
                settingsReturn = Mode.NAV_HIDDEN;
            }
        } else if (mode == Mode.FULLSCREEN) {
            mode = Mode.NAV_HIDDEN;
        }
    }

    /** 播放列表被清空：收起导航/全屏，但不改变首启页或设置页的显示。 */
    public void hidePlaybackScene() {
        autoHide.cancel();
        menuOpen = false;
        actionColumnVisible = false;
        focusInGroups = false;
        focusInActions = false;
        if (mode == Mode.SETTINGS) {
            settingsReturn = Mode.NAV_HIDDEN;
        } else if (mode != Mode.SETUP) {
            mode = Mode.NAV_HIDDEN;
        }
    }

    // ---------------------------------------------------- 菜单 / 焦点 / 计时

    public void openMenu() {
        menuOpen = true;
    }

    public void closeMenu() {
        menuOpen = false;
    }

    public void setActionColumnVisible(boolean visible) {
        actionColumnVisible = visible;
    }

    public void setFocusInGroups(boolean inGroups) {
        focusInGroups = inGroups;
    }

    public void setFocusInActions(boolean inActions) {
        focusInActions = inActions;
    }

    /** 设置自动收起时长（5/8/15 秒；0 = 不自动收起），下一次计时/按键起生效。 */
    public void setAutoHideMs(long autoHideMs) {
        autoHide.setAutoHideMs(autoHideMs);
    }

    /** 任意按键重置计时；只在导航展开时有效。 */
    public void noteKey(long now) {
        if (mode == Mode.NAV_SHOWN) {
            autoHide.noteKey(now);
        }
    }

    /** 菜单/设置/首启/错误 OSD 期间暂停自动收起；解除后重新计时。 */
    public void setBlocked(boolean blocked, long now) {
        autoHide.setBlocked(blocked, now);
    }

    /** 到点则收起导航并返回 true；调用方据此播放收起动画。 */
    public boolean consumeAutoHide(long now) {
        if (mode != Mode.NAV_SHOWN || !autoHide.consume(now)) {
            return false;
        }
        mode = Mode.NAV_HIDDEN;
        actionColumnVisible = false;
        focusInActions = false;
        return true;
    }

    // ------------------------------------------------------------ 退出确认

    public void setExitListener(ExitListener listener) {
        exitListener = listener;
    }

    public void armExit() {
        if (exitArmed) {
            return;
        }
        exitArmed = true;
        if (exitListener != null) {
            exitListener.onExitArmedChanged(true);
        }
    }

    public void disarmExit() {
        if (!exitArmed) {
            return;
        }
        exitArmed = false;
        if (exitListener != null) {
            exitListener.onExitArmedChanged(false);
        }
    }
}
