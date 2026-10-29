# tv-live — Agent 指南

## 先读

- 先读 `README.md`（构建/安装入口）和 `docs/CONVENTIONS.md`（硬约束：文档落点、批次台账、厂商适配分层、隐私、提交规则）；改代码/文档前先读后者。
- 行为契约在 `docs/specs/v1.0.md`；当前版本 `1.0.52`。公开仓库历史从该版本快照开始，`docs/BACKLOG.md` 中更早的 commit hash 不可解析。

## 构建与测试

- 单模块 Android 原生 Java（`:app`），无 Compose / AndroidX UI / XML layout；UI 在 `app/src/main/java/me/qnnp/tvlive/ui/` 用 framework View 搭。JDK 17、Gradle 8.9、AGP 8.7.3、Java 8。
- 设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，或本机 `local.properties` 写 `sdk.dir=...`（该文件已 gitignore）。
- 单测：`./gradlew :app:testDebugUnitTest --tests 'me.qnnp.tvlive.ui.KeyRouterTest'`
- 合并/发布门禁：`./gradlew clean test assembleDebug`；发布用 `assembleRelease`。当前 645 tests，不得减少。
- 无 CI；lint 不阻断（`abortOnError=false`），门禁必须本地跑 Gradle。
- 测试是 JVM 单测（`returnDefaultValues=true`）；Android/UI 依赖逻辑不要直接测，抽纯 Java 类（参考 `KeyRouter`、`UiState`、`ListRowRefresh`）。

## 架构与硬约束

- 单 Activity：`MainActivity`。TV/横屏用 `TvLayout` + 根层 `NavView`；手机竖屏用 `PhoneLayout`。
- 导航是独立 `PopupWindow`；设置/首启/浮层在 `uiLayer` 的 `PopupWindow`。不要把导航或浮层塞进 `TvLayout`：TCL 4.4 的 HWC 会让全宽 SurfaceView 压住同窗口 View。
- 按键全部走 `ui/KeyRouter` + `ui/UiState`；列表/菜单 View 不拿 Android 焦点，焦点是 Activity 状态（`focusInGroups` / `focusIndex` / `adapter.setFocusVisible`）。改方向键或 Back 前先看 `KeyRouterTest`、`UiStateTest`。
- 厂商适配：开机广播只在 `boot/BootActions`；启动前厂商动作只在 `boot/BootHooks` / `BootLaunchHook`。禁止把厂商包名/action 写进 `MainActivity` / `BootLauncher` / `TvLayout`；通用设备必须降级为 no-op。
- API19/TCL 约束：`/sdcard` 不可用（用 `getFilesDir()`）；TLS 1.0；不能声明 `android:banner`；targetSdk 28 是兼容选择。

## 设备验证

- 真机地址不入库，用本地环境变量 `TV_LIVE_ADB`；API19 AVD 为 `tv-api19`（1920×1080 / 240dpi）。
- API19 安装：`adb install -r <apk>`；不要加 `-g`（4.4 的 adb 不认）。
- debug / release 签名不同，互相覆盖会清空源/PIN；电视侧载用 release 包。
- TCL 遥控注入较慢，导航自动收起（5/8/15s）可能在多键测试中途触发，误判为「Back 收起了导航」。把整串按键和截图放进一次 `adb shell 'input keyevent …; sleep …'`，或临时调大自动收起。
- UI 改动必须看 API19 或真机截图并至少迭代一次；截图/APK 放 `/tmp/opencode/`，不进仓库。

## 改动与发布

- 行为变化：先改 `docs/specs/v1.0.md`，再改代码，最后补 `docs/BACKLOG.md`（真实命令、测试数、风险）。不要新增 `NOTES.md` / `TODO.md` 等散文件。
- 提交：`batch N: ...` 或 `fix: ...`，一次一批/一个修复。
- 发布：同时改 `app/build.gradle` 的 `versionCode` + `versionName` 和 README 版本；`./gradlew clean test assembleRelease`；打 tag `vX.Y.Z`；`gh release create vX.Y.Z <apk>` 上传 APK（产物在 `app/build/outputs/apk/release/app-release.apk`）。
- 签名：仓外 properties（默认 `~/.android-keys/tvlive-release.properties`，可用 `TVLIVE_RELEASE_KEYS` 覆盖）；keystore/密码绝不入库。keys 缺失时 release 仍能 assemble，但产物未签名。
- 隐私：不写内网 IP、设备序列号、PIN/token、本机绝对路径。公开 `main` 只用 1.0.52 快照后的历史；不要 push `private-main` 或旧 worktree 分支（origin 的默认 push refspec 只含 `main` + tags）。
