# 批次 1：项目骨架 + 解析/分组/源存储

- worktree：`tvlive-core`（从 `main` 切出）
- 依赖：无（第一批）
- 不做：UI、播放器、HTTP、二维码、开机自启、默认桌面、设备安装

先读 `docs/specs/v1.0.md` 的 §1–§5、§10、§12；设计参考本批不用。

## 目标

建立可构建的 Android 工程，完成纯 Java 的 m3u/m3u8 频道表解析、分组/编号、私有目录源存储，并用 JVM 单测覆盖边界。APK 能 `assembleDebug`，但本批**不要 Activity/UI**。

## 文件（约 9 个）

1. `settings.gradle`（扩展现有最小文件；pluginManagement/dependencyResolutionManagement/include ':app'）
2. `build.gradle`（root；AGP 8.7.3 apply false）
3. `gradle.properties`（JVM 内存、`android.useAndroidX=true`、`android.nonTransitiveRClass=true`）
4. `app/build.gradle`（namespace/applicationId `me.qnnp.tvlive`；compileSdk 35；minSdk 19；targetSdk 28；Java 8；JUnit 4.13.2；本批无其他依赖）
5. `app/src/main/AndroidManifest.xml`（label「直播」；INTERNET / ACCESS_NETWORK_STATE / RECEIVE_BOOT_COMPLETED；`usesCleartextTraffic=true`；无 `android:banner`；无 Activity）
6. `app/src/main/java/me/qnnp/tvlive/data/PlaylistModel.java`（Channel / Variant / Playlist / Group；纯 Java）
7. `app/src/main/java/me/qnnp/tvlive/data/PlaylistParser.java`（编码、EXTINF、EXTGRP、EXTVLCOPT、URL、变体合并；纯 Java）
8. `app/src/main/java/me/qnnp/tvlive/data/ChannelSet.java`（分类、组顺序、全局编号；纯 Java）
9. `app/src/main/java/me/qnnp/tvlive/data/PlaylistRepository.java`（`getFilesDir()/playlist.m3u` 读写/原子写/SourceInfo；可用 `Context`，单测用 `File` 构造）
10. `app/src/test/java/me/qnnp/tvlive/data/PlaylistCoreTest.java`（JUnit 4；覆盖下面的用例）

> 文件数略超 8 是构建骨架 + 4 个逻辑文件 + 1 个测试文件；逻辑量很小，不再拆批。

## 数据模型

- `Channel`：`id`（tvg-id 优先，否则 `group|normalizedName`）、`name`、`group`、`logoUrl`（解析但不显示）、`List<Variant>`、`number`（后续分配）。
- `Variant`：`url`、`userAgent`、`referer`。
- `Playlist`：`channels`、`groups`、`skipped`（非 http/https 或无效 URL 数）、`error`（可为 null）。
- `Group`：`name`、`List<Channel>`（可选；UI 用）。
- 所有模型纯 Java，不 import Android。

## 解析口径

- 输入 byte[] + baseUrl（源 URL；本地源可为 null）。
- BOM：UTF-8 BOM 识别；UTF-8 严格解码失败转 GB18030；再失败抛 `PlaylistParseException`。
- 行：`#EXTM3U`、`#EXTINF:<duration> attrs,title`、`#EXTGRP:<group>`、`#EXTVLCOPT:http-user-agent=...`、`#EXTVLCOPT:http-referrer=...`。
- `#KODIPROP:` 忽略；`#EXT-X-` 出现即判定为 HLS 媒体列表，不是频道表 → 抛错「不是频道列表」。
- attrs：`tvg-id`、`tvg-name`、`tvg-logo`、`group-title`；其他忽略。`tvg-chno` 不参与编号。
- 分组优先级：每条 `#EXTINF` 的 `group-title` 非空时优先用它；否则用最近一次 `#EXTGRP`（粘性，直到下一个）；两者都空再由 `ChannelSet` 按名称分类。
- URL 行：`http://`/`https://`；其他跳过并计数；相对 URL 在 baseUrl 为 http 时用 `new URL(baseUrl, line)`，否则跳过。
- 名称：trim、全角空格归一、去空；空名用 URL 末段；仍空则跳过。
- 变体合并：同 `tvg-id` 优先；否则同 group + 规范化名称（trim/大小写归一/连续空格归一）；保持源顺序；UA/Referer 跟随 URL 行前的 EXTVLCOPT。
- 结果：空文件/无频道 → 抛错；https 保留，不全局剔除。

## 分组 / 编号

- `group-title` 有意义时用它；`General`/`China`/`中国`/`默认`/`未分类`/空 → 按名称分类。
- 名称分类：含 `CCTV`/`央视` → 央视；含 `卫视` → 卫视；含 `地方`/`城市` → 地方；含 `港澳台`/`香港`/`澳门`/`台湾` → 港澳台；否则其他。
- 组顺序：央视 / 卫视 / 地方 / 港澳台 / 其他；「收藏」由 UI 虚拟组，不在本批。名称优先 + group-title 别名（央视频道/卫视频道/地方频道/台湾香港澳门…）；其余一律归其他，不保留源里的英文类别 tab。
- 组内保持源顺序；全局 `number` 从 1 连续到 N；收藏不参与本批编号。
- `ChannelSet.organize(List<Channel>)` 返回排序/编号后的 `Playlist`；`groups` 按最终显示顺序。

## 源存储

- `PlaylistRepository`：
  - `File playlistFile(Context)` / `File playlistFile(File filesDir)` → `filesDir/playlist.m3u`
  - `byte[] read(File)`；`void writeAtomic(File, byte[])`（同目录 `.tmp` + rename）
  - `Playlist load(Context)`（读 + parse + organize）
  - `void save(Context, byte[])`（写入，不解析）
  - `void delete(Context)`
  - `SourceInfo`：`label`（host 或文件名）、`size`、`lastModified`、`channelCount`、`hasSource`
- 不写外部存储；不申请存储权限；不硬编码 `/data/media/0`、`/sdcard`、`/data/local/tmp`。
- 单测用 `File` 构造 + `java.nio.file.Files.createTempDirectory`（JVM 可用）；不要用 Android `Context`。

## 单测（PlaylistCoreTest）

至少覆盖：

1. UTF-8 + BOM + EXTINF 基本解析（名称/分组/URL）
2. GB18030 中文频道名
3. attrs：tvg-id/tvg-name/tvg-logo/group-title
4. `#EXTGRP` 覆盖后续条目分组
5. `#EXTVLCOPT` UA/Referer 跟随 URL
6. `#KODIPROP` 忽略
7. https URL 保留
8. 非 http(s) 跳过并计数
9. 相对 URL 解析（baseUrl）
10. 同名同组多 URL 合并为变体
11. 空文件/只有 HLS 标签/无频道 → 抛错
12. `General` group-title 按名称分类
13. 组顺序 + 全局编号连续
14. 空名称回退 URL 末段
15. 仓库写入/读回/删除/原子写（临时 File）

## 验收

- `./gradlew test assembleDebug` 绿；测试全过。
- 无 Activity/UI/player/HTTP 代码。
- `git status --short` 无 `local.properties`、`build/`、`.gradle/`、临时文件。
- 只提交本批源码/构建文件；**严禁 `git push`、合并、PR、tag**。
- 不 adb / 不安装 APK / 不碰电视或模拟器。
- `local.properties` 如不存在，写 `sdk.dir=<SDK 路径>`，不提交。

## 返回

- commit hash
- 改动文件列表
- `./gradlew test assembleDebug` 的结论/测试数
- 未覆盖/风险
- 需管理会话决策的点
