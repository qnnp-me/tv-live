# 批次 2b：TV UI 核心（列表 / 预览 / 全屏 / 遥控 / OSD / 菜单 / 退出）

- worktree：`tvlive-ui`（从 `main` 切出，已含批次 1 + 2a）
- 依赖：`PlaybackController`（批次 2a）已合并
- 不做：首启设置页、设置页、开机自启、默认桌面、HTTP、二维码、图标/品牌收尾（批次 2c / 3）
- 设计参考：`docs/design/tv-ui.html` 的「列表 / 全屏 / 换台 / 菜单 / 返回」5 个状态（「首次」页属 2c/3）
- 不做设备安装：实现只 build/test；管理会话装模拟器/TV、截图/按键验收

先读 `docs/specs/v1.0.md` §3、§6、§7、§10、§12 和本文件。

## 目标

把 2a 的播放层接成一个可用的 10-foot TV UI：左列表 + 右预览、选中 300ms 防抖、OK 全屏、干净全屏、整半点 1 分钟带秒时间、2.5s OSD、右侧菜单、收藏、上次频道、数字/CH/方向键、双击返回退出。首启无源只显示一个「还没有频道源」占位；2c 再换成设置页。

## 文件（约 9 个）

1. `app/src/main/AndroidManifest.xml`（加 `MainActivity`：LAUNCHER、landscape、configChanges、singleTask、theme；无 banner）
2. `res/values/styles.xml`（`Theme.TvLive`：Holo NoActionBar Fullscreen、黑底、无标题/无 overlay）
3. `res/values/colors.xml`（ink/surface/text/muted/tally/hairline）
4. `res/values/strings.xml`（列表/菜单/OSD/退出等文案）
5. `app/src/main/java/me/qnnp/tvlive/prefs/AppPrefs.java`（收藏 id 集合、上次频道 id、自动跳台默认 true；2c 再扩）
6. `.../ui/ChannelListAdapter.java`（分组过滤、收藏组、行视图：台号/名称/星；无台标）
7. `.../ui/TvLayout.java`（根 FrameLayout、左栏 32%、分组条、ListView、预览 16:9、now 信息、全屏扩展；单 SurfaceView）
8. `.../ui/Overlays.java`（OSD / 菜单 / 时钟 / 退出提示；内嵌视图，不引第三方）
9. `.../ui/MainActivity.java`（生命周期、加载源、选择/防抖、播放器接线、按键、收藏、上次台、自动跳台/卡顿、退出）

> 9 个文件略超 8；UI 是一个整体，拆开会造成同一 Activity/布局来回改，故本批按一个整体交付；逻辑量集中，管理会话逐屏验收。

## 数据 / 源

- 启动 `PlaylistRepository.load(this)`：
  - 成功 → 列表 + 预览；上次频道存在且在该源 → 直接全屏；否则列表。
  - `FileNotFoundException`/解析失败/无源 → 只显示「还没有频道源」占位（2c 替换为设置页）；不自动导入。
- 源由管理会话测试时预置：`/data/data/me.qnnp.tvlive/files/playlist.m3u`（root copy + chown）；App 不做 debug 导入分支。
- 组：收藏（虚拟，在最前）/ 央视 / 卫视 / 地方 / 港澳台 / 其他组 / 其他；组内源顺序；号码全局。
- 列表不加载台标；星标只显示已收藏；无永久 OSD。

## 布局 / 视觉

按 `docs/design/tv-ui.html`，实际用 dp/sp（不用 cqw）：

- 根 `FrameLayout`；左栏约 32%：brand「直播」、分组条、ListView、底部提示「OK 全屏 · 长按 OK 菜单（收藏）」。
- 右侧：16:9 `SurfaceView` + now 信息（台号/名称/分组/预览）；预览区黑底。
- 全屏：只把视频容器扩到根大小、隐藏左栏/now；**同一个 SurfaceView，不重建、不 reparent**；OSD/菜单/时钟/退出提示浮在根上。
- 颜色：ink `#070C0D`、surface `#0B1214`、text `#E8EDEA`、muted `#7E908B`、tally `#E36A4A`、hairline `rgba(230,236,232,.07)`；唯一强色 tally。
- 字号/行高：1080p 下频道名约 23sp、行高约 56–58dp；720p 用 dimens/限定资源避免裁字；列表可滚动。
- 选中行 full-bleed 背景 + 左侧 2dp tally 条 + 台号 accent；分组 active 下划线；无卡片/渐变装饰。
- 无台标、无永久频道名/时间/水印/状态栏；全屏只有画面 + 半时钟。

## 交互 / 状态

- 列表：上下移动；左/右切组；OK 进全屏；长按 OK 或 MENU 打开菜单；数字键 2s 缓冲直选；CH+/− 换台；Info 显示详情 OSD。
- 预览：选中后 300ms 防抖再 `controller.play(channel)`；连续移动取消上一个；列表焦点不因预览丢失。
- 全屏：上下/CH 切台；左右切组并跳到该组第一台；数字直选；OK 出 2.5s OSD；长按 OK/MENU 菜单；Back 回列表。
- OSD：左下角，台号/名称/分组（分辨率后续补）；2.5s；错误消息中文；不挡画面主体。
- 加载角标：预览/全屏右下角小提示「加载中…」；起播/切台 500ms 无首帧才出现；首帧/错误/菜单打开时隐藏；不挡 OSD/时钟/菜单。
- 时钟：全屏播放时，距最近 :00/:30 ≤30s 显示 `HH:mm:ss`，1s 刷新；右上角；菜单打开时隐藏；其余时间隐藏。
- 菜单：右侧浮层；左栏/全屏都能开；播放不中断；第一项 收藏/取消收藏，切换源（有变体才可点）、频道信息、重新加载列表、设置（本批占位提示「设置下一批接入」）、退出；Back 关闭。
- 收藏：菜单第一项；若遥控有红键/书签键，`PROG_RED`/`BOOKMARK` 直接切换；收藏后行尾星标 + 收藏组置顶；收藏按 channel id 存 SharedPreferences。
- 上次频道：每次成功起播存 id；启动恢复；源里没有则第一台。
- 退出：全屏 Back → 列表；列表第一次 Back 只显示 2s「再按一次返回退出」；2s 内再按才 `finish()`；菜单 Back 只关菜单；Home 只后台（2c 再改默认桌面分支）。
- 自动跳台：全屏 `onAllVariantsFailed` 后，自动跳台开启且连续失败 <5 → 3s 倒计时 OSD（任意键取消）→ 下一台；列表内只提示不跳；连续 5 次停手。
- 卡顿：全屏/预览播放中，`getCurrentPosition()` 10s 不前进 → 按失效处理（重试当前台一次，再失败则自动跳台/提示）。
- 无源/解析失败：显示占位文案 + 错误原因；不闪退；2c 替换为设置页。
- 生命周期：`onStop` 保存上次台、`controller.release()`；`onStart` 重新加载并恢复；`FLAG_KEEP_SCREEN_ON`；音频焦点由 2a 引擎处理。
- 视频/全屏不重建 Surface；`surfaceCreated` 绑定、`surfaceDestroyed` 解绑。

## 与 2a 的接线

```java
PlaybackController controller = new PlaybackController(new EngineFactory.Default(getApplicationContext()));
controller.setListener(...);
controller.setSurface(surfaceHolder.getSurface());
controller.play(channel);
```

- `onChannelStarted`：全屏/预览的 now 信息更新；不在启动时弹 OSD。
- `onFirstFrame`：隐藏加载角标。
- `onError(err, true)`：全屏/列表显示「换源/重试」短提示；不打断用户操作。
- `onAllVariantsFailed`：按上面的自动跳台/提示策略；这是最终错误 OSD 的唯一来源（2a 不发 `onError(false)`）。
- `onEnded`：直播流意外结束 → 同 `onAllVariantsFailed` 的处理；不自动释放。
- `stop()` 会清 currentChannel；UI 自己保存 lastChannel/selected。

## 不做

- 首启设置页/QR/HTTP/源导入（2c/3）。
- 设置页、开机自启、默认桌面、图标/品牌收尾（2c）。
- 台标、EPG、录制、投屏、云。
- 设备安装/adb/TV 修改。

## 验收

- `./gradlew test assembleDebug` 绿（现有 24 tests 不回归）。
- `git status` 干净；不提交 `local.properties`/build/.gradle；无 scratch。
- 只本地 commit；严禁 push/合并/PR/tag；不 adb/不碰 TV/模拟器。
- 提交信息 `batch 2b: tv ui core`。
- 管理会话设备验收：装模拟器 1920×1080@240dpi + TV 4.4.3；预置 `cn.m3u`；截图列表/全屏/OSD/菜单/退出；按键（方向/OK/长按/数字/CH/Back）；收藏/上次台；播放/切台/失效；内存；720p 不裁字。

## 返回

commit hash、文件、测试结论、UI 未覆盖/风险、需管理会话决策的点。
