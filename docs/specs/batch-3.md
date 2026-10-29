# 批次 3：手机 HTTP 设置页 / 多源导入 / 二维码 / 搜索

- 拆两段串行：**3a `tvlive-http`**（HTTP + 手机页源管理）→ **3b `tvlive-setup`**（QR + 首启接线 + 搜索/切台）
- 依赖：2f 已合并
- 不做：完整手机遥控（音量/全屏/收藏）留 v1.1；不预置源；不端口映射/云

## 3a：HTTP 服务 + 手机页源管理

### 服务

- `control/SimpleHttpServer`：自研最小 HTTP/1.1（ServerSocket + 每连接一线程 + 10s 超时），端口 18088（占用则 18089–18098 顺延），仅监听局域网；App 启动即起，退出停。
- `control/TokenStore`：6 位 PIN（首次随机、持久），失败 5 次/分钟退避；token（3b 二维码用）预留。
- 鉴权：写操作必须 PIN（header `X-Pin` 或 query `pin`）；读状态同 PIN；错误返回 401/429 中文。
- 最大请求 5MB；只接受 m3u/m3u8 文本；拒绝路径穿越/非局域网来源。
- 手机页 `assets/control/index.html`（内联 CSS/JS，无 CDN）：
  - 源列表：名称/台数/状态/启用开关/上移/下移/改名/删除；添加 URL；上传 m3u；重新加载全部。
  - 当前状态：频道总数、当前台、版本；PIN 输入框（localStorage 记住）。
  - 移动端优先，320–430px 宽；深色，与 TV 同一 tally 色；无外部字体/图标。

### API

- `GET /api/state` → `{version, channels, current, sources:[{id,name,kind,enabled,order,channels,lastError}]}`
- `POST /api/source/add` `{url,name?}` → 下载/校验/保存 → 返回源；失败中文原因
- `POST /api/source/upload` multipart `file` → 保存为 FILE 源
- `POST /api/source/toggle` `{id,enabled}`
- `POST /api/source/delete` `{id}`
- `POST /api/source/rename` `{id,name}`
- `POST /api/source/order` `{id,delta}`
- `POST /api/reload`
- 所有写操作成功后 TV 侧重新加载合并并刷新列表/导航；失败保留旧缓存。

### TV 侧接线

- `SettingsView` 源区显示真实「手机地址 http://IP:18088 · PIN 123456」；「添加源」改为提示用手机打开（3b 加 QR）。
- App 启动起服务；`onDestroy` 停；端口/IP 变化刷新显示。
- 不做 QR 生成、不做首启页接线、不做搜索（3b）。

### 测试 / 验收

- `./gradlew test assembleDebug` 绿；86 tests 不回归；新增 PIN/限流、m3u 校验、路径/大小限制测试。
- 宿主 `curl`：state 鉴权失败/成功；add 坏 URL；upload 超 5MB；toggle/rename/order/delete；导入后 state 变化。
- 手机浏览器同 WiFi 打开手机页，添加一个真实 m3u URL、上传一个文件、禁用/删除源，TV 列表刷新。
- 64K 方法数门禁；不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit。

## 3b：二维码 / 首启接线 / 搜索 + 切台

### QR

- ZXing core 3.5.3 `QRCodeWriter` 生成 Bitmap（无 banner/adaptive icon）。
- `SetupView`：真实 IP/端口/PIN；二维码内容 `http://IP:18088/#t=<token>`；无 WiFi 显示「请先连接 WiFi」并禁用地址/二维码；导入成功后自动进列表。
- `SettingsView`：源区显示地址/PIN + 小二维码（或「手机扫码」入口）。
- token：一次性/短时（10 分钟），二维码 URL 带 token 免 PIN；过期回 PIN。

### 首启 / 本机导入

- 首启无源 → SetupView；手机导入成功 → TV 自动刷新列表并进播放；失败中文提示。
- 「在本机输入地址」→ URL 输入（系统 IME 或遥控器）→ 下载/校验/保存；失败提示；不阻塞首启。

### 搜索 / 切台

- `GET /api/search?q=`：按频道名包含匹配（不区分大小写），返回前 50 `{id,name,group,source}`。
- 手机页搜索框：输入即搜；点结果 → `POST /api/play {id}` → TV 主线程切到该台（导航隐藏态直接播；全屏保持全屏）。
- 搜索结果为空/源失败提示。

### 验收

- 二维码 zbar 解码得到 `http://IP:18088/#t=...`；手机扫码打开手机页。
- 手机添加 URL/上传文件后 TV 自动刷新；首启流程完整。
- 手机搜「CCTV1」→ 点结果 → TV 切台；全屏/隐藏态都正确。
- 2g/2f 回归：导航/主菜单/操作列/音量/多源/设置页；86 tests 不回归。
- 不 push/合并/PR/tag；不 adb/不碰设备；只本地 commit。

## 风险

- NanoHTTPD/ZXing 增加方法数，需 <64K；必要时 R8 只保留 core。
- TCL 4.4 的 TLS 1.0：手机页导入 https 源可在 TV 上失败，提示 TLS；不阻断保存。
- 手机页与 TV 同 WiFi 要求；AP 隔离/防火墙会失败，设置页给排查提示。
