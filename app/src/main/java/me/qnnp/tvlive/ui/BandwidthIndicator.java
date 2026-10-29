package me.qnnp.tvlive.ui;

import me.qnnp.tvlive.player.PlaybackEngine;

/**
 * 台名卡网速行的开关、格式与去重（批次 15，纯 Java，可在 JVM 单测）。
 *
 * <p>引擎的 500ms 网速回调先进这里：设置「显示网速=关」时直接丢弃，不格式化、不回调
 * UI（引擎仍在统计，只是界面不刷）；开着时只在文案变化时才回调，避免每秒两次
 * {@code setText} 让台名卡反复测量。</p>
 */
public final class BandwidthIndicator implements PlaybackEngine.BandwidthListener {

    /** 文案出口；全部在主线程回调。 */
    public interface Listener {

        void onSpeedText(String text);
    }

    private boolean enabled = true;
    private Listener listener;
    private String lastText;

    /** 设置页「显示网速」开关；关闭时清掉去重缓存，重新打开后第一帧一定会刷新。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            lastText = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 换台/重新缓冲：允许下一次同样文案再刷一遍（台名卡已重置成「—」）。 */
    public void reset() {
        lastText = null;
    }

    @Override
    public void onBandwidth(long bytesPerSecond) {
        if (!enabled) {
            return;
        }
        Listener target = listener;
        if (target == null) {
            return;
        }
        String text = SpeedFormat.formatWithWarning(bytesPerSecond);
        if (text.equals(lastText)) {
            return;
        }
        lastText = text;
        target.onSpeedText(text);
    }
}
