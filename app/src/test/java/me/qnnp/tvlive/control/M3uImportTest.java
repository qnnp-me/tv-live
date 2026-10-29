package me.qnnp.tvlive.control;

import org.junit.Test;

import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class M3uImportTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String VALID = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-name=\"CCTV1\" group-title=\"央视\",CCTV1\n"
            + "http://example.com/1.m3u8\n"
            + "#EXTINF:-1 group-title=\"央视\",CCTV2\n"
            + "http://example.com/2.m3u8\n";

    @Test
    public void acceptsValidM3u() throws Exception {
        assertEquals(2, M3uImport.validate(VALID.getBytes(UTF_8), "http://example.com/a.m3u"));
    }

    @Test
    public void acceptsUtf8Bom() throws Exception {
        byte[] body = VALID.getBytes(UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertEquals(2, M3uImport.validate(withBom, null));
    }

    @Test
    public void rejectsEmptyAndOversize() {
        assertError("文件为空", new byte[0]);
        assertError("文件超过 5MB", new byte[(int) M3uImport.MAX_BYTES + 1]);
    }

    @Test
    public void rejectsBinaryContent() {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        png[10] = 0;
        assertError("不是 m3u 文本", png);
    }

    @Test
    public void rejectsHlsMediaPlaylist() {
        String media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n"
                + "#EXTINF:6.0,\nsegment.ts\n";
        assertError("HLS", media.getBytes(UTF_8));
    }

    @Test
    public void rejectsTextWithoutChannels() {
        try {
            M3uImport.validate("<html>not a playlist</html>".getBytes(UTF_8), null);
            fail("应拒绝无频道内容");
        } catch (M3uImport.ImportException e) {
            assertTrue(e.getMessage() == null || !e.getMessage().isEmpty());
        }
    }

    @Test
    public void extensionCheckIsCaseInsensitive() {
        assertTrue(M3uImport.hasM3uExtension("list.m3u"));
        assertTrue(M3uImport.hasM3uExtension("LIST.M3U8"));
        assertTrue(M3uImport.hasM3uExtension("  family.m3u8  "));
        assertFalse(M3uImport.hasM3uExtension("list.txt"));
        assertFalse(M3uImport.hasM3uExtension("m3u"));
        assertFalse(M3uImport.hasM3uExtension(null));
    }

    @Test
    public void suggestsReadableNames() {
        assertEquals("family", M3uImport.suggestName("http://host/a/family.m3u?x=1", "网络源"));
        assertEquals("CCTV", M3uImport.suggestName("/tmp/CCTV.m3u8", "上传源"));
        assertEquals("file", M3uImport.suggestName("file.m3u", "上传源"));
        assertEquals("上传源", M3uImport.suggestName("   ", "上传源"));
        assertEquals("a", M3uImport.suggestName("http://host/", "a"));
    }

    private static void assertError(String expectedPart, byte[] data) {
        try {
            M3uImport.validate(data, null);
            fail("应拒绝该内容");
        } catch (M3uImport.ImportException e) {
            String message = e.getMessage();
            assertTrue("消息应包含「" + expectedPart + "」，实际：" + message,
                    message != null && message.contains(expectedPart));
        }
    }
}
