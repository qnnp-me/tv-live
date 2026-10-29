package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 批次 10 双画面编排（JVM，假引擎 + 假调度器）。
 *
 * <p>覆盖：KEEP 切台复用引擎；DUAL standby 静音起播、首帧后交换并停旧引擎；失败保持
 * 当前台；解码器/内存不足退回保留画面；KEEP↔DUAL↔极速来回切换不遗留 standby；
 * Surface 按槽位 A/B 绑定。</p>
 */
public class DualPlaybackTest {

    private static final String V1 = "http://example.com/1.m3u8";
    private static final String V2 = "http://example.com/2.m3u8";
    private static final String V3 = "http://example.com/3.m3u8";

    private PlaybackControllerTest.FakeFactory factory;
    private PlaybackControllerTest.FakeScheduler scheduler;
    private RecordingListener listener;
    private DualPlayback playback;

    @Before
    public void setUp() {
        factory = new PlaybackControllerTest.FakeFactory();
        scheduler = new PlaybackControllerTest.FakeScheduler();
        listener = new RecordingListener();
        playback = new DualPlayback(factory, scheduler, listener);
    }

    // ---------------------------------------------------------------- KEEP

    @Test
    public void keepModeReusesEngineAcrossChannelSwitch() {
        playback.setMode(SwitchMode.KEEP);
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);

        assertEquals(DualPlayback.Result.SINGLE, playback.play(first, false));
        PlaybackControllerTest.FakeEngine engine = factory.last();
        engine.onPrepared();
        engine.onFirstFrame();

        assertEquals(DualPlayback.Result.SINGLE, playback.play(second, false));

        assertEquals("KEEP 切台不得新建引擎", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals(2, engine.prepareCount);
        assertEquals(V2, engine.variant.url());
        assertFalse("复用实例，旧帧留在 TextureView 上", engine.stopCalled || engine.releaseCalled);
        assertNull("KEEP 没有 standby", playback.standby());
    }

    // ---------------------------------------------------------------- DUAL

    @Test
    public void dualSwitchMutesStandbyAndKeepsFrontPlaying() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);

        // 首播没有可继续的前台，走单路。
        assertEquals(DualPlayback.Result.SINGLE, playback.play(first, false));
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();
        assertEquals(1f, front.volume, 0f);

        assertEquals(DualPlayback.Result.DUAL, playback.play(second, false));

        assertTrue(playback.isSwitching());
        assertEquals(2, factory.created.size());
        PlaybackControllerTest.FakeEngine standby = factory.last();
        assertEquals(V2, standby.variant.url());
        assertEquals("standby 必须静音缓冲", 0f, standby.volume, 0f);
        assertEquals("前台继续出声", 1f, front.volume, 0f);
        assertFalse("交换前不停旧引擎", front.stopCalled || front.releaseCalled);
        assertEquals(0, listener.switched.size());
        assertTrue("交换前前台仍是 A", playback.activeIsA());
    }

    @Test
    public void dualFirstFrameSwapsAlphaAndStopsOldEngine() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine standby = factory.last();
        standby.onPrepared();
        standby.onFirstFrame();

        assertFalse("首帧后交换到 B", playback.activeIsA());
        assertFalse(playback.isSwitching());
        assertEquals(1, listener.switched.size());
        assertSame(second, listener.switched.get(0));
        assertEquals("新前台出声", 1f, standby.volume, 0f);
        assertTrue("旧引擎必须停止并释放", front.stopCalled && front.releaseCalled);
        assertSame(standby, factory.last());
        assertSame(second, playback.active().getCurrentChannel());
        assertEquals("全程只创建两路引擎", 2, factory.created.size());
    }

    @Test
    public void dualFailureKeepsCurrentChannelAndStopsStandby() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine standby = factory.last();
        standby.onPrepared();
        // HTTP 404 不会重试，也不换引擎：直接判定全部失败。
        standby.fail(PlaybackError.of(PlaybackError.Kind.HTTP_NOT_FOUND, "404"));

        assertFalse(playback.isSwitching());
        assertEquals(1, listener.aborted.size());
        assertSame(second, listener.aborted.get(0));
        assertSame("失败时告知仍在播的当前台", first, listener.abortedFront.get(0));
        assertTrue("standby 必须停止", standby.stopCalled && standby.releaseCalled);
        assertFalse("当前台继续播，不得停止", front.stopCalled || front.releaseCalled);
        assertSame(first, playback.active().getCurrentChannel());
        assertTrue("失败不交换 alpha", playback.activeIsA());
        assertTrue("双画面设置保持", playback.isDual());
        assertEquals(0, listener.switched.size());
    }

    @Test
    public void decoderFailureFallsBackToKeepAndReleasesStandby() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine standby = factory.last();
        // Exo 解码失败 → 控制器同 variant 换 MediaPlayer；MediaPlayer 也解码失败 → 全失败。
        standby.fail(PlaybackError.of(PlaybackError.Kind.DECODER, "HEVC 不支持"));
        PlaybackControllerTest.FakeEngine mediaPlayer = factory.last();
        assertNotSame(standby, mediaPlayer);
        assertEquals("mp", mediaPlayer.type);
        mediaPlayer.fail(PlaybackError.of(PlaybackError.Kind.DECODER, "HEVC 不支持"));

        assertEquals(1, listener.unsupported.size());
        assertSame(second, listener.unsupported.get(0));
        assertSame("回退时告知仍在播的当前台", first, listener.unsupportedFront.get(0));
        assertFalse("退回单路", playback.isDual());
        assertNull(playback.standby());
        assertTrue(playback.activeIsA());
        assertFalse("当前台不得被停", front.stopCalled || front.releaseCalled);
        assertSame(first, playback.active().getCurrentChannel());
    }

    @Test
    public void decoderFailureWithActiveBReleasesStandbyAndKeepsFrontSlotUsable() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine engineA = factory.last();
        engineA.onPrepared();
        engineA.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine engineB = factory.last();
        engineB.onPrepared();
        engineB.onFirstFrame();
        assertFalse("B 是前台，A 是 standby", playback.activeIsA());

        // 第三台由 A 槽位起播：Exo/MediaPlayer 都解码失败 → 回退 KEEP。
        playback.play(channel("CCTV-3", V3), false);
        PlaybackControllerTest.FakeEngine exoStandby = factory.last();
        exoStandby.fail(PlaybackError.of(PlaybackError.Kind.DECODER, "HEVC 不支持"));
        PlaybackControllerTest.FakeEngine mpStandby = factory.last();
        assertEquals("mp", mpStandby.type);
        mpStandby.fail(PlaybackError.of(PlaybackError.Kind.DECODER, "HEVC 不支持"));

        assertEquals(1, listener.unsupported.size());
        assertSame(second, listener.unsupportedFront.get(0));
        assertFalse("退回单路", playback.isDual());
        assertNull(playback.standby());
        assertTrue("前台归位 A", playback.activeIsA());
        assertTrue("standby 引擎已释放", exoStandby.stopCalled && exoStandby.releaseCalled);
        assertTrue("standby 兜底引擎已释放", mpStandby.stopCalled && mpStandby.releaseCalled);
        assertTrue("旧前台 B 随退出 DUAL 释放", engineB.stopCalled && engineB.releaseCalled);

        // 前台槽位可用：重播后事件仍能到达编排层（release() 清掉的 relay 已装回）。
        assertEquals(DualPlayback.Result.SINGLE, playback.play(second, false));
        PlaybackControllerTest.FakeEngine replay = factory.last();
        replay.onPrepared();
        replay.onFirstFrame();
        assertEquals("首播 + 重播两路起播", 2, listener.started.size());
        assertEquals("standby 首帧只提交交换，不单独转发", 2, listener.firstFrames.size());
    }

    @Test
    public void outOfMemoryWhileStartingStandbyFallsBackAndReplaysFront() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        factory.oomOnNextCreate = true;
        assertEquals(DualPlayback.Result.FALLBACK, playback.play(second, false));

        assertEquals(1, listener.unsupported.size());
        assertSame(second, listener.unsupported.get(0));
        assertSame(first, listener.unsupportedFront.get(0));
        assertEquals("OOM 归类为内存不足", PlaybackError.Kind.MEMORY,
                listener.unsupportedError.get(0).kind());
        assertFalse(playback.isDual());
        assertNull(playback.standby());
        assertSame(first, playback.active().getCurrentChannel());
        assertEquals("没有为失败的目标台建出引擎", 1, factory.created.size());
        assertFalse("前台引擎不得被释放", front.stopCalled || front.releaseCalled);
        assertEquals("OOM 后主引擎重播当前台一次", 2, front.prepareCount);
        assertEquals(V1, front.variant.url());
    }

    @Test
    public void outOfMemoryWhileStandbyIsSlotAReplaysFrontOnMainEngine() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        Channel third = channel("CCTV-3", V3);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine engineA = factory.last();
        engineA.onPrepared();
        engineA.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine engineB = factory.last();
        engineB.onPrepared();
        engineB.onFirstFrame();
        assertFalse("交换后 B 是前台", playback.activeIsA());

        // 下一台由 A 槽位（现在是 standby）起播：OOM 时备用路是 A。
        factory.oomOnNextCreate = true;
        assertEquals(DualPlayback.Result.FALLBACK, playback.play(third, false));

        assertEquals(1, listener.unsupported.size());
        assertSame(second, listener.unsupportedFront.get(0));
        assertEquals(PlaybackError.Kind.MEMORY, listener.unsupportedError.get(0).kind());
        assertFalse(playback.isDual());
        assertNull(playback.standby());
        assertTrue("回退后前台归位 A", playback.activeIsA());
        assertSame("当前台仍是 B 上的那一台", second, playback.active().getCurrentChannel());
        assertTrue("旧前台的引擎随退出 DUAL 释放", engineB.stopCalled && engineB.releaseCalled);

        assertEquals("A 槽位重播需要新引擎", 3, factory.created.size());
        PlaybackControllerTest.FakeEngine replay = factory.last();
        assertEquals(V2, replay.variant.url());
        // relay 已装回：起播/首帧事件仍能到达编排层。
        replay.onPrepared();
        replay.onFirstFrame();
        // standby 的 onChannelStarted 不转发，所以这里只有首播 + 重播两次。
        assertEquals(2, listener.started.size());
        assertSame(second, listener.started.get(1));
        // standby 的首帧只用于提交交换，不单独转发；这里只有 A 的首帧 + 重播首帧。
        assertEquals(2, listener.firstFrames.size());
        assertSame(second, listener.firstFrames.get(1));
    }

    @Test
    public void memoryGateRejectsDualAndKeepsKeepMode() {
        factory = new PlaybackControllerTest.FakeFactory();
        scheduler = new PlaybackControllerTest.FakeScheduler();
        listener = new RecordingListener();
        // 512MB 级设备：堆上限只有 48MB，必然撑不住两路 1080p。
        playback = new DualPlayback(factory, scheduler, listener,
                DualMemoryGate.Budget.of(48L * 1024L * 1024L, 48, false));

        playback.setMode(SwitchMode.DUAL);

        assertFalse("内存不足不得进入 DUAL", playback.isDual());
        assertNull(playback.standby());
        assertEquals(SwitchMode.KEEP, playback.getMode());
        assertEquals(1, listener.unsupported.size());
        assertNull("门槛拒绝没有目标台", listener.unsupported.get(0));
        assertEquals(PlaybackError.Kind.MEMORY, listener.unsupportedError.get(0).kind());
        assertEquals("设备内存不足，已使用保留画面",
                listener.unsupportedError.get(0).message());

        // 单路仍可正常起播（回退结果可用）。
        assertEquals(DualPlayback.Result.SINGLE, playback.play(channel("CCTV-1", V1), false));
        assertEquals(1, factory.created.size());
    }

    @Test
    public void memoryGateAllowsDualOnNormalDevice() {
        factory = new PlaybackControllerTest.FakeFactory();
        scheduler = new PlaybackControllerTest.FakeScheduler();
        listener = new RecordingListener();
        playback = new DualPlayback(factory, scheduler, listener,
                DualMemoryGate.Budget.of(192L * 1024L * 1024L, 192, false));

        playback.setMode(SwitchMode.DUAL);

        assertTrue(playback.isDual());
        assertTrue("正常设备进入 DUAL 并建 standby", playback.standby() != null);
        assertEquals(0, listener.unsupported.size());
    }

    @Test
    public void audioFocusOnlyAppliesToFrontAcrossDualSwap() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        Channel third = channel("CCTV-3", V3);
        playback.setMode(SwitchMode.DUAL);

        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        assertTrue("首播/前台申请焦点", front.handleAudioFocus);
        front.onPrepared();
        front.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine standby = factory.last();
        assertFalse("standby 不抢焦点", standby.handleAudioFocus);
        assertTrue("前台保持焦点", front.handleAudioFocus);

        standby.onPrepared();
        standby.onFirstFrame();
        assertTrue("交换后新前台申请焦点", standby.handleAudioFocus);
        assertFalse("旧前台放弃焦点", front.handleAudioFocus);

        // 再切一台：现在的 standby 是 A 槽位，新引擎不抢焦点；B 仍是前台。
        playback.play(third, false);
        PlaybackControllerTest.FakeEngine newStandby = factory.last();
        assertNotSame(standby, newStandby);
        assertFalse(newStandby.handleAudioFocus);
        assertTrue(standby.handleAudioFocus);
    }

    @Test
    public void keepModeAlwaysRequestsAudioFocus() {
        playback.setMode(SwitchMode.KEEP);
        playback.play(channel("CCTV-1", V1), false);
        assertTrue(factory.last().handleAudioFocus);
    }

    @Test
    public void dualSwitchesOnlyForDifferentTargetAndNeverForForcedRetry() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        // 同台：直接复用，不启动 standby。
        assertEquals(DualPlayback.Result.SINGLE, playback.play(first, false));
        assertFalse(playback.isSwitching());
        assertEquals(1, factory.created.size());
        assertSame(front, factory.last());
        assertEquals(2, front.prepareCount);

        // 强制重播（卡顿恢复/换线路）：单路，不占用 standby。
        assertEquals(DualPlayback.Result.SINGLE, playback.play(second, true));
        assertFalse(playback.isSwitching());
        assertEquals(1, factory.created.size());
        assertSame(front, factory.last());
        assertEquals(V2, front.variant.url());
    }

    // ---------------------------------------------------------------- 模式切换

    @Test
    public void modeSwitchKeepToDualToDirectReleasesStandbyAndResetsSlot() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.setMode(SwitchMode.KEEP);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        playback.setMode(SwitchMode.DUAL);
        assertTrue("进入双画面创建 standby", playback.standby() != null);
        assertEquals("standby 只在 play 时才建引擎", 1, factory.created.size());
        playback.play(second, false);
        PlaybackControllerTest.FakeEngine standby = factory.last();
        standby.onPrepared();
        standby.onFirstFrame();
        assertFalse(playback.activeIsA());

        // DUAL → KEEP：standby（现在是旧前台）释放，前台归位 A，等 Activity 重播。
        playback.setMode(SwitchMode.KEEP);
        assertNull(playback.standby());
        assertTrue(playback.activeIsA());
        assertTrue("旧 B 引擎必须释放", standby.stopCalled && standby.releaseCalled);
        assertNull("A 槽位已被停，重播由 Activity 负责", playback.active().getCurrentChannel());

        // KEEP 重播：A 槽位建新引擎。
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine reborn = factory.last();
        assertNotSame(standby, reborn);
        assertEquals(3, factory.created.size());

        // KEEP → 极速：前台控制器改成 DIRECT，但切台仍复用引擎（避免 codec 反复创建）。
        playback.setMode(SwitchMode.DIRECT);
        playback.play(second, false);
        assertEquals("极速直切也复用引擎", 3, factory.created.size());
        assertSame(reborn, factory.last());
        assertFalse("切台不 release", reborn.releaseCalled);
        assertEquals(SwitchMode.DIRECT, playback.getMode());
    }

    @Test
    public void directModeReleasesStandbyWhenLeavingDual() {
        Channel first = channel("CCTV-1", V1);
        playback.setMode(SwitchMode.DUAL);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        playback.setMode(SwitchMode.DIRECT);

        assertNull(playback.standby());
        assertFalse(playback.isDual());
        assertTrue(playback.activeIsA());
        assertEquals(SwitchMode.DIRECT, playback.getMode());
    }

    // ---------------------------------------------------------------- Surface / 音量

    @Test
    public void surfacesFollowSlotsAcrossDualSwitch() {
        Surface surfaceA = textureSurface();
        Surface surfaceB = textureSurface();
        playback.setSurfaces(surfaceA, surfaceB);
        playback.setMode(SwitchMode.DUAL);

        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);
        playback.play(first, false);
        PlaybackControllerTest.FakeEngine slotAEngine = factory.last();
        assertSame("KEEP/首播绑 A", surfaceA, slotAEngine.lastSurface);
        slotAEngine.onPrepared();
        slotAEngine.onFirstFrame();

        playback.play(second, false);
        PlaybackControllerTest.FakeEngine slotBEngine = factory.last();
        assertSame("standby 绑 B", surfaceB, slotBEngine.lastSurface);
        slotBEngine.onPrepared();
        slotBEngine.onFirstFrame();

        // 交换后 B 是前台，A 变 standby；槽位绑定不换，避免重绑打断出画。
        assertSame(surfaceB, slotBEngine.lastSurface);
        assertSame(surfaceA, slotAEngine.lastSurface);

        // 退出双画面：前台回 A，Activity 重播后新引擎仍绑 A。
        playback.setMode(SwitchMode.KEEP);
        playback.play(first, false);
        assertEquals(3, factory.created.size());
        assertSame(surfaceA, factory.last().lastSurface);

        // 切 SurfaceView 模式：只给 A 传 SurfaceView 的 Surface。
        playback.setMode(SwitchMode.DIRECT);
        Surface surfaceView = textureSurface();
        playback.setSurfaces(surfaceView, null);
        playback.play(first, false);
        assertSame(surfaceView, factory.last().lastSurface);
    }

    @Test
    public void controllerVolumePassesThroughToEngineBeforeAndAfterPlay() {
        PlaybackController controller = new PlaybackController(factory, scheduler);
        Channel channel = channel("CCTV-1", V1);

        controller.setVolume(0f);
        controller.play(channel);
        PlaybackControllerTest.FakeEngine engine = factory.last();
        assertEquals("引擎创建时套用目标音量", 0f, engine.volume, 0f);

        controller.setVolume(1f);
        assertEquals("已建引擎透传", 1f, engine.volume, 0f);

        controller.setVolume(-3f);
        assertEquals(0f, controller.getVolume(), 0f);
        assertEquals(0f, engine.volume, 0f);

        controller.setVolume(9f);
        assertEquals(1f, controller.getVolume(), 0f);
    }

    // ---------------------------------------------------------------- 批次 15：网速

    @Test
    public void dualSwitchReportsStandbyBandwidthOnly() {
        playback.setMode(SwitchMode.DUAL);
        final List<Long> speeds = new ArrayList<Long>();
        playback.setBandwidthListener(new PlaybackEngine.BandwidthListener() {
            @Override
            public void onBandwidth(long bytesPerSecond) {
                speeds.add(bytesPerSecond);
            }
        });

        playback.play(channel("CCTV-1", V1), false);
        PlaybackControllerTest.FakeEngine front = factory.last();
        front.onPrepared();
        front.onFirstFrame();

        // 没有进行中的切台：只有前台一路的数字会到界面。
        front.bandwidthListener.onBandwidth(1000L);
        assertEquals(1, speeds.size());
        assertEquals(1000L, speeds.get(0).longValue());

        // 双画面切台：standby 在缓冲新台，前台旧台的回调不能盖掉它。
        assertEquals(DualPlayback.Result.DUAL, playback.play(channel("CCTV-2", V2), false));
        PlaybackControllerTest.FakeEngine standby = factory.last();
        front.bandwidthListener.onBandwidth(2000L);
        standby.bandwidthListener.onBandwidth(3000L);
        assertEquals(2, speeds.size());
        assertEquals(3000L, speeds.get(1).longValue());

        // 首帧后交换：新前台（原 standby）上报，旧前台不再上报。
        standby.onFirstFrame();
        front.bandwidthListener.onBandwidth(4000L);
        standby.bandwidthListener.onBandwidth(5000L);
        assertEquals(3, speeds.size());
        assertEquals(5000L, speeds.get(2).longValue());
    }

    // ---------------------------------------------------------------- 辅助

    /** JVM 单测没有真实 Surface：用 SurfaceTexture 包一份即可（假引擎只记引用）。 */
    private static Surface textureSurface() {
        return new Surface(new SurfaceTexture(0));
    }

    private static Channel channel(String id, String url) {
        Channel channel = new Channel(id, id, "测试", null);
        channel.addVariant(new Variant(url));
        return channel;
    }

    /** 记录编排层事件。 */
    static final class RecordingListener implements DualPlayback.Listener {

        final List<Channel> started = new ArrayList<Channel>();
        final List<Channel> firstFrames = new ArrayList<Channel>();
        final List<Channel> failed = new ArrayList<Channel>();
        final List<Channel> ended = new ArrayList<Channel>();
        final List<Channel> switched = new ArrayList<Channel>();
        final List<Channel> aborted = new ArrayList<Channel>();
        final List<Channel> abortedFront = new ArrayList<Channel>();
        final List<Channel> unsupported = new ArrayList<Channel>();
        final List<Channel> unsupportedFront = new ArrayList<Channel>();
        final List<PlaybackError> unsupportedError = new ArrayList<PlaybackError>();

        @Override
        public void onChannelStarted(Channel channel, Variant variant) {
            started.add(channel);
        }

        @Override
        public void onFirstFrame(Channel channel) {
            firstFrames.add(channel);
        }

        @Override
        public void onError(PlaybackError error, boolean willRetry) {
        }

        @Override
        public void onAllVariantsFailed(Channel channel, PlaybackError lastError) {
            failed.add(channel);
        }

        @Override
        public void onEnded(Channel channel) {
            ended.add(channel);
        }

        @Override
        public void onDualSwitched(Channel channel) {
            switched.add(channel);
        }

        @Override
        public void onDualAborted(Channel target, Channel front, PlaybackError error) {
            aborted.add(target);
            abortedFront.add(front);
        }

        @Override
        public void onDualUnsupported(Channel target, Channel front, PlaybackError error) {
            unsupported.add(target);
            unsupportedFront.add(front);
            unsupportedError.add(error);
        }
    }
}
