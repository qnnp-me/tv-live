# 批次 2f：多源合并 / 源管理

- worktree：`tvlive-sources`（从 `main` 切出，已含 2e）
- 依赖：批次 2e 已合并
- 不做：HTTP 服务 / 手机页 / 二维码 / 手机搜索（批次 3）；默认桌面；播放器引擎
- 目标：最多 8 个源合并成一张频道表；同名频道合并线路；设置页管理源；启动读本地缓存。

## 数据模型

- `SourceRecord`：id（稳定字符串，如 `s1`）、name、kind（URL/FILE）、location、enabled、order、lastUpdated、channelCount、lastError、cachePath。
- `files/sources.json`：`{version:1, sources:[...]}`；原子写（tmp + rename）。
- URL 源导入时下载到 `files/sources/<id>.m3u`；FILE 源复制到同目录。启动只读本地缓存，不联网；手动「重载」才重新下载 URL。
- 迁移：现有 `files/playlist.m3u` → 第一个源（name「当前源」，kind FILE，order 0，enabled true）；保留旧文件读取兼容，不重复导入。
- 上限：8 源、单文件 5MB、总解析条数 ≤8000、去重后频道 ≤5000；超限拒绝并中文提示。

## 合并

- 每源独立解析（复用 `PlaylistParser`）；失败只标记该源 `lastError`，不阻塞其他源。
- 合并键：`tvg-id` 去 `@` 优先；否则 `group|normalizedName`（2b 规则）。
- 同键合并 variants：源 `order` 小的在前；同源内保持 URL 顺序；去重 URL。
- 分组仍 6 组；「其他」子组按源 `group-title`（2e），重名子组合并。
- 收藏/上次台 id 不变；删除源后失效 id 忽略，不崩。
- 频道 `Channel` 增加 `sourceName`（首个来源）；菜单「频道信息」显示 `源名 · 线路 i/n`。

## 设置页

- 「源」区改为源列表：每行名称 + 台数 + 状态（正常/失败原因）+ 启用开关。
- 选中源 OK 进入操作：启用/停用、重载（URL 重新下载；FILE 重读）、改名、上移、下移、删除。
- 「添加源」留批次 3（手机页/本机 URL）；本批不做。
- 「重新加载全部」= 重读全部本地缓存并合并（不下载）。
- 无源 → SetupView；删除最后一个源 → SetupView；某源失败 → 其余正常显示 + 顶部提示。

## 启动 / 刷新

- 启动：读 `sources.json` + 各源缓存，后台解析合并；先显示「读取中」，完成后列表；有上次台则全屏。
- 缓存缺失/解析失败：跳过该源，其余可用；设置页标错误。
- 手动重载 URL 源：下载 → 校验 → 保存缓存 → 解析 → 合并；失败保留旧缓存 + 错误提示。
- 不做后台定时刷新、不做常驻服务。

## 菜单 / 文案

- 菜单「切换源」改名「切换线路」（它一直是 variant 切换）。
- 「频道信息」显示来源源名、线路序号/总数。
- 设置页「更换源（手机设置页，批次 3）」改为「添加源（批次 3）」。

## 文件（约 10 个）

1. `data/SourceRecord.java`（新建）
2. `data/SourceStore.java`（新建：json 读写、缓存路径、迁移、上限）
3. `data/ChannelMerger.java`（新建：多源合并/去重/优先级）
4. `data/PlaylistLoader.java`（改：多源加载/单源失败隔离）
5. `data/Channel.java`（sourceName）
6. `ui/SettingsView.java`（源列表 + 操作）
7. `ui/MainActivity.java`（启动加载、重载、菜单文案、信息）
8. `res/values/strings.xml`
9. `test/.../SourceStoreTest.java`、`test/.../ChannelMergerTest.java`（新建）

不新建 Activity；不引第三方；HTTP/手机页留 3。

## 验收

- `./gradlew test assembleDebug` 绿；2d/2e 测试不回归；新增 store/merger 测试。
- 模拟器 + TV：
  - 两个源（iptv4 + shanghai）合并；同名频道线路数增加；源 order 决定优先线路。
  - 禁用源 → 其频道消失；删除源 → 恢复；删到 0 → SetupView。
  - 单源坏文件 → 其余正常 + 错误提示；重载 URL 源失败保留旧缓存。
  - 启动读缓存不联网（飞行模式可起）；871+76 合并后列表/滚动正常。
  - 2d 竖排栏/两段 OK/→ 菜单；2e 子组/失败停止/角标不回归。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit `batch 2f: multi-source merge and source management`。

## 风险

- 同名不同台误合并：只做规范化名称精确匹配，不做模糊。
- 8 源全量解析在 TV 4.4 可能 2–5s：后台 + 「读取中」；不做解析缓存（v1.0 接受）。
- 5000 台内存/滚动：RecyclerView 复用已有；实测 PSS。
