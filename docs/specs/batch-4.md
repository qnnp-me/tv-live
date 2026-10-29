# 批次 4：代码收敛（按键 / 状态机 / UI 组件 / 文案尺寸）

- 拆两段串行：**4a 按键路由 + 状态机** → **4b UI 组件 + 文案尺寸**
- 依赖：3b 已合并
- 硬约束：**不改任何用户可见行为**；每步跑全量 tests + 模拟器/TV 按键回归
- 不做：新功能、手机适配（5）、播放器/解析重构

## 4a：按键路由 + 状态机

现状问题：
- `MainActivity` 超 2000 行；`dispatchKeyEvent` 巨型 switch，另散落 `handleSetupKey`/`handleSettingsKey`/`onDirection`/`onBackKey`/`onMenuKey`/`onShortOk`/`onLongOk`/长按计时。
- 状态分散：`NavVisibility`、`menuOpen`、`settingsVisible`、`setupVisible`、`fullscreen`、`focusInGroups`、操作列焦点、`exitArmed`。
- 同一个键在不同状态优先级靠阅读代码推断，2g 已出现状态不同步。

目标：
1. 新增 `ui/UiState.java`：`Mode {SETUP, SETTINGS, FULLSCREEN, NAV_SHOWN, NAV_HIDDEN}` + `menuOpen`、`actionColumnVisible`、`focusInGroups`、`exitArmed`；所有状态迁移集中到一处（enter/exit 方法），`NavVisibility`/`TvLayout` 由它驱动，不再各自维护。
2. 新增 `ui/KeyRouter.java`：`boolean handle(KeyEvent)`，按 `UiState` 分派；回调接口 `Host`（onSelect/onLongSelect/onBack/onMenu/onDirection/onDigit/onChannelStep/onVolume/onInfo/onExit）。
   - OK 短按/长按、Back、MENU、方向、数字、CH+/−、音量、INFO 全部集中。
   - 优先级：菜单 > 设置/首启 > 全屏 > 导航（组栏/列表/操作列）> 隐藏态。
   - 首启/设置页只保留其必要键，其他吞掉。
3. `MainActivity` 只保留 `dispatchKeyEvent → keyRouter.handle()` + 回调实现；删除散落的按键方法。
4. 行为保持：列出现有按键矩阵（每个 mode 的键→动作），逐条对齐；2g 的 Back 语义、OK 两段、长按 OK、MENU 主菜单、操作列 `→/←`、组内循环、数字/CH、音量全部不变。
5. 测试：`KeyRouterTest` 覆盖每个 mode 的关键键与优先级（菜单打开时 Back 只关菜单、全屏 Back 回隐藏、导航 Back 收起、隐藏 Back 双击、OK 两段、长按 OK、数字/CH、音量）；现有 198 tests 不回归。

验收：`./gradlew clean test assembleDebug` 绿；模拟器/TV 按键矩阵回归（不截图，手动）；commit `batch 4a: key router and ui state`；不改布局/文案。

## 4b：UI 组件 + 文案尺寸

1. 抽 `ui/FocusRow`（或 RowStyle 工具）：GroupBar 组项、ChannelListAdapter 行、ActionColumn 操作项、Overlays 菜单项共用的 full-bleed 焦点、tally 条、字号/内边距、播放点；去掉各文件重复的 paint/颜色/尺寸代码。
2. `SettingsView`/`SetupView` 抽通用 `SettingRow`/`SectionTitle`/`ActionButton`，统一焦点与点击态。
3. `strings.xml`/`dimens.xml` 命名统一：`row_*`、`menu_*`、`settings_*`、`setup_*`；删未使用项；720p/1080p 两套 key 对齐。
4. 删除死代码/未用 import/重复分支；`Overlays` 的 OSD/角标/音量/时钟布局复用同一 helper。
5. 视觉不变：TV/模拟器截图对比 2g/3b 基线（列表/导航/操作列/菜单/设置/首启/全屏 OSD）。

验收：`./gradlew clean test assembleDebug` 绿；198 tests 不回归；截图对比无视觉差异；commit `batch 4b: focus row and style consolidation`。

## 风险

- 按键重构易回归：先列矩阵，再写 KeyRouterTest，最后设备按键逐条过。
- 状态迁移集中后要避免双重驱动：`UiState` 为唯一源，`NavVisibility` 只做计时，不再自己改 mode。
- UI 抽取不要改变 dp/sp 数值；截图对比为准。
