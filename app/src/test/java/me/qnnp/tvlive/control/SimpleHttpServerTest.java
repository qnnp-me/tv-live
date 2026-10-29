package me.qnnp.tvlive.control;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import me.qnnp.tvlive.control.StateProvider.Snapshot;
import me.qnnp.tvlive.control.StateProvider.SourceEntry;
import me.qnnp.tvlive.data.SourceRecord;
import me.qnnp.tvlive.data.SourceStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 真实 SimpleHttpServer 回环测试：鉴权、限流、路由、路径校验、multipart 上传、连接饥饿。
 *
 * <p>用 18088-18098 的端口（与 App 默认一致），每个用例独立起停。</p>
 */
public class SimpleHttpServerTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String M3U = "#EXTM3U\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV1\n"
            + "http://example.com/1.m3u8\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV2\n"
            + "http://example.com/2.m3u8\n";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dir;
    private FakeHost host;
    private TokenStore tokens;
    private SourceApi api;
    private DiskStateProvider provider;
    private SimpleHttpServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("files");
        host = new FakeHost(dir);
        tokens = new TokenStore(dir);
        api = new SourceApi(host, new SourceApi.Fetcher() {
            @Override
            public byte[] fetch(String url) throws IOException {
                if (url.contains("bad")) {
                    throw new IOException("HTTP 404");
                }
                return M3U.getBytes(UTF_8);
            }
        });
        provider = new DiskStateProvider(dir);
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1 综合", "央视", "固定源"));
        host.channels.add(new SourceApi.ChannelRef("c2", "湖南卫视", "卫视", "固定源"));
        server = new SimpleHttpServer(api, tokens, new SimpleHttpServer.AssetSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return "<!DOCTYPE html><html><body>直播手机页</body></html>".getBytes(UTF_8);
            }
        }, provider, 18088, 18098);
        port = server.startServer();
        assertTrue("端口应可用", port >= 18088 && port <= 18098);
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    public void portFallsBackWhenBusy() throws Exception {
        // setUp 的实例已占用本轮的起始端口；第二个实例必须顺延到下一个。
        // 不假设固定是 18088：Release/Debug 两套单测并行时可能从 18089 起。
        assertTrue(port >= SimpleHttpServer.FIRST_PORT && port < SimpleHttpServer.LAST_PORT);
        SimpleHttpServer second = new SimpleHttpServer(api, tokens, new SimpleHttpServer.AssetSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return "page".getBytes(UTF_8);
            }
        }, provider, SimpleHttpServer.FIRST_PORT, SimpleHttpServer.LAST_PORT);
        try {
            int actual = second.startServer();
            assertTrue("必须跳过已被占用的 " + port + "（实际 " + actual + "）", actual > port);
            assertTrue(second.isRunning());
        } finally {
            second.shutdown();
        }
    }

    @Test
    public void pathAndLengthGuards() {
        assertFalse(SimpleHttpServer.isSafePath("../etc/passwd"));
        assertFalse(SimpleHttpServer.isSafePath("/a/../b"));
        assertFalse(SimpleHttpServer.isSafePath("/a//b"));
        assertFalse(SimpleHttpServer.isSafePath("api/state"));
        assertTrue(SimpleHttpServer.isSafePath("/api/state"));
        assertTrue(SimpleHttpServer.isSafePath("/"));

        assertTrue(SimpleHttpServer.isBodyAllowed(0L));
        assertTrue(SimpleHttpServer.isBodyAllowed(SimpleHttpServer.MAX_REQUEST_BYTES));
        assertFalse(SimpleHttpServer.isBodyAllowed(SimpleHttpServer.MAX_REQUEST_BYTES + 1L));
        assertFalse(SimpleHttpServer.isBodyAllowed(-1L));

        assertEquals(-1L, SimpleHttpServer.contentLength(null));
        assertEquals(12L, SimpleHttpServer.contentLength("12"));
        assertEquals(-1L, SimpleHttpServer.contentLength("abc"));
        assertEquals(-1L, SimpleHttpServer.contentLength("-3"));
    }

    // ------------------------------------------------------------------ 页面

    @Test
    public void servesPhonePageWithoutPin() throws Exception {
        HttpResult result = request("GET", "/", null, null, null);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("直播手机页"));
        assertTrue(result.contentType.startsWith("text/html"));
    }

    @Test
    public void pingIsPublic() throws Exception {
        HttpResult result = request("GET", "/api/ping", null, null, null);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"pinRequired\":true"));
        assertTrue(result.body.contains("0.1.0-test"));
    }

    @Test
    public void unknownPathIs404() throws Exception {
        assertEquals(404, request("GET", "/nope", pin(), null, null).status);
        assertEquals(404, request("GET", "/api/nope", pin(), null, null).status);
    }

    @Test
    public void pathTraversalIsRejected() throws Exception {
        HttpResult result = request("GET", "/../sources.json", pin(), null, null);
        assertTrue(result.status == 400 || result.status == 404);
        assertFalse(result.body.contains("sources"));
    }

    @Test
    public void wrongMethodIs405() throws Exception {
        assertEquals(405, request("POST", "/api/state", pin(), null, "{}").status);
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    public void stateWithoutPinIs401InChinese() throws Exception {
        HttpResult result = request("GET", "/api/state", null, null, null);
        assertEquals(401, result.status);
        assertTrue(result.body.contains("PIN"));
    }

    @Test
    public void stateWithWrongPinIs401() throws Exception {
        assertEquals(401, request("GET", "/api/state", "000000", null, null).status);
    }

    @Test
    public void pinFromQueryOrHeaderWorks() throws Exception {
        assertEquals(200, request("GET", "/api/state", pin(), null, null).status);
        assertEquals(200, request("GET", "/api/state?pin=" + pin(), null, null, null).status);
    }

    @Test
    public void fiveWrongPinsThrottleWith429() throws Exception {
        for (int i = 0; i < TokenStore.MAX_FAILURES; i++) {
            assertEquals(401, request("GET", "/api/state", "111111", null, null).status);
        }
        HttpResult throttled = request("GET", "/api/state", pin(), null, null);
        assertEquals(429, throttled.status);
        assertTrue(throttled.body.contains("秒后再试"));
        assertNotNull(throttled.retryAfter);
    }

    // ------------------------------------------------------------------ 源操作

    @Test
    public void addSourceViaApiUpdatesState() throws Exception {
        HttpResult added = request("POST", "/api/source/add", pin(),
                "application/json", "{\"url\":\"http://example.com/a.m3u\",\"name\":\"家庭\"}");
        assertEquals(200, added.status);
        assertTrue(added.body.contains("\"ok\":true"));
        assertEquals(1, host.reloads);

        HttpResult state = request("GET", "/api/state", pin(), null, null);
        assertEquals(200, state.status);
        assertTrue(state.body.contains("\"name\":\"家庭\""));
        assertTrue(state.body.contains("\"channels\":2"));
        assertTrue(state.body.contains("\"kind\":\"URL\""));
    }

    @Test
    public void stateIsServedFromSnapshotWithoutParsingCaches() throws Exception {
        addUrl("http://example.com/a.m3u", "A");
        SourceRecord record = SourceStore.load(dir).get(0);
        java.io.FileOutputStream out = new java.io.FileOutputStream(
                SourceStore.cacheFile(dir, record));
        try {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        } finally {
            out.close();
        }
        long started = System.currentTimeMillis();
        HttpResult state = request("GET", "/api/state", pin(), null, null);
        long elapsed = System.currentTimeMillis() - started;
        assertEquals(200, state.status);
        assertTrue(state.body.contains("\"ready\":true"));
        assertTrue(state.body.contains("\"channels\":2"));
        assertFalse(state.body.contains("不是 m3u"));
        assertFalse(state.body.contains("缓存"));
        assertTrue("state 应立刻返回（实际 " + elapsed + "ms）", elapsed < 2000L);
    }

    @Test
    public void stopDoesNotWaitForAcceptThread() throws Exception {
        long started = System.nanoTime();
        server.shutdown();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertFalse(server.isRunning());
        assertEquals(-1, server.port());
        assertTrue("shutdown 不得 join 等待 accept 线程（实际 " + elapsedMs + "ms）",
                elapsedMs < 500L);
        server = null;
    }

    @Test
    public void idleClientConnectionsDoNotStarvePage() throws Exception {
        // 模拟手机浏览器的多条预连接：固定线程/队列优先的池会被全部占住。
        List<Socket> idle = new ArrayList<Socket>();
        try {
            for (int i = 0; i < 5; i++) {
                idle.add(new Socket("127.0.0.1", port));
            }
            long started = System.currentTimeMillis();
            HttpResult page = request("GET", "/", null, null, null);
            long elapsed = System.currentTimeMillis() - started;
            assertEquals(200, page.status);
            assertTrue("空闲连接不应拖住页面（实际 " + elapsed + "ms）", elapsed < 1500L);
        } finally {
            for (Socket socket : idle) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 清理失败不影响断言。
                }
            }
        }
    }

    @Test
    public void slowConnectionsStayWithinBoundedPool() throws Exception {
        // 10 条慢连接（连上但不发请求）：线程数必须停在 max，不能一连接一线程。
        List<Socket> slow = new ArrayList<Socket>();
        try {
            for (int i = 0; i < 10; i++) {
                slow.add(new Socket("127.0.0.1", port));
            }
            long deadline = System.currentTimeMillis() + 3000L;
            while (server.clientPoolSize() < SimpleHttpServer.POOL_MAX
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertEquals("慢连接不得突破最大线程数",
                    SimpleHttpServer.POOL_MAX, server.clientPoolSize());

            // 关掉慢连接后页面必须马上恢复（队列里的任务会立刻 EOF 退出）。
            for (Socket socket : slow) {
                socket.close();
            }
            slow.clear();
            long started = System.currentTimeMillis();
            HttpResult page = request("GET", "/", null, null, null);
            long elapsed = System.currentTimeMillis() - started;
            assertEquals(200, page.status);
            assertTrue("慢连接释放后页面应恢复（实际 " + elapsed + "ms）", elapsed < 3000L);
            assertTrue("线程数回落到上限内", server.clientPoolSize()
                    <= SimpleHttpServer.POOL_MAX);
        } finally {
            for (Socket socket : slow) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 清理失败不影响断言。
                }
            }
        }
    }

    @Test
    public void clientPoolIsBounded() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch running = new CountDownLatch(SimpleHttpServer.POOL_MAX);
        ThreadPoolExecutor pool = SimpleHttpServer.newClientPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "TvLive-http-test");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            assertEquals(SimpleHttpServer.POOL_CORE, pool.getCorePoolSize());
            assertEquals(SimpleHttpServer.POOL_MAX, pool.getMaximumPoolSize());
            assertEquals(SimpleHttpServer.POOL_QUEUE, pool.getQueue().remainingCapacity());
            assertTrue("满了要抛拒绝给 accept 线程回 503",
                    pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);

            Runnable blocker = new Runnable() {
                @Override
                public void run() {
                    running.countDown();
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            int capacity = SimpleHttpServer.POOL_MAX + SimpleHttpServer.POOL_QUEUE;
            for (int i = 0; i < capacity; i++) {
                pool.execute(blocker);
            }
            assertTrue("池内任务应立刻占到 max 线程", running.await(3, TimeUnit.SECONDS));
            assertEquals("线程数严格等于最大线程数",
                    SimpleHttpServer.POOL_MAX, pool.getPoolSize());
            assertEquals(SimpleHttpServer.POOL_QUEUE, pool.getQueue().size());

            try {
                pool.execute(blocker);
                fail("超出 最大线程 + 队列 后必须拒绝，由调用方回 503");
            } catch (RejectedExecutionException expected) {
                // 预期：不再新建线程。
            }
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void socketTimeoutIsFiveSeconds() {
        assertEquals(5000, SimpleHttpServer.SOCKET_TIMEOUT_MS);
    }

    @Test
    public void pageDoesNotWaitForBlockingProvider() throws Exception {
        FixedStateProvider blocking = new FixedStateProvider();
        blocking.blocking = true;
        SimpleHttpServer guarded = new SimpleHttpServer(api, tokens, new SimpleHttpServer.AssetSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return "<html>page</html>".getBytes(UTF_8);
            }
        }, blocking, 18120, 18130);
        try {
            int guardedPort = guarded.startServer();
            assertTrue(guardedPort > 0);

            long started = System.currentTimeMillis();
            HttpResult page = requestOn(guardedPort, "GET", "/", null, null, (byte[]) null);
            long pageElapsed = System.currentTimeMillis() - started;
            assertEquals(200, page.status);
            assertTrue(page.body.contains("page"));
            assertTrue("页面不依赖 provider（实际 " + pageElapsed + "ms）", pageElapsed < 1000L);

            started = System.currentTimeMillis();
            HttpResult state = requestOn(guardedPort, "GET", "/api/state", pin(), null,
                    (byte[]) null);
            long stateElapsed = System.currentTimeMillis() - started;
            assertEquals(200, state.status);
            assertTrue(state.body.contains("\"ready\":false"));
            assertTrue("provider 卡住时 state 也不能等（实际 " + stateElapsed + "ms）",
                    stateElapsed < 1000L);
        } finally {
            guarded.shutdown();
        }
    }

    @Test
    public void stateFallsBackToCachedSnapshotWhenProviderBlocks() throws Exception {
        FixedStateProvider fixed = new FixedStateProvider();
        SimpleHttpServer guarded = new SimpleHttpServer(api, tokens, new SimpleHttpServer.AssetSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return "page".getBytes(UTF_8);
            }
        }, fixed, 18132, 18142);
        try {
            int guardedPort = guarded.startServer();
            assertTrue(guardedPort > 0);

            HttpResult first = requestOn(guardedPort, "GET", "/api/state", pin(), null,
                    (byte[]) null);
            assertEquals(200, first.status);
            assertTrue(first.body.contains("\"channels\":7"));

            fixed.blocking = true;
            long started = System.currentTimeMillis();
            HttpResult cached = requestOn(guardedPort, "GET", "/api/state", pin(), null,
                    (byte[]) null);
            long elapsed = System.currentTimeMillis() - started;
            assertEquals(200, cached.status);
            assertTrue("应返回上一份快照", cached.body.contains("\"channels\":7"));
            assertTrue("provider 卡住时不能阻塞请求（实际 " + elapsed + "ms）", elapsed < 1000L);
        } finally {
            guarded.shutdown();
        }
    }

    @Test
    public void stateIsNotReadyWithoutProvider() throws Exception {
        SimpleHttpServer bare = new SimpleHttpServer(api, tokens, new SimpleHttpServer.AssetSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return "page".getBytes(UTF_8);
            }
        }, null, 18100, 18110);
        try {
            int barePort = bare.startServer();
            assertTrue(barePort > 0);
            HttpResult state = requestOn(barePort, "GET", "/api/state", pin(), null, (byte[]) null);
            assertEquals(200, state.status);
            assertTrue(state.body.contains("\"ready\":false"));
            assertTrue(state.body.contains("TV 正在加载"));
        } finally {
            bare.shutdown();
        }
    }

    @Test
    public void addBadUrlReturnsChineseError() throws Exception {
        HttpResult result = request("POST", "/api/source/add", pin(),
                "application/json", "{\"url\":\"nope\"}");
        assertEquals(400, result.status);
        assertTrue(result.body.contains("http://"));
        assertEquals(0, host.reloads);
    }

    @Test
    public void addDownloadFailureReturns502() throws Exception {
        HttpResult result = request("POST", "/api/source/add", pin(),
                "application/json", "{\"url\":\"http://example.com/bad.m3u\"}");
        assertEquals(502, result.status);
        assertTrue(result.body.contains("下载失败"));
    }

    @Test
    public void malformedJsonIs400() throws Exception {
        HttpResult result = request("POST", "/api/source/add", pin(),
                "application/json", "{oops}");
        assertEquals(400, result.status);
        assertTrue(result.body.contains("请求格式错误"));
    }

    @Test
    public void toggleRenameOrderDeleteWork() throws Exception {
        addUrl("http://example.com/a.m3u", "A");
        addUrl("http://example.com/b.m3u", "B");

        assertEquals(200, post("/api/source/toggle", "{\"id\":\"s1\",\"enabled\":false}").status);
        HttpResult state = request("GET", "/api/state", pin(), null, null);
        assertTrue(state.body.contains("\"enabled\":false"));

        assertEquals(200, post("/api/source/rename", "{\"id\":\"s1\",\"name\":\"新名\"}").status);
        assertEquals(200, post("/api/source/order", "{\"id\":\"s2\",\"delta\":-1}").status);

        List<SourceRecord> records = SourceStore.load(dir);
        assertEquals("B", records.get(0).name());
        assertEquals("新名", records.get(1).name());

        assertEquals(200, post("/api/source/delete", "{\"id\":\"s1\"}").status);
        assertEquals(1, SourceStore.load(dir).size());
    }

    @Test
    public void locationEndpointChangesSourceAndReloads() throws Exception {
        addUrl("http://example.com/a.m3u", "A");
        int reloadsBefore = host.reloads;

        HttpResult result = post("/api/source/location",
                "{\"id\":\"s1\",\"url\":\"http://example.com/b.m3u\"}");
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"location\":\"http://example.com/b.m3u\""));
        assertEquals(reloadsBefore + 1, host.reloads);

        HttpResult state = request("GET", "/api/state", pin(), null, null);
        assertTrue(state.body.contains("\"location\":\"http://example.com/b.m3u\""));
    }

    @Test
    public void locationEndpointRequiresPinAndRejectsUnknownSource() throws Exception {
        addUrl("http://example.com/a.m3u", "A");
        assertEquals(401, request("POST", "/api/source/location", null,
                "application/json", "{\"id\":\"s1\",\"url\":\"http://example.com/b.m3u\"}")
                .status);
        assertEquals(404, post("/api/source/location",
                "{\"id\":\"s9\",\"url\":\"http://example.com/b.m3u\"}").status);
    }

    @Test
    public void reloadEndpointRequestsTvReload() throws Exception {
        int before = host.reloads;
        HttpResult result = post("/api/reload", "{}");
        assertEquals(200, result.status);
        assertEquals(before + 1, host.reloads);
    }

    // -------------------------------------------------- search / play（3b）

    @Test
    public void searchEndpointRequiresPinAndMatchesName() throws Exception {
        assertEquals(401, request("GET", "/api/search?q=CCTV", null, null, null).status);

        HttpResult result = request("GET", "/api/search?q=cctv", pin(), null, null);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"id\":\"c1\""));
        assertTrue(result.body.contains("\"name\":\"CCTV1 综合\""));
        assertFalse(result.body.contains("\"id\":\"c2\""));
        assertTrue(result.body.contains("\"total\":1"));
    }

    @Test
    public void searchEmptyQueryReturnsEmptyResults() throws Exception {
        HttpResult result = request("GET", "/api/search?q=", pin(), null, null);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"results\":[]"));
    }

    @Test
    public void searchIsGetOnly() throws Exception {
        assertEquals(405, post("/api/search?q=cctv", "{}").status);
    }

    @Test
    public void playEndpointSwitchesChannelOnTv() throws Exception {
        HttpResult result = post("/api/play", "{\"id\":\"c2\"}");
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"ok\":true"));
        assertEquals(1, host.played.size());
        assertEquals("c2", host.played.get(0));
    }

    @Test
    public void playUnknownChannelIs404WithChineseReason() throws Exception {
        HttpResult result = post("/api/play", "{\"id\":\"nope\"}");
        assertEquals(404, result.status);
        assertTrue(result.body.contains("频道不存在"));
        assertTrue(host.played.isEmpty());
    }

    @Test
    public void playRequiresPostAndPin() throws Exception {
        assertEquals(401, request("POST", "/api/play", null, "application/json",
                "{\"id\":\"c1\"}").status);
        assertEquals(405, request("GET", "/api/play", pin(), null, null).status);
    }

    @Test
    public void qrTokenWorksViaQueryAndHeader() throws Exception {
        String token = tokens.issueToken();
        assertEquals(200, request("GET", "/api/state?t=" + token, null, null, null).status);
        assertEquals(200, request("GET", "/api/state", token, null, null).status);
        assertEquals(200, request("GET", "/api/search?q=CCTV&t=" + token,
                null, null, null).status);
    }

    @Test
    public void qrTokenIsAcceptedViaXTvLiveTokenHeader() throws Exception {
        // 手机页 token 模式固定发 X-TvLive-Token（不再把 token 塞进 X-Pin）。
        String token = tokens.issueToken();
        HttpResult result = requestWithHeader("GET", "/api/state",
                SimpleHttpServer.HEADER_LEGACY_PIN, token);
        assertEquals(200, result.status);
        assertTrue(result.body.contains("\"ready\""));
    }

    @Test
    public void uploadMultipartSavesFileSource() throws Exception {
        String boundary = "----TvLiveBoundary7d91";
        byte[] body = multipart(boundary, "list.m3u", M3U.getBytes(UTF_8));
        HttpResult result = requestBytes("POST", "/api/source/upload", pin(),
                "multipart/form-data; boundary=" + boundary, body);
        assertEquals(200, result.status);
        List<SourceRecord> records = SourceStore.load(dir);
        assertEquals(1, records.size());
        assertEquals(SourceRecord.Kind.FILE, records.get(0).kind());
        assertEquals("list", records.get(0).name());
        assertTrue(SourceStore.cacheFile(dir, records.get(0)).isFile());
    }

    @Test
    public void uploadRawBodyWithoutFilenameIsAccepted() throws Exception {
        HttpResult result = request("POST", "/api/source/upload", pin(),
                "application/octet-stream", M3U);
        // 不带文件名时只按内容校验，源名回退为「上传源」。
        assertEquals(200, result.status);
        assertEquals("上传源", SourceStore.load(dir).get(0).name());
    }

    @Test
    public void uploadRejectsNonM3uExtension() throws Exception {
        String boundary = "----TvLiveBoundary7d92";
        byte[] body = multipart(boundary, "list.txt", M3U.getBytes(UTF_8));
        HttpResult result = requestBytes("POST", "/api/source/upload", pin(),
                "multipart/form-data; boundary=" + boundary, body);
        assertEquals(400, result.status);
        assertTrue(result.body.contains(".m3u"));
        assertTrue(SourceStore.load(dir).isEmpty());
    }

    @Test
    public void successBodyIsNotAnExceptionDump() throws Exception {
        HttpResult result = request("POST", "/api/source/add", pin(),
                "application/json", "{\"url\":\"http://example.com/a.m3u\"}");
        assertEquals(200, result.status);
        assertFalse(result.body.contains("Exception"));
    }

    @Test
    public void multipartFindsFileAfterOtherFields() {
        String boundary = "b1";
        String body = "--b1\r\n"
                + "Content-Disposition: form-data; name=\"note\"\r\n\r\n"
                + "hello\r\n"
                + "--b1\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.m3u\"\r\n"
                + "Content-Type: audio/x-mpegurl\r\n\r\n"
                + "#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://example.com/1.m3u8\n"
                + "\r\n--b1--\r\n";
        SimpleHttpServer.Part part = SimpleHttpServer.multipartFile(
                body.getBytes(UTF_8), boundary);
        assertNotNull(part);
        assertEquals("a.m3u", part.filename);
        assertTrue(new String(part.data, UTF_8).startsWith("#EXTM3U"));
    }

    @Test
    public void responseUsesHttp11WithLengthAndClose() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n")
                    .getBytes(UTF_8));
            output.flush();
            String raw = new String(readAllBytes(socket.getInputStream()), UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200 "));
            assertTrue(raw.contains("Content-Type: text/html; charset=utf-8"));
            assertTrue(raw.contains("Content-Length: "));
            assertTrue(raw.contains("Connection: close"));
            assertTrue(raw.endsWith("直播手机页</body></html>"));
        } finally {
            socket.close();
        }
    }

    @Test
    public void malformedRequestIsRejectedWithoutHanging() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            output.write("GARBAGE\r\n\r\n".getBytes(UTF_8));
            output.flush();
            String statusLine = readStatusLine(socket.getInputStream());
            assertTrue("应返回 400，实际：" + statusLine,
                    statusLine != null && statusLine.contains(" 400 "));
        } finally {
            socket.close();
        }
    }

    @Test
    public void oversizeContentLengthIsRejectedBeforeBody() throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(3000);
            OutputStream output = socket.getOutputStream();
            String head = "POST /api/source/upload HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + SimpleHttpServer.HEADER_PIN + ": " + pin() + "\r\n"
                    + "Content-Length: " + (SimpleHttpServer.MAX_REQUEST_BYTES + 1) + "\r\n\r\n";
            output.write(head.getBytes(UTF_8));
            output.flush();
            String statusLine = readStatusLine(socket.getInputStream());
            assertTrue("应返回 413，实际：" + statusLine,
                    statusLine != null && statusLine.contains(" 413 "));
        } finally {
            socket.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readStatusLine(InputStream input) throws IOException {
        StringBuilder out = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                out.append((char) value);
            }
        }
        return out.toString();
    }

    private void addUrl(String url, String name) throws Exception {
        HttpResult result = post("/api/source/add",
                "{\"url\":\"" + url + "\",\"name\":\"" + name + "\"}");
        assertEquals(200, result.status);
    }

    private HttpResult post(String path, String json) throws Exception {
        return request("POST", path, pin(), "application/json", json);
    }

    private String pin() {
        return tokens.pin();
    }

    private static byte[] multipart(String boundary, String filename, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(("--" + boundary + "\r\n").getBytes(UTF_8));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"\r\n").getBytes(UTF_8));
            out.write("Content-Type: audio/x-mpegurl\r\n\r\n".getBytes(UTF_8));
            out.write(content);
            out.write("\r\n".getBytes(UTF_8));
            out.write(("--" + boundary + "--\r\n").getBytes(UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private HttpResult request(String method, String path, String pin, String contentType,
            String body) throws Exception {
        return requestOn(port, method, path, pin, contentType,
                body == null ? null : body.getBytes(UTF_8));
    }

    private HttpResult requestOn(int targetPort, String method, String path, String pin,
            String contentType, String body) throws Exception {
        return requestOn(targetPort, method, path, pin, contentType,
                body == null ? null : body.getBytes(UTF_8));
    }

    private HttpResult requestBytes(String method, String path, String pin, String contentType,
            byte[] body) throws Exception {
        return requestOn(port, method, path, pin, contentType, body);
    }

    private HttpResult requestWithHeader(String method, String path, String header,
            String value) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty(header, value);
        int status = connection.getResponseCode();
        InputStream input = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String text = input == null ? "" : readAll(input);
        connection.disconnect();
        return new HttpResult(status, text, "", null);
    }

    private HttpResult requestOn(int targetPort, String method, String path, String pin,
            String contentType, byte[] body) throws Exception {        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + targetPort + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Connection", "close");
        if (pin != null) {
            connection.setRequestProperty(SimpleHttpServer.HEADER_PIN, pin);
        }
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = input == null ? "" : readAll(input);
        String retryAfter = connection.getHeaderField("Retry-After");
        String type = connection.getContentType() == null ? "" : connection.getContentType();
        connection.disconnect();
        return new HttpResult(status, text, type, retryAfter);
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), UTF_8);
        } finally {
            input.close();
        }
    }

    private static final class HttpResult {

        final int status;
        final String body;
        final String contentType;
        final String retryAfter;

        HttpResult(int status, String body, String contentType, String retryAfter) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
            this.retryAfter = retryAfter;
        }
    }

    /** 固定快照；blocking=true 时故意拖 5 秒，模拟被误实现成慢操作的 provider。 */
    private static final class FixedStateProvider implements StateProvider {

        volatile boolean blocking;

        @Override
        public Snapshot snapshot() {
            if (blocking) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Snapshot.notReady();
                }
            }
            List<SourceEntry> entries = new ArrayList<SourceEntry>();
            entries.add(new SourceEntry("s1", "固定源", "URL", true, 0, 7, null));
            return new Snapshot(true, 7, "c1", "CCTV1", null, entries);
        }
    }

    /** 模拟 TV 快照：只读 sources.json 元数据，不解析 m3u。 */
    private static final class DiskStateProvider implements StateProvider {

        private final File dir;

        DiskStateProvider(File dir) {
            this.dir = dir;
        }

        @Override
        public Snapshot snapshot() {
            try {
                List<SourceRecord> records = SourceStore.load(dir);
                List<SourceEntry> entries = new ArrayList<SourceEntry>(records.size());
                int channels = 0;
                for (SourceRecord record : records) {
                    entries.add(new SourceEntry(record.id(), record.name(),
                            record.kind().name(), record.enabled(), record.order(),
                            record.channelCount(), record.lastError(),
                            record.isUrl() ? record.location() : null));
                    if (record.enabled()) {
                        channels += record.channelCount();
                    }
                }
                return new Snapshot(true, channels, null, null, null, entries);
            } catch (IOException e) {
                return new Snapshot(false, 0, null, null, e.getMessage(),
                        Collections.<SourceEntry>emptyList());
            }
        }
    }

    private static final class FakeHost implements SourceApi.Host {

        private final File dir;
        private int reloads;
        private final List<SourceApi.ChannelRef> channels =
                new ArrayList<SourceApi.ChannelRef>();
        private final List<String> played = new ArrayList<String>();

        FakeHost(File dir) {
            this.dir = dir;
        }

        @Override
        public File filesDir() {
            return dir;
        }

        @Override
        public String appVersion() {
            return "0.1.0-test";
        }

        @Override
        public void requestReload() {
            reloads++;
        }

        @Override
        public List<SourceApi.ChannelRef> channels() {
            return channels;
        }

        @Override
        public boolean play(String id) {
            played.add(id);
            return true;
        }
    }
}
