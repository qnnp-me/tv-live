# 批次 2d：竖排分组栏 / 焦点与播放分离 / 两段 OK / 右键菜单

- worktree：`tvlive-nav`（从 `main` 切出，已含 2c）
- 依赖：批次 2c 已合并
- 不做：其他子组（2e）、连续失败停止 / 错误常驻角标（2e）、HTTP/QR/手机搜索（3）、播放层改动
- 交互定稿：竖排分组栏；焦点 ≠ 播放；OK 两段；→ 打开焦点台操作菜单；全屏保持 2b

## 目标

把 2b 的「横排组 tab + 选中 300ms 自动预览 + OK 直接全屏」改成「竖排分组栏 + 焦点只移动 + OK 两段」；右键（→）对焦点台打开操作菜单；修列表底部压行。播放/解析/HTTP 不动。

## 布局（TvLayout）

左栏仍 32% 宽，内部改为两列：

```
sidebar
  brand
  content (horizontal, weight=1)
    groupBar  宽 30%（1080p ≈ 190dp）：竖排 收藏/央视/卫视/地方/港澳台/其他
    channelList 宽 70%：频道行
  hint（固定底部，不覆盖列表）
```

- 组项：组名 + 数量（`央视 19`），两段字号；active 左侧 tally 条 + 组名亮色；组内含当前播放台时右侧弱 `●`。
- 组项高度 56dp（1080p）/ 38dp（720p），7 项不滚动；焦点项 full-bleed 背景。
- 列表行沿用 2b 尺寸；频道名过长省略号；行尾星标保留；播放行行尾加弱 `●`（tally 色），与焦点条并存。
- **列表底部**：hint 固定占位，列表高度不覆盖 hint；列表 `clipToPadding=false` + `paddingBottom = hint 高度 + 8dp`，末项可完整滚出。修 2b 第 9 行压底。
- 720p 用 `values/dimens.xml` 缩放，1080p 用 `values-sw720dp/dimens.xml`；新增组栏尺寸两边都补。

## 焦点 / 播放分离

状态：`focusInGroups`、`focusChannel`、`playingChannel`。

- 上下移动：只改 `focusChannel`，不换台、不加载、不触发预览。
- 左右切组：只改组筛选和焦点，不换台；预览继续播 `playingChannel`。
- 删除 2b 的 300ms 选中预览/防抖；`playingChannel` 只由 OK、全屏、数字/CH（全屏）、上次台恢复改变。
- 列表行显示：焦点行 tally 条；播放行 `●`；两者可同时存在。
- 组栏 `●`：该组包含 `playingChannel` 时显示。
- 首次进列表：焦点 = 上次播放台（有）；否则当前组第一台。全屏 Back 回列表：组切到播放台所在组，焦点 = 播放台。

## OK 两段

- 焦点台 == 播放台 → OK 进全屏。
- 焦点台 != 播放台 → 第一下 OK 只切预览（留在列表），第二下 OK 全屏。
- 提示动态：
  - 焦点 != 播放：`OK 切预览 · → 菜单`
  - 焦点 == 播放：`OK 全屏 · → 菜单`
- 列表里预览失败：OSD 说明原因，留在列表，**不自动跳台**，焦点不变。
- 预览切换中：加载角标照旧（500ms 无首帧）；旧画面保留到新台首帧或错误。

## 数字键 / CH（列表）

- 数字键：2s 缓冲，输入全局号 → 切到该台所在组并把焦点移到该台，**不播放**；focusInGroups=false。
- CH+/−：焦点移到上/下一个频道（跨组，按全局号），**不播放**；到首/尾不循环。
- 全屏：数字/CH 直接换台（保持 2b）；左右切组并跳该组第一台。

## 右键菜单（→）

- 列表焦点行按 → 打开右侧菜单，**作用于焦点台**（不是播放台）。
- 菜单项同 2b：收藏/取消收藏（第一项）、切换源（多 variant 才可用）、频道信息、重新加载列表、设置、退出。
- 菜单打开时不改焦点、不中断预览；Back 只关菜单；菜单打开时 → 不重复打开。
- 全屏菜单保持 2b：作用于播放台；全屏 → 仍切组。
- 组栏按 → / OK：进入列表（焦点到该组第一台），不弹菜单。

## 组栏导航

- 组栏 UP/DOWN：移动组焦点；列表立即切到该组并显示（焦点该组第一台），**不播放**。
- 组栏 RIGHT/OK：进入列表，focusInGroups=false。
- 列表 LEFT：回组栏，焦点停当前组。
- 收藏组空：保留组项 + 「收藏为空 · 长按 OK 收藏」提示（2b 口径）。

## 全屏（保持 2b）

- 无常驻 UI；OK 出 2.5s OSD；长按 OK/MENU 菜单；Back 回列表。
- 上下/CH/数字直接换台；左右切组并跳该组第一台。
- 播放失败 3s 自动跳下一个（2e 再改连续失败停止）。

## 文件（约 10 个）

1. `ui/TvLayout.java`（左栏两列、组栏竖排、hint 固定、列表 padding）
2. `ui/ChannelListAdapter.java`（播放行 `●`、焦点态、行复用不回归）
3. `ui/GroupBar.java`（新建：竖排组项、数量、active、组内播放点、焦点）
4. `ui/MainActivity.java`（焦点/播放分离、两段 OK、右键菜单目标、数字/CH、组导航、删除自动预览）
5. `ui/Overlays.java`（如需菜单标题/提示文案）
6. `data/ChannelNavigator.java`（新建：跨组上一/下一、按全局号查找、组筛选）
7. `res/values/strings.xml`、`dimens.xml`、`values-sw720dp/dimens.xml`
8. `test/.../ChannelNavigatorTest.java`（新建）

不新建 Activity/Fragment；不引第三方；不改播放器/解析/HTTP。

## 验收

- `./gradlew test assembleDebug` 绿；29 tests 不回归；新增 navigator 测试。
- 模拟器 1920×1080 + TV 4.4.3（管理会话装）：
  - 焦点上下/切组时预览不换、不加载；播放行 `●` 与焦点条分离。
  - 非播放台 OK 一次只切预览、再 OK 全屏；播放台 OK 直接全屏。
  - → 菜单作用于焦点台；收藏/信息/variants 正确；Back 只关菜单。
  - 数字键移焦点不播放（列表）；CH+/− 移焦点；全屏数字/CH 直接换台。
  - 组栏 6 项 + 收藏；active 可见；组内播放点正确。
  - 871 台列表滚到底最后一行完整；TV 第 9 行不再压底。
  - 2b 的加载角标、错误 OSD、收藏、上次台、双击返回不回归。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit `batch 2d: vertical groups, focus-only nav, two-step ok, context menu`。

## 返回

commit hash、文件、测试结论、未覆盖/风险、需管理会话决策的点。
