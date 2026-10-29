package me.qnnp.tvlive.ui;

/**
 * 切台加载反馈状态：区分「原加载角标」与「台名卡」，并把显示条件集中在一处便于单测。
 *
 * <p>时序：{@link #begin()} → 120ms 后 {@link #delayElapsed()} → 有台名时显示卡；
 * 起播（onPrepared）或首帧时 {@link #end()} 收起。菜单/设置/首启页会盖住播放反馈，
 * 由调用方通过 {@code pageOpen} 传入；导航显示不影响台名卡（它属于切台反馈，
 * 不是频道 OSD）。</p>
 */
public final class SwitchFeedback {

    private boolean cardEnabled;
    private boolean pending;
    private boolean delayElapsed;
    private boolean firstFrame;

    /** 设置页「切台提示=台名卡/关」。 */
    public void setCardEnabled(boolean enabled) {
        this.cardEnabled = enabled;
    }

    public boolean cardEnabled() {
        return cardEnabled;
    }

    public void begin() {
        pending = true;
        delayElapsed = false;
        firstFrame = false;
    }

    public void delayElapsed() {
        if (pending) {
            delayElapsed = true;
        }
    }

    public void firstFrame() {
        firstFrame = true;
        pending = false;
        delayElapsed = false;
    }

    public void end() {
        pending = false;
        delayElapsed = false;
    }

    public boolean isPending() {
        return pending;
    }

    public boolean isDelayElapsed() {
        return delayElapsed;
    }

    /**
     * 是否显示台名卡。{@code navigationShown} 只为明确语义与测试覆盖而保留：
     * 导航展开时仍显示切台反馈；频道 OSD 的 5c 规则不在这里。
     */
    public boolean shouldShowCard(boolean navigationShown, boolean pageOpen) {
        return cardEnabled && pending && delayElapsed && !firstFrame && !pageOpen;
    }

    /** 台名卡关闭时沿用原加载角标：同样等到延迟结束，且不在菜单/设置页。 */
    public boolean shouldShowLoadingBadge(boolean pageOpen) {
        return !cardEnabled && pending && delayElapsed && !firstFrame && !pageOpen;
    }
}
