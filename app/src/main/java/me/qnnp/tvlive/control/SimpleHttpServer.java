package me.qnnp.tvlive.control;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import me.qnnp.tvlive.control.SourceApi.Result;

/**
 * 自研最小 HTTP/1.1 服务（替代在 TCL 4.4 上不可靠的 NanoHTTPD）：18088 起，占用则 18089–18098 顺延。
 *
 * <p><b>线程模型</b>：一个 daemon accept 线程 + 有界 daemon 线程池（核心 {@value #POOL_CORE}、
 * 最大 {@value #POOL_MAX}、队列 {@value #POOL_QUEUE}），socket 读写超时
 * {@value #SOCKET_TIMEOUT_MS} 毫秒，响应一律 {@code Connection: close}，连接在 finally 关闭。
 * 慢连接最多占满池子，再多来的连接直接回 503 并关闭：不会像「每连接一线程」那样
 * 让 API 19 每线程约 1MB 的栈无限堆积。</p>
 *
 * <p>协议范围只覆盖手机页需要的部分：GET/POST、请求行 + headers（大小写不敏感）、
 * Content-Length（body ≤5MB）、multipart/form-data 单文件上传、UTF-8 文本响应。
 * PIN 鉴权、来源限制、路由与业务动作沿用 {@link SourceApi}/{@link TokenStore}；
 * {@code /api/state} 只读 {@link StateProvider} 快照（独立线程 + 时间预算，不解析磁盘）。</p>
 */
public final class SimpleHttpServer {

    public static final int FIRST_PORT = 18088;
    public static final int LAST_PORT = 18098;
    public static final int SOCKET_TIMEOUT_MS = 5_000;
    /** 连接处理线程池：核心 2、最大 6、队列 16（最多 22 个在跑/排队，满了回 503）。 */
    public static final int POOL_CORE = 2;
    public static final int POOL_MAX = 6;
    public static final int POOL_QUEUE = 16;
    /** 单次请求体上限 5MB。 */
    public static final long MAX_REQUEST_BYTES = SourceApi.MAX_REQUEST_BYTES;
    /** 请求行 + headers 上限，超过直接 400。 */
    public static final int MAX_HEADER_BYTES = 16 * 1024;
    /** /api/state 读 provider 的时间预算；超时返回上一份快照。 */
    public static final long SNAPSHOT_TIMEOUT_MS = 250L;

    public static final String HEADER_PIN = "X-Pin";
    /** 兼容 v1.0 §8 里的旧头名。 */
    public static final String HEADER_LEGACY_PIN = "X-TvLive-Token";
    public static final String HEADER_FILENAME = "X-Filename";
    public static final String PAGE_ASSET = "control/index.html";
    public static final String PAGE_MIME = "text/html; charset=utf-8";
    public static final String JSON_MIME = "application/json; charset=utf-8";
    public static final String TEXT_MIME = "text/plain; charset=utf-8";

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Charset LATIN_1 = Charset.forName("ISO-8859-1");
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private final SourceApi api;
    private final TokenStore tokens;
    private final AssetSource assets;
    private final int firstPort;
    private final int lastPort;
    private final ExecutorService snapshotReader;
    private final AtomicLong connectionSeq = new AtomicLong();
    private final AtomicBoolean snapshotReading = new AtomicBoolean(false);
    /** 连接处理池：startServer 时按需创建；shutdown 后置空，重启会新建。 */
    private final Object poolLock = new Object();
    private volatile ThreadPoolExecutor clientPool;

    /** provider 可在服务启动后绑定；HTTP 线程不直接调用它（见 {@link #snapshotForState()}）。 */
    private volatile StateProvider stateProvider;
    /** 上一份已就绪快照；provider 缺失/变慢时返回它。 */
    private volatile StateProvider.Snapshot cachedSnapshot = StateProvider.Snapshot.notReady();
    private volatile byte[] page;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile int port = -1;

    /** 手机页资源读取（Android 侧用 AssetManager，单测注入假实现）。 */
    public interface AssetSource {

        byte[] read(String path) throws IOException;
    }

    public SimpleHttpServer(SourceApi api, TokenStore tokens, AssetSource assets) {
        this(api, tokens, assets, null, FIRST_PORT, LAST_PORT);
    }

    public SimpleHttpServer(SourceApi api, TokenStore tokens, AssetSource assets,
            StateProvider stateProvider) {
        this(api, tokens, assets, stateProvider, FIRST_PORT, LAST_PORT);
    }

    public SimpleHttpServer(SourceApi api, TokenStore tokens, AssetSource assets,
            StateProvider stateProvider, int firstPort, int lastPort) {
        if (api == null || tokens == null || assets == null) {
            throw new IllegalArgumentException("api/tokens/assets == null");
        }
        if (firstPort <= 0 || lastPort < firstPort) {
            throw new IllegalArgumentException("端口范围无效");
        }
        this.api = api;
        this.tokens = tokens;
        this.assets = assets;
        this.stateProvider = stateProvider;
        this.firstPort = firstPort;
        this.lastPort = lastPort;
        this.snapshotReader = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "TvLive-state-snapshot");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 启动后再绑定 provider；之前 {@code /api/state} 返回 ready:false。 */
    public void setStateProvider(StateProvider provider) {
        this.stateProvider = provider;
    }

    /** 依次尝试端口并启动 accept 线程；成功返回实际端口，全被占用返回 -1。 */
    public synchronized int startServer() {
        if (running && port > 0) {
            return port;
        }
        for (int candidate = firstPort; candidate <= lastPort; candidate++) {
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(candidate), 16);
            } catch (IOException e) {
                closeQuietly(socket);
                continue;
            } catch (RuntimeException e) {
                closeQuietly(socket);
                continue;
            }
            serverSocket = socket;
            port = candidate;
            running = true;
            final ServerSocket acceptSocket = socket;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(acceptSocket);
                }
            }, "TvLive-http-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return port;
        }
        running = false;
        serverSocket = null;
        port = -1;
        return -1;
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 停止监听（已接受的连接自行结束）。
     *
     * <p>不 join accept 线程：它是 daemon，关闭 socket 后阻塞中的 accept 会立刻抛异常退出。
     * onDestroy 是主线程调用，等待 1 秒只会造成退出卡顿/ANR。</p>
     */
    public synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        closeQuietly(socket);
        acceptThread = null;
        port = -1;
    }

    /** 关闭监听与快照/连接池（Activity onDestroy）。 */
    public synchronized void shutdown() {
        stop();
        shutdownClientPool();
        snapshotReader.shutdownNow();
    }

    // ------------------------------------------------------------------ 接受连接

    private void acceptLoop(final ServerSocket boundSocket) {
        while (running) {
            // stop 后立刻 restart 会换 socket；旧线程只认自己绑定的那个。
            if (boundSocket != serverSocket) {
                break;
            }
            Socket client = null;
            try {
                client = boundSocket.accept();
                configure(client);
                startClientThread(client);
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                // accept 异常（例如瞬时 fd 不足）：退避后重试，不退出循环。
                closeQuietly(client);
                sleepQuietly(100L);
            } catch (RuntimeException e) {
                closeQuietly(client);
                sleepQuietly(100L);
            }
        }
    }

    private static void configure(Socket socket) throws IOException {
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
    }

    private void startClientThread(final Socket socket) {
        ThreadPoolExecutor pool;
        synchronized (poolLock) {
            pool = clientPool;
            if (pool == null || pool.isShutdown()) {
                pool = newClientPool();
                clientPool = pool;
            }
        }
        try {
            pool.execute(new ClientTask(socket));
        } catch (RejectedExecutionException e) {
            // 池与队列都满（慢连接/预连接堆积）：不再新建线程，直接 503 并关闭。
            writeQuietly(socket, text(503, "服务繁忙，请稍后重试"));
            closeQuietly(socket);
        }
    }

    /** 有界连接池；核心线程常驻，队列满后最多再开 4 个，仍排不下就 AbortPolicy 抛拒绝。 */
    static ThreadPoolExecutor newClientPool(final ThreadFactory factory) {
        GrowFirstQueue queue = new GrowFirstQueue(POOL_QUEUE);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(POOL_CORE, POOL_MAX, 30L,
                TimeUnit.SECONDS, queue, factory, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(false);
        // 让队列在池未满时先拒绝入队，execute 才会继续长线程：
        // 否则第 3 条连接就排到队列里，5 条空闲连接就能把手机页一起拖住。
        queue.owner = pool;
        return pool;
    }

    /**
     * 「先长线程、再排队」的队列：持有池未满时 offer 返回 false，让 ThreadPoolExecutor
     * 继续 addWorker；达到 max 后才真正排队。线程数仍严格受 max 限制。
     */
    private static final class GrowFirstQueue extends ArrayBlockingQueue<Runnable> {

        private volatile ThreadPoolExecutor owner;

        GrowFirstQueue(int capacity) {
            super(capacity);
        }

        @Override
        public boolean offer(Runnable task) {
            ThreadPoolExecutor pool = owner;
            if (pool == null || pool.getPoolSize() < pool.getMaximumPoolSize()) {
                return false;
            }
            return super.offer(task);
        }
    }

    /** 当前连接池线程数（单测断言「有界」用）。 */
    int clientPoolSize() {
        ThreadPoolExecutor pool = clientPool;
        return pool == null ? 0 : pool.getPoolSize();
    }

    private ThreadPoolExecutor newClientPool() {
        return newClientPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "TvLive-http-" + connectionSeq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** 单个连接的任务；shutdownNow 丢弃排队任务时用它关掉对应 socket。 */
    private final class ClientTask implements Runnable {

        private final Socket socket;

        ClientTask(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            handle(socket);
        }

        void closeQuietlyNow() {
            closeQuietly(socket);
        }
    }

    private void shutdownClientPool() {
        ThreadPoolExecutor pool;
        synchronized (poolLock) {
            pool = clientPool;
            clientPool = null;
        }
        if (pool == null) {
            return;
        }
        // 已排队但没跑的连接不会自己进 finally，逐个关掉，避免 fd 泄漏。
        List<Runnable> dropped = pool.shutdownNow();
        for (Runnable task : dropped) {
            if (task instanceof ClientTask) {
                ((ClientTask) task).closeQuietlyNow();
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream output = socket.getOutputStream();
            Request request = readRequest(input, socket);
            if (request == null) {
                return;
            }
            Response response = route(request);
            writeResponse(output, response);
        } catch (HttpError e) {
            writeQuietly(socket, text(e.status, e.getMessage()));
        } catch (SocketTimeoutException e) {
            // 空闲/半开连接：超时直接关，不占资源。
        } catch (IOException e) {
            // 客户端断开或写失败：忽略。
        } catch (RuntimeException e) {
            writeQuietly(socket, text(500, "服务内部错误"));
        } finally {
            closeQuietly(socket);
        }
    }

    // ------------------------------------------------------------------ 请求解析

    private Request readRequest(InputStream input, Socket socket) throws IOException, HttpError {
        int[] budget = {0};
        String requestLine = readLine(input, budget);
        if (requestLine == null) {
            return null;
        }
        if (requestLine.isEmpty()) {
            requestLine = readLine(input, budget);
            if (requestLine == null || requestLine.isEmpty()) {
                throw new HttpError(400, "请求格式错误");
            }
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 3 || !parts[2].startsWith("HTTP/")) {
            throw new HttpError(400, "请求格式错误");
        }
        String method = parts[0].toUpperCase(Locale.US);
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new HttpError(405, "只支持 GET / POST");
        }

        String target = parts[1];
        int queryIndex = target.indexOf('?');
        String rawPath = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        String query = queryIndex >= 0 ? target.substring(queryIndex + 1) : "";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        while (true) {
            String line = readLine(input, budget);
            if (line == null) {
                throw new HttpError(400, "请求头不完整");
            }
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpError(400, "请求头格式错误");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (!headers.containsKey(name)) {
                headers.put(name, value);
            }
        }

        long contentLength = contentLength(headers.get("content-length"));
        if ("POST".equals(method) && contentLength < 0L) {
            throw new HttpError(411, "缺少 Content-Length");
        }
        if (contentLength > MAX_REQUEST_BYTES) {
            throw new HttpError(413, "请求超过 5MB");
        }
        byte[] body = readBody(input, contentLength);

        Request request = new Request();
        request.method = method;
        request.path = decodePercent(rawPath);
        request.query = query;
        request.headers = headers;
        request.body = body;
        request.remoteIp = socket.getInetAddress() == null
                ? null : socket.getInetAddress().getHostAddress();
        return request;
    }

    /** 读取一行（去掉 CRLF），累计字节数超过 {@link #MAX_HEADER_BYTES} 视为坏请求。 */
    private static String readLine(InputStream input, int[] budget) throws IOException, HttpError {
        StringBuilder out = new StringBuilder(64);
        while (true) {
            int value = input.read();
            if (value < 0) {
                return out.length() == 0 ? null : out.toString();
            }
            budget[0]++;
            if (budget[0] > MAX_HEADER_BYTES) {
                throw new HttpError(400, "请求头过大");
            }
            if (value == '\n') {
                return out.toString();
            }
            if (value != '\r') {
                out.append((char) value);
            }
        }
    }

    /** Content-Length 解析：缺失/非法返回 -1（供路由与单测使用）。 */
    static long contentLength(String value) {
        if (value == null) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0L ? -1L : parsed;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static byte[] readBody(InputStream input, long length) throws IOException, HttpError {
        if (length <= 0L) {
            return new byte[0];
        }
        byte[] data = new byte[(int) length];
        int offset = 0;
        while (offset < data.length) {
            int read = input.read(data, offset, data.length - offset);
            if (read < 0) {
                throw new HttpError(400, "请求体不完整");
            }
            offset += read;
        }
        return data;
    }

    // ------------------------------------------------------------------ 路由

    private Response route(Request request) {
        if (!LanAddress.isLanClient(request.remoteIp)) {
            return text(403, "仅限局域网访问");
        }
        String path = request.path;
        if (!isSafePath(path)) {
            return text(400, "路径不合法");
        }
        if ("/".equals(path) || "/index.html".equals(path)) {
            if (!"GET".equals(request.method)) {
                return text(405, "只支持 GET");
            }
            // 页面只读内存缓存，不碰 provider/源列表。
            return page();
        }
        if ("/favicon.ico".equals(path)) {
            return text(404, "");
        }
        if (!path.startsWith("/api/")) {
            return text(404, "页面不存在");
        }
        if ("/api/ping".equals(path)) {
            if (!"GET".equals(request.method)) {
                return text(405, "只支持 GET");
            }
            return json(200, "{\"ok\":true,\"version\":" + Json.quote(api.stateVersion())
                    + ",\"pinRequired\":true}");
        }

        Route route = Route.of(path);
        if (route == null) {
            return text(404, "接口不存在");
        }
        boolean expectGet = route == Route.STATE || route == Route.SEARCH;
        if (expectGet != "GET".equals(request.method)) {
            return text(405, expectGet ? "只支持 GET" : "只支持 POST");
        }

        String pin = pinOf(request);
        TokenStore.Auth auth = tokens.authenticate(pin);
        if (auth == TokenStore.Auth.THROTTLED) {
            int retry = Math.max(1, tokens.retryAfterSeconds());
            Response response = json(429, "{\"ok\":false,\"error\":"
                    + Json.quote("PIN 错误次数过多，请 " + retry + " 秒后再试") + "}");
            response.extraHeaders.put("Retry-After", String.valueOf(retry));
            return response;
        }
        if (auth != TokenStore.Auth.OK) {
            return json(401, "{\"ok\":false,\"error\":\"PIN 错误\"}");
        }

        byte[] body = request.body;
        String filename = request.headers.get("x-filename");
        String contentType = request.headers.get("content-type");
        if ("POST".equals(request.method) && contentType != null
                && contentType.toLowerCase(Locale.US).contains("multipart/form-data")) {
            String boundary = boundaryOf(contentType);
            if (boundary == null) {
                return text(400, "上传格式错误");
            }
            Part part = multipartFile(body, boundary);
            if (part == null) {
                return text(400, "没有收到文件");
            }
            body = part.data;
            filename = part.filename;
            if (!isBodyAllowed(body.length)) {
                return text(413, "请求超过 5MB");
            }
        }
        if (!isBodyAllowed(body.length)) {
            return text(413, "请求超过 5MB");
        }

        try {
            return dispatch(route, body, filename, request.query);
        } catch (Json.JsonException e) {
            return text(400, "请求格式错误：" + e.getMessage());
        } catch (RuntimeException e) {
            return text(500, "服务内部错误");
        }
    }

    private Response dispatch(Route route, byte[] body, String filename, String query)
            throws Json.JsonException {
        switch (route) {
            case STATE:
                return from(api.state(snapshotForState()));
            case SEARCH:
                return from(api.search(parseQuery(query).get("q")));
            case PLAY: {
                Map<String, Object> request = requireObject(body);
                return from(api.play(Json.string(request, "id")));
            }
            case RELOAD:
                return from(api.reload());
            case ADD: {
                Map<String, Object> request = requireObject(body);
                return from(api.add(Json.string(request, "url"), Json.string(request, "name")));
            }
            case UPLOAD:
                return from(api.upload(body, filename));
            case TOGGLE: {
                Map<String, Object> request = requireObject(body);
                Boolean enabled = Json.bool(request, "enabled");
                if (enabled == null) {
                    return text(400, "缺少 enabled");
                }
                return from(api.toggle(Json.string(request, "id"), enabled.booleanValue()));
            }
            case DELETE: {
                Map<String, Object> request = requireObject(body);
                return from(api.delete(Json.string(request, "id")));
            }
            case RENAME: {
                Map<String, Object> request = requireObject(body);
                return from(api.rename(Json.string(request, "id"), Json.string(request, "name")));
            }
            case LOCATION: {
                Map<String, Object> request = requireObject(body);
                return from(api.changeLocation(Json.string(request, "id"),
                        Json.string(request, "url")));
            }
            case ORDER: {
                Map<String, Object> request = requireObject(body);
                Long delta = Json.number(request, "delta");
                if (delta == null) {
                    return text(400, "缺少 delta");
                }
                return from(api.order(Json.string(request, "id"), delta.intValue()));
            }
            default:
                return text(404, "接口不存在");
        }
    }

    // ------------------------------------------------------------------ 鉴权/工具

    private static String pinOf(Request request) {
        String header = request.headers.get("x-pin");
        if (header == null) {
            header = request.headers.get("x-tvlive-token");
        }
        if (header != null && !header.isEmpty()) {
            return header;
        }
        Map<String, String> query = parseQuery(request.query);
        // 二维码地址带 #t=<token>，页面把它放进 query/header；也支持 token 参数名。
        String token = query.get("t");
        if (token == null || token.isEmpty()) {
            token = query.get("token");
        }
        if (token != null && !token.isEmpty()) {
            return token;
        }
        String pin = query.get("pin");
        return pin == null || pin.isEmpty() ? null : pin;
    }

    /** 拒绝路径穿越与目录列举；只放行普通接口路径。 */
    static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) {
            return false;
        }
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        if (path.contains("..") || path.contains("//")) {
            return false;
        }
        return true;
    }

    /** 请求体是否在上限内。 */
    public static boolean isBodyAllowed(long bytes) {
        return bytes >= 0L && bytes <= MAX_REQUEST_BYTES;
    }

    private Response page() {
        byte[] data = page;
        if (data == null) {
            try {
                data = assets.read(PAGE_ASSET);
            } catch (IOException e) {
                data = null;
            }
            if (data == null || data.length == 0) {
                return text(500, "手机设置页缺失，请重新安装");
            }
            page = data;
        }
        return new Response(200, PAGE_MIME, data);
    }

    private Response from(Result result) {
        return json(result.status(), result.json());
    }

    private static Response json(int status, String body) {
        return new Response(status, JSON_MIME, body == null ? new byte[0] : body.getBytes(UTF_8));
    }

    private static Response text(int status, String body) {
        return new Response(status, TEXT_MIME,
                body == null ? new byte[0] : body.getBytes(UTF_8));
    }

    private static Map<String, Object> requireObject(byte[] body) throws Json.JsonException {
        if (body == null || body.length == 0) {
            throw new Json.JsonException("请求内容为空");
        }
        return Json.parseObject(new String(body, UTF_8));
    }

    // ------------------------------------------------------------------ 快照

    /**
     * provider 可能被误实现成慢操作（读磁盘/等主线程），所以不在 HTTP 线程直接调用：
     * 交给唯一的守护线程读，最多等 {@value #SNAPSHOT_TIMEOUT_MS} 毫秒，超时返回上一份快照。
     */
    private StateProvider.Snapshot snapshotForState() {
        final StateProvider provider = stateProvider;
        if (provider == null) {
            return cachedSnapshot;
        }
        if (!snapshotReading.compareAndSet(false, true)) {
            return cachedSnapshot;
        }
        Future<StateProvider.Snapshot> future = null;
        try {
            future = snapshotReader.submit(new Callable<StateProvider.Snapshot>() {
                @Override
                public StateProvider.Snapshot call() {
                    try {
                        StateProvider.Snapshot snapshot = provider.snapshot();
                        if (snapshot != null && snapshot.ready()) {
                            cachedSnapshot = snapshot;
                        }
                        return snapshot;
                    } finally {
                        snapshotReading.set(false);
                    }
                }
            });
            StateProvider.Snapshot snapshot = future.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return snapshot == null ? cachedSnapshot : snapshot;
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            return cachedSnapshot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cachedSnapshot;
        } catch (ExecutionException e) {
            snapshotReading.set(false);
            return cachedSnapshot;
        } catch (RejectedExecutionException e) {
            snapshotReading.set(false);
            return cachedSnapshot;
        }
    }

    // ------------------------------------------------------------------ 响应

    private static void writeQuietly(Socket socket, Response response) {
        try {
            OutputStream output = socket.getOutputStream();
            writeResponse(output, response);
        } catch (IOException ignored) {
            // 客户端已断开。
        }
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        byte[] body = response.body == null ? new byte[0] : response.body;
        StringBuilder header = new StringBuilder(160);
        header.append("HTTP/1.1 ").append(response.status).append(' ')
                .append(reason(response.status)).append("\r\n");
        header.append("Content-Type: ").append(response.contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Cache-Control: no-store\r\n");
        header.append("Pragma: no-cache\r\n");
        header.append("Expires: 0\r\n");
        header.append("X-Content-Type-Options: nosniff\r\n");
        header.append("Referrer-Policy: no-referrer\r\n");
        for (Map.Entry<String, String> entry : response.extraHeaders.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        header.append("\r\n");
        output.write(header.toString().getBytes(LATIN_1));
        if (body.length > 0) {
            output.write(body);
        }
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            case 429:
                return "Too Many Requests";
            case 500:
                return "Internal Server Error";
            case 502:
                return "Bad Gateway";
            case 503:
                return "Service Unavailable";
            default:
                return "Unknown";
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败无需上报。
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ multipart

    static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.US);
        int index = lower.indexOf("boundary=");
        if (index < 0) {
            return null;
        }
        String value = contentType.substring(index + "boundary=".length()).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon).trim();
        }
        if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /** 从 multipart body 里取第一个文件段（name="file" 或带 filename 的段）。 */
    static Part multipartFile(byte[] body, String boundary) {
        if (body == null || body.length == 0 || boundary == null || boundary.isEmpty()) {
            return null;
        }
        byte[] marker = ("--" + boundary).getBytes(LATIN_1);
        int position = indexOf(body, marker, 0);
        while (position >= 0) {
            int afterMarker = position + marker.length;
            if (afterMarker + 1 < body.length
                    && body[afterMarker] == '-' && body[afterMarker + 1] == '-') {
                return null;
            }
            if (afterMarker < body.length && body[afterMarker] == '\r') {
                afterMarker++;
                if (afterMarker < body.length && body[afterMarker] == '\n') {
                    afterMarker++;
                }
            }
            int headersEnd = indexOf(body, HEADER_END, afterMarker);
            if (headersEnd < 0) {
                return null;
            }
            String headers = new String(body, afterMarker, headersEnd - afterMarker, LATIN_1);
            int dataStart = headersEnd + HEADER_END.length;
            byte[] closing = concat(CRLF, marker);
            int dataEnd = indexOf(body, closing, dataStart);
            if (dataEnd < 0) {
                return null;
            }
            String disposition = headerValue(headers, "content-disposition");
            String name = attribute(disposition, "name");
            String filename = attribute(disposition, "filename");
            if (filename != null || "file".equals(name)) {
                byte[] data = new byte[dataEnd - dataStart];
                System.arraycopy(body, dataStart, data, 0, data.length);
                return new Part(filename, data);
            }
            position = indexOf(body, marker, dataEnd + CRLF.length);
        }
        return null;
    }

    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** 取 Content-Disposition 里的 name="..." / filename="..."（大小写不敏感）。 */
    private static String attribute(String disposition, String key) {
        if (disposition == null) {
            return null;
        }
        String lower = disposition.toLowerCase(Locale.US);
        int index = lower.indexOf(key + "=");
        while (index >= 0) {
            int valueStart = index + key.length() + 1;
            if (valueStart < disposition.length() && disposition.charAt(valueStart) == '"') {
                int valueEnd = disposition.indexOf('"', valueStart + 1);
                if (valueEnd > valueStart) {
                    return disposition.substring(valueStart + 1, valueEnd);
                }
            } else {
                int valueEnd = disposition.indexOf(';', valueStart);
                if (valueEnd < 0) {
                    valueEnd = disposition.length();
                }
                return disposition.substring(valueStart, valueEnd).trim();
            }
            index = lower.indexOf(key + "=", valueStart);
        }
        return null;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        if (data == null || pattern == null || pattern.length == 0) {
            return -1;
        }
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ URL

    static String decodePercent(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), UTF_8);
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            key = decodePercent(key.replace('+', ' '));
            value = decodePercent(value.replace('+', ' '));
            if (!result.containsKey(key)) {
                result.put(key, value);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 模型

    private static final class Request {

        String method;
        String path;
        String query;
        Map<String, String> headers;
        byte[] body;
        String remoteIp;
    }

    private static final class Response {

        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> extraHeaders = new LinkedHashMap<String, String>();

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    /** multipart 文件段。 */
    static final class Part {

        final String filename;
        final byte[] data;

        Part(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }
    }

    /** 解析/协议错误，映射到 HTTP 状态码与中文原因。 */
    private static final class HttpError extends Exception {

        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** 固定路由；不在表内的路径一律 404。 */
    private enum Route {
        STATE("/api/state"),
        SEARCH("/api/search"),
        PLAY("/api/play"),
        ADD("/api/source/add"),
        UPLOAD("/api/source/upload"),
        TOGGLE("/api/source/toggle"),
        DELETE("/api/source/delete"),
        RENAME("/api/source/rename"),
        LOCATION("/api/source/location"),
        ORDER("/api/source/order"),
        RELOAD("/api/reload");

        private final String path;

        Route(String path) {
            this.path = path;
        }

        static Route of(String path) {
            for (Route route : values()) {
                if (route.path.equals(path)) {
                    return route;
                }
            }
            return null;
        }
    }
}
