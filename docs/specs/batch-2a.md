# 批次 2a：播放引擎 + 播放控制器

- worktree：`tvlive-player`（从 `main` 切出，已含批次 1）
- 依赖：批次 1（`PlaylistModel.Channel/Variant`）已合并
- 不做：UI / Activity / HTTP / 二维码 / 设置 / 开机自启 / 设备安装

先读 `docs/specs/v1.0.md` §6、§10、§12 和本文件。

## 目标

把 ExoPlayer 2.18.1 与 MediaPlayer 兜底封成统一播放层，给批次 2b 的 UI 一个稳定 API：切台、同频道多变体轮换、解码器/容器失败自动换 MediaPlayer、错误分类、Surface 绑定、停止/释放。控制器用 JVM 单测（假引擎）覆盖策略；真实解码在 2b/设备验。

## 文件（约 8 个）

1. `app/build.gradle`（加 `exoplayer-core:2.18.1`、`exoplayer-hls:2.18.1`；不加 exoplayer-ui）
2. `app/src/main/java/me/qnnp/tvlive/player/PlaybackError.java`（纯 Java；`Kind` + 中文 message）
3. `.../player/PlaybackEngine.java`（接口 + Callback；`Surface`、prepare、stop、release、position/playing）
4. `.../player/EngineFactory.java`（接口；Default 实现创建 Exo/MediaPlayer）
5. `.../player/ExoPlaybackEngine.java`
6. `.../player/MediaPlayerPlaybackEngine.java`
7. `.../player/PlaybackController.java`（策略/变体/引擎切换；不做 UI）
8. `app/src/test/java/me/qnnp/tvlive/player/PlaybackControllerTest.java`（假引擎，JVM）

## 播放层 API（2b 用）

```java
interface PlaybackEngine {
  void setSurface(Surface surface);
  void prepare(Variant variant, Callback cb);
  void stop();
  void release();
  long getCurrentPosition();
  boolean isPlaying();

  interface Callback {
    void onPrepared();
    void onFirstFrame();
    void onError(PlaybackError error);
    void onEnded();
  }
}

interface EngineFactory {
  PlaybackEngine createExo();
  PlaybackEngine createMediaPlayer();
}

final class PlaybackController {
  PlaybackController(EngineFactory factory);
  void setListener(Listener listener);
  void setSurface(Surface surface);
  void play(Channel channel);   // 从 variant 0 开始
  void stop();
  void release();
  boolean isPlaying();
  long getCurrentPosition();
  PlaybackState getState();
  Channel getCurrentChannel();
  Variant getCurrentVariant();

  interface Listener {
    void onChannelStarted(Channel channel, Variant variant);
    void onFirstFrame(Channel channel);
    void onError(PlaybackError error, boolean willRetry);
    void onAllVariantsFailed(Channel channel, PlaybackError lastError);
    void onEnded(Channel channel);
  }
}
```

- `PlaybackState`：IDLE / PREPARING / PLAYING / ERROR（可放在 Controller 内部）。
- 线程：全部 main thread；Controller 不自己起线程。
- `setSurface` 可早于 `play`；新引擎创建时自动套用；`setSurface(null)` 允许。
- `play`：停止当前引擎；清空状态；variant 0 用 Exo；无 variant → `onAllVariantsFailed`。
- `stop`：停止/释放当前引擎，状态 IDLE；`release` 彻底释放；重复调用安全。

## 变体 / 引擎策略

每个 variant 的顺序：**先 Exo，再 MediaPlayer**。

- Exo 网络类错误（NETWORK/TIMEOUT/HTTP/TLS）：不试 MediaPlayer，直接下一个 variant（仍用 Exo）。
- Exo 解码器/容器类错误（DECODER/CONTAINER）：同一 variant 换 MediaPlayer；MediaPlayer 成功即停。
- MediaPlayer 解码器/容器错误：下一个 variant，回到 Exo。
- MediaPlayer 网络类错误：下一个 variant，回到 Exo。
- 所有 variant 失败：`onAllVariantsFailed(channel, lastError)`；不自动切频道（3 秒自动跳台由 2b UI 做）。
- 中间失败：`onError(error, willRetry=true)`（willRetry 恒为 true）；最终失败：`onAllVariantsFailed`，不再补 `onError(false)`。2b 的最终错误 OSD 用 `onAllVariantsFailed`。
- `onChannelStarted` 在引擎 `onPrepared` 时发；`onFirstFrame` 转发；`onEnded` 转发。
- 取消/重入：新的 `play` 必须让旧引擎的迟到回调失效（generation token 或置空 listener）。

## Exo 引擎

- `DefaultRenderersFactory(context).setEnableDecoderFallback(true)`。
- `DefaultHttpDataSource.Factory`：UA/Referer（Variant 字段）、connect/read 8s、不跨协议重定向。
- `DefaultMediaSourceFactory(dataSourceFactory)`：`.m3u8` HLS、TS 走 Progressive（DefaultExtractorsFactory）；如自动推断不可靠，按 URL 后缀显式建 `HlsMediaSource` / `ProgressiveMediaSource`。
- `DefaultLoadControl`：约 min 3000 / max 12000 / playback 1000 / after 2000；`setPrioritizeTimeOverSizeThresholds(false)`；512MB 不 OOM。
- `ExoPlayer.Builder(context, renderersFactory).setLoadControl(...).build()`；`setAudioAttributes(..., true)` 处理音频焦点；`playWhenReady=true`。
- `onPlaybackStateChanged(READY)` → onPrepared；`onRenderedFirstFrame` → onFirstFrame；`onPlayerError` → 分类；`onPlaybackStateChanged(ENDED)` → onEnded。
- `onTracksChanged` 检查视频/音频 MIME：
  - 视频 MIME 为 HEVC 且 `MediaCodecUtil.getDecoderInfos(mime,false,false)` 为空 → `DECODER`（message「本机不支持 HEVC」）。
  - 音频 AC-3/E-AC-3 且无解码器 → `DECODER`（「本机不支持 AC-3」）。
  - 不 crash；交给 Controller 换引擎/变体。
- Surface：`setVideoSurface(surface)`；release 清空。
- 错误分类：`ExoPlaybackException` 按 type + cause 链：
  - `InvalidResponseCodeException` 401/403 → HTTP_AUTH；404 → HTTP_NOT_FOUND；其他 → HTTP。
  - `SSLException`/`SSLProtocolException` → TLS。
  - `SocketTimeoutException` → TIMEOUT；`UnknownHostException`/`ConnectException`/`HttpDataSourceException` → NETWORK。
  - `DecoderInitializationException`/`DecoderException`/`MediaCodecUtil.DecoderQueryException` → DECODER。
  - `ParserException`/`UnrecognizedInputFormatException` → CONTAINER。
  - 其他 → UNKNOWN。

## MediaPlayer 引擎

- `MediaPlayer`；`setSurface`；`setAudioStreamType(STREAM_MUSIC)`。
- `setDataSource(url, headers)`（UA/Referer；API 19 可用）；`prepareAsync`。
- `OnPrepared` → start + onPrepared；`OnInfo MEDIA_INFO_VIDEO_RENDERING_START` → onFirstFrame；`OnCompletion` → onEnded；`OnError` → 分类。
- 错误：`MEDIA_ERROR_IO` → NETWORK；`MEDIA_ERROR_SERVER_DIED` → DECODER（解码器进程退出）；`MEDIA_ERROR_TIMED_OUT` → TIMEOUT；`MEDIA_ERROR_MALFORMED`/`UNSUPPORTED`/`NOT_VALID_FOR_PROGRESSIVE_PLAYBACK` → CONTAINER/DECODER；其他 UNKNOWN。
- `stop()` 清空 currentChannel；2b 如需停止后展示上次台，自行缓存。`onEnded` 后置 IDLE、不自动释放/换台，2b 决定重试或下一台。
- `reset/release`；重复 stop 安全。

## 控制器单测

`PlaybackControllerTest`（JVM，假引擎/假工厂，不碰 Android Surface）：

1. 两 variant，第一 Exo 网络错误 → 第二 Exo 成功；不试 MediaPlayer；`onChannelStarted` variant 2。
2. 一 variant，Exo 解码错误 → MediaPlayer 同 variant 成功；无 variant 2。
3. Exo 解码错误 + MediaPlayer 解码错误 → 下一 variant Exo；再失败 → 全失败。
4. 所有 variant 失败 → `onAllVariantsFailed` 一次，lastError 正确。
5. `stop`/`release` 释放当前引擎；重复调用安全。
6. 重入 `play` 后旧回调不生效（generation）。
7. `onError(willRetry=true)` 与最终失败次数正确。

## 验收

- `./gradlew test assembleDebug` 绿；控制器测试全过。
- 无 Activity/UI/HTTP；无 `exoplayer-ui`；无设备安装。
- APK 能构建；64K 方法数不超（若 D8 报错，先回报，不加 multidex 硬撑）。
- `git status` 干净；不提交 `local.properties`/build/.gradle。
- 只本地 commit；**严禁 push/合并/PR/tag**；不 adb/不碰电视/模拟器。
- 提交信息 `batch 2a: playback engines + controller`。

## 返回

commit hash、文件、测试结论/数量、错误分类与策略的未覆盖点、需管理会话决策的点。
