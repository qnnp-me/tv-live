package me.qnnp.tvlive.control;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 手机页地址/二维码内容（3b）：无 WiFi（ip == null）时不可用，这与首启页
 * 「请先连接 WiFi、禁用地址/二维码」是同一判定。
 */
public class ControlAddressTest {

    @Test
    public void unavailableWithoutNetworkOrPort() {
        assertFalse(ControlAddress.available(null, 18088));
        assertFalse(ControlAddress.available("", 18088));
        assertFalse(ControlAddress.available("  ", 18088));
        assertFalse(ControlAddress.available("192.168.1.5", 0));
        assertFalse(ControlAddress.available("192.168.1.5", -1));
        assertFalse(ControlAddress.available("192.168.1.5", 70000));
        assertTrue(ControlAddress.available("192.168.1.5", 18088));
    }

    @Test
    public void pageUrlUsesActualPort() {
        assertNull(ControlAddress.page(null, 18088));
        assertEquals("http://192.168.1.5:18088",
                ControlAddress.page("192.168.1.5", 18088));
        assertEquals("http://10.0.0.9:18089",
                ControlAddress.page(" 10.0.0.9 ", 18089));
    }

    @Test
    public void qrContentCarriesTokenFragment() {
        assertEquals("http://192.168.1.5:18088/?v=5#t=abc123",
                ControlAddress.qr("192.168.1.5", 18088, "abc123"));
        // token 为空时退回带版本参数的页面地址（页面要求 PIN）。
        assertEquals("http://192.168.1.5:18088/?v=5",
                ControlAddress.qr("192.168.1.5", 18088, null));
        assertNull(ControlAddress.qr(null, 18088, "abc123"));
    }

    @Test
    public void tokenCanBeReadBackFromUrl() {
        String url = ControlAddress.qr("192.168.1.5", 18088, "token32");
        assertEquals("token32", ControlAddress.tokenOf(url));
        assertEquals("token32", ControlAddress.tokenOf(
                "http://192.168.1.5:18088/?v=5#t=token32"));
        assertEquals("querytok", ControlAddress.tokenOf(
                "http://192.168.1.5:18088/?v=5&t=querytok"));
        assertEquals("hashquery", ControlAddress.tokenOf(
                "http://192.168.1.5:18088/#/x?t=hashquery"));
        assertNull(ControlAddress.tokenOf("http://192.168.1.5:18088/?v=5"));
        assertNull(ControlAddress.tokenOf(null));
    }
}
