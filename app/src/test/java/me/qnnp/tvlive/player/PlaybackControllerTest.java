package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.media.MediaPlayer;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Variant;

/**
 * 控制器策略单测（JVM，假引擎 + 假调度器）：批次 2a 的引擎/variant 轮换、
 * 批次 7 的网络重试（1s/3s、可取消、不误报全量失败），以及批次 8 的引擎实例复用
 * （同类型切台/重试不重建，类型切换才替换）。
 *
 * <p>假引擎只记录调用与回调，不碰 Android Surface。</p>
 */
public class PlaybackControllerTest {

    private static final String V1 = "http://example.com/1.m3u8";
    private static final String V2 = "http://example.com/2.ts";

    private FakeFactory factory;
    private FakeScheduler scheduler;
    private RecordingListener listener;
    private PlaybackController controller;

    @Before
    public void setUp() {
        factory = new FakeFactory();
        scheduler = new FakeScheduler();
        listener = new RecordingListener();
        controller = new PlaybackController(factory, scheduler);
        controller.setListener(listener);
    }

    // ---------------------------------------------------------------- 1

    @Test
    public void exoNetworkErrorRetriesSameVariantThenTriesNextVariant() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        assertEquals(1, factory.created.size());
        FakeEngine engine = factory.last();
        assertEquals("exo", engine.type);
        assertEquals(V1, engine.variant.url());

        engine.fail(network("源一网络失败"));

        // 第一次重试：原 variant，延迟 1s，期间保留同一个引擎实例。
        assertTrue(controller.isRetrying());
        assertEquals(1, controller.getRetryCount());
        assertEquals("重试不得新建引擎", 1, factory.created.size());
        assertEquals(Arrays.asList(1000L), scheduler.delays);
        assertFalse("重试期间不得释放引擎", engine.stopCalled || engine.releaseCalled);
        assertEquals(1, listener.errors.size());
        assertTrue(listener.errors.get(0).willRetry);

        scheduler.runLast();
        assertEquals("重试复用同一实例", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals("重试必须留在同一 variant", V1, engine.variant.url());
        assertEquals(2, engine.prepareCount);

        engine.fail(network("源一第二次网络失败"));
        assertEquals(2, controller.getRetryCount());
        assertEquals(1, factory.created.size());
        assertTrue(controller.isRetrying());
        assertEquals(Arrays.asList(1000L, 3000L), scheduler.delays);

        scheduler.runLast();
        assertEquals(1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals(V1, engine.variant.url());
        assertEquals(3, engine.prepareCount);

        // 两次重试耗尽后才换下一个 variant，仍复用 Exo 实例、不创建 MediaPlayer。
        engine.fail(network("源一第三次网络失败"));
        assertEquals(1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals("网络错误重试耗尽后不应创建 MediaPlayer", "exo", engine.type);
        assertEquals(V2, engine.variant.url());
        assertEquals(4, engine.prepareCount);
        assertFalse(controller.isRetrying());
        assertEquals(0, controller.getRetryCount());

        engine.onPrepared();

        assertEquals(1, listener.started.size());
        assertSame(channel, listener.started.get(0).channel);
        assertEquals(V2, listener.started.get(0).variant.url());
        assertEquals(PlaybackController.PlaybackState.PLAYING, controller.getState());
        assertTrue(controller.isPlaying());
        assertFalse("换 variant 复用实例时不得释放", engine.releaseCalled);
        assertEquals(3, listener.errors.size());
        for (RecordingListener.ErrorRecord record : listener.errors) {
            assertTrue(record.willRetry);
        }
        assertEquals(0, listener.allFailed.size());
    }

    // ---------------------------------------------------------------- 2

    @Test
    public void exoDecoderErrorFallsBackToMediaPlayerOnSameVariant() {
        Channel channel = channel("CCTV-1", V1);

        controller.play(channel);
        FakeEngine exo = factory.last();
        assertEquals("exo", exo.type);

        exo.fail(decoder("解码器初始化失败"));

        assertEquals(2, factory.created.size());
        FakeEngine mp = factory.last();
        assertEquals("mp", mp.type);
        assertEquals("同一 variant 换 MediaPlayer", V1, mp.variant.url());

        mp.onPrepared();
        mp.onFirstFrame();

        assertEquals(1, listener.started.size());
        assertEquals(V1, listener.started.get(0).variant.url());
        assertSame(channel, listener.started.get(0).channel);
        assertEquals(1, listener.firstFrames.size());
        assertSame(channel, listener.firstFrames.get(0));
        assertEquals(0, listener.allFailed.size());
        assertEquals(PlaybackController.PlaybackState.PLAYING, controller.getState());
        assertTrue(exo.stopCalled && exo.releaseCalled);
        assertEquals(1, listener.errors.size());
        assertTrue(listener.errors.get(0).willRetry);

        mp.onEnded();
        assertEquals(1, listener.ended.size());
        assertSame(channel, listener.ended.get(0));
        assertEquals(PlaybackController.PlaybackState.IDLE, controller.getState());
        assertFalse(controller.isPlaying());
    }

    @Test
    public void channelWithoutVariantsFailsImmediately() {
        Channel channel = channel("空频道");

        controller.play(channel);

        assertEquals(0, factory.created.size());
        assertEquals(1, listener.allFailed.size());
        assertSame(channel, listener.allFailed.get(0).channel);
        assertEquals("该频道没有可用地址", listener.allFailed.get(0).error.message());
        assertEquals(PlaybackController.PlaybackState.ERROR, controller.getState());
    }

    // ---------------------------------------------------------------- 3

    @Test
    public void bothEnginesFailingOnVariantMovesToNextVariantExoThenAllFail() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        FakeEngine exo1 = factory.last();
        exo1.fail(decoder("Exo 解码失败"));

        FakeEngine mp1 = factory.last();
        assertEquals("mp", mp1.type);
        assertEquals(V1, mp1.variant.url());
        mp1.fail(decoder("MediaPlayer 解码失败"));

        assertEquals("应回到 Exo 试第二个 variant", 3, factory.created.size());
        FakeEngine exo2 = factory.last();
        assertEquals("exo", exo2.type);
        assertEquals(V2, exo2.variant.url());

        exo2.fail(decoder("Exo 第二源解码失败"));
        FakeEngine mp2 = factory.last();
        assertEquals("mp", mp2.type);
        assertEquals(V2, mp2.variant.url());

        mp2.fail(decoder("MediaPlayer 第二源解码失败"));

        assertEquals(4, factory.created.size());
        assertEquals(1, listener.allFailed.size());
        assertSame(channel, listener.allFailed.get(0).channel);
        assertEquals(PlaybackError.Kind.DECODER, listener.allFailed.get(0).error.kind());
        assertEquals("MediaPlayer 第二源解码失败", listener.allFailed.get(0).error.message());
        assertEquals(PlaybackController.PlaybackState.ERROR, controller.getState());
        assertFalse(controller.isPlaying());
        assertTrue(mp2.stopCalled && mp2.releaseCalled);
    }

    // ---------------------------------------------------------------- 4

    @Test
    public void allVariantsFailingReportsOnceWithLastError() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        factory.last().fail(tls("第一次失败"));
        assertEquals(0, listener.allFailed.size());

        PlaybackError last = PlaybackError.of(PlaybackError.Kind.HTTP_NOT_FOUND, "第二次失败");
        factory.last().fail(last);

        assertEquals(1, listener.allFailed.size());
        assertSame(last, listener.allFailed.get(0).error);
        assertSame(channel, listener.allFailed.get(0).channel);
        assertEquals(PlaybackController.PlaybackState.ERROR, controller.getState());
        assertEquals(0, listener.started.size());
    }

    // ---------------------------------------------------------------- 5

    @Test
    public void stopAndReleaseAreIdempotentAndFreeEngine() {
        Channel channel = channel("CCTV-1", V1);

        controller.setSurface(null);
        controller.play(channel);
        FakeEngine engine = factory.last();
        assertTrue("新引擎应套用早于 play 设置的 surface", engine.surfaceApplied);
        engine.onPrepared();
        assertTrue(controller.isPlaying());
        assertEquals(4321L, controller.getCurrentPosition());

        controller.stop();

        assertTrue(engine.stopCalled);
        assertTrue(engine.releaseCalled);
        assertEquals(PlaybackController.PlaybackState.IDLE, controller.getState());
        assertFalse(controller.isPlaying());
        assertEquals(0L, controller.getCurrentPosition());
        assertNull(controller.getCurrentChannel());
        assertNull(controller.getCurrentVariant());

        controller.stop();
        assertEquals("重复 stop 不应新建引擎", 1, factory.created.size());
        assertEquals(0, listener.allFailed.size());

        controller.play(channel);
        assertEquals(2, factory.created.size());
        FakeEngine second = factory.last();
        controller.release();
        assertTrue(second.stopCalled);
        assertTrue(second.releaseCalled);
        assertEquals(PlaybackController.PlaybackState.IDLE, controller.getState());

        controller.release();
        assertEquals("重复 release 不应新建引擎", 2, factory.created.size());
    }

    // ---------------------------------------------------------------- 6

    @Test
    public void reentrantPlayInvalidatesLateCallbacksFromOldAttempt() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);

        controller.play(first);
        FakeEngine engine = factory.last();
        // 保存上一次尝试的回调：切台后引擎实例被复用，但旧回调必须失效。
        PlaybackEngine.Callback stale = engine.callback;

        controller.play(second);
        assertEquals("同类型切台复用实例", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertFalse(engine.stopCalled || engine.releaseCalled);
        assertEquals(2, engine.prepareCount);
        assertEquals(V2, engine.variant.url());

        // 旧尝试的迟到回调必须全部失效。
        stale.onPrepared();
        stale.onFirstFrame();
        stale.onError(network("迟到的错误"));

        assertEquals(0, listener.started.size());
        assertEquals(0, listener.firstFrames.size());
        assertEquals(0, listener.errors.size());
        assertEquals(0, listener.allFailed.size());
        assertEquals(PlaybackController.PlaybackState.PREPARING, controller.getState());

        engine.onPrepared();

        assertEquals(1, listener.started.size());
        assertSame(second, listener.started.get(0).channel);
        assertEquals(V2, listener.started.get(0).variant.url());
        assertSame(second, controller.getCurrentChannel());
        assertSame(second.variants().get(0), controller.getCurrentVariant());
        assertEquals(PlaybackController.PlaybackState.PLAYING, controller.getState());
    }

    // ---------------------------------------------------------------- 7

    @Test
    public void intermediateErrorsAreRetryableAndFinalFailureIsNotAnError() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        factory.last().fail(decoder("1 Exo 解码"));     // exo v1 -> mp v1
        factory.last().fail(decoder("2 MP 解码"));      // mp v1 -> exo v2
        factory.last().fail(tls("3 Exo TLS"));          // exo v2 是最后一个 variant

        assertEquals(2, listener.errors.size());
        for (RecordingListener.ErrorRecord record : listener.errors) {
            assertTrue("中间失败都应 willRetry", record.willRetry);
        }
        assertEquals(1, listener.allFailed.size());
        assertEquals("3 Exo TLS", listener.allFailed.get(0).error.message());
        assertEquals(0, listener.started.size());

        // 最终失败之后迟到的回调不再改变状态。
        factory.last();
        assertEquals(PlaybackController.PlaybackState.ERROR, controller.getState());
    }

    @Test
    public void mediaPlayerErrorCodesAreClassified() {
        assertEquals(PlaybackError.Kind.DECODER,
                MediaPlayerPlaybackEngine.classify(MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0).kind());
        assertEquals(PlaybackError.Kind.NETWORK,
                MediaPlayerPlaybackEngine.classify(MediaPlayer.MEDIA_ERROR_IO, 0).kind());
    }

    // ---------------------------------------------------------------- 批次 7：网络重试

    @Test
    public void tlsErrorSwitchesVariantWithoutRetry() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        FakeEngine engine = factory.last();
        engine.fail(tls("TLS 握手失败"));

        assertEquals("TLS 不重试", 0, scheduler.delays.size());
        assertFalse(controller.isRetrying());
        assertEquals("换 variant 复用 Exo 实例", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals("exo", engine.type);
        assertEquals(V2, engine.variant.url());
        assertEquals(1, listener.errors.size());
        assertTrue(listener.errors.get(0).willRetry);
        assertEquals(0, listener.allFailed.size());
    }

    @Test
    public void http404AndDecoderErrorsSkipNetworkRetry() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        FakeEngine exo = factory.last();
        exo.fail(PlaybackError.of(PlaybackError.Kind.HTTP_NOT_FOUND, "HTTP 404"));
        assertEquals("404 不重试", 0, scheduler.delays.size());
        assertFalse(controller.isRetrying());
        assertEquals(1, factory.created.size());
        assertEquals(V2, exo.variant.url());

        // 解码错误也不进入网络重试：同一 variant 直接换 MediaPlayer。
        controller.play(channel);
        assertEquals("重新 play 同类型仍复用实例", 1, factory.created.size());
        assertSame(exo, factory.last());
        assertEquals(V1, exo.variant.url());
        exo.fail(decoder("解码器初始化失败"));
        assertEquals("解码错误不延迟重试", 0, scheduler.delays.size());
        assertFalse(controller.isRetrying());
        assertEquals("类型切换才重建", 2, factory.created.size());
        assertEquals("mp", factory.last().type);
        assertEquals(V1, factory.last().variant.url());
    }

    @Test
    public void timeoutAndUnknownAreRetryableOnSameVariant() {
        Channel channel = channel("CCTV-1", V1);

        controller.play(channel);
        FakeEngine engine = factory.last();
        engine.fail(PlaybackError.of(PlaybackError.Kind.TIMEOUT, "连接超时"));
        assertTrue(controller.isRetrying());
        assertEquals(1, controller.getRetryCount());
        assertEquals(Arrays.asList(1000L), scheduler.delays);

        scheduler.runLast();
        assertEquals("超时重试复用实例", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals("exo", engine.type);
        assertEquals(V1, engine.variant.url());
        assertEquals(2, engine.prepareCount);

        engine.fail(PlaybackError.of(PlaybackError.Kind.UNKNOWN, "未知网络错误"));
        assertTrue(controller.isRetrying());
        assertEquals(2, controller.getRetryCount());
        assertEquals(Arrays.asList(1000L, 3000L), scheduler.delays);
        assertEquals(0, listener.allFailed.size());
    }

    @Test
    public void manualPlayCancelsPendingRetry() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);

        controller.play(first);
        FakeEngine engine = factory.last();
        engine.fail(network("源一网络失败"));
        assertTrue(controller.isRetrying());
        FakeScheduler.Task pending = scheduler.tasks.get(0);

        controller.play(second);

        assertTrue("手动换台必须取消挂起重试", pending.cancelled);
        assertEquals(0, scheduler.activeCount());
        assertFalse(controller.isRetrying());
        assertEquals("同类型切台复用实例", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals(V2, engine.variant.url());
        assertEquals(2, engine.prepareCount);
        assertFalse(engine.stopCalled || engine.releaseCalled);

        // generation 双保险：即使已取消的任务被误执行，也不能再启动旧频道。
        scheduler.forceRun(pending);
        assertEquals("旧重试任务不得创建引擎", 1, factory.created.size());
        assertSame(second, controller.getCurrentChannel());
    }

    @Test
    public void stopAndReleaseCancelPendingRetry() {
        Channel channel = channel("CCTV-1", V1, V2);

        controller.play(channel);
        factory.last().fail(network("第一次网络失败"));
        assertTrue(controller.isRetrying());

        controller.stop();

        assertEquals(0, scheduler.activeCount());
        assertFalse(controller.isRetrying());
        assertEquals(PlaybackController.PlaybackState.IDLE, controller.getState());

        controller.play(channel);
        factory.last().fail(network("第二次网络失败"));
        assertTrue(controller.isRetrying());

        controller.release();

        assertEquals(0, scheduler.activeCount());
        assertFalse(controller.isRetrying());
        assertEquals(PlaybackController.PlaybackState.IDLE, controller.getState());
    }

    @Test
    public void retryWindowDoesNotReportAllVariantsFailed() {
        Channel channel = channel("CCTV-1", V1);

        controller.play(channel);
        factory.last().fail(network("第一次网络失败"));

        assertTrue(controller.isRetrying());
        assertEquals("重试期间不得报全量失败", 0, listener.allFailed.size());

        scheduler.runLast();
        factory.last().fail(network("第二次网络失败"));
        assertTrue(controller.isRetrying());
        assertEquals(0, listener.allFailed.size());

        scheduler.runLast();
        factory.last().fail(network("第三次网络失败"));

        assertFalse(controller.isRetrying());
        assertEquals(1, listener.allFailed.size());
        assertSame(channel, listener.allFailed.get(0).channel);
        assertEquals(PlaybackController.PlaybackState.ERROR, controller.getState());
    }

    // ---------------------------------------------------------------- 批次 8：复用实例

    @Test
    public void channelSwitchReusesSameEngineInstance() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);

        controller.play(first);
        FakeEngine engine = factory.last();
        engine.onPrepared();
        assertEquals(1, listener.started.size());

        controller.play(second);

        assertEquals("factory 只应创建一次", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals(2, engine.prepareCount);
        assertEquals(V2, engine.variant.url());
        assertFalse("切台不得 stop/release 正在复用的引擎",
                engine.stopCalled || engine.releaseCalled);

        engine.onPrepared();
        assertEquals(2, listener.started.size());
        assertSame(second, listener.started.get(1).channel);
        assertEquals(V2, listener.started.get(1).variant.url());
        assertEquals(PlaybackController.PlaybackState.PLAYING, controller.getState());
    }

    @Test
    public void networkRetryReusesSameEngineInstance() {
        controller.play(channel("CCTV-1", V1));
        FakeEngine engine = factory.last();

        engine.fail(network("断网"));
        assertTrue(controller.isRetrying());

        scheduler.runLast();

        assertEquals("重试不得 new ExoPlayer", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertEquals(2, engine.prepareCount);
        assertEquals(V1, engine.variant.url());
        assertFalse(engine.stopCalled || engine.releaseCalled);
    }

    @Test
    public void mediaPlayerNetworkRetryReusesMediaPlayerEngineInstance() {
        controller.play(channel("CCTV-1", V1));
        FakeEngine exo = factory.last();
        exo.fail(decoder("Exo 解码失败"));

        FakeEngine mp = factory.last();
        assertEquals("mp", mp.type);
        assertEquals(2, factory.created.size());

        mp.fail(network("MediaPlayer 网络失败"));
        assertTrue(controller.isRetrying());

        scheduler.runLast();

        assertEquals("MediaPlayer 重试复用实例", 2, factory.created.size());
        assertSame(mp, factory.last());
        assertEquals(2, mp.prepareCount);
        assertEquals(V1, mp.variant.url());
        assertFalse(mp.stopCalled || mp.releaseCalled);
    }

    @Test
    public void exoToMediaPlayerSwitchReplacesEngineInstance() {
        controller.play(channel("CCTV-1", V1));
        FakeEngine exo = factory.last();
        assertEquals("exo", exo.type);

        exo.fail(decoder("Exo 解码失败"));

        assertEquals("类型切换必须重建", 2, factory.created.size());
        FakeEngine mp = factory.last();
        assertNotSame(exo, mp);
        assertEquals("mp", mp.type);
        assertEquals(V1, mp.variant.url());
        assertTrue(exo.stopCalled && exo.releaseCalled);
    }

    @Test
    public void playAfterReleaseCreatesNewEngineInstance() {
        Channel channel = channel("CCTV-1", V1);

        controller.play(channel);
        FakeEngine first = factory.last();
        first.onPrepared();

        controller.release();
        assertTrue(first.stopCalled && first.releaseCalled);

        // release 清掉 listener/surface，恢复后应得到全新引擎。
        listener = new RecordingListener();
        controller.setListener(listener);
        controller.play(channel);

        assertEquals(2, factory.created.size());
        FakeEngine second = factory.last();
        assertNotSame(first, second);
        assertFalse(second.releaseCalled);

        second.onPrepared();
        assertEquals(1, listener.started.size());
        assertEquals(PlaybackController.PlaybackState.PLAYING, controller.getState());
    }

    // ---------------------------------------------------------------- 批次 9：切台方式

    @Test
    public void directModeReusesEngineOnChannelSwitch() {
        Channel first = channel("CCTV-1", V1);
        Channel second = channel("CCTV-2", V2);

        controller.play(first);
        FakeEngine reused = factory.last();
        reused.onPrepared();

        controller.setSwitchMode(SwitchMode.DIRECT);
        controller.play(second);

        assertEquals("极速直切也必须复用引擎，避免反复创建 MediaCodec",
                1, factory.created.size());
        assertSame(reused, factory.last());
        assertFalse("切台不应 release 旧引擎", reused.releaseCalled);
        assertEquals(V2, reused.variant.url());
    }

    @Test
    public void controllerTreatsDualAsKeepBecauseOrchestrationIsExternal() {
        controller.play(channel("CCTV-1", V1));
        FakeEngine engine = factory.last();
        engine.onPrepared();

        // DUAL 的双引擎编排由 DualPlayback 负责；单控制器只按 KEEP 复用实例。
        controller.setSwitchMode(SwitchMode.DUAL);
        assertEquals(SwitchMode.KEEP, controller.getSwitchMode());
        controller.play(channel("CCTV-2", V2));

        assertEquals("控制器层不得为 DUAL 重建引擎", 1, factory.created.size());
        assertSame(engine, factory.last());
        assertFalse(engine.stopCalled || engine.releaseCalled);
    }

    @Test
    public void defaultModeIsKeepAndFirstFrameBoostDefaultsOff() {
        assertEquals(SwitchMode.KEEP, controller.getSwitchMode());
        assertFalse(controller.isFirstFrameBoost());
    }

    @Test
    public void firstFrameBoostIsPassedWhenCreatingExoEngine() {
        controller.setFirstFrameBoost(true);
        controller.play(channel("CCTV-1", V1));
        assertEquals(Boolean.TRUE, factory.lastExoBoost);

        controller.stop();
        controller.setFirstFrameBoost(false);
        controller.play(channel("CCTV-1", V1));

        assertEquals(2, factory.created.size());
        assertEquals(Boolean.FALSE, factory.lastExoBoost);
    }

    @Test
    public void firstFrameBoostChangeRebuildsOnNextChannelSwitch() {
        controller.setFirstFrameBoost(true);
        controller.play(channel("CCTV-1", V1));
        FakeEngine first = factory.last();
        first.onPrepared();
        assertEquals(Boolean.TRUE, factory.lastExoBoost);

        controller.setFirstFrameBoost(false);
        controller.play(channel("CCTV-2", V2));

        assertEquals("切换首帧加速后下一台重建引擎", 2, factory.created.size());
        assertTrue(first.stopCalled && first.releaseCalled);
        assertEquals(Boolean.FALSE, factory.lastExoBoost);
        assertEquals(V2, factory.last().variant.url());
    }

    // ---------------------------------------------------------------- 批次 14：音频焦点

    @Test
    public void audioFocusDefaultsOnAndAppliesToExistingAndNewEngines() {
        assertTrue("单引擎默认申请焦点", controller.isHandleAudioFocus());

        controller.play(channel("CCTV-1", V1));
        FakeEngine first = factory.last();
        assertTrue(first.handleAudioFocus);

        controller.setHandleAudioFocus(false);
        assertFalse("已建引擎立即透传", first.handleAudioFocus);

        // 类型切换（Exo→MediaPlayer）新建的引擎也继承当前目标值。
        first.fail(decoder("解码失败"));
        FakeEngine mediaPlayer = factory.last();
        assertEquals("mp", mediaPlayer.type);
        assertFalse("新引擎继承 standby 焦点开关", mediaPlayer.handleAudioFocus);

        controller.setHandleAudioFocus(true);
        assertTrue(mediaPlayer.handleAudioFocus);
    }

    // ---------------------------------------------------------------- 批次 15：网速

    @Test
    public void bandwidthListenerAppliesToCurrentAndRebuiltEngines() {
        CountingBandwidth listener = new CountingBandwidth();
        // 早于 play 设置：引擎创建后必须套用。
        controller.setBandwidthListener(listener);

        controller.play(channel("CCTV-1", V1));
        FakeEngine exo = factory.last();
        assertSame("新建引擎要套用网速回调", listener, exo.bandwidthListener);

        // 网络重试复用实例：仍指向同一个回调。
        exo.fail(network("源一网络失败"));
        scheduler.runLast();
        assertSame(exo, factory.last());
        assertSame(listener, factory.last().bandwidthListener);

        // 类型切换（Exo→MediaPlayer）重建引擎：新实例同样继承。
        exo.fail(decoder("解码失败"));
        FakeEngine mediaPlayer = factory.last();
        assertEquals("mp", mediaPlayer.type);
        assertSame("重建后的引擎要重新套用", listener, mediaPlayer.bandwidthListener);
    }

    @Test
    public void setBandwidthListenerDuringPlaybackReachesExistingEngine() {
        controller.play(channel("CCTV-1", V1));
        FakeEngine exo = factory.last();

        CountingBandwidth listener = new CountingBandwidth();
        controller.setBandwidthListener(listener);

        assertSame("已建引擎立即透传", listener, exo.bandwidthListener);
    }

    @Test
    public void rebuildOnNextPlayRecreatesEngineAfterStall() {
        Channel channel = channel("CCTV-1", V1);

        controller.play(channel);
        FakeEngine first = factory.last();
        first.onPrepared();
        assertEquals(1, factory.created.size());

        controller.rebuildOnNextPlay();
        controller.play(channel);

        assertEquals("卡死重试必须重建引擎", 2, factory.created.size());
        assertNotSame(first, factory.last());
        assertTrue(first.releaseCalled);
    }

    // ---------------------------------------------------------------- 辅助

    private static PlaybackError network(String message) {
        return PlaybackError.of(PlaybackError.Kind.NETWORK, message);
    }

    private static PlaybackError decoder(String message) {
        return PlaybackError.of(PlaybackError.Kind.DECODER, message);
    }

    private static PlaybackError tls(String message) {
        return PlaybackError.of(PlaybackError.Kind.TLS, message);
    }

    static Channel channel(String id, String... urls) {
        Channel channel = new Channel(id, id, "测试", null);
        for (String url : urls) {
            channel.addVariant(new Variant(url));
        }
        return channel;
    }

    /** 记录网速回调次数的假监听。 */
    static final class CountingBandwidth implements PlaybackEngine.BandwidthListener {

        int calls;
        long lastBytesPerSecond;

        @Override
        public void onBandwidth(long bytesPerSecond) {
            calls++;
            lastBytesPerSecond = bytesPerSecond;
        }
    }

    /** 假引擎：记录调用，测试代码手动驱动回调。 */
    static final class FakeEngine implements PlaybackEngine {

        final String type;
        Variant variant;
        PlaybackEngine.Callback callback;
        Surface lastSurface;
        boolean surfaceApplied;
        boolean stopCalled;
        boolean releaseCalled;
        int prepareCount;
        boolean playing;
        long position = 4321L;
        /** 最近一次 setVolume；双画面断言 standby 恒 0、前台为 1。 */
        float volume = 1f;
        /** 最近一次 setHandleAudioFocus；双画面断言只有前台为 true。 */
        boolean handleAudioFocus = true;
        /** 批次 15：控制器套用的网速回调。 */
        PlaybackEngine.BandwidthListener bandwidthListener;

        FakeEngine(String type) {
            this.type = type;
        }

        @Override
        public void setSurface(Surface surface) {
            this.lastSurface = surface;
            surfaceApplied = true;
        }

        @Override
        public void setVolume(float volume) {
            this.volume = volume;
        }

        @Override
        public void setHandleAudioFocus(boolean handleAudioFocus) {
            this.handleAudioFocus = handleAudioFocus;
        }

        @Override
        public void setBandwidthListener(PlaybackEngine.BandwidthListener listener) {
            this.bandwidthListener = listener;
        }

        @Override
        public void prepare(Variant variant, Callback callback) {
            this.variant = variant;
            this.callback = callback;
            prepareCount++;
        }

        @Override
        public void stop() {
            stopCalled = true;
            playing = false;
        }

        @Override
        public void release() {
            releaseCalled = true;
            playing = false;
        }

        @Override
        public long getCurrentPosition() {
            return playing ? position : 0L;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        void onPrepared() {
            playing = true;
            callback.onPrepared();
        }

        void onFirstFrame() {
            callback.onFirstFrame();
        }

        void fail(PlaybackError error) {
            playing = false;
            callback.onError(error);
        }

        void onEnded() {
            playing = false;
            callback.onEnded();
        }
    }

    /** 假调度器：记录延迟、由测试手动执行；cancel 标记后可断言。 */
    static final class FakeScheduler implements Scheduler {

        final class Task implements Cancelable {
            final Runnable action;
            final long delayMs;
            boolean cancelled;

            Task(Runnable action, long delayMs) {
                this.action = action;
                this.delayMs = delayMs;
            }

            @Override
            public void cancel() {
                cancelled = true;
                tasks.remove(this);
            }
        }

        final List<Task> tasks = new ArrayList<Task>();
        final List<Long> delays = new ArrayList<Long>();

        @Override
        public Cancelable schedule(Runnable action, long delayMs) {
            Task task = new Task(action, delayMs);
            tasks.add(task);
            delays.add(delayMs);
            return task;
        }

        void runLast() {
            Task task = tasks.remove(tasks.size() - 1);
            assertFalse("已取消的任务不应被执行", task.cancelled);
            task.action.run();
        }

        void forceRun(Task task) {
            task.action.run();
        }

        int activeCount() {
            return tasks.size();
        }
    }

    /** 假工厂：按创建顺序记录引擎，便于断言尝试序列。 */
    static final class FakeFactory implements EngineFactory {

        final List<FakeEngine> created = new ArrayList<FakeEngine>();
        Boolean lastExoBoost;
        /** 置 true 后下一次建 Exo 抛 OutOfMemoryError（模拟双画面开不了第二路）。 */
        boolean oomOnNextCreate;

        @Override
        public PlaybackEngine createExo() {
            if (oomOnNextCreate) {
                oomOnNextCreate = false;
                throw new OutOfMemoryError("test: 第二路解码器分配失败");
            }
            FakeEngine engine = new FakeEngine("exo");
            created.add(engine);
            return engine;
        }

        @Override
        public PlaybackEngine createExo(boolean firstFrameBoost) {
            lastExoBoost = firstFrameBoost;
            return createExo();
        }

        @Override
        public PlaybackEngine createMediaPlayer() {
            FakeEngine engine = new FakeEngine("mp");
            created.add(engine);
            return engine;
        }

        FakeEngine last() {
            return created.get(created.size() - 1);
        }
    }

    /** 记录监听者事件。 */
    static final class RecordingListener implements PlaybackController.Listener {

        static final class Started {
            final Channel channel;
            final Variant variant;

            Started(Channel channel, Variant variant) {
                this.channel = channel;
                this.variant = variant;
            }
        }

        static final class ErrorRecord {
            final PlaybackError error;
            final boolean willRetry;

            ErrorRecord(PlaybackError error, boolean willRetry) {
                this.error = error;
                this.willRetry = willRetry;
            }
        }

        static final class Failed {
            final Channel channel;
            final PlaybackError error;

            Failed(Channel channel, PlaybackError error) {
                this.channel = channel;
                this.error = error;
            }
        }

        final List<Started> started = new ArrayList<Started>();
        final List<Channel> firstFrames = new ArrayList<Channel>();
        final List<ErrorRecord> errors = new ArrayList<ErrorRecord>();
        final List<Failed> allFailed = new ArrayList<Failed>();
        final List<Channel> ended = new ArrayList<Channel>();

        @Override
        public void onChannelStarted(Channel channel, Variant variant) {
            started.add(new Started(channel, variant));
        }

        @Override
        public void onFirstFrame(Channel channel) {
            firstFrames.add(channel);
        }

        @Override
        public void onError(PlaybackError error, boolean willRetry) {
            errors.add(new ErrorRecord(error, willRetry));
        }

        @Override
        public void onAllVariantsFailed(Channel channel, PlaybackError lastError) {
            allFailed.add(new Failed(channel, lastError));
        }

        @Override
        public void onEnded(Channel channel) {
            ended.add(channel);
        }
    }
}
