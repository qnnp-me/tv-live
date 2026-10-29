package me.qnnp.tvlive.ui;

/**
 * 导航自动收起计时（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 4a 起本类只做计时，不再保存/迁移模式：导航是显示、全屏还是隐藏由
 * {@link UiState} 唯一决定。调用方在进入「导航展开」时 {@link #arm(long)}，
 * 收起/全屏时 {@link #cancel()}；每次按键用 {@link #noteKey(long)} 重置计时。</p>
 *
 * <p>批次 18 起时长可配置（5/8/15 秒；0 表示不自动收起），默认仍是 8 秒。
 * 菜单、设置页、首启页、错误 OSD 等阻塞期间不计时，解除阻塞后按当前时长重新计时。</p>
 *
 * <p>时间由调用方传入（Activity 用 {@code SystemClock.elapsedRealtime()}，
 * 单测用固定毫秒），本类不持有时钟或 Handler。</p>
 */
public final class NavVisibility {

    /** 默认无按键 8 秒自动收起导航。 */
    public static final long AUTO_HIDE_MS = 8000L;

    /** 调用方希望计时（导航展开）；阻塞期间保留该意图，解除后重新计时。 */
    private boolean active;
    /** 自动收起截止时间；-1 表示当前不计时。 */
    private long autoHideAt = -1L;
    private boolean blocked;
    /** 自动收起时长；<=0 表示不自动收起（Back/OK 仍可收）。 */
    private long autoHideMs = AUTO_HIDE_MS;

    /** 开始计时（阻塞期间只记录意图，不设截止时间）。 */
    public void arm(long now) {
        active = true;
        armAt(now);
    }

    /** 停止计时（收起导航 / 进入全屏 / 清空播放状态）。 */
    public void cancel() {
        active = false;
        autoHideAt = -1L;
    }

    /** 任意按键：计时中且未阻塞时重置 8 秒。 */
    public void noteKey(long now) {
        if (!active) {
            return;
        }
        armAt(now);
    }

    /**
     * 菜单/设置/首启/错误 OSD 期间阻塞自动收起；解除阻塞时若仍计时，从当前时刻重新计时。
     */
    public void setBlocked(boolean blocked, long now) {
        if (this.blocked == blocked) {
            return;
        }
        this.blocked = blocked;
        if (blocked) {
            autoHideAt = -1L;
        } else if (active) {
            armAt(now);
        }
    }

    public boolean isBlocked() {
        return blocked;
    }

    /** 自动收起截止时间；-1 表示不计时。仅用于诊断/测试。 */
    public long autoHideAt() {
        return autoHideAt;
    }

    /** 设置自动收起时长；<=0 表示不自动收起（下一次 arm/noteKey 生效）。 */
    public void setAutoHideMs(long autoHideMs) {
        this.autoHideMs = Math.max(0L, autoHideMs);
        if (this.autoHideMs <= 0L) {
            autoHideAt = -1L;
        }
    }

    public long autoHideMs() {
        return autoHideMs;
    }

    /** 是否启用自动收起（时长为 0 表示关闭，仅手动收起）。 */
    public boolean isAutoHideEnabled() {
        return autoHideMs > 0L;
    }

    public boolean isDue(long now) {
        return active && !blocked && autoHideAt >= 0L && now >= autoHideAt;
    }

    /** 到点则停止计时并返回 true；调用方据此收起导航。 */
    public boolean consume(long now) {
        if (!isDue(now)) {
            return false;
        }
        cancel();
        return true;
    }

    private void armAt(long now) {
        if (blocked || autoHideMs <= 0L) {
            autoHideAt = -1L;
        } else {
            autoHideAt = now + autoHideMs;
        }
    }
}
