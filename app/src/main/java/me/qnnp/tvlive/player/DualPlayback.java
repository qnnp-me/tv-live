package me.qnnp.tvlive.player;

import android.view.Surface;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 两条播放路径编排（批次 10，纯 Java，可在 JVM 单测）。
 *
 * <ul>
 *   <li><b>KEEP / DIRECT</b>：单控制器。KEEP 复用引擎，切换只换 MediaSource，旧帧保留到
 *       新台首帧（配合 TextureView 的 SurfaceTexture）；DIRECT 每次切台销毁重建。</li>
 *   <li><b>DUAL</b>：两个 {@link PlaybackController} 各绑一个 TextureView。当前台继续播，
 *       standby 以音量 0 起播新台；standby 首帧后音量切 1、alpha 交换（由 Activity 播放
 *       本类事件），旧前台 stop+release。失败保持当前台，不自动跳台。</li>
 * </ul>
 *
 * <p>槽位固定：A 绑第一个 TextureView、B 绑第二个；{@link #activeIsA()} 只决定谁是前台，
 * 交换时不重绑 Surface（避免打断正在出画的引擎）。收不到 Surface 时（旋转/后台）传 null，
 * 引擎不会因 null 主动清屏。</p>
 *
 * <p>线程与 {@link PlaybackController} 相同：全部方法/回调都在 main thread。</p>
 *
 * <p>批次 14：只有前台引擎申请音频焦点（standby 传 false），避免两路互抢把前台 pause；
 * 进 DUAL 前先过 {@link DualMemoryGate}；第二路 OOM/解码失败时彻底释放 standby 并退回
 * 保留画面（OOM 时当前台由主引擎重播一次），不留半初始化状态。</p>
 */
public final class DualPlayback {

    /** 本次 play 实际走的路径。 */
    public enum Result {
        /** 单控制器播放（KEEP/DIRECT、首播、重试、同台）。 */
        SINGLE,
        /** standby 已开始缓冲，等它的首帧后提交。 */
        DUAL,
        /** 双画面不可用（内存/解码器受限）：已回到单路，当前台已由主引擎恢复。 */
        FALLBACK
    }

    /** 事件出口；前台事件语义与 {@link PlaybackController.Listener} 一致。 */
    public interface Listener {

        void onChannelStarted(Channel channel, Variant variant);

        void onFirstFrame(Channel channel);

        void onError(PlaybackError error, boolean willRetry);

        void onAllVariantsFailed(Channel channel, PlaybackError lastError);

        void onEnded(Channel channel);

        /** 双画面已提交：alpha 已交换、前台引擎已换人，旧引擎已停。 */
        void onDualSwitched(Channel channel);

        /** 双画面切台失败：保持当前台（target 为失败目标，front 为仍在播的台）。 */
        void onDualAborted(Channel target, Channel front, PlaybackError error);

        /** 双画面不可用（OOM / 解码器）：已退回单路，调用方应把设置切回保留画面。 */
        void onDualUnsupported(Channel target, Channel front, PlaybackError error);
    }

    private static final PlaybackError STREAM_ENDED =
            PlaybackError.of(PlaybackError.Kind.UNKNOWN, "直播流结束");

    private final EngineFactory factory;
    private final Scheduler scheduler;
    private final DualMemoryGate.Budget memoryBudget;
    private Listener listener;

    private final Slot slotA;
    private Slot slotB;

    private Surface surfaceA;
    private Surface surfaceB;

    /** 前台是否 A；交换时翻转，槽位与 Surface 绑定不变。 */
    private boolean activeIsA = true;
    /** 是否 DUAL（存在 standby）。 */
    private boolean dual;
    private SwitchMode mode = SwitchMode.KEEP;
    /** 正在等待首帧的目标台 id；null 表示没有进行中的双画面切台。 */
    private String pendingId;
    /** 实时网速回调：两个槽位都装上，按「谁在缓冲/前台」决定是否上报。 */
    private PlaybackEngine.BandwidthListener bandwidthListener;

    public DualPlayback(EngineFactory factory, Listener listener) {
        this(factory, new HandlerScheduler(), listener, DualMemoryGate.systemBudget());
    }

    public DualPlayback(EngineFactory factory, Listener listener, DualMemoryGate.Budget memoryBudget) {
        this(factory, new HandlerScheduler(), listener, memoryBudget);
    }

    public DualPlayback(EngineFactory factory, Scheduler scheduler, Listener listener) {
        this(factory, scheduler, listener, DualMemoryGate.systemBudget());
    }

    public DualPlayback(EngineFactory factory, Scheduler scheduler, Listener listener,
                        DualMemoryGate.Budget memoryBudget) {
        if (factory == null) {
            throw new IllegalArgumentException("factory == null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler == null");
        }
        this.factory = factory;
        this.scheduler = scheduler;
        this.listener = listener;
        this.memoryBudget = memoryBudget != null ? memoryBudget : DualMemoryGate.systemBudget();
        slotA = new Slot();
        slotA.controller.setVolume(1f);
        syncAudioFocus();
    }

    // ------------------------------------------------------------------ 查询

    /** 前台控制器（当前出声/显示的那路）。 */
    public PlaybackController active() {
        return activeIsA || slotB == null ? slotA.controller : slotB.controller;
    }

    /** standby 控制器；非 DUAL 时为 null。 */
    public PlaybackController standby() {
        return standbySlot() == null ? null : standbySlot().controller;
    }

    public SwitchMode getMode() {
        return mode;
    }

    public boolean isDual() {
        return dual;
    }

    /** 是否正在等 standby 首帧（双画面切台进行中）。 */
    public boolean isSwitching() {
        return pendingId != null;
    }

    /** 前台槽位；Activity 用它同步 TextureVideoLayer 的 alpha。 */
    public boolean activeIsA() {
        return activeIsA;
    }

    // ------------------------------------------------------------------ 配置

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 切台方式：DUAL 建 standby；KEEP/DIRECT 释放 standby 并把前台归位到 slotA。
     * 只改变配置，不主动 play；模式切换后的重播由 Activity 负责（可接受一次短暂黑屏）。
     *
     * <p>进入 DUAL 前先过内存门槛（{@link DualMemoryGate}）：堆上限不足或低内存设备
     * 直接拒绝，保持保留画面并通过 {@link Listener#onDualUnsupported} 告知调用方。</p>
     */
    public void setMode(SwitchMode mode) {
        SwitchMode requested = mode == null ? SwitchMode.KEEP : mode;
        this.mode = requested;
        SwitchMode engineMode = requested == SwitchMode.DIRECT ? SwitchMode.DIRECT : SwitchMode.KEEP;
        slotA.controller.setSwitchMode(engineMode);
        if (slotB != null) {
            slotB.controller.setSwitchMode(SwitchMode.KEEP);
        }
        if (requested == SwitchMode.DUAL && !DualMemoryGate.allows(memoryBudget)) {
            // 内存不足：不进 DUAL，也不留半初始化的第二路。
            this.mode = SwitchMode.KEEP;
            setDual(false);
            if (listener != null) {
                listener.onDualUnsupported(null, active().getCurrentChannel(),
                        PlaybackError.of(PlaybackError.Kind.MEMORY, "设备内存不足，已使用保留画面"));
            }
            return;
        }
        setDual(requested == SwitchMode.DUAL);
    }

    public void setFirstFrameBoost(boolean enabled) {
        slotA.controller.setFirstFrameBoost(enabled);
        if (slotB != null) {
            slotB.controller.setFirstFrameBoost(enabled);
        }
    }

    /**
     * 实时网速回调：装到两路控制器上，之后新建的 standby 槽位也会自动装上。
     *
     * <p>双画面切台时只有 standby 在缓冲新台（前台还在播旧台），因此只上报 standby 的
     * 网速；没有进行中的切台时上报前台那一路。</p>
     */
    public void setBandwidthListener(PlaybackEngine.BandwidthListener listener) {
        this.bandwidthListener = listener;
        installBandwidthListener(slotA);
        if (slotB != null) {
            installBandwidthListener(slotB);
        }
    }

    private void installBandwidthListener(Slot slot) {
        if (slot == null) {
            return;
        }
        PlaybackEngine.BandwidthListener target = bandwidthListener;
        slot.controller.setBandwidthListener(target == null ? null : new SlotBandwidth(slot, target));
    }

    /**
     * 绑定两路 Surface：A 固定给第一个 TextureView，B 给第二个；SurfaceView 模式只传 A。
     * 传 null 表示暂时没有可用 Surface（旋转/后台），引擎不会因此清屏。
     */
    public void setSurfaces(Surface surfaceA, Surface surfaceB) {
        this.surfaceA = surfaceA;
        this.surfaceB = surfaceB;
        bindSurfaces();
    }

    /** 后台恢复：release() 会清掉 listener/surface，这里重新装回来（槽位不变）。 */
    public void restore() {
        slotA.installRelay();
        if (slotB != null) {
            slotB.installRelay();
        }
        slotA.controller.setVolume(1f);
        if (slotB != null) {
            slotB.controller.setVolume(0f);
        }
        bindSurfaces();
        slotA.controller.setSurface(surfaceA);
        if (slotB != null) {
            slotB.controller.setSurface(surfaceB);
        }
    }

    // ------------------------------------------------------------------ 播放

    /**
     * 播放/切台。
     *
     * @param force true 表示重试 / 卡顿恢复 / 切源，必须走单路（不占用 standby）
     * @return 实际路径；{@link Result#DUAL} 时调用方等待 {@link Listener#onDualSwitched}
     */
    public Result play(Channel channel, boolean force) {
        Slot standby = standbySlot();
        if (canDualSwitch(channel, force, standby)) {
            pendingId = channel.id();
            Channel front = active().getCurrentChannel();
            try {
                standby.controller.setVolume(0f);
                standby.controller.play(channel);
                return Result.DUAL;
            } catch (OutOfMemoryError error) {
                // 内存不够再开一路解码：彻底释放 standby、退回保留画面，
                // 并用主引擎把当前台重播一次，避免留在半初始化状态。
                PlaybackError failure = PlaybackError.of(PlaybackError.Kind.MEMORY,
                        "设备内存不足，已使用保留画面", error);
                return recoverFromDualFailure(standby, channel, front, failure);
            }
        }

        // 单路：取消进行中的 standby（例如重试/同台强制重播），前台按 KEEP/DIRECT 播。
        cancelStandby();
        active().play(channel);
        return Result.SINGLE;
    }

    /** 取消进行中的双画面切台（停止 standby），当前台不动；用于前台失败/自动跳台。 */
    public void cancelPendingSwitch() {
        cancelStandby();
    }

    /** 停止两路（清空列表/退出播放场景）。 */
    public void stop() {
        pendingId = null;
        slotA.controller.stop();
        if (slotB != null) {
            slotB.controller.stop();
            slotB.controller.setVolume(0f);
        }
    }

    /** 下一次 play 前两路都强制重建引擎（起播超时/解码器卡死兜底）。 */
    public void rebuildOnNextPlay() {
        slotA.controller.rebuildOnNextPlay();
        if (slotB != null) {
            slotB.controller.rebuildOnNextPlay();
        }
    }

    /** 当前前台引擎最近一帧视频时刻；standby 不参与。 */
    public long lastVideoFrameAtMs() {
        return active().lastVideoFrameAtMs();
    }

    /** 彻底释放两路（onStop/onDestroy）；之后仍可 restore()+play。 */
    public void release() {
        pendingId = null;
        slotA.controller.release();
        if (slotB != null) {
            slotB.controller.release();
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 一条固定槽位：控制器 + 它的事件转发；Surface 由 A/B 决定。 */
    private final class Slot {

        final PlaybackController controller;
        PlaybackController.Listener relay;

        Slot() {
            controller = new PlaybackController(factory, scheduler);
            installRelay();
        }

        void installRelay() {
            relay = new Relay(this);
            controller.setListener(relay);
        }
    }

    private final class Relay implements PlaybackController.Listener {

        private final Slot slot;

        Relay(Slot slot) {
            this.slot = slot;
        }

        @Override
        public void onChannelStarted(Channel channel, Variant variant) {
            if (isActive(slot) && listener != null) {
                listener.onChannelStarted(channel, variant);
            }
        }

        @Override
        public void onFirstFrame(Channel channel) {
            if (isActive(slot)) {
                if (listener != null) {
                    listener.onFirstFrame(channel);
                }
                return;
            }
            if (isStandby(slot)) {
                commitDual(slot, channel);
            }
        }

        @Override
        public void onError(PlaybackError error, boolean willRetry) {
            if (isActive(slot) && listener != null) {
                listener.onError(error, willRetry);
            }
        }

        @Override
        public void onAllVariantsFailed(Channel channel, PlaybackError lastError) {
            if (isActive(slot)) {
                if (listener != null) {
                    listener.onAllVariantsFailed(channel, lastError);
                }
            } else if (isStandby(slot)) {
                failDual(slot, channel, lastError);
            }
        }

        @Override
        public void onEnded(Channel channel) {
            if (isActive(slot)) {
                if (listener != null) {
                    listener.onEnded(channel);
                }
            } else if (isStandby(slot)) {
                failDual(slot, channel, STREAM_ENDED);
            }
        }
    }

    /**
     * 单槽位的网速转发：只有「正在缓冲的 standby」或「没有进行中切台时的前台」上报，
     * 避免双画面时两路数字互相覆盖。
     */
    private final class SlotBandwidth implements PlaybackEngine.BandwidthListener {

        private final Slot slot;
        private final PlaybackEngine.BandwidthListener target;

        SlotBandwidth(Slot slot, PlaybackEngine.BandwidthListener target) {
            this.slot = slot;
            this.target = target;
        }

        @Override
        public void onBandwidth(long bytesPerSecond) {
            if (isStandby(slot) || (isActive(slot) && !isSwitching())) {
                target.onBandwidth(bytesPerSecond);
            }
        }
    }

    private Slot standbySlot() {
        if (!dual || slotB == null) {
            return null;
        }
        return activeIsA ? slotB : slotA;
    }

    private boolean isActive(Slot slot) {
        return slot != null && slot == (activeIsA ? slotA : slotB);
    }

    private boolean isStandby(Slot slot) {
        Slot standby = standbySlot();
        // 双画面切台进行中才认 standby 的首帧/失败；没有 pending 时旧回调一律丢弃。
        return standby != null && slot == standby && pendingId != null;
    }

    private boolean canDualSwitch(Channel channel, boolean force, Slot standby) {
        if (!dual || standby == null || channel == null || force) {
            return false;
        }
        PlaybackController front = active();
        Channel current = front.getCurrentChannel();
        if (current == null || current.id().equals(channel.id())) {
            return false;
        }
        PlaybackController.PlaybackState state = front.getState();
        return state == PlaybackController.PlaybackState.PLAYING
                || state == PlaybackController.PlaybackState.PREPARING;
    }

    /** standby 首帧：交换音量与槽位，停掉旧前台（旧帧不参与显示）。 */
    private void commitDual(Slot standby, Channel channel) {
        if (channel == null || pendingId == null || !pendingId.equals(channel.id())) {
            // 迟到的旧 standby 首帧：不交换，避免把错误的台提到前台。
            return;
        }
        pendingId = null;
        standby.controller.setVolume(1f);

        Slot oldFront = activeIsA ? slotA : slotB;
        activeIsA = !activeIsA;
        // 角色换人：新前台申请音频焦点，旧前台立即放弃，避免两路互抢。
        syncAudioFocus();
        // 前台/待命角色换人：重装两组转发，之后的事件按新角色分派。
        slotA.installRelay();
        if (slotB != null) {
            slotB.installRelay();
        }
        oldFront.controller.stop();
        oldFront.controller.setVolume(0f);
        bindSurfaces();
        if (listener != null) {
            listener.onDualSwitched(channel);
        }
    }

    /** standby 失败：停止 standby，保持当前台；解码器不支持时永久退回保留画面。 */
    private void failDual(Slot standby, Channel channel, PlaybackError error) {
        if (standby != standbySlot() || pendingId == null) {
            return;
        }
        pendingId = null;
        PlaybackError failure = error != null
                ? error
                : PlaybackError.of(PlaybackError.Kind.UNKNOWN, "播放失败");
        if (failure.kind() == PlaybackError.Kind.DECODER) {
            Channel front = active().getCurrentChannel();
            standby.controller.setVolume(0f);
            // 解码器撑不住第二路：standby 的引擎/监听一并释放，前台槽位保持可用。
            releaseStandby(standby);
            setDual(false);
            restoreFrontSlot();
            if (listener != null) {
                listener.onDualUnsupported(channel, front, failure);
            }
            return;
        }
        standby.controller.stop();
        standby.controller.setVolume(0f);
        if (listener != null) {
            listener.onDualAborted(channel, active().getCurrentChannel(), failure);
        }
    }

    /**
     * OOM 恢复：standby 彻底释放（stop + release），退回单路，清空半初始化状态，
     * 再用主引擎把当前台重播一次。调用方随后会把设置切回保留画面。
     */
    private Result recoverFromDualFailure(Slot standby, Channel target, Channel front,
                                          PlaybackError failure) {
        pendingId = null;
        releaseStandby(standby);
        // 归位槽位并释放另一路；standby 可能是 A，release() 清掉了它的 listener/surface。
        setDual(false);
        restoreFrontSlot();
        if (front != null && !front.variants().isEmpty()) {
            try {
                // 主引擎重播：OOM 后不信任旧会话的半初始化状态。
                active().play(front);
            } catch (RuntimeException e) {
                // 重播本身失败也不能挡住「已回退」的告知。
            } catch (OutOfMemoryError e) {
                // 堆真的耗尽时保持单路，交给 Activity 的重播/错误处理兜底。
            }
        }
        if (listener != null) {
            listener.onDualUnsupported(target, front, failure);
        }
        return Result.FALLBACK;
    }

    /** 彻底释放备用控制器：stop() 释放引擎，release() 清掉监听/Surface（重播前会装回）。 */
    private void releaseStandby(Slot standby) {
        if (standby == null) {
            return;
        }
        try {
            standby.controller.stop();
        } catch (RuntimeException e) {
            // 引擎已坏也不能挡住回退。
        }
        try {
            standby.controller.release();
        } catch (RuntimeException e) {
            // 同上。
        }
    }

    /**
     * setDual(false) 之后前台固定回 A 槽位；若 A 刚被 {@link #releaseStandby} 释放过，
     * 监听/Surface 已被清空，重播前必须装回来，否则收不到起播/首帧事件。
     */
    private void restoreFrontSlot() {
        slotA.installRelay();
        slotA.controller.setSurface(surfaceA);
        slotA.controller.setVolume(1f);
    }

    /** 按当前角色同步音频焦点：只有前台申请，standby 不抢。 */
    private void syncAudioFocus() {
        slotA.controller.setHandleAudioFocus(activeIsA || slotB == null);
        if (slotB != null) {
            slotB.controller.setHandleAudioFocus(!activeIsA);
        }
    }

    private void cancelStandby() {
        pendingId = null;
        Slot standby = standbySlot();
        if (standby != null) {
            standby.controller.stop();
            standby.controller.setVolume(0f);
        }
    }

    private void setDual(boolean enabled) {
        if (dual == enabled) {
            return;
        }
        dual = enabled;
        pendingId = null;
        if (enabled) {
            if (slotB == null) {
                slotB = new Slot();
                slotB.controller.setSwitchMode(SwitchMode.KEEP);
                slotB.controller.setFirstFrameBoost(slotA.controller.isFirstFrameBoost());
                slotB.controller.setVolume(0f);
                installBandwidthListener(slotB);
            }
            // A 是前台、B 是 standby：只有 A 申请音频焦点。
            syncAudioFocus();
            bindSurfaces();
            return;
        }

        // 退出双画面：先把前台归位到 A，再释放备用路，避免把正在显示的 B 直接拆掉。
        Slot old = slotB;
        slotB = null;
        if (!activeIsA) {
            // 前台在 B：A 此时是 standby（早已 stop），本轮改为前台；重播由 Activity 负责。
            slotA.controller.stop();
            activeIsA = true;
            slotA.controller.setVolume(1f);
        }
        // B 不再存在，A 独占焦点。
        syncAudioFocus();
        if (old != null) {
            old.controller.stop();
            old.controller.release();
        }
        bindSurfaces();
        slotA.controller.setSurface(surfaceA);
    }

    private void bindSurfaces() {
        slotA.controller.setSurface(surfaceA);
        if (slotB != null) {
            slotB.controller.setSurface(surfaceB);
        }
    }
}
