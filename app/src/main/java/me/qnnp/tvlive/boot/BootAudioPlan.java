package me.qnnp.tvlive.boot;

/**
 * 开机音频自检计划（纯 Java）。
 *
 * <p>TCL L32F3800A（MT5507 / Android 4.4.3）上，开机信号可能在音频 HAL/信源路由
 * 就绪前就把 App 拉起；ExoPlayer 的 AudioTrack 显示 active，但电视扬声器没有声音。
 * 多次关机/开机后时而恢复，说明是启动时序问题，不是播放器解码失败。</p>
 *
 * <p>处理：开机拉起（from_boot）且是 TCL legacy 时，在 BOOT_COMPLETED 后延迟
 * {@link #REPLAY_DELAY_MS} 重建一次引擎/重播当前台，让 AudioTrack 在系统完全
 * 起来后重新创建；如果 BOOT_COMPLETED 没收到，开机 {@link #FALLBACK_BOOT_MS}
 * 后兜底一次。只在开机首播会话做一次。</p>
 */
public final class BootAudioPlan {

    /** BOOT_COMPLETED 后再等这么久，给音频 HAL/信源路由留时间。 */
    public static final long REPLAY_DELAY_MS = 10_000L;

    /** 没收到 BOOT_COMPLETED 时的兜底时刻（elapsedRealtime，开机以来）。 */
    public static final long FALLBACK_BOOT_MS = 150_000L;

    private BootAudioPlan() {
    }

    /** 只有开机拉起且是 TCL legacy 才需要做音频自检。 */
    public static boolean shouldArm(boolean fromBoot, boolean tclLegacy) {
        return fromBoot && tclLegacy;
    }

    /** 兜底检查：pending 且已到开机兜底时刻。 */
    public static boolean shouldFallback(long elapsedRealtimeMs, boolean pending) {
        return pending && elapsedRealtimeMs >= FALLBACK_BOOT_MS;
    }
}
