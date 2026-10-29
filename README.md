# tv-live

自研极简直播 App，目标设备为 Android 4.4.3 / API 19 电视（TCL L32F3800A 实机验证），同时保持普通 Android TV 可安装。

- 包名：`me.qnnp.tvlive`
- 版本：`1.0.52`（versionCode 53）
- 设计参考：`docs/design/tv-ui.html`（6 个状态：首次 / 列表 / 全屏 / 换台 / 菜单 / 返回）
- 规格：`docs/specs/v1.0.md`
- 批次规格：`docs/specs/batch-*.md`
- 台账：`docs/BACKLOG.md`

## 构建

```bash
./gradlew test assembleDebug
```

JDK 17；Android SDK `compileSdk 35`、`build-tools 35.0.0`。设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，或在本机 `local.properties` 写 `sdk.dir=<SDK 路径>`（该文件已 gitignore，不要提交）。

发布签名：keystore 与 properties 放仓外；默认读取 `~/.android-keys/tvlive-release.properties`，可用环境变量 `TVLIVE_RELEASE_KEYS` 指向其他路径。

## 设备

- 电视：adb 地址用本地环境变量 `TV_LIVE_ADB` 指定，例如 `adb connect "$TV_LIVE_ADB"`；真实地址不入库。
- 本地模拟器：`emulator-5554`（API 19 / 1920×1080 / 240dpi / 512MB）
- 设备 notes / root / 固件信息只放仓外本地文件，不提交。

## 状态

- 当前版本：`1.0.52`（versionCode 53），TCL L32F3800A 实机验收通过。
- 批次 1–36 已合并/发布，逐批台账见 `docs/BACKLOG.md`。
- 近期重点：开机自启（`BootActions` / `BootHooks` / 设备白名单）、切台方式（KEEP/DUAL/DIRECT）、渲染模式（SurfaceView/TextureView）、设置分层、性能优化（冷启动约 700ms、PSS 约 9.7MB）。
- 文档 / 代码 / 台账约定见 `docs/CONVENTIONS.md`，后续改动先读它。

## 设备与发布补充

- TV 安装：先卸载旧 debug 包（签名不同不能覆盖），再装 release；源/PIN 会清空，首次用手机扫码重新导入。
- 开机自启：TCL 需要 root 把包加入 `/system/etc/appwhitelist_bama.xml`，见仓外设备 notes；通用设备走标准 receiver。
- GitHub Release：<https://github.com/qnnp-me/tv-live/releases/tag/v1.0.52>

## License

MIT © 2026 qnnp-me，见 `LICENSE`。
