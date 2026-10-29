# 批次 2c：设置 / 首启静态页 / 开机自启 / 返回系统桌面 / 图标

- worktree：`tvlive-shell`（从 `main` 切出，已含 2b）
- 依赖：批次 2b 已合并
- 不做：HTTP 服务 / 手机页 / 二维码生成 / 源 URL 导入（批次 3）；完整手机遥控（v1.1）；默认桌面（TCL 拒 HOME，v1.1 独立包）
- 设计参考：`docs/design/tv-ui.html` 的「首次」页；2c 只做静态布局 + URL/PIN 占位，3 再接线

## 目标

把 2b 的无源占位换成首启设置页；加设置页；开机自启开关；「返回系统桌面」；App 图标。2b 的 UI/播放不改，只做壳层。

## 文件（约 9 个）

1. `AndroidManifest.xml`（`BootReceiver` 默认 disabled、图标；**无任何 HOME 声明**）
2. `boot/BootReceiver.java`（`BOOT_COMPLETED`/`QUICKBOOT_POWERON`；检查开关；启动 MainActivity）
3. `boot/HomeController.java`（返回系统桌面：bama `.Launcher` 优先，缺失回 Home 选择器）
4. `ui/SetupView.java`（首启：手机地址/PIN/二维码占位/在本机输入地址；2c 显示占位，3 接线）
5. `ui/SettingsView.java`（源状态/重新加载/清除；开机自启；返回系统桌面；关于；3 再补源导入/QR/PIN）
6. `prefs/AppPrefs.java`（bootAutostart 默认 false；pendingSourceUrl；lastError）
7. `ui/MainActivity.java`（无源 → SetupView；菜单设置 → SettingsView；boot intent；无源静默空态）
8. `res/values/strings.xml` + `res/mipmap-*/ic_launcher.png`（多密度 PNG，无 banner/adaptive icon；styles 如需）

## 首启设置页

- 布局按设计「首次」：brand「直播」；「还没有频道源」；手机地址 + 6 位 PIN + 二维码占位；「在本机输入地址」。
- 2c 用占位值/静态二维码框；3 换成真实 IP/端口/PIN/二维码并起 HTTP 服务。
- 「在本机输入地址」打开系统 IME 的 URL 输入框；2c 可保存 URL 字符串/尝试基础导入，或提示 3 接入；不阻塞首启布局验收。
- 无 WiFi：显示「请先连接 WiFi」，二维码/地址不可用。
- 导入成功后 3 自动切列表；2c 不需要模拟。

## 设置页

- 源：当前源标签/频道数/最后更新（SourceInfo）、重新加载、清除；「更换源」留 3（手机页/URL）。
- 开机自启：默认关；开关调用 `PackageManager.setComponentEnabledSetting` 启停 BootReceiver；持久化。
- 返回系统桌面：显式拉 `com.tcl.bama.launcher/.Launcher`；不存在则回系统 Home 选择器。不做默认桌面开关。
- 关于：版本、当前 IP/端口/PIN（3 接线前显示占位）、诊断/最近错误。
- 菜单「设置」从 2b 的占位改为打开 SettingsView。

## 开机自启

- Manifest：`RECEIVE_BOOT_COMPLETED` 已有；receiver `android:enabled="false"` 默认；`BOOT_COMPLETED`/`QUICKBOOT_POWERON`。
- 开关启用后才收广播；`onReceive` 检查 prefs；启动 MainActivity（`FLAG_ACTIVITY_NEW_TASK`）。
- 开机有源+上次台 → 全屏；无源/网络未就绪 → SetupView/列表重试；不黑屏。
- 不做后台常驻服务；不 root；现代 Android 后台启动限制按 v1.0 风险。

## 系统桌面（不做默认桌面）

- 主 APK 不声明 HOME。TCL PackageVerifier 拒绝任何第三方 HOME（`activity-alias` 与普通 activity 均 `INSTALL_FAILED_VERIFICATION_FAILURE`，已用最小 APK 在 TV 坐实）。
- 只保留「返回系统桌面」：显式拉 `com.tcl.bama.launcher/.Launcher`；失败回 `CATEGORY_HOME` 选择器。
- 双击返回保持 2b 的 finish；不因桌面改变退出语义。
- 默认桌面留 v1.1 独立包（通用 Android TV）；TCL 预计同样拒绝，不承诺。

## 图标 / 清单

- 多密度 PNG `res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`；`android:icon`；无 `android:banner`、无 adaptive icon/roundIcon。
- 不新增权限；不加 HOME 声明。
- `BootReceiver` `android:exported="true"`；API 19 兼容。

## 验收

- `./gradlew test assembleDebug` 绿（29 tests 不回归）。
- 模拟器 1920×1080 + TV 4.4.3：
  - 清数据/无源 → SetupView；有源 → 列表；菜单设置 → SettingsView。
  - 开机自启开关：启用后 receiver enabled；禁用后 disabled（`dumpsys package`/`pm` 用 root 查）。
  - 清单无 HOME：`aapt2 dump xmltree`/`pm dump` 无 `CATEGORY_HOME`；TV 能正常覆盖安装。
  - 「返回系统桌面」到 bama；bama 缺失时回 Home 选择器。
  - 图标出现在 bama/launcher；安装无 banner 崩溃。
  - 2b 的列表/全屏/加载/OSD/收藏/退出不回归。
- 不 push/合并/PR/tag；不 adb/设备由管理会话做；只本地 commit `batch 2c: settings, boot, home, icon`。

## 返回

commit hash、文件、测试结论、未覆盖/风险、需管理会话决策的点。
