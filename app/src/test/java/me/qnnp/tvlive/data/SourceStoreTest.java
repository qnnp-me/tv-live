package me.qnnp.tvlive.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.SourceRecord.Kind;

/**
 * 批次 2f：源索引 sources.json、每源缓存、旧 playlist.m3u 迁移、上限。
 */
public class SourceStoreTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    // -------------------------------------------------------- 迁移

    @Test
    public void migratesLegacyPlaylistOnceAsFirstSource() throws Exception {
        File dir = Files.createTempDirectory("tvlive-migrate").toFile();
        try {
            byte[] legacy = utf8("#EXTM3U\n"
                    + "#EXTINF:-1 group-title=\"央视\",CCTV-1 综合\n"
                    + "http://example.com/cctv1.m3u8\n"
                    + "#EXTINF:-1,湖南卫视\n"
                    + "http://example.com/hunan.m3u8\n");
            PlaylistRepository.save(dir, legacy);

            List<SourceRecord> first = SourceStore.load(dir);
            assertEquals(1, first.size());
            SourceRecord record = first.get(0);
            assertEquals("s1", record.id());
            assertEquals("当前源", record.name());
            assertEquals(Kind.FILE, record.kind());
            assertEquals(0, record.order());
            assertTrue(record.enabled());
            assertEquals(SourceStore.defaultCachePath("s1"), record.cachePath());
            assertNull(record.lastError());

            File cache = SourceStore.cacheFile(dir, record);
            assertTrue(cache.isFile());
            assertEquals(legacy.length, cache.length());

            // 再读不会重复导入。
            List<SourceRecord> second = SourceStore.load(dir);
            assertEquals(1, second.size());
            assertEquals("s1", second.get(0).id());

            Playlist merged = PlaylistRepository.loadAll(dir).playlist();
            assertNotNull(merged);
            assertEquals(2, merged.channelCount());
            assertEquals("当前源", merged.channels().get(0).sourceName());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void emptyDirectoryLoadsNoSources() throws Exception {
        File dir = Files.createTempDirectory("tvlive-empty").toFile();
        try {
            assertTrue(SourceStore.load(dir).isEmpty());
            PlaylistRepository.MultiPlaylist loaded = PlaylistRepository.loadAll(dir);
            assertFalse(loaded.hasSources());
            assertNull(loaded.playlist());
            assertNull(loaded.fatalError());
        } finally {
            deleteRecursively(dir);
        }
    }

    // -------------------------------------------------------- 读写

    @Test
    public void roundTripsSourceIndexAtomically() throws Exception {
        File dir = Files.createTempDirectory("tvlive-index").toFile();
        try {
            List<SourceRecord> sources = Arrays.asList(
                    new SourceRecord("s2", "上海电信", Kind.URL, "http://example.com/sh.m3u",
                            false, 1, 123456789L, 76, "HTTP 403 \"失败\"\n重试", "sources/s2.m3u"),
                    new SourceRecord("s1", "当前源", Kind.FILE, "/tmp/a.m3u",
                            true, 0, 42L, 871, null, null));
            SourceStore.save(dir, sources);

            File index = SourceStore.indexFile(dir);
            assertTrue(index.isFile());
            assertFalse("原子写不应留下 tmp", new File(dir, "sources.json.tmp").exists());
            String json = new String(PlaylistRepository.read(index), UTF_8);
            assertTrue(json.startsWith("{\"version\":1,\"sources\":["));
            assertTrue(json.contains("\"name\":\"上海电信\""));

            List<SourceRecord> loaded = SourceStore.load(dir);
            assertEquals(2, loaded.size());
            // 按 order 排序：s1 在前。
            assertEquals("s1", loaded.get(0).id());
            assertEquals("s2", loaded.get(1).id());
            SourceRecord url = loaded.get(1);
            assertEquals(Kind.URL, url.kind());
            assertFalse(url.enabled());
            assertEquals(1, url.order());
            assertEquals(123456789L, url.lastUpdated());
            assertEquals(76, url.channelCount());
            assertEquals("HTTP 403 \"失败\"\n重试", url.lastError());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsMoreThanEightSources() throws Exception {
        File dir = Files.createTempDirectory("tvlive-limit").toFile();
        try {
            List<SourceRecord> sources = new ArrayList<SourceRecord>();
            for (int i = 1; i <= SourceStore.MAX_SOURCES; i++) {
                sources.add(record("s" + i, i - 1));
            }
            SourceStore.save(dir, sources);
            assertEquals(8, SourceStore.load(dir).size());

            sources.add(record("s9", 8));
            try {
                SourceStore.save(dir, sources);
                fail("超过 8 个源应当失败");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("8"));
            }
            // 旧索引仍可读。
            assertEquals(8, SourceStore.load(dir).size());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void rejectsSourceFileOverFiveMegabytes() throws Exception {
        File dir = Files.createTempDirectory("tvlive-size").toFile();
        try {
            SourceRecord record = new SourceRecord("s1", "大文件", Kind.FILE, "/tmp/big.m3u",
                    true, 0, 0L, 0, null, null);
            byte[] tooBig = new byte[(int) SourceStore.MAX_SOURCE_BYTES + 1];
            try {
                SourceStore.writeCacheFile(dir, record, tooBig);
                fail("超过 5MB 应当失败");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("5MB"));
            }
            assertFalse(SourceStore.cacheFile(dir, record).exists());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void deletesSourceWithCacheAndCanClearAll() throws Exception {
        File dir = Files.createTempDirectory("tvlive-delete").toFile();
        try {
            SourceStore.save(dir, Arrays.asList(record("s1", 0), record("s2", 1)));
            SourceStore.writeCacheFile(dir, record("s1", 0), utf8("#EXTM3U\n#EXTINF:-1,A\nhttp://e.com/a\n"));
            SourceStore.writeCacheFile(dir, record("s2", 1), utf8("#EXTM3U\n#EXTINF:-1,B\nhttp://e.com/b\n"));

            List<SourceRecord> left = SourceStore.deleteSource(dir, "s1");
            assertEquals(1, left.size());
            assertEquals("s2", left.get(0).id());
            assertFalse(SourceStore.cacheFile(dir, "s1").exists());
            assertTrue(SourceStore.cacheFile(dir, "s2").exists());

            // 旧文件也一起清掉，避免下次启动被重新迁移。
            PlaylistRepository.save(dir, utf8("#EXTM3U\n#EXTINF:-1,C\nhttp://e.com/c\n"));
            SourceStore.clearAll(dir);
            assertFalse(SourceStore.indexFile(dir).exists());
            assertFalse(PlaylistRepository.playlistFile(dir).exists());
            assertFalse(SourceStore.cacheFile(dir, "s2").exists());
            assertTrue(SourceStore.load(dir).isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void nextIdFillsSmallestHole() {
        List<SourceRecord> sources = Arrays.asList(record("s1", 0), record("s3", 1));
        assertEquals("s2", SourceStore.nextId(sources));

        List<SourceRecord> full = new ArrayList<SourceRecord>();
        for (int i = 1; i <= SourceStore.MAX_SOURCES; i++) {
            full.add(record("s" + i, i - 1));
        }
        assertNull(SourceStore.nextId(full));
    }

    @Test
    public void corruptIndexFailsWithChineseReason() throws Exception {
        File dir = Files.createTempDirectory("tvlive-corrupt").toFile();
        try {
            PlaylistRepository.writeAtomic(SourceStore.indexFile(dir), utf8("{not json"));
            try {
                SourceStore.load(dir);
                fail("损坏的 sources.json 应当失败");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
                assertTrue(expected.getMessage().contains("源配置"));
            }
            PlaylistRepository.MultiPlaylist loaded = PlaylistRepository.loadAll(dir);
            assertFalse(loaded.hasSources());
            assertNotNull(loaded.fatalError());
            assertNull(loaded.playlist());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    public void cacheFileFallsBackOnTraversalPath() {
        File dir = new File("/tmp/tvlive-cache-check");
        SourceRecord evil = new SourceRecord("s1", "x", Kind.FILE, "", true, 0, 0L, 0, null,
                "../outside.m3u");
        File file = SourceStore.cacheFile(dir, evil);
        assertEquals("s1.m3u", file.getName());
        assertTrue(file.getParentFile().getName().equals("sources"));
    }

    // -------------------------------------------------------- helpers

    private static SourceRecord record(String id, int order) {
        return new SourceRecord(id, "源" + id, Kind.FILE, "/tmp/" + id + ".m3u",
                true, order, 0L, 0, null, null);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(UTF_8);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
