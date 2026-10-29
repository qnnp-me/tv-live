# 批次 2e：其他子组 / 连续失败停止 / 错误角标

- worktree：`tvlive-fail`（从 `main` 切出，已含 2d）
- 依赖：批次 2d 已合并
- 不做：HTTP/QR/手机搜索（3）、EPG、播放器引擎改动（2a 不动）
- 目标：解决「其他」大量频道难找、全屏连续失效无限跳、失败后黑屏无提示。

## 1. 其他子组

### 数据

- `Channel` 保留原始 `sourceGroup`（group-title，空则空）。
- `ChannelSet` 增加 `subGroups(String group)`：返回 `其他` 组内按 `sourceGroup` 聚合的子组（名称 + 数量），按源中首次出现顺序；空 sourceGroup → `未分组`。
- 仅「其他」有子组；收藏/央视/卫视/地方/港澳台不做。
- 子组名过长（> 8 字）省略号；空/未知 → `未分组`。
- 解析/分组单测：iptv4 的其他 → 纪录/电影/音乐/春晚/直播中国 等；cn → General/News/Movies…；domestic（无 group-title）→ 仅 `未分组`。

### UI

- 组栏选中「其他」时，其下缩进显示子组：`全部`（默认）+ 各子组；子组字号小一级、无数量或弱数量。
- UP/DOWN 在 6 个一级组 + 其他子组间移动；`其他` 本身 = 全部其他台。
- 子组焦点切换时列表过滤到该子组（焦点第一台，不播放）。
- 切到别的规范组时，其他子组收起；再回「其他」默认「全部」。
- 组项数量：一级组显示总数；子组显示子组数量。
- 子组树最多 12 项，超出组栏滚动；active/播放点同 2d。

## 2. 连续失败停止

- 全屏自动跳失败链：同一轮连续 5 个频道失败 → 停止自动跳，回列表，提示「连续 5 个频道播放失败，请检查源」。
- 起播成功、用户手动换台/切组、回列表、重新加载源 → 计数清零。
- 全屏单次失败仍 3s 自动跳下一个（2b）。
- 列表预览失败：不自动跳，不计入全屏链；提示后等用户操作。
- 上次台恢复失败：回列表并提示（不黑屏、不无限重试）。

## 3. 错误角标

- 失败时先出 2.5s 错误 OSD（2b）；OSD 消失后保留右下角轻量角标：
  - 全屏自动跳中：`播放失败 · 3 秒后换台`
  - 列表预览失败：`播放失败 · 按 OK 重试`
  - 连续 5 个停止：`连续 5 个失败 · 按 OK 重试`
- 角标在下次起播成功 / 手动换台 / 回列表 / 打开菜单时隐藏；不与「加载中…」同时显示，错误优先。
- 不挡 OSD（左下）、时钟（右上）、菜单（右侧）；复用 2b 角标样式与位置，不引第三方。
- 全屏黑屏时至少保留角标，避免「一脸茫然」。

## 文件（约 8 个）

1. `data/Channel.java` / `data/ChannelSet.java`（sourceGroup、子组聚合）
2. `ui/GroupBar.java`（其他子组树、缩进、滚动）
3. `ui/MainActivity.java`（子组导航/过滤、失败计数、回列表提示）
4. `ui/PlaybackController.java` 或 `ui/MainActivity.java`（连续失败计数，抽纯类便于测试）
5. `ui/Overlays.java`（错误角标状态机）
6. `res/values/strings.xml`、`dimens.xml`、`values-sw720dp/dimens.xml`
7. `test/.../ChannelSetTest.java`、`test/.../FailureStreakTest.java`

不改 2d 的焦点/两段 OK/右键菜单；不引第三方。

## 验收

- `./gradlew test assembleDebug` 绿；29 + 2d 新增测试不回归；新增子组/失败计数测试。
- 模拟器 + TV：
  - iptv4：其他下出现 5+ 子组，切换过滤正确；domestic：其他仅「未分组」不崩。
  - 全屏连续 5 个失败停止并回列表提示；成功起播后计数清零。
  - 错误角标在 OSD 后保留，换台/回列表/菜单隐藏；加载角标不与其重叠。
  - 2d 的竖排栏/两段 OK/右键菜单/播放点不回归。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit `batch 2e: other subgroups, failure streak, error badge`。

## 返回

commit hash、文件、测试结论、未覆盖/风险、需管理会话决策的点。
