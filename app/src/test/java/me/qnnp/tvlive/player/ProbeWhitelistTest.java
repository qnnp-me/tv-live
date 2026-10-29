package me.qnnp.tvlive.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** 探测白名单：TTL / profile 隔离 / 删除 / LRU 上限。 */
public class ProbeWhitelistTest {

    private static final String URL = "http://example.com/live/a.m3u8";

    @Test
    public void returnsEntryWithinTtl() {
        ProbeWhitelist whitelist = new ProbeWhitelist();
        whitelist.put(entry(URL, "1920x1080 legacy", 1000L, 2000L));
        assertNotNull(whitelist.get(URL, "1920x1080 legacy", 1500L));
        assertNull(whitelist.get(URL, "1920x1080 legacy", 2000L));
    }

    @Test
    public void profileIsPartOfKey() {
        ProbeWhitelist whitelist = new ProbeWhitelist();
        whitelist.put(entry(URL, "1920x1080 legacy", 1000L, 2000L));
        assertNull(whitelist.get(URL, "3840x2160", 1500L));
    }

    @Test
    public void removeDropsAllProfilesForUrl() {
        ProbeWhitelist whitelist = new ProbeWhitelist();
        whitelist.put(entry(URL, "1920x1080 legacy", 1000L, 2000L));
        whitelist.put(entry(URL, "3840x2160", 1000L, 2000L));
        whitelist.remove(URL);
        assertNull(whitelist.get(URL, "1920x1080 legacy", 1500L));
        assertNull(whitelist.get(URL, "3840x2160", 1500L));
    }

    @Test
    public void touchExtendsExpiryForSuccessfulPlayback() {
        ProbeWhitelist whitelist = new ProbeWhitelist();
        long now = 1_000L;
        long firstExpiry = now + ProbeWhitelist.TTL_MS;
        whitelist.put(entry(URL, "1920x1080 legacy", now, firstExpiry));
        assertNotNull(whitelist.touch(URL, "1920x1080 legacy", firstExpiry - 1L));
        assertNotNull(whitelist.get(URL, "1920x1080 legacy", firstExpiry + 1L));
        assertNotNull(whitelist.get(URL, "1920x1080 legacy",
                firstExpiry + ProbeWhitelist.TTL_MS - 2L));
    }

    @Test
    public void restoreDropsExpiredEntries() {
        ProbeWhitelist whitelist = new ProbeWhitelist();
        java.util.List<ProbeWhitelist.Entry> entries =
                new java.util.ArrayList<ProbeWhitelist.Entry>();
        entries.add(entry(URL, "1920x1080 legacy", 1000L, 1500L));
        entries.add(entry(URL, "1920x1080 legacy", 1000L, 2500L));
        whitelist.restore(entries, 2000L);
        assertEquals(1, whitelist.entries().size());
        assertNotNull(whitelist.get(URL, "1920x1080 legacy", 2000L));
    }

    private static ProbeWhitelist.Entry entry(String url, String profile,
                                              long now, long expires) {
        return new ProbeWhitelist.Entry(url, profile, 1920, 1080,
                "video/avc", "test", expires);
    }
}
