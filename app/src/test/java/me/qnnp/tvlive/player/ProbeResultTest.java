package me.qnnp.tvlive.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 预探测结果模型：supported/unsupported/unknown 的语义。 */
public class ProbeResultTest {

    @Test
    public void supportedCarriesFormat() {
        ProbeResult result = ProbeResult.supported(1920, 1080, "video/avc", "segment");
        assertEquals(ProbeResult.Status.SUPPORTED, result.status());
        assertEquals(1920, result.width());
        assertEquals(1080, result.height());
        assertEquals("video/avc", result.mime());
        assertEquals("segment", result.method());
        assertFalse(result.isUnsupported());
        assertFalse(result.isUnknown());
    }

    @Test
    public void unsupportedCarriesMessage() {
        ProbeResult result = ProbeResult.unsupported("4K", "master");
        assertEquals(ProbeResult.Status.UNSUPPORTED, result.status());
        assertTrue(result.isUnsupported());
        assertEquals("4K", result.message());
        assertEquals("master", result.method());
    }

    @Test
    public void unknownIsNotUnsupported() {
        ProbeResult result = ProbeResult.unknown("raw");
        assertTrue(result.isUnknown());
        assertFalse(result.isUnsupported());
        assertEquals("raw", result.method());
    }
}
