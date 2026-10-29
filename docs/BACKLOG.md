# tv-live 台账

> 公开仓库从 1.0.52 快照建立；下表早期 commit hash 来自未公开的本地历史，在公开仓库中不可解析。

## v1.0（当前）

| 批次 | worktree | 内容 | 状态 | commit | 门禁 | 备注 |
|---|---|---|---|---|---|---|
| 1 | `tvlive-core` | 项目骨架 + m3u 解析 / 分组 / 源存储 + JVM 单测 | merged | `06f5e7a` / merge `43966f9` | `./gradlew clean test assembleDebug` ✅ 15 tests | session `ses_e7e1e3f74ffeuqmDVjWamoarYv`；无 UI / 播放器 / HTTP；主仓独立门禁通过 |
| 2a | `tvlive-player` | ExoPlayer / MediaPlayer 播放引擎 + 播放控制器 | merged | `22fde71` / merge `8122bb3` | build + test ✅ 24 tests / 40321 methods | session `ses_e7e172702ffePCIub8G40YFJR1`；无 UI / HTTP / 设备；主仓独立门禁通过 |
| 2b | `tvlive-ui` | TV UI 核心：列表 / 预览 / 全屏 / OSD / 菜单 / 退出 / 时钟 / 遥控 / 收藏 / 上次台 / 自动跳台 | merged | `9f58304` / merge `e7fa517` | build ✅ 29 tests；模拟器/TV + 5 源 | 6 组/名称优先/变体/显示/加载角标已修；TV 列表第 9 行轻微压底待 3 收 |
| 2c | `tvlive-shell` | 设置页 / 首启静态页 / 开机自启 / 返回系统桌面 / 图标 | merged | `6538b9e` / merge `966d424` | build ✅ 29 tests；TV 安装成功；setup/list/settings/自启广播/双击返回验过 | 默认桌面已砍（TCL 拒任何 HOME）；「关于」需滚动；自启用广播验未真重启 |
| 2d | `tvlive-nav` | 竖排分组栏 / 焦点≠播放 / 两段 OK / 右键菜单 / 数字与 CH 移焦点 / 列表底部 | merged | `30b7688` + `f21309f` / merge `7bf7cec` | build ✅ 42 tests；TV/模拟器：焦点不动播放、两段 OK、→ 菜单焦点台、数字移焦点、末项完整 | hint/空组/菜单/失败清点已修；失败清点要等重试链结束，2e 角标收 |
| 2e | `tvlive-fail` | 其他子组 / 连续 5 失败停止 / 错误角标 | merged | `d87e237` / merge `f39cde1` | build ✅ 57 tests；TV iptv4 8 子组；模拟器连续 5 失败停止 + 角标 | 「其他」→「未分组」；OSD 2.5s 与角标共存 |
| 2g | `tvlive-navbar` | 导航默认隐藏 / 覆盖层动画 / 8s 自动收起 / 44% / 跑马灯 / 主菜单 + 频道操作列 / 组内循环 / 左右音量 | merged | `82e04aa` / merge `3ce5ff0` | build ✅ 67 tests；TV 实装（截图受 TCL 视频层限制） | 用户先收口；待后续实机确认：OK/长按弹导航、Back 层级、操作列/音量条；TextureView 已弃用回 SurfaceView |
| 2f | `tvlive-sources` | 多源（≤8）合并 / 源管理 / 缓存与迁移 | merged | `9fb8546` / merge `6022bca` | build ✅ 86 tests；模拟器迁移/源列表/设置页暂停验过 | 超限截断；id 取首源（排序可能失效，4 修）；改名走 3 手机页 |
| 3a | `tvlive-http` | HTTP 服务 / PIN+token / 手机页源管理（URL/上传/启用/排序/删除/改名） | merged | `8fce9c8` / merge `090fcec` | build ✅ 162 tests；TV：18088 页面 233ms、state 21ms、577 台 | 自研 SimpleHttpServer 替换 NanoHTTPD（TV 上后者挂死）；端口 18088；手机页可导入 |
| 3b | `tvlive-setup` | 二维码 / 首启接线 / 本机 URL 导入 / 手机页搜索+切台 / 改地址 | merged | `fedb590` / merge `01dc24c` | build ✅ 198 tests；模拟器：QR v5 免 PIN、搜 cctv1、点台切台、改地址成功/失败保留 | 手机页简化（搜索/加源 + 源管理折叠）；手机页 320/390 自查；改地址保留 id/名称/顺序/启用 |
| 4a | `tvlive-cleanup` | 按键路由 / 状态机收敛（UiState + KeyRouter） | merged | `966420b` / merge `512383d` | build ✅ 238 tests；模拟器：导航/操作列/主菜单/Back/音量回归 | 行为不变；设备按键矩阵待 TV 开 |
| 4b | `tvlive-ui2` | UI 组件 / 文案尺寸收敛（FocusRow / SettingRow / strings / dimens） | merged | `21250c4` / merge `ff8bb90` | build ✅ 238 tests；模拟器：导航/操作列/菜单/设置截图与 4a 一致 | 视觉零变化；新增 FocusRow/SettingRow/SectionTitle/ActionButton |
| 5a | `tvlive-phone` | 手机竖屏布局 / 触屏 / 设置首启窄屏 | merged | `d21a8ab` / merge `07dc195` | build ✅ 254 tests；模拟器竖屏：点行切预览、双击全屏、长按操作条、tab | 布局日志 1080x1920 video=608；TV AVD 截图缩放非布局问题 |
| 5b | `tvlive-phone2` | 全屏手势 / 系统栏 / 旋转续播 / 手机设置入口 | merged | `c594746` / merge `6b0817a` | build ✅ 283 tests；模拟器：点画面 OSD/返回、旋转 layout switched 不崩、TV 回归 | Surface 切布局 destroy/create 1–2 帧，播放器不重起 |
| 5c | `tvlive-okfix` | TV 去掉独立全屏 / UiState 与布局同步 / OK 弹导航 | review | `2b0f656` | build ✅ 294 tests；模拟器 TV 模式 OK 弹导航通过；TV 待装 | 待办「画面态上下换台反转」已由 5c-fix 完成；TV offline 待唤醒 |
| 5c-fix | `tvlive-keys` | 画面态上下换台反转（上=频道+ 下一台、下=频道− 上一台）；CH/导航不变 | done | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 299 tests | 从 main `db45457` 切出；只本地 commit，未推 |
| 6a | `tvlive-navz` | TV 导航与视频并排：导航显示时视频缩到右侧 56%，导航落在左侧 44%（TCL SurfaceView 层级压过 navOverlay，导航看不见）；画面在容器内 16:9 等比 fit + 居中黑边 | done（本地） | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 322 tests；模拟器 TV 1920×1080 实测播放：并排时 Surface `[845,237][1920,842]`（1075×605，16:9 不变形、上下黑边居中），导航 `[0,0][845,1080]`；Back 收起后 Surface 回全屏 16:9 铺满；弹出/收起跨画面播放不中断（同一 Surface，时间码连续） | 只本地 commit；`SurfaceView` 不重建；TCL 实机待装验；非 16:9 屏幕（手机横屏 19.5:9）全屏会出现左右黑边（等比而不拉伸） |
| 8b | `tvlive` | TCL 实机：同窗口 UI 被 HWC 合成的 SurfaceView 压住（导航/菜单/设置/OSD 全中），改为独立窗口承载——导航 44% PopupWindow、菜单/设置/OSD 全屏 PopupWindow（触摸按模式开关）；视频保持 SurfaceView 全宽 16:9 | done（本地） | `7627602` | `./gradlew clean test assembleRelease` ✅；TCL 1.0.6 实测：按 OK 导航覆盖并排布正常、8s 自动收起、Back 收起、DOWN 移焦点；MENU 菜单可见；设置页二维码/源列表可见 | 手机竖屏全屏 OSD 的返回按钮在 popup 触摸关闭时可能点不到，待验；菜单/设置常驻全屏覆盖窗口需确认不吞视频区触摸 |
| 11 | `tvlive-perf1` | 启动/退出稳定性：PIN 去 fsync + 内存缓存 + 后台生成；MediaPlayer 兜底 DNS/connect 移出主线程；onDestroy 不再 join HTTP 线程；navPopup 销毁清理 | done（本地） | `22fc680` | 388 tests；TCL 1.0.9 冷启动 1055ms，退出无卡顿 | — |
| 12 | `tvlive-perf2` | 渲染/列表：Overlays 去掉整树 bringChildToFront；KEEP 只保留一个 TextureView；列表焦点增量刷新；倒计时只改文本 | done（本地） | `ed091cb` | 397 tests；模拟器长按音量/上下键无闪烁 | — |
| 13 | `tvlive-perf3a` | 源解析：mtime+length 缓存解析快照；m3u 逐行流式解析（5MB 峰值 23–26MB→8MB，131→90ms） | done（本地） | `319c820` | 408 tests；差分 12000 例结果一致 | — |
| 13b | `tvlive-perf3b` | 网络/启动：网卡 30s 缓存；HTTP 有界线程池（2/6/16，5s 超时）；设置页/首启页惰性创建；回前台只起一次解码器 | done（本地） | `9a1ff1d` | 416 tests；模拟器冷启动 +549ms、回前台单次 play | — |
| 14 | `tvlive-perf4` | 播放器：standby 放弃音频焦点；DUAL 内存门槛 96MB；OOM/解码失败回退清理；切台去掉多余 stop | done（本地） | `e1af275` | 443 tests；TCL 1.0.11 冷启动 700ms | 实机音频焦点/切台不黑待目视 |
| 15 | `tvlive-netspeed` | 台名卡实时网速：Exo `TransferListener` 只统计 `isNetwork` 字节 → 1s 滑窗 + 0.35 指数平滑 → 主线程 500ms 回调；`SpeedFormat`/`BandwidthIndicator` 纯类；设置页「播放」第 4 行「显示网速」开/关 | done（本地） | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 466 tests；模拟器 TV：40KB/s 慢速源显示「网络很慢 · 48 KB/s」，MediaPlayer/首包前「—」，关闭开关后台名卡无网速行且不刷 UI | 只本地 commit；网速行随现有台名卡显示（重缓冲本身不新建台名卡，引擎在 STATE_BUFFERING 重新开始取样）；TCL 实机待验 |
| 8 | `tvlive-navroot` | 导航从 TvLayout 搬到 root 层（NavView，44% 宽、90% 黑 + 右缘渐变），盖在全宽视频上不再挤压；撤销并排逻辑；设置页全屏 90% 黑半透明覆盖、视频全宽继续播放 | done（本地） | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 330 tests；模拟器 TV 1920×1080 实测：导航 `[0,0][845,1080]`、Surface 全屏 16:9；右侧操作列/Back/8s 自动收起正常；设置页视频可见、返回到导航正常 | 只本地 commit；TCL 实机待装验；竖屏 PhoneLayout 未改 |
| 10 | `tvlive-dual` | 双 TextureView 视频层：KEEP 单引擎复用（旧帧留在 SurfaceTexture）、DUAL 双引擎（standby 静音 → 首帧 alpha 交换 → 停旧引擎）、极速直切仍 SurfaceView；设置页「双画面」可选 | done（本地） | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 382 tests；JVM：KEEP 复用引擎、DUAL 静音/首帧交换/失败保持当前台/解码器与 OOM 回退、三模式来回切换释放 standby、Surface 槽位绑定 | 只本地 commit；**TCL 实机待验**：TextureView 在 4.4 是 GPU 合成（两路时解码器/内存/卡顿风险），且留帧依赖 SurfaceTexture 不被 `releaseTexImage`；MediaPlayer 回退路径的 `reset()` 可能清帧 |
| 6 | `release` | 发布：versionName 1.0.0、release 签名、最终 APK、总验收 | released | `87c9271` | build ✅ 283 tests；release v1+v2 签名/对齐；模拟器首启验过 | APK `/tmp/opencode/tvlive-1.0.0.apk`；TV 待开电视卸载旧 debug 后安装 |

## v1.1（暂缓）

- 完整手机遥控：频道列表、搜索、音量、全屏、收藏。
- 在 v1.0 的 HTTP 服务器和单页上扩展，不另起服务。

## 已确认的决定

- 包名 `me.qnnp.tvlive`；App 名「直播」。
- 初始源为空；首启手机设置页 + 二维码 + URL/PIN 手输；不预置、不自动扫描。
- 设计：B 布局（左列表 + 右预览）；全屏只有画面；整点/半点前后各 30 秒显示 `HH:mm:ss`。
- 收藏：列表/全屏长按 OK 或 MENU → 菜单第一项 收藏/取消收藏；星标 + 收藏组。
- 退出：全屏 Back → 列表；列表第一次 Back 只出 2 秒提示，2 秒内再按一次才退出；默认桌面时改为返回 bama。
- 开机自启：标准 `BOOT_COMPLETED` receiver；设置开关；默认关。
- 默认桌面：可选，默认关；默认禁用的 HOME activity-alias + 系统 Home 选择器；bama `.Launcher`。
- 通用 APP：不 root、不硬编码 TCL 路径、不默认改桌面、不申请存储权限。
| 16 | `tvlive-merge` | 跨源频道合并：名称归一化（去分隔符/画质后缀、保留 `+`）+ 头部中英别名表（CCTV/卫视/港澳台）；显示名优先中文；仍只在同组内合并 | done（本地） | `037038b` | 458 tests；577→568（仅同组合并） | 剩余重复主要是跨组中英文（`Hunan TV` vs `湖南卫视`），未开跨组 |
| 17 | `tvlive-render` | 设置项「视频渲染」：自动 / SurfaceView / TextureView；SurfaceView+双画面自动降级 KEEP 并提示 | done（本地） | `1cdaf00` | 497 tests | TEXTURE+DIRECT 性能未实机验 |
| 18 | `tvlive-boot` | 开机信号适配器：`BootActions` 通用+厂商 action 表、`BootSignalReceiver`、`BootCoordinator`（开关/退出/5 分钟/30 秒去重）、`BootLog`、`BootLauncher` | done（本地） | `3e8c898` | 518 tests；模拟厂商广播可拉起 | TCL 拉起后会被系统白名单清理 |
| 19 | `tvlive-settings-tiers` | 设置页分层：一级「播放」5 项（切台/渲染/画面比例/切台提示/显示网速）+ 「高级」折叠（首帧加速/导航宽度/自动收起/时钟角标）；`VideoGeometry` fit/stretch/crop | done（本地） | `dab2254` | 539 tests；模拟器 2340×1080 FIT/CROP 通过 | CROP+SurfaceView 依赖屏幕边界裁切 |
| 20 | `tvlive-boot-delay` | 开机延迟拉起：AlarmManager 等待默认桌面就绪（`HomeResolver`）、12 秒 elapsed 兜底、20 秒上限、`from_boot` 缓起播 3 秒 | done（本地） | `ac65edd` | 583 tests | TCL 实际拉起后仍被 `AppWhiteListBama` 清理，需改系统白名单 |
| 21 | `tvlive-boot-front` | 拉起后前台确认：`BootFrontPlan` + 非导出 `BootFrontCheckReceiver`，2 秒一次查 top activity，被桌面盖住就 `startActivity` 带回，最多 12 次；`userExited` 立即结束；`BootWaitPlan` 轮询 1 秒 / settle 3 秒 | done（本地） | 本地 `HEAD` | `./gradlew clean test assembleDebug` ✅ 597 tests | 只本地 commit；TCL 实机待装验（白名单已加，需确认不再被 AllAppsActivity 抢走） |
| fix | `tvlive` | 1.0.12：极速直切不再每次切台 release+new ExoPlayer（TCL 上反复创建 MediaCodec 会耗尽，之后所有台起不来） | released | `54cd5eb` | 1.0.12 装 TCL；切十几个台不再出现全放不了 | — |
| fix | `tvlive` | 1.0.18：SurfaceView 从无到有时强制重播当前台，修复「切台后只有声音没有画面」 | released | `1.0.18` | 583 tests；TCL 待复验 | 只在 surface 恢复时触发，短暂黑一下 |
| 21 | `tvlive-boot-front` | 开机前台保活：拉起后每 2s 检查 top，不是本 App 就 `REORDER_TO_FRONT` 带回，最多 12 次；等待桌面轮询 1s、settle 3s | done（本地） | `9f2de88` | 597 tests | 12 次窗口内用户切走会被拉回 |
| 22 | `tvlive-boot-audio` | 开机音频兜底：from_boot 起播 5s 后重播一次（最多 2 次）修 AudioTrack 路由；已随后续实机验证正常而移除 | 已回退 | `5483b83` → `eeb394d` | 605 tests → 599 | 依赖 isMusicActive，判断不了路由；已删 |
| 23 | `tvlive-boot-direct` | 开机直连：`BootLaunchPlan.DIRECT_LAUNCH=true`，收到信号 1s 后直接 launchNow，不再等桌面；等待/轮询/兜底代码原样保留，开关可回退 | done（本地） | `c690788` | 607 tests；TCL 1.0.21 自启成功、无巴马弹窗、声音正常 | 1s 时桌面可能未就绪，靠 BootFront 带回 |
| 24 | `tvlive-drop-audio-repair` | 移除开机音频定时重播（`BootAudioRepair` 及测试），保留直接拉起/前台检测/白名单 | done（本地） | `eeb394d` | 599 tests | 若某固件再现有声无画需人工换台 |
| 25 | `tvlive-boot-home` | 开机先切回 TCL 主页信源再拉起：`BootHomeSwitch` 向 `com.tcl.sourcemananger/com.tcl.insrc.ShortcutKeyReceiver` 发 `ENTER_HOME`；切源成功再 +1.5s 拉起（alarm 带 `EXTRA_DIRECT_LAUNCH`），非 TCL 直接拉起 | done（本地） | `955ea43` | 605 tests；`clean test assembleDebug` ✅ | 只本地 commit；TCL F3800T 实机待验「有画无声」是否消除；切源耗时超过 1.5s 仍可能带 HDMI 音频 |
| 26 | `tvlive-boot-home` | 启动前厂商动作表：`BootLaunchHook` 接口 + `BootHooks.table()/runAll()`（`Build.MANUFACTURER` 含 TCL 或存在 `com.tcl.sourcemananger` → `TclHomeHook`），`BootLauncher.switchHomeThenDelay` 只调 `BootHooks.runAll`；与 `BootActions`（开机信号表）职责分开 | done（本地） | `c8c0b95` | 613 tests；`clean test assembleDebug` ✅ | 只本地 commit；表里目前仅 TCL 一项，实机行为等同批次 25 |
| 27 | `tvlive-boot-home-retry` | 第一次开机信源在 HDMI 无声：TCL 厂商名直接返回 `TclHomeHook`（不再依赖 `sourcemananger` 组件探测）；`BootFrontCheckReceiver` 前 3 次 bring 前补发 `ENTER_HOME`（失败不影响 bring）；切源后延迟 1.5s → 2.5s | done（本地） | `fba658e` | 617 tests；`clean test assembleDebug` ✅ | 只本地 commit；实机 TCL L32F3800A 待复验「关机前 HDMI → 第一次开机有声」 |
| fix | `tvlive` | 1.0.27：TCL 开机信源切回 Android 层，`TclSourceSwitch` 反射 `TTvCommonManager.setInputSource(EN_TCL_STORAGE)`，失败退回 `ENTER_HOME`；前台确认窗口 12 次内补发 | released | `a25d133` | 617 tests；TCL 实机重启后自启有声音 | 反射依赖 TCL 系统 jar，通用设备 no-op |
| fix | `tvlive` | 1.0.28：`ExoPlaybackEngine.setSurface(null)` 时清空 `attachedSurface`，并在复用前检查 `Surface.isValid()`；修复 TCL 上 Surface 对象复用但已失效、Exo 去重导致的黑屏有声 | released | `1.0.28` | 620→617 tests；TCL 实机待复验 | 依赖 Surface 有效性判断 |
| fix | `tvlive` | 1.0.29：切台方式切到 SurfaceView 时强制 `INVISIBLE → 120ms → VISIBLE` 重建 Surface，走 surfaceCreated 重绑 + 重播；修复「切切台方式不恢复、切渲染方式才恢复」 | released | `1.0.29` | 617 tests；TCL 实机待复验 | 重建时短暂黑一下 |
| fix | `tvlive` | 1.0.30–1.0.35：SurfaceView 偶发黑屏排查——surface 看门狗（连续 2s 无有效 Surface 重建）、`GONE→1s→reparent` 强制 HWC 重分配 layer、首帧 2s 超时重配 renderer（`setVideoSurface(null)+surface`）；TCL 上仍偶发，确认是合成层/解码器 flush 问题 | released | `1.0.30`–`1.0.35` | 617–620 tests；TCL 实测仍偶发 | SurfaceView 在 TCL 上不可靠，建议默认 TextureView |
| fix | `tvlive` | 1.0.36–1.0.42：连续切台合并——画面态 CH+/− 550ms 内不切台、旧台继续播、焦点/OSD 跟随、以最后落点为准、落点回原台跳过；基准从播放台改为焦点台；OK/MENU/Back 取消排队；导航选台仍立即播 | released | `1.0.36`–`1.0.42` | 617 tests；TCL 实机手感确认 | 单切也要等 550ms |
| 28 | `tvlive` | 1.0.43：开机 action 统一策略表（分类/延迟/窗口/同类去重）；USB/存储窗口 2 分钟 + 延迟 15s，网络 6s、用户唤醒 4s，窗口改按 `elapsedRealtime`；Exo 清晰度上限 1080p 防 4K HEVC 卡死；起播 15s 看门狗 + 引擎重建 | done（本地） | 本地 `HEAD` | 631 tests；TCL 实机：4K-only 东方卫视超时 → 重试重建 → 自动跳东南卫视首帧/有声；开机 551s 的 USB 广播判 `SKIP_TOO_LATE` | 1080p 上限会让 4K 电视也只用 1080p；4K-only 源会等 15s 后自动跳；起播 15s 可能对极慢源多一次重试 |
| 29 | `tvlive` | 1.0.44：TCL Android 4.4（MT5507）直接跳过 4K 频道/线路——1.0.43 的 1080p selector 仍被 4K-only 源带到 `MediaCodec.flush` 卡死，随后 OMX 整体不可用；新增 `PlaybackLimits.is4kChannel`（名称/id/线路 URL/已知 4K 源），命中则 OSD「已跳过 4K 频道」并自动跳台；设备判定用 TCL 专有 `com.tcl.sourcemananger` 包（本机 manufacturer=MTK） | done（本地） | 本地 `HEAD` | 634 tests；TCL 1.0.44 实机：东方卫视 → `skip 4k`（未分配 codec）→ 东南卫视首帧；随后 CCTV13 首帧/音频；1.0.43 遗留 OMX 卡死需重启电视清除 | 仅 TCL SDK<=19 生效，4K 电视不受影响；会少 4K 台；若源把 4K 线路伪装成普通 URL 仍可能漏拦 |
| 30 | `tvlive` | 1.0.45：起播预探测——`StreamProbe` 按 HLS master `RESOLUTION/CODECS` → media 首/末分片 `MediaExtractor` → Exo `TsExtractor`/`FragmentedMp4Extractor` → raw 兜底，结果缓存；`VideoProfile` API21+ 查 4K 能力、API19 固定 1080p；移除频道名屏蔽，普通 URL 探测失败放行（避免 404/301/TLS 误杀），仅已知 4K/实机卡死 URL 负缓存跳过 | done（本地） | 本地 `HEAD` | 637 tests；TCL 实机：CCTV13/东南卫视 probe 404/301/TLS → UNKNOWN 放行 → 首帧；东方卫视 `bestv.cn/148/3/` known-bad → 未分配 codec → 自动跳东南卫视首帧/音频 | 只探首/末分片，直播中途切 4K 仍可能漏；`bestv` URL 是硬编码负缓存；4K 电视未实机；renderer 层 Format 上限待做 |
| 31 | `tvlive` | 1.0.46：开机音频自检——`from_boot` + TCL legacy 在 `BOOT_COMPLETED` 后 10s 重切 Android 信源、重建引擎并重播当前台一次；BOOT_COMPLETED 未收到时开机 150s 兜底；只在开机首播会话做一次 | done（本地） | 本地 `HEAD` | 639 tests；TCL 实机重启：BOOT_COMPLETED 08:01:35 → boot audio replay 08:01:43 → CCTV13 first frame + active 44.1k AudioTrack | 开机约 100s 会有一次短暂重播；若音频 HAL 更晚才就绪仍可能无声；非 TCL legacy 不启用 |
| 32 | `tvlive` | 1.0.47：运行时 TCL 信源/音频路由自检——`onStart` 立即查 `TclSourceSwitch.isAndroidSource`，false 则切回 Android 并 2.5s 后重建引擎/重播当前台；运行中每 2s 复查（10s 冷却）；开机音频自检只在源非 Android 时兜底 | done（本地） | 本地 `HEAD` | 639 tests；TCL 实机 App 重启：`audio route onStart android=false` → `setAndroidSource=true` → replay → CCTV13 first frame + active 44.1k track；可重复复现/恢复 | 若用户主动切 HDMI，App 会切回 Android；反射依赖 TCL 系统 jar；切换后有一次 2.5s 重播；查询返回 null 时不动作 |
| 33 | `tvlive` | 1.0.48：持久化探测白名单——成功 Format 按 URL+设备 profile 存 24h（最多 300 条 LRU），命中直接跳过播放列表/分片探测；播放失败/起播超时自动 invalidate 重新探测；普通频道探测 UNKNOWN 不写白名单 | done（本地） | 本地 `HEAD` | 643 tests；TCL 实机浙江卫视：首次 probe `hls-segment-mediaextractor` 约 3.5s，App 重启后 probe `whitelist`，首帧约 1.1s | 24h 内 live 流若换清晰度仍可能陈旧；失败会失效但首次已受影响；白名单按 URL，源换地址会重探 |
| 34 | `tvlive` | 1.0.49：白名单升级——首帧成功后按实际选中 Format 写入（即使预探测 UNKNOWN），TTL 24h→7 天；每次成功播放 `touch` 续期，常用 URL 实际不过期；失败/超时失效；切台直接跳过播放列表/分片探测 | done（本地） | 本地 `HEAD` | 644 tests；TCL 实机 CCTV3：预探测 UNKNOWN，首帧后写入 `method=playback`；下次 play `method=whitelist`，不再下载分片 | URL 内容仍可能中途换清晰度，Exo 1080p 上限/看门狗保留；白名单只跳过探测，不跳过安全上限 |
| 35 | `tvlive` | 1.0.51：视频帧看门狗——`VideoFrameMetadataListener` 记录最近一帧；PLAYING 但 12s 无新帧 → 重建重试一次，再失败自动跳台；修复 `checkStall` 在位置前进时重置 `stallRetryDone` 导致同一台无限重试；同时 `checkStall` 增加日志 | done（本地） | 本地 `HEAD` | 644 tests；TCL 江西卫视：首帧后 12s 无帧 → `video freeze retry` → 12s 后 `fail` → 自动跳台；东南卫视正常首帧+音频 | 12s 才恢复；音频-only 无视频帧不启用；MediaPlayer 兜底无帧回调；若源本身长期坏，只能跳台 |
| fix | `tvlive` | 1.0.52：导航焦点唯一 + 操作列 Back——组栏拿焦点时列表行不再画焦点（内部 `focusIndex` 保留），组项焦点 = 亮底色 + 亮组名；频道 `→` 打开操作列后，Back 只收起操作列，保留导航与列表焦点，再按一次才收导航 | released | `v1.0.52` | `./gradlew clean test assembleRelease` ✅ 645 tests；API19 1920×1080 + API35 模拟器通过；TCL L32F3800A 实机 1.0.52：组栏焦点时列表行无 tally/底色，操作列 Back 后导航与列表焦点保留、二次 Back 才收导航 | TCL 实机已验；发布 APK `/tmp/opencode/tvlive-1.0.52.apk`；手机竖屏操作条仍走 `dismissActions()` |

## 暂不做 / 已推迟

- EPG、回看、录制、投屏、云同步。
- 后台常驻 HTTP 服务；手机遥控完整版（v1.1）。
- 默认桌面默认开启。
- 2026-10-29 开机偶发无声（重启不复现）：关机前 App 开着，暂不按「关机前停在 HDMI」定性；疑开机 25s 早期拉起、起播早于音频路由/HAL 就绪。下次出现先抓 `from_boot` / 起播时刻 / 音频焦点 / 输出设备日志，再决定是否加「起播后路由自检 + 重播」硬化。
