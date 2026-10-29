package me.qnnp.tvlive.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LanAddressTest {

    @Test
    public void acceptsPrivateV4Ranges() {
        assertTrue(LanAddress.isLanClient("192.168.1.7"));
        assertTrue(LanAddress.isLanClient("10.0.0.9"));
        assertTrue(LanAddress.isLanClient("172.16.0.2"));
        assertTrue(LanAddress.isLanClient("172.31.255.254"));
        assertTrue(LanAddress.isLanClient("169.254.3.4"));
        assertTrue(LanAddress.isLanClient("127.0.0.1"));
        assertTrue(LanAddress.isLanClient(" 192.168.0.9 "));
    }

    @Test
    public void rejectsPublicAndGarbage() {
        assertFalse(LanAddress.isLanClient("8.8.8.8"));
        assertFalse(LanAddress.isLanClient("172.32.0.1"));
        assertFalse(LanAddress.isLanClient("11.0.0.1"));
        assertFalse(LanAddress.isLanClient("192.169.0.1"));
        assertFalse(LanAddress.isLanClient("256.1.1.1"));
        assertFalse(LanAddress.isLanClient("host.local"));
        assertFalse(LanAddress.isLanClient(""));
        assertFalse(LanAddress.isLanClient(null));
    }

    @Test
    public void acceptsIpv6LocalAndMappedLoopback() {
        assertTrue(LanAddress.isLanClient("::1"));
        assertTrue(LanAddress.isLanClient("0:0:0:0:0:0:0:1"));
        assertTrue(LanAddress.isLanClient("fe80::1%wlan0"));
        assertTrue(LanAddress.isLanClient("fd12:3456::1"));
        assertTrue(LanAddress.isLanClient("::ffff:192.168.1.9"));
        assertFalse(LanAddress.isLanClient("2001:4860:4860::8888"));
    }

    // ------------------------------------------------------------ 30 秒缓存

    /** 可推进的时钟 + 可计数的枚举次数。 */
    private static final class FakeSource implements LanAddress.IpSource {

        int calls;
        String next = "192.168.1.10";

        @Override
        public String lookup() {
            calls++;
            return next;
        }
    }

    private static final class FakeClock implements LanAddress.Clock {

        long now;

        @Override
        public long now() {
            return now;
        }
    }

    @Test
    public void cacheHitsWithinTtlAndRefreshesAfter() {
        FakeSource source = new FakeSource();
        FakeClock clock = new FakeClock();
        LanAddress.Cache cache = new LanAddress.Cache(source, clock, LanAddress.CACHE_TTL_MS);

        assertEquals("192.168.1.10", cache.get());
        assertEquals(1, source.calls);

        // 设置页反复进入 / onStart 里多次调用：TTL 内不再枚举网卡。
        clock.now += LanAddress.CACHE_TTL_MS - 1L;
        assertEquals("192.168.1.10", cache.get());
        assertEquals("192.168.1.10", cache.get());
        assertEquals(1, source.calls);

        // 到期后重新枚举，拿到新 IP。
        clock.now += 1L;
        source.next = "10.0.0.7";
        assertEquals("10.0.0.7", cache.get());
        assertEquals(2, source.calls);
    }

    @Test
    public void invalidateForcesImmediateLookup() {
        FakeSource source = new FakeSource();
        FakeClock clock = new FakeClock();
        LanAddress.Cache cache = new LanAddress.Cache(source, clock, LanAddress.CACHE_TTL_MS);

        assertEquals("192.168.1.10", cache.get());
        clock.now += 1_000L;
        cache.invalidate();
        assertFalse(cache.isValid());

        source.next = "192.168.0.20";
        assertEquals("192.168.0.20", cache.get());
        assertEquals(2, source.calls);
        assertTrue(cache.isValid());
    }

    @Test
    public void notConnectedResultIsCachedToo() {
        FakeSource source = new FakeSource();
        source.next = null;
        FakeClock clock = new FakeClock();
        LanAddress.Cache cache = new LanAddress.Cache(source, clock, LanAddress.CACHE_TTL_MS);

        assertNull(cache.get());
        assertNull(cache.get());
        assertEquals("未连 WiFi 也要缓存，避免反复枚举", 1, source.calls);

        cache.invalidate();
        assertNull(cache.get());
        assertEquals(2, source.calls);
    }
}
