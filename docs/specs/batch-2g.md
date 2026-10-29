# 批次 2g：导航浮层 / 自动隐藏 / 动画 / 跑马灯

- worktree：`tvlive-navbar`（从 `main` 切出，已含 2e）
- 依赖：批次 2e 已合并
- 不做：多源（2f）、HTTP/QR/搜索（3）、默认桌面；播放层不动
- 用户定稿：导航默认隐藏；弹出后长时间不操作自动隐藏；宽度 44%

## 状态

`NAV_HIDDEN`（默认） / `NAV_SHOWN` / `FULLSCREEN` + 既有 SETUP/SETTINGS/MENU。

- 有源 + 上次台启动 → `NAV_HIDDEN` 直接播；无源 → SetupView；导入成功 → `NAV_HIDDEN`。
- `NAV_SHOWN` 是覆盖层，不是独立 Activity；画面仍在底层全宽。
- 全屏 Back → `NAV_HIDDEN`（不回到导航）；导航隐藏态再按 Back 才是退出提示。

## 导航浮层

- 导航宽 44%（1080p ≈ 845px；720p 564px）；内部三列：组栏 24% + 频道列表 52% + 频道操作列 24%（操作列常驻，不再弹频道菜单浮层）。
- 覆盖在画面上：底色 90% 不透明（`#E60B1012`），右边缘 24dp 渐变到透明；**不做实时 blur**（4.4/566MB 性能），不做 RenderScript。
- 弹出：220ms `translationX(-width→0)` + `alpha(0→1)`，DecelerateInterpolator；收起反向；动画结束 `GONE`，不拦截按键/焦点。
- 导航显示时画面仍全宽，右侧 56% 可见；隐藏时视频容器全宽。

## 自动隐藏

- `NAV_SHOWN` 且 8s 无按键 → 自动收起（焦点/播放不变，继续播）。
- 任意按键重置 8s；菜单打开、设置页/首启页、错误 OSD 显示期间不自动隐藏；关闭后重新计时。
- `NAV_HIDDEN` 不自动弹出。

## 按键

`NAV_HIDDEN`：

- 上/下/CH+/−/数字：直接换台（同 2b 全屏），出 2.5s OSD。
- **LEFT/RIGHT：音量 −/＋**（`STREAM_MUSIC`，每次 1 格，到 0/最大停），自绘音量 OSD 2s；不切组。
- **OK / Back / 长按 OK：弹出导航**，焦点=播放台；Back 不再直接退出。
- MENU：主菜单（不弹导航）。
- INFO：OSD。
- 播放失败：全屏同款 3s 自动跳（2e 连续失败停止生效）。

`NAV_SHOWN`：

- 保持 2d：上下/切组/数字/CH 只移焦点；OK 两段；→ 进操作列；组栏 →/OK 进列表。
- **Back：第一次只显示 2 秒「再按一次返回退出」，2 秒内再按退出；不收起导航**。导航只通过 8s 自动收起（菜单打开时暂停计时）。
- **列表 UP/DOWN 组内循环**：组第一台 UP → 该组最后一台；最后一台 DOWN → 组第一台；单台组原地。不跨组（跨组仍走组栏/数字/CH）。
- 组栏 UP/DOWN 保持循环；收藏组同样组内循环。
- **组栏切组/进入列表的焦点落点**：若该组包含当前播放台 → 焦点落在播放台；否则落该组第一台（收藏组同理），不播放。
- 列表 `→`：进入频道操作列；`←` 在列表时回组栏；组栏 `→` 进列表。
- 8s 无操作自动收起；收起后按键切回 `NAV_HIDDEN` 语义。
- **音量 OSD**：底部居中细条 + `音量 40%`，2s 消失；不用系统音量条（`FLAG_SHOW_UI` 关），不挡 OSD/时钟/菜单/加载角标。0 显示「静音」，最大显示「最大」。

`FULLSCREEN`：保持 2b/2e；Back → `NAV_HIDDEN`。LEFT/RIGHT 与隐藏态一致改为音量（不再切组）；切组走导航组栏或数字直选。

菜单：

- **主菜单**：MENU 键在任意状态（NAV_HIDDEN / NAV_SHOWN / FULLSCREEN）弹出，全局项：重新加载列表、设置、退出；仍为右侧浮层。
- **频道操作列**：常驻在导航内、频道列表右侧，作用于焦点台：收藏/取消收藏、切换线路（多线路可用）、频道信息、全屏播放；不再单独弹出菜单，长按 OK 不再弹频道菜单。操作列顶部显示焦点台名称。
- 列表 `→` 进入操作列；操作列 `←` 回列表；操作列上下选择、OK 执行；焦点在操作列时不播放、不改预览。
- 主菜单打开时不弹导航；主菜单 `LEFT`/`Back` 关闭；菜单打开时 LEFT 不切组。

## 跑马灯

- 列表行名超长：非焦点行 `END` 省略；焦点行 `MARQUEE` 无限循环（`setSelected(true)`，`marqueeRepeatLimit=-1`）。
- RecyclerView 复用时复位 `selected/ellipsize`，避免非焦点行滚动。
- 44% 宽度下仍超长的名称才滚动；短名不滚。
- 组栏子组名超长同样省略（不滚动）。

## 文件（约 7 个）

1. `ui/MainActivity.java`（状态机、按键路由、8s 自动隐藏、全屏 Back 目标）
2. `ui/TvLayout.java`（44% 覆盖层、渐变、动画、GONE）
3. `ui/ChannelListAdapter.java`（焦点行 marquee、复用复位）
4. `ui/GroupBar.java`（宽度/省略）
5. `ui/Overlays.java`（菜单 LEFT 关闭）
6. `res/values/strings.xml`、`dimens.xml`、`values-sw720dp/dimens.xml`
7. `test/.../NavVisibilityTest.java`（8s/按键重置/菜单不隐藏）

不改播放器/解析/子组逻辑；不引第三方。

## 验收

- `./gradlew test assembleDebug` 绿；2e 测试不回归。
- 模拟器 + TV：
  - 启动直接画面（导航隐藏）；OK/LEFT 弹出；Back 收起；再 Back 退出提示。
  - 卫视组第一台按上到该组最后一台、最后一台按下回第一台；单台组不跳组。
  - 8s 不操作自动收起；按键重置；菜单打开不收起。
  - 44% 宽度；长名焦点行跑马灯、非焦点省略；子组不滚。
  - 菜单 LEFT/Back 关闭；全屏 Back 回画面。
  - 导航隐藏/全屏 LEFT/RIGHT 只调音量并出音量条，不切组；0/最大边界正常。
  - 2d 焦点≠播放/两段 OK/→ 菜单；2e 子组/连续失败/角标不回归。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit `batch 2g: nav overlay, auto-hide, marquee`。

## 风险

- 4.4 上 marquee 在 RecyclerView 复用可能残留 selected：复用必须复位。
- 覆盖层 + 动画在 566MB 设备上只动 transform/alpha，不重绘视频 Surface。
- 8s 自动隐藏对慢速选台可能偏短：先 8s，实测再调。
