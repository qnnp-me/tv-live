package me.qnnp.tvlive.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** DNS 预解析只做 host 提取；不发起真实网络请求。 */
public class DnsPrefetchTest {

    @Test
    public void extractsHostFromHlsAndHttpUrls() {
        assertEquals("live.example.com",
                DnsPrefetch.hostOf("https://live.example.com/a/b.m3u8?token=1"));
        assertEquals("127.0.0.1", DnsPrefetch.hostOf("http://127.0.0.1:8080/live.m3u8"));
    }

    @Test
    public void invalidOrHostlessUrlsReturnNull() {
        assertNull(DnsPrefetch.hostOf(null));
        assertNull(DnsPrefetch.hostOf(""));
        assertNull(DnsPrefetch.hostOf("not a url"));
        assertNull(DnsPrefetch.hostOf("file:///sdcard/a.m3u8"));
    }

    @Test
    public void prefetchWithNoHostIsNoOp() {
        // 不应抛异常，也不应把空 host 交给解析线程。
        DnsPrefetch.prefetchUrl(null);
        DnsPrefetch.prefetchUrl("");
        DnsPrefetch.prefetch(null);
        DnsPrefetch.prefetch("");
    }
}
