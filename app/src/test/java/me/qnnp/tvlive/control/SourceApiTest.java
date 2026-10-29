package me.qnnp.tvlive.control;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.qnnp.tvlive.control.StateProvider.Snapshot;
import me.qnnp.tvlive.control.StateProvider.SourceEntry;
import me.qnnp.tvlive.data.SourceRecord;
import me.qnnp.tvlive.data.SourceStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SourceApiTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String M3U = "#EXTM3U\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV1\n"
            + "http://example.com/1.m3u8\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV2\n"
            + "http://example.com/2.m3u8\n"
            + "#EXTINF:-1 group-title=\"卫视\",湖南卫视\n"
            + "http://example.com/3.m3u8\n";

    private static final String M3U_NEW = "#EXTM3U\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV5\n"
            + "http://example.com/5.m3u8\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV6\n"
            + "http://example.com/6.m3u8\n";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dir;
    private FakeHost host;
    private FakeFetcher fetcher;
    private DiskStateProvider provider;
    private SourceApi api;

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("files");
        host = new FakeHost(dir);
        fetcher = new FakeFetcher();
        provider = new DiskStateProvider(dir);
        api = new SourceApi(host, fetcher);
    }

    // ------------------------------------------------------------------ state

    private SourceApi.Result state() {
        return api.state(provider.snapshot());
    }

    @Test
    public void stateWithoutSnapshotIsNotReady() {
        SourceApi.Result result = api.state();
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"ready\":false"));
        assertTrue(result.json().contains("\"version\":\"0.1.0-test\""));
        assertTrue(result.json().contains("\"sources\":[]"));
        assertTrue(result.json().contains("TV 正在加载"));
    }

    @Test
    public void stateReportsCurrentChannel() {
        provider.currentId = "c1";
        provider.currentName = "CCTV1";
        SourceApi.Result result = state();
        assertTrue(result.json().contains("\"ready\":true"));
        assertTrue(result.json().contains("\"current\":{\"id\":\"c1\",\"name\":\"CCTV1\"}"));
    }

    @Test
    public void stateUsesSnapshotWithoutParsingCaches() throws Exception {
        api.add("http://example.com/a.m3u", "磁盘源");
        byte[] indexBefore = readAll(SourceStore.indexFile(dir));

        // 快照里的数字/名称和磁盘完全不同：state 必须只回快照。
        List<SourceEntry> entries = new ArrayList<SourceEntry>();
        entries.add(new SourceEntry("sX", "快照源", "URL", true, 0, 42, "快照错误"));
        Snapshot snapshot = new Snapshot(true, 577, "c9", "CCTV9", null, entries);

        SourceApi.Result result = api.state(snapshot);
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"channels\":577"));
        assertTrue(result.json().contains("\"name\":\"快照源\""));
        assertTrue(result.json().contains("\"channels\":42"));
        assertTrue(result.json().contains("\"lastError\":\"快照错误\""));
        assertFalse(result.json().contains("磁盘源"));

        // 不再 loadAll：索引文件内容一个字节都不该被改写。
        assertTrue(java.util.Arrays.equals(indexBefore, readAll(SourceStore.indexFile(dir))));
    }

    @Test
    public void stateNeverTouchesFilesDir() {
        SourceApi.Host exploding = new SourceApi.Host() {
            @Override
            public File filesDir() {
                throw new AssertionError("state 不应访问文件系统");
            }

            @Override
            public String appVersion() {
                return "0.1.0-test";
            }

            @Override
            public void requestReload() {
                // state 不应触发重载。
                throw new AssertionError("state 不应触发重载");
            }

            @Override
            public List<SourceApi.ChannelRef> channels() {
                // state 不应读取频道快照。
                throw new AssertionError("state 不应读取频道快照");
            }

            @Override
            public boolean play(String id) {
                throw new AssertionError("state 不应切台");
            }
        };
        SourceApi isolated = new SourceApi(exploding, fetcher);
        SourceApi.Result result = isolated.state(provider.snapshot());
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"ready\":true"));
    }

    @Test
    public void stateIgnoresBrokenCaches() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        SourceRecord record = SourceStore.load(dir).get(0);
        java.io.FileOutputStream out = new java.io.FileOutputStream(
                SourceStore.cacheFile(dir, record));
        try {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        } finally {
            out.close();
        }
        SourceApi.Result result = state();
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"ready\":true"));
        assertTrue(result.json().contains("\"channels\":3"));
        assertFalse(result.json().contains("不是 m3u"));
        assertFalse(result.json().contains("缓存"));
    }

    // -------------------------------------------------------------------- add

    @Test
    public void addUrlSourceDownloadsValidatesAndReloads() throws Exception {
        SourceApi.Result result = api.add("http://example.com/list.m3u", "家庭源");
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"ok\":true"));
        assertTrue(result.json().contains("\"name\":\"家庭源\""));
        assertTrue(result.json().contains("\"channels\":3"));
        assertEquals(1, host.reloads);

        List<SourceRecord> records = SourceStore.load(dir);
        assertEquals(1, records.size());
        SourceRecord record = records.get(0);
        assertEquals("s1", record.id());
        assertEquals(SourceRecord.Kind.URL, record.kind());
        assertTrue(SourceStore.cacheFile(dir, record).isFile());

        SourceApi.Result state = state();
        assertTrue(state.json().contains("\"channels\":3"));
        assertTrue(state.json().contains("\"id\":\"s1\""));
    }

    @Test
    public void addDerivesNameFromUrl() throws Exception {
        api.add("http://example.com/iptv/family.m3u", null);
        assertEquals("family", SourceStore.load(dir).get(0).name());
    }

    @Test
    public void addRejectsBadUrlWithoutNetworkAccess() {
        assertEquals(400, api.add("", null).status());
        assertEquals(400, api.add("ftp://example.com/a.m3u", null).status());
        assertEquals(400, api.add("   ", null).status());
        assertEquals(400, api.add("http://example.com/" + repeat('a', 2100), null).status());
        assertEquals(0, fetcher.calls);
        assertEquals(0, host.reloads);
    }

    @Test
    public void addRejectsDuplicateAddress() throws Exception {
        api.add("http://example.com/list.m3u", null);
        SourceApi.Result second = api.add("http://example.com/list.m3u", null);
        assertEquals(409, second.status());
        assertTrue(second.json().contains("已存在"));
        assertEquals(1, SourceStore.load(dir).size());
    }

    @Test
    public void addRejectsInvalidPlaylist() throws Exception {
        fetcher.body = "not a playlist".getBytes(UTF_8);
        SourceApi.Result result = api.add("http://example.com/list.m3u", null);
        assertEquals(400, result.status());
        assertEquals(0, host.reloads);
        assertEquals(0, SourceStore.load(dir).size());
    }

    @Test
    public void addReportsDownloadFailure() {
        fetcher.failure = new IOException("HTTP 404");
        SourceApi.Result result = api.add("http://example.com/list.m3u", null);
        assertEquals(502, result.status());
        assertTrue(result.json().contains("下载失败"));
        assertEquals(0, host.reloads);
    }

    @Test
    public void addReportsTlsHint() {
        fetcher.failure = new IOException("SSL handshake aborted");
        SourceApi.Result result = api.add("https://example.com/list.m3u", null);
        assertEquals(502, result.status());
        assertTrue(result.json().contains("https"));
    }

    @Test
    public void addStopsAtEightSources() throws Exception {
        for (int i = 1; i <= SourceStore.MAX_SOURCES; i++) {
            assertEquals(200, api.add("http://example.com/" + i + ".m3u", "源" + i).status());
        }
        SourceApi.Result overflow = api.add("http://example.com/9.m3u", null);
        assertEquals(409, overflow.status());
        assertTrue(overflow.json().contains("最多"));
        assertEquals(SourceStore.MAX_SOURCES, SourceStore.load(dir).size());
    }

    // ----------------------------------------------------------------- upload

    @Test
    public void uploadSavesFileSource() throws Exception {
        SourceApi.Result result = api.upload(M3U.getBytes(UTF_8), "家庭列表.m3u");
        assertEquals(200, result.status());
        List<SourceRecord> records = SourceStore.load(dir);
        assertEquals(1, records.size());
        assertEquals(SourceRecord.Kind.FILE, records.get(0).kind());
        assertEquals("家庭列表", records.get(0).name());
        assertNotNull(records.get(0).cachePath());
        assertTrue(SourceStore.cacheFile(dir, records.get(0)).isFile());
        assertEquals(1, host.reloads);
    }

    @Test
    public void uploadRejectsWrongExtension() {
        SourceApi.Result result = api.upload(M3U.getBytes(UTF_8), "list.txt");
        assertEquals(400, result.status());
        assertTrue(result.json().contains(".m3u"));
    }

    @Test
    public void uploadRejectsOversizeFile() {
        byte[] big = new byte[(int) M3uImport.MAX_BYTES + 1];
        big[0] = '#';
        SourceApi.Result result = api.upload(big, "big.m3u");
        assertEquals(413, result.status());
        assertTrue(result.json().contains("5MB"));
    }

    @Test
    public void uploadRejectsEmptyAndInvalidContent() {
        assertEquals(400, api.upload(new byte[0], "a.m3u").status());
        assertEquals(400, api.upload("hello".getBytes(UTF_8), "a.m3u").status());
    }

    @Test
    public void uploadAcceptsMissingFilename() throws Exception {
        SourceApi.Result result = api.upload(M3U.getBytes(UTF_8), null);
        assertEquals(200, result.status());
        assertEquals("上传源", SourceStore.load(dir).get(0).name());
    }

    // ------------------------------------------------------- toggle/rename/order

    @Test
    public void toggleChangesEnabledAndPersists() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        SourceApi.Result result = api.toggle("s1", false);
        assertEquals(200, result.status());
        assertFalse(SourceStore.load(dir).get(0).enabled());
        assertTrue(state().json().contains("\"enabled\":false"));
        assertEquals(200, api.toggle("s1", true).status());
        assertTrue(SourceStore.load(dir).get(0).enabled());
    }

    @Test
    public void toggleUnknownSourceIs404() {
        assertEquals(404, api.toggle("s9", false).status());
    }

    @Test
    public void renameTrimsAndPersists() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        SourceApi.Result result = api.rename("s1", "  新名字  ");
        assertEquals(200, result.status());
        assertEquals("新名字", SourceStore.load(dir).get(0).name());
        assertEquals(400, api.rename("s1", "   ").status());
        assertEquals(400, api.rename("s1", null).status());
        assertEquals(404, api.rename("s9", "x").status());
    }

    @Test
    public void renameTruncatesLongNames() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        String longName = repeat('名', 60);
        assertEquals(200, api.rename("s1", longName).status());
        assertEquals(SourceApi.MAX_NAME_LENGTH, SourceStore.load(dir).get(0).name().length());
    }

    @Test
    public void orderMovesSourceAndRenumbers() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        api.add("http://example.com/b.m3u", "B");
        api.add("http://example.com/c.m3u", "C");

        assertEquals(200, api.order("s3", -1).status());
        List<SourceRecord> records = SourceStore.load(dir);
        assertEquals("A", records.get(0).name());
        assertEquals("C", records.get(1).name());
        assertEquals("B", records.get(2).name());
        assertEquals(1, records.get(1).order());

        // 同一个源再上移到顶。
        assertEquals(200, api.order("s3", -1).status());
        assertEquals("C", SourceStore.load(dir).get(0).name());

        // 顶了再上移 = 边界错误，顺序不变。
        SourceApi.Result edge = api.order("s3", -1);
        assertEquals(400, edge.status());
        assertTrue(edge.json().contains("边界"));
        assertEquals("C", SourceStore.load(dir).get(0).name());
    }

    @Test
    public void orderRejectsInvalidDeltaAndUnknownSource() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        assertEquals(400, api.order("s1", 0).status());
        assertEquals(400, api.order("s1", 2).status());
        assertEquals(404, api.order("s9", 1).status());
    }

    @Test
    public void deleteRemovesSourceAndCache() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        api.add("http://example.com/b.m3u", "B");
        File cache = SourceStore.cacheFile(dir, SourceStore.load(dir).get(0));

        assertEquals(200, api.delete("s1").status());
        assertEquals(1, SourceStore.load(dir).size());
        assertFalse(cache.exists());
        assertEquals(404, api.delete("s1").status());
    }

    @Test
    public void deleteLastSourceClearsEverything() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        assertEquals(200, api.delete("s1").status());
        assertTrue(SourceStore.load(dir).isEmpty());
        assertFalse(SourceStore.indexFile(dir).exists());
        assertEquals(2, host.reloads);
    }

    // ------------------------------------------------------- 改地址（3b）

    @Test
    public void changeLocationReplacesUrlKeepsMetadataAndReloads() throws Exception {
        api.add("http://example.com/a.m3u", "家庭源");
        api.add("http://example.com/b.m3u", "备用源");
        api.toggle("s1", false);
        int reloadsBefore = host.reloads;
        fetcher.body = M3U_NEW.getBytes(UTF_8);

        SourceApi.Result result = api.changeLocation("s1", "http://example.com/new.m3u");
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"name\":\"家庭源\""));
        assertTrue(result.json().contains("\"location\":\"http://example.com/new.m3u\""));

        List<SourceRecord> records = SourceStore.load(dir);
        SourceRecord changed = records.get(0);
        assertEquals("s1", changed.id());
        assertEquals("家庭源", changed.name());
        assertFalse("启用状态应保留", changed.enabled());
        assertEquals("顺序应保留", 0, changed.order());
        assertEquals(SourceRecord.Kind.URL, changed.kind());
        assertEquals("http://example.com/new.m3u", changed.location());
        assertEquals(2, changed.channelCount());
        assertEquals(reloadsBefore + 1, host.reloads);

        String cache = new String(readAll(SourceStore.cacheFile(dir, changed)), UTF_8);
        assertTrue(cache.contains("CCTV5"));
        assertFalse(cache.contains("CCTV1"));

        String state = state().json();
        assertTrue(state.contains("\"location\":\"http://example.com/new.m3u\""));
        assertTrue(state.contains("\"channels\":2"));
    }

    @Test
    public void changeLocationDownloadFailureKeepsOldSourceAndCache() throws Exception {
        api.add("http://example.com/a.m3u", "家庭源");
        SourceRecord before = SourceStore.load(dir).get(0);
        byte[] cacheBefore = readAll(SourceStore.cacheFile(dir, before));
        int reloadsBefore = host.reloads;

        fetcher.failure = new IOException("HTTP 500");
        SourceApi.Result result = api.changeLocation("s1", "http://example.com/new.m3u");
        assertEquals(502, result.status());
        assertTrue(result.json().contains("下载失败"));

        SourceRecord after = SourceStore.load(dir).get(0);
        assertEquals(before.location(), after.location());
        assertEquals(before.kind(), after.kind());
        assertEquals(before.channelCount(), after.channelCount());
        assertEquals(before.name(), after.name());
        assertTrue(java.util.Arrays.equals(cacheBefore,
                readAll(SourceStore.cacheFile(dir, after))));
        assertEquals(reloadsBefore, host.reloads);
    }

    @Test
    public void changeLocationInvalidPlaylistKeepsOldSource() throws Exception {
        api.add("http://example.com/a.m3u", "家庭源");
        fetcher.body = "not a playlist".getBytes(UTF_8);
        SourceApi.Result result = api.changeLocation("s1", "http://example.com/new.m3u");
        assertEquals(400, result.status());
        assertEquals("http://example.com/a.m3u",
                SourceStore.load(dir).get(0).location());
    }

    @Test
    public void changeLocationRejectsDuplicateWithoutDownloading() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        api.add("http://example.com/b.m3u", "B");
        int calls = fetcher.calls;
        SourceApi.Result result = api.changeLocation("s1", "http://example.com/b.m3u");
        assertEquals(409, result.status());
        assertTrue(result.json().contains("已存在"));
        assertEquals("重复地址不应下载", calls, fetcher.calls);
        assertEquals("http://example.com/a.m3u",
                SourceStore.load(dir).get(0).location());
    }

    @Test
    public void changeLocationConvertsFileSourceToUrl() throws Exception {
        api.upload(M3U.getBytes(UTF_8), "本地列表.m3u");
        fetcher.body = M3U_NEW.getBytes(UTF_8);
        SourceApi.Result result = api.changeLocation("s1", "http://example.com/remote.m3u");
        assertEquals(200, result.status());

        SourceRecord changed = SourceStore.load(dir).get(0);
        assertEquals(SourceRecord.Kind.URL, changed.kind());
        assertEquals("http://example.com/remote.m3u", changed.location());
        assertEquals("本地列表", changed.name());
        assertTrue(changed.enabled());
        assertEquals(2, changed.channelCount());
        assertTrue(new String(readAll(SourceStore.cacheFile(dir, changed)), UTF_8)
                .contains("CCTV5"));
    }

    @Test
    public void changeLocationValidatesIdAndAddress() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        int calls = fetcher.calls;
        assertEquals(400, api.changeLocation(null, "http://example.com/b.m3u").status());
        assertEquals(400, api.changeLocation("s1", "").status());
        assertEquals(400, api.changeLocation("s1", "   ").status());
        assertEquals(400, api.changeLocation("s1", "ftp://example.com/b.m3u").status());
        assertEquals(400, api.changeLocation("s1", "http://example.com/"
                + repeat('a', 2100)).status());
        assertEquals(404, api.changeLocation("s9", "http://example.com/b.m3u").status());
        assertEquals("参数非法不应下载", calls, fetcher.calls);
        assertEquals("http://example.com/a.m3u",
                SourceStore.load(dir).get(0).location());
    }

    @Test
    public void reloadRequestsTvReloadOnly() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        int before = host.reloads;
        assertEquals(200, api.reload().status());
        assertEquals(before + 1, host.reloads);
        assertEquals(1, fetcher.calls);
    }

    @Test
    public void kindIsIncludedInState() throws Exception {
        api.add("http://example.com/a.m3u", "A");
        api.upload(M3U.getBytes(UTF_8), "b.m3u");
        String json = state().json();
        assertTrue(json.contains("\"kind\":\"URL\""));
        assertTrue(json.contains("\"kind\":\"FILE\""));
    }

    // ----------------------------------------------------- search / play（3b）

    @Test
    public void searchMatchesNameContainsIgnoringCase() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1 综合", "央视", "家庭源"));
        host.channels.add(new SourceApi.ChannelRef("c2", "湖南卫视", "卫视", "家庭源"));

        SourceApi.Result upper = api.search("CCTV1");
        assertEquals(200, upper.status());
        assertTrue(upper.json().contains("\"id\":\"c1\""));
        assertTrue(upper.json().contains("\"group\":\"央视\""));
        assertTrue(upper.json().contains("\"source\":\"家庭源\""));
        assertFalse(upper.json().contains("\"id\":\"c2\""));

        assertTrue(api.search("cctv").json().contains("\"id\":\"c1\""));
        assertTrue(api.search(" 卫视 ").json().contains("\"id\":\"c2\""));
    }

    @Test
    public void searchNormalizesPunctuationAndPlusWord() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV-1", "央视", "家庭源"));
        host.channels.add(new SourceApi.ChannelRef("c2", "CCTV1", "央视", "家庭源"));
        host.channels.add(new SourceApi.ChannelRef("c3", "CCTV+ 1", "央视", "家庭源"));
        host.channels.add(new SourceApi.ChannelRef("c4", "湖南卫视", "卫视", "家庭源"));

        // cctv1 → CCTV-1（原名含符号）与 CCTV1（原名直接包含）。
        String one = api.search("cctv1").json();
        assertTrue(one.contains("\"id\":\"c1\""));
        assertTrue(one.contains("\"id\":\"c2\""));
        assertTrue(one.contains("\"id\":\"c3\""));
        assertFalse(one.contains("\"id\":\"c4\""));

        // cctvplus1 → CCTV+ 1（+ 读作 plus）。
        String plus = api.search("cctvplus1").json();
        assertTrue(plus.contains("\"id\":\"c3\""));
        assertFalse(plus.contains("\"id\":\"c1\""));
        assertFalse(plus.contains("\"id\":\"c2\""));
        assertFalse(plus.contains("\"id\":\"c4\""));

        // 中文仍按原名包含匹配。
        String chinese = api.search("湖南").json();
        assertTrue(chinese.contains("\"id\":\"c4\""));
        assertFalse(chinese.contains("\"id\":\"c1\""));

        // 原名直接包含的旧行为保留（大小写不敏感）。
        assertTrue(api.search("cctv-1").json().contains("\"id\":\"c1\""));
    }

    @Test
    public void searchReturnsAtMostFiftyAndFlagsTruncated() {
        for (int i = 0; i < 60; i++) {
            host.channels.add(new SourceApi.ChannelRef("c" + i, "测试台" + i, "测试", null));
        }
        SourceApi.Result result = api.search("测试台");
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"total\":60"));
        assertTrue(result.json().contains("\"truncated\":true"));
        int occurrences = result.json().split("\"id\":", -1).length - 1;
        assertEquals(SourceApi.MAX_SEARCH_RESULTS, occurrences);
        assertTrue(result.json().contains("\"id\":\"c49\""));
        assertFalse(result.json().contains("\"id\":\"c50\""));
    }

    @Test
    public void searchBlankOrUnknownQueryReturnsEmptyList() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1", "央视", "源"));
        for (String query : new String[] {null, "", "   ", "不存在"}) {
            SourceApi.Result result = api.search(query);
            assertEquals(200, result.status());
            assertTrue(result.json().contains("\"results\":[]"));
        }
    }

    @Test
    public void playKnownChannelRequestsSwitchWithoutBlocking() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1", "央视", "源"));
        SourceApi.Result result = api.play(" c1 ");
        assertEquals(200, result.status());
        assertTrue(result.json().contains("\"id\":\"c1\""));
        assertEquals(1, host.played.size());
    }

    @Test
    public void playUnknownOrInvalidChannelIsRejected() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1", "央视", "源"));
        assertEquals(400, api.play(null).status());
        assertEquals(400, api.play("  ").status());
        assertEquals(400, api.play(repeat('x', SourceApi.MAX_CHANNEL_ID_LENGTH + 1)).status());
        assertEquals(404, api.play("nope").status());
        assertTrue(host.played.isEmpty());
    }

    @Test
    public void playReportsHostRefusal() {
        host.channels.add(new SourceApi.ChannelRef("c1", "CCTV1", "央视", "源"));
        host.playAllowed = false;
        SourceApi.Result result = api.play("c1");
        assertEquals(409, result.status());
        assertEquals("电视当前无法切台（没有可用列表）", result.errorMessage());
    }

    @Test
    public void errorMessageIsExtractedForUi() {
        assertEquals("源地址不能为空", api.add("", null).errorMessage());
        assertEquals(null, api.state(provider.snapshot()).errorMessage());
    }

    private static byte[] readAll(File file) throws IOException {
        return me.qnnp.tvlive.data.PlaylistRepository.read(file);
    }

    /** 模拟 TV 快照：只读 sources.json 的元数据，不解析 m3u。 */
    private static final class DiskStateProvider implements StateProvider {

        private final File dir;
        private String currentId;
        private String currentName;

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
                return new Snapshot(true, channels, currentId, currentName, null, entries);
            } catch (IOException e) {
                return new Snapshot(false, 0, null, null, e.getMessage(),
                        Collections.<SourceEntry>emptyList());
            }
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(c);
        }
        return out.toString();
    }

    private static final class FakeHost implements SourceApi.Host {

        private final File dir;
        private int reloads;
        private final List<SourceApi.ChannelRef> channels =
                new ArrayList<SourceApi.ChannelRef>();
        private final List<String> played = new ArrayList<String>();
        private boolean playAllowed = true;

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
            if (!playAllowed) {
                return false;
            }
            played.add(id);
            return true;
        }
    }

    private static final class FakeFetcher implements SourceApi.Fetcher {

        private byte[] body = M3U.getBytes(UTF_8);
        private IOException failure;
        private int calls;
        private final List<String> urls = new ArrayList<String>();

        @Override
        public byte[] fetch(String url) throws IOException {
            calls++;
            urls.add(url);
            if (failure != null) {
                throw failure;
            }
            return body;
        }
    }
}
