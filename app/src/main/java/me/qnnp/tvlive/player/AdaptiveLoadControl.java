package me.qnnp.tvlive.player;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;

/**
 * 两档自适应缓冲：起播档快速出画，READY 之后切稳定档抗抖动。
 *
 * <p>内部同时持有两个 {@link DefaultLoadControl} 委托，生命周期回调两边都转发，
 * 让两个委托的内部状态（target buffer / isLoading）保持一致；真正生效的
 * {@code shouldContinueLoading/shouldStartPlayback} 只问当前档位。两个委托共享
 * 同一个 {@link DefaultAllocator}，避免运行中切换 allocator 导致 ExoPlayer 持有过期实例。</p>
 *
 * <p>首帧加速关闭时两档都用 {@link BufferProfile#ORIGINAL}，{@link #setStable(boolean)}
 * 不产生任何变化，行为与旧版本完全一致。</p>
 */
public final class AdaptiveLoadControl implements LoadControl {

    /** 与 DefaultLoadControl 默认分配粒度一致（64 KiB），避免额外常量依赖。 */
    private static final int ALLOCATION_SIZE = 64 * 1024;

    private final boolean adaptive;
    private final DefaultAllocator allocator;
    private final DefaultLoadControl startup;
    private final DefaultLoadControl stable;
    private boolean useStable;

    public AdaptiveLoadControl(boolean adaptive) {
        this.adaptive = adaptive;
        this.allocator = new DefaultAllocator(true, ALLOCATION_SIZE);
        this.startup = build(allocator, adaptive ? BufferProfile.STARTUP : BufferProfile.ORIGINAL);
        this.stable = build(allocator, adaptive ? BufferProfile.STABLE : BufferProfile.ORIGINAL);
    }

    private static DefaultLoadControl build(DefaultAllocator allocator, BufferProfile profile) {
        return new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(profile.minBufferMs(), profile.maxBufferMs(),
                        profile.bufferForPlaybackMs(),
                        profile.bufferForPlaybackAfterRebufferMs())
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
    }

    /** 当前档位参数；关闭自适应时恒为 {@link BufferProfile#ORIGINAL}。 */
    public BufferProfile currentProfile() {
        if (!adaptive) {
            return BufferProfile.ORIGINAL;
        }
        return useStable ? BufferProfile.STABLE : BufferProfile.STARTUP;
    }

    public boolean isStable() {
        return adaptive && useStable;
    }

    public boolean isAdaptive() {
        return adaptive;
    }

    /** 切稳定档；关闭自适应时忽略。卡顿/重缓冲不会再调回 {@link #setStable(boolean)}。 */
    public void setStable(boolean stable) {
        if (!adaptive) {
            return;
        }
        this.useStable = stable;
    }

    /** 再次切台/重新 prepare 时回到起播档。 */
    public void resetToStartup() {
        this.useStable = false;
    }

    private DefaultLoadControl active() {
        return useStable ? stable : startup;
    }

    // ------------------------------------------------------------ LoadControl

    @Override
    public void onPrepared() {
        startup.onPrepared();
        stable.onPrepared();
    }

    @Override
    public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups,
            ExoTrackSelection[] trackSelections) {
        startup.onTracksSelected(renderers, trackGroups, trackSelections);
        stable.onTracksSelected(renderers, trackGroups, trackSelections);
    }

    @Override
    public void onStopped() {
        startup.onStopped();
        stable.onStopped();
    }

    @Override
    public void onReleased() {
        startup.onReleased();
        stable.onReleased();
    }

    @Override
    public Allocator getAllocator() {
        return allocator;
    }

    @Override
    public long getBackBufferDurationUs() {
        return active().getBackBufferDurationUs();
    }

    @Override
    public boolean retainBackBufferFromKeyframe() {
        return active().retainBackBufferFromKeyframe();
    }

    @Override
    public boolean shouldContinueLoading(long playbackPositionUs, long bufferedDurationUs,
            float playbackSpeed) {
        return active().shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed);
    }

    @Override
    public boolean shouldStartPlayback(long bufferedDurationUs, float playbackSpeed,
            boolean rebuffering, long targetBufferDurationUs) {
        return active().shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering,
                targetBufferDurationUs);
    }
}
