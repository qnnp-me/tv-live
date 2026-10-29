# 项目约定（文档 / 代码 / 台账）

这份文件是给后续开发（包括 AI 会话）看的硬约束。**改代码前先读，改完按这里落文档。**
违反约定的改动即使能跑，也会在评审时退回。

## 1. 文档落点（只放这些地方，不要新建散落文件）

| 内容 | 位置 |
|---|---|
| 产品规格 / 行为契约 | `docs/specs/v1.0.md`（按章节改，不要另起一份 spec） |
| 单批次的详细方案 | `docs/specs/batch-*.md`（只有需要展开时才写） |
| UI 设计参考 | `docs/design/` |
| 批次台账（谁做了什么、commit、门禁、风险） | `docs/BACKLOG.md` |
| 设备/root/电视固件相关 | 仓外设备 notes（本地路径不入库），**不写进仓库** |
| 本文档 | `docs/CONVENTIONS.md` |

- 临时调查、截图、APK 一律放 `/tmp/opencode/`，不进仓库。
- 不新增 `NOTES.md`、`TODO.md`、`SUMMARY.md` 之类的散文件；待办进 `docs/BACKLOG.md` 的「暂不做 / 已推迟」。

## 2. 批次台账格式（`docs/BACKLOG.md`）

每完成一个批次（合并或本地 commit）就加一行，列固定：

```
| 批次 | worktree | 内容（一句话） | 状态 | commit | 门禁/证据 | 风险/待验 |
```

- 状态只能是：`done（本地）` / `merged` / `released` / `已回退`。
- 门禁必须写真实命令和测试数（如 `./gradlew clean test assembleDebug` ✅ 617 tests）。
- 风险要写「没验什么」「什么情况下会回退」，不能只写「无」。
- 回退/删除的批次也要留行，写清楚为什么删（例：批次 22 音频重播已回退）。

## 3. 厂商适配的硬约束

开机自启的分层见 `docs/specs/v1.0.md` §9，这里只列禁止项：

- **禁止**把厂商包名、广播 action、系统组件名直接写进 `MainActivity` / `BootLauncher` / `TvLayout`。
- 开机信号（哪些广播能唤醒）只进 `boot/BootActions`。
- 启动前厂商动作（切信源、切音频路由）只进 `boot/BootHooks` + `BootLaunchHook` 实现。
- 设备层 root 改动（白名单、remount、系统属性）只进设备 notes；App 代码只保留「尝试 + 失败静默」。
- 任何厂商适配必须能在通用设备上降级为 no-op，不能因为探测失败就丢自启。

## 4. 代码注释规范

遇到厂商/固件差异时，注释必须写清五件事（参考 `TclHomeHook` / `TclSourceSwitch` / `BootFrontCheckReceiver`）：

1. **设备**：型号 + 芯片 + Android 版本（如 TCL L32F3800A / MT5507 / 4.4.3）
2. **现象**：用户能看到什么（有画无声、黑屏有声、导航不出来）
3. **原因**：代码/系统层为什么发生（HWC、sourcemananger 未注册、白名单清理）
4. **处理**：这段代码做了什么、为什么这样做
5. **回退**：开关/常量在哪，改回什么值能恢复旧行为

不要写「修复 bug」「兼容 TCL」这种没信息量的注释。

## 5. 开关与可回退

- 任何绕过/特殊路径都要有常量或开关（如 `BootLaunchPlan.DIRECT_LAUNCH`、`HOME_SWITCH_MAX_ATTEMPT`）。
- 删功能时不要留死代码；确实要保留的旧链路，注释写明「默认不走，开关恢复」。
- 不允许把「暂时注释掉」当成删除。

## 6. 安全 / 隐私

- 仓库里不写 PIN、token、keystore 密码、内网 IP、设备序列号。
- 设备 IP、白名单路径、恢复命令只放仓外 notes。
- 日志不打印频道 URL 的完整 query、不打印 token。

## 7. 改动流程

1. 行为变化先改 `docs/specs/v1.0.md` 对应章节（或先在会话里定稿），再改代码。
2. 一批一提交，commit message 用 `batch N: <一句话>`；修复用 `fix: <现象>`。
3. 合并前必须跑 `./gradlew clean test assembleDebug`，测试数不得减少；减少要说明删了哪些用例。
4. 涉及 TCL 的改动，合并前至少跑一次 API 19 模拟器；能上电视的，优先上电视并记录日志证据。
5. 收尾时同步三处：`docs/BACKLOG.md`（台账）、`docs/specs/v1.0.md`（行为）、设备 notes（root/固件）。

## 8. 当前关键开关（改之前先看这里）

| 开关 | 位置 | 作用 |
|---|---|---|
| `BootLaunchPlan.DIRECT_LAUNCH` | `boot/BootLaunchPlan.java` | true=按 `BootActionPolicy` 延迟直接拉起；false=走等桌面链路 |
| `BootActionPolicy` 分类/延迟/窗口 | `boot/BootActionPolicy.java` | 开机 action 的拉起延迟（BOOT 1s / 网络 6s / 用户 4s / USB+存储 15s）、窗口与同类去重 |
| `BootLaunchPlan.BOOT_HOME_SWITCH_DELAY_MS` | 同上 | 切信源后延迟拉起（默认 2500ms） |
| `BootFrontPlan.HOME_SWITCH_MAX_ATTEMPT` | `boot/BootFrontPlan.java` | 前台确认窗口内补发切源的次数（12） |
| `RootLayers.SCRIM_ALPHA` | `ui/RootLayers.java` | 设置/导航覆盖层不透明度（90%） |
| `PlaybackSettings` 各项 | `prefs/PlaybackSettings.java` | 切台方式 / 渲染 / 画面比例 / 缓冲 / 网速等用户设置 |
| 运行时 TCL 信源自检 | `ui/MainActivity.checkTclAudioRoute` | onStart + 每 2s 查 `TclSourceSwitch.isAndroidSource`；非 Android 切回并重播 |
| `BootAudioPlan` | `boot/BootAudioPlan.java` | 开机音频自检：BOOT_COMPLETED+10s 重播一次，150s 兜底 |
| `ProbeWhitelist` / `ProbeStore` | `player/ProbeWhitelist.java` / `player/ProbeStore.java` | 探测成功结果持久化 24h、URL+profile 键、失败失效 |
| `VIDEO_FREEZE_TIMEOUT_MS` | `ui/MainActivity.java` | PLAYING 无新视频帧的看门狗（默认 12s） |
| `StreamProbe` / `VideoProfile` | `player/StreamProbe.java` / `player/VideoProfile.java` | 起播前探测真实格式；API21+ 按 4K 能力、API19 固定 1080p；legacy 未知普通 URL 放行，仅已知 4K/实机卡死 URL 负缓存跳过 |
