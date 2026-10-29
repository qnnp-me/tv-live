# 批次 5：App 适配手机（触屏 / 竖屏 / 系统栏）

- 拆两段：**5a 手机竖屏 + 触屏** → **5b 全屏手势 / 系统栏 / 横屏**
- 依赖：4b 已合并
- 同一 APK，不拆包；TV 遥控路径必须保持
- 不做：手机遥控网页（已有）、投屏、手势自定义

## 设备判定

- `smallestScreenWidthDp < 600` → **手机模式**（优先；TV 通常 sw720dp）。
- 否则电视模式（现有 `UiState`/`KeyRouter`/导航浮层）。
- 手机模式仍保留 KeyRouter（蓝牙遥控/键盘/返回键），但触摸事件走 `TouchRouter`/`PhoneLayout`。

## 5a：手机竖屏布局 + 触屏

布局（竖屏 360–430dp 宽）：

```
[ 16:9 视频（全宽，当前台） ]
[ 横向分组 tab：收藏 央视 卫视 地方 港澳台 其他 ]
[ 频道列表（RecyclerView，行高 56dp，台号+名称+收藏星） ]
[ 底部：当前台/分组 + 提示 ]
```

- 视频播放当前台，不因列表滚动变化。
- 分组 tab 横向滚动，active 下划线；点击切组，列表回顶。
- 点频道行：选中 + 切预览（视频区立即播）；再点同一行/双击：全屏。
- 长按行：底部操作条/弹层（收藏、切换线路、频道信息、全屏播放），触屏按钮 ≥44px。
- 列表原生滚动、惯性；下拉不刷新。
- 设置/首启：单列、可滚动、触屏点击；首启二维码在窄屏下方居中；输入用系统 IME。
- 横屏手机：复用 TV 布局（44% 导航浮层），触屏点行=切预览、双击=全屏；`→` 操作列可点。
- 状态栏：沉浸式；视频不压状态栏；底部不压手势条。

## 5b：全屏 / 系统栏 / 旋转

- 全屏：点画面出 2.5s OSD + 半透明返回按钮；系统返回/手势退全屏回列表。
- 音量：手机用硬件音量键（App 不拦截左右）；TV 保留左右音量条。
- 旋转：竖屏↔横屏不重建播放器/Surface；布局切换时保留当前台与列表位置。
- 设置/首启在横竖屏都可滚动、不裁字。
- 深色状态栏/导航栏图标（API 19 有限，至少保持黑底）。

## 文件（预计）

- `ui/PhoneLayout.java`（竖屏布局）
- `ui/TouchRouter.java` 或 MainActivity 触摸监听
- `ui/UiState.java`（增加 PHONE 模式/布局类型）
- `ui/MainActivity.java`（判定、布局切换、触摸事件、旋转）
- `ui/SettingsView/SetupView`（窄屏尺寸）
- `values-sw600dp`/`values` dimens/strings
- 测试：设备判定、触摸路由（点/双击/长按）、旋转状态保留

## 验收

- `./gradlew test assembleDebug` 绿；238 tests 不回归。
- 模拟器 `wm size 1080x1920` + `wm density 420`（sw≈411dp）截图：竖屏布局、分组 tab、点台切预览、双击全屏、长按操作条、设置/首启滚动。
- `wm size 1920x1080` 回电视布局，TV 回归导航/按键不破坏。
- 真机手机（用户）确认触屏手感、返回手势、旋转。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit。

## 风险

- 手机 GPU/内存差异：继续 SurfaceView，不用 TextureView（TV 已证卡顿）。
- 触摸与遥控焦点混用：触摸事件只改播放/选中，不抢 KeyRouter 焦点；两者不互相打架。
- 竖屏视频 16:9 占高约 200dp，列表空间足够；小屏 320dp 需压缩行高/字号。

## 5a 实施记录

- 设备判定：`DeviceMode.isPhone(sw)`（纯 Java）；`isPhonePortrait(sw, portrait)` 决定是否用
  `PhoneLayout`。手机 = `sw < 600dp`，电视与横屏手机都用 `TvLayout`。
- 布局抽象：新增 `LiveLayout`（`FrameLayout` 子类）作为 Activity 的唯一调用面，
  `TvLayout` / `PhoneLayout` 都实现它；`MainActivity` 不再出现布局分支散落。
- 触摸：`TouchRouter`（纯 Java，300ms 双击窗）判定点行/再点同一行（双击）/长按；
  `PhoneLayout` 自己挂 ListView 的 item 事件，横屏手机由 Activity 给 `TvLayout` 列表挂同一套。
  触摸只改选中/播放/分组，不 `requestFocus`；遥控/键盘仍只走 `KeyRouter`。
- 手机场景：列表常驻 → `UiState` 进入 `NAV_SHOWN`，不做 8s 自动收起；Back 先关底部操作条，
  再按一次退出（沿用「再按一次返回退出」提示）。退出全屏回列表（不等同电视的 NAV_HIDDEN）。
- 全屏（5a 只做最小语义）：双击 / 再点当前台 / 操作条「全屏播放」进入，点画面或 Back 退出，
  暂不做 2.5s OSD、系统栏与旋转细化（5b）。
- 系统栏：仍沿用 3 键沉浸式（`applyImmersiveMode` 未改）；旋转/手势条留 5b。
- 旋转：manifest 去掉 `screenOrientation="landscape"`；`onConfigurationChanged` 里手机竖屏↔横屏
  重建布局并续播当前台（Surface 会重建，5b 再收敛为不重建播放器/保留列表位置）。
- 窄屏设置/首启：都是单列 + 滚动；设置行高 48dp / 源行 56dp（触摸热区 ≥44dp）；
  首启二维码在下方居中（60% 宽，上限 300dp）；输入仍用系统 IME 的 AlertDialog。
- 已知缺口（不计入 5a 验收）：手机主界面没有「设置」入口（只有遥控 MENU / 首启页可进设置），
  待 5b 或后续在底部/操作条补一个入口。

### 5a 修复：竖屏「没铺满」排查

现象（TV AVD + `wm size 1080x1920` + `wm density 420`）：截图里内容只有中间约 607–680px 宽，
tab/列表两侧黑边，视频区高度约 340 而不是 608。

实测结论：**`PhoneLayout` 的几何是对的，窗口被系统缩放了**。

- 截图本身是 **1920×1080**（TV AVD 面板），应用内容 607×1080 居中，左右各 656px 黑。
- 截图里 tab 高 71px、行高 83px、tab 起点 y=342；420dpi 下应为 tab 126px、行 147px、视频 608px。
  71/126 = 83/147 = 342/608 = **0.5625 = 1080/1920**：整块 1080×1920 的画面被等比缩到 1920×1080 面板里。
- 也就是说应用按 1080 宽布局（video=608、tab 在 608），只是**窗口/显示被整体缩放并居中**；
  布局内部不存在 680 固定宽。Robolectric 以 1080×1920 测量 `PhoneLayout` 复核：
  videoStage = 1080×608、ListView 宽 1080、panels topMargin = 608。

修复（都是加固 + 可诊断，不改视觉）：

- `videoStage` 初始高度不再写 0：构造时按屏幕宽度给 16:9，`panels` 同步带 topMargin；
  `onMeasure` 统一走 `videoHeightFor(width)`（`width` 取本 View 测量宽，取不到再退 `getWidth()`/
  `displayMetrics.widthPixels`）。
- `AndroidManifest` 显式 `android:resizeableActivity="true"`：避免系统把「未声明方向」的 App 放进
  size-compat 沙箱（旧尺寸窗口 + 缩放），那正是「内容只占中间」的典型成因。
- 新增两行日志用于下次判定（无需再猜）：
  `TvLive: device@onCreate phone=… sw=… display=…` 和
  `TvLive: phone layout 1080x1920 video=608 dp=411x731 display=1080x1920@420`。
  若 `phone layout` 的宽 < `display` 宽 → 窗口被系统缩小（沙箱/多窗）；
  若两者都 1080 而截图仍窄 → 截图是面板缩放（`wm size` 在固定横屏 AVD 上的表现），不是布局问题。
- 几何单测：`PhoneLayoutGeometryTest`（1080→608、720→405、840/945 按比例、宽度未知不为负）。

模拟器验收建议：TV AVD 的物理面板是 1920×1080，`wm size 1080x1920` 只会改逻辑尺寸，
截图（面板）必然 letterbox；要验竖屏外观请用**竖屏面板的手机 AVD**，或以上面的日志为准。


## 5b 实施记录

- 共享视频视图：`SurfaceView` 改由 `MainActivity` 创建，`LiveLayout.attachSurface()` 交给当前布局
  （`PhoneLayout.videoStage` / `TvLayout.preview`）。旋转只把同一个实例搬到新布局，
  **播放器不重建、不重起播**（`RotationResume`：有引擎 → 只重绑 Surface；引擎已释放才回退重起播）。
  搬移时框架会 destroy/create 一次 surface，路径是「先 `setSurface(null)` 解绑 → 新布局
  `surfaceCreated` 重绑」，期间引擎继续解码（音频不断），不重新 prepare、不弹「加载中…」。
  `surfaceDestroyed` 用 `boundSurface` 身份判断，旧 Surface 的迟到回调不解绑新 Surface。
- 旋转状态保留：`playingChannelId` / `UiState` / 分组 / 焦点不动；列表新增 `ListAnchor`
  （首个可见行 + 偏移，`setSelectionFromTop(paddingTop + offset)` 还原），旋转后不跳顶。
  转屏后若原是全屏，会再给一次 OSD + 返回按钮，用户不用猜怎么退。
- 全屏（手机）：点画面出 2.5s OSD + 左上「返回」半透明按钮（48dp，`Overlays` 最上层，
  横竖屏一致）；OSD 可见时再点收起；返回按钮 / 系统返回 / 返回手势都退全屏回列表。
  OSD 计时独立成 `FullscreenOsd`（纯 Java，2.5s），与电视导航的 8s 自动收起互不影响。
  竖屏全屏保持 16:9 居中黑边（`PhoneLayout.applySurfaceFit`），进入全屏不强制旋转。
- 系统栏：`SystemInsets`（纯 Java）统一夹负数与「电视恒 0」；手机在 root 上装
  `OnApplyWindowInsetsListener`，把状态栏/刘海（top）、导航栏/手势条（bottom）与侧边 inset
  作为 padding（API 29+ 再合并 `getSystemGestureInsets()`，沉浸式下也能避开手势条）。
  视频不压状态栏、底栏不压手势条；横屏手机同一条路径（侧边栏落在 left/right）。
  手机窗口背景显式黑底（API 19 无图标明暗控制），电视仍用主题 ink。
- 设置入口：`PhoneLayout` 底栏第二行与横屏 `TvLayout` 底栏都加触摸「设置」（48dp，≥44dp 宽），
  点击 `openSettings()`；可见性由 `PhoneChrome.settingsEntryVisible` 统一判定
  （全屏/设置页/首启页隐藏，电视恒不显示，遥控 MENU 不变）。
- 音量：手机 `dispatchKeyEvent` 把 `VOLUME_UP/DOWN/MUTE` 直接交回系统（不拦截、不弹自绘音量条）；
  `KeyRouter` 本来就不消费这三个键（`KeyRouterTest.hardwareVolumeKeysAreNotConsumed`）。
  电视隐藏/全屏态遥控左右键的自绘音量条保持不变。
- 窄屏设置/首启：`SettingsView` 窄屏滚动区底部给固定提示行留出高度（横屏不再压最后一行）；
  二维码浮层按屏幕短边算尺寸，卡片套 `ScrollView`（超高可滚，不裁地址/PIN/提示）；
  `SetupView` 窄屏不再按旋转前的屏宽写死 `sub` 最大宽度。
- 纯 Java 单测（新增 26）：`SystemInsetsTest`（竖/横/手势/挖孔/负数/电视恒 0）、
  `FullscreenOsdTest`（2.5s 窗口/再点收起/重开计时）、`ListAnchorTest`（还原/夹取/空列表）、
  `RotationResumeTest`（在线只重绑、释放才重起播）、`PhoneChromeTest`（设置入口/返回按钮可见性）、
  `KeyRouterTest` 增补硬件音量键不消费、遥控左右音量保留。
- 门禁：`./gradlew clean test assembleDebug` ✅ 280 tests（5a 的 254 全绿，无回归）。
- 模拟器验收点（本批不 adb，留管理会话）：
  - `wm size 1080x1920` + `wm density 420`（sw≈411dp）：竖屏 16:9 + tab + 列表 + 底栏「设置」；
    双击全屏 → 点画面出 OSD/返回按钮 → 2.5s 自动收 → 返回按钮退全屏回列表；
    `wm size 1920x1080` 旋转：`TvLive: layout switched … (surface rebind only)` 日志、
    列表停在原行、当前台继续播、视频不重新握手。
  - 同一 AVD 关掉导航栏/手势条（`wm overscan` 或系统设置切换手势导航）：
    `TvLive: insets SystemInsets{…}` 日志的 bottom 与手势区一致，底栏不压手势条。
  - TV（sw720dp）：`phone=false`，inset 恒 0、无「设置」按钮、左右音量条 / MENU / 导航浮层不变。
- 已知风险：SurfaceView 跨布局搬移时框架必 destroy/create 一次 surface（约 1–2 帧），
  期间显示 videoStage 底色；若要在真机上彻底零重建，需要把视频层升到 root 常驻并用
  透明挖孔让布局透出（TCL 4.4 的 z 序无法在无设备条件下验证，本批不做）。

### 5b 修复 1：全屏点画面不出 OSD/返回按钮

现象：双击进全屏后点视频区（模拟器 x540,y900），只有加载角标，没有 2.5s OSD 和左上返回按钮；
Back 能回列表。

原因：全屏时视频区铺满，触摸落在 `SurfaceView` 上，`videoStage` 的 click 收不到。

修复（两条路径都盖上，且不会重复触发）：

- `VideoTapTouch`：给 `SurfaceView` 自己挂触摸监听，`ACTION_DOWN → ACTION_UP` 位移 ≤ touchSlop
  判为点按并消费整段手势（父容器不会再把同一次点按算成 click）。
- `PhoneLayout` 新增全屏触摸层 `videoTapLayer`（最后添加、透明、MATCH_PARENT，仅全屏 VISIBLE），
  `TvLayout` 同样有 `videoTapLayer`（预览之上、导航浮层之下，`fullscreen && 手机` 才 VISIBLE）。
- 两个布局的点击都只走 `listener.onVideoTap()` → `onFullscreenTap()`：第一次出 OSD + 返回按钮，
  OSD 可见时再点收起，2.5s 自动收；返回按钮 48dp，点击退全屏回列表。

### 5b 修复 2：旋转没走 layout switch（Activity 被重建）

现象：`wm size 1920x1080` + `wm density 240` 后 logcat 出现两次 `device@onCreate`（phone=true→false），
没有 `layout switched ... surface rebind only`。

原因：只声明了 `orientation|screenSize|keyboardHidden`；`wm size`/`wm density` 会改变
`smallestScreenWidthDp`（411dp ↔ 720dp，跨过 600dp 手机/电视分界）与 `screenLayout`/`density`，
这些没声明 → 系统重建 Activity。

修复：

- `AndroidManifest` 的 MainActivity 改为
  `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|navigation|density|fontScale|uiMode`
  （`density` 必须一起声明：只改密度同样会重建）。
- `onConfigurationChanged` 不再「非手机直接 return」：先按新配置重算
  `phoneDevice`/`phonePortrait`，设备类型或布局类型变了才 `switchLayout()`；同类型内的尺寸/密度变化
  只刷新 inset 并打 `config changed but layout unchanged`。
- 跨设备类型（手机↔电视）时列表行高/字号由 `phoneDevice` 在构造时决定，换一份
  `ChannelListAdapter`（收藏/焦点/播放台沿用）并收起手机底部操作条，再切布局。
- 日志验收：旋转/尺寸变化后应只有 `device@configChanged ...` + `layout switched: ... (surface rebind only)`，
  不再出现第二次 `device@onCreate`；当前台、播放状态、列表行位置不变。
- 门禁：`./gradlew clean test assembleDebug` ✅ 283 tests（280 全绿 + `VideoTapTouchTest` 3 个）。

### 5b 修复 3：旋转崩溃（child already has a parent）

现象：`wm size 1920x1080` + `wm density 240` 必现
`IllegalStateException: The specified child already has a parent`
at `TvLayout.attachSurface` ← `createLayout` ← `switchLayout` ← `onConfigurationChanged`。

原因：`switchLayout()` 只把旧布局从 rootView 摘掉，旧布局自己仍然持有共享 `SurfaceView`
（`videoStage` / `preview` 的子视图），再 attach 到新布局就撞「一个 View 两个父容器」。

修复：

- `LiveLayout.detachSurface()`：`PhoneLayout` / `TvLayout` 各自清空视频宿位并置空引用。
- `switchLayout()` 顺序固定为「解绑 Surface → `old.detachSurface()` → 兜底
  `parent.removeView(surfaceView)` → `rootView.removeView(old)` → `createLayout()`（attach）」。
- 两个 `attachSurface()` 开头再兜底一次 `view.getParent()` 摘除，任何调用顺序都不会崩。

### 5b 修复 4：竖屏全屏点画面还是不出 OSD/返回按钮

两处原因叠加：

1. 触摸分发不可靠：`SurfaceView` 在上面，`videoStage` 的 click 收不到（上一版加的
   `videoTapLayer` 也未必是命中者）。
2. 进入全屏时自动展示 OSD/返回按钮，2.5s 内的第一次点按被当成「收起」，
   观感就是「点了没反应」。

修复：

- `PhoneLayout` / `TvLayout` 在 `dispatchTouchEvent` 里用 `TapTracker` 先判点按
  （父层是唯一稳定入口）：竖屏全屏整屏判点按；横屏只在「手机 + 全屏」时拦。
- `MainActivity` 给竖屏 `PhoneLayout` 也注册统一的 `videoTapListener`（横竖屏同一份回调），
  布局内 `fireVideoTap()` 优先走它，5a 的 `Listener.onVideoTap` 保留兜底。
- 语义修正：进全屏/旋转后只自动出 2.5s 频道 OSD（`showFullscreenOsdOnly`），**返回按钮不自动出现**；
  点画面才出 OSD + 返回按钮（`overlays.isBackChromeVisible()` 判断），再点收起，2.5s 自动收。
- 门禁：`./gradlew clean test assembleDebug` ✅ 283 tests。
