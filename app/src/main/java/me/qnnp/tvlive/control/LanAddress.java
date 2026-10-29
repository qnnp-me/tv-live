package me.qnnp.tvlive.control;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * 局域网地址：取电视本机 IPv4（手机页地址用）与判断来源是否局域网。
 *
 * <p>只监听/服务私网与本机回环：10/8、172.16/12、192.168/16、169.254/16、
 * 127/8、IPv6 回环/link-local/ULA。公网来源一律拒绝，避免误把设置页暴露到外网。</p>
 */
public final class LanAddress {

    /**
     * 枚举结果的内存缓存时长：设置页反复进入 / onStart / 广播刷新都不再重复枚举网卡。
     * IP 真变化时由网络广播调用 {@link #invalidate()} 主动失效。
     */
    public static final long CACHE_TTL_MS = 30_000L;

    /** 取本机 IPv4 的实现（单测可替换 Enumeration）。 */
    public interface IpSource {

        String lookup();
    }

    /** 毫秒时钟（单测可推进）。 */
    public interface Clock {

        long now();
    }

    /** 30 秒缓存：TTL 内直接返回上次结果（含 null），失效后重新枚举。 */
    static final class Cache {

        private final IpSource source;
        private final Clock clock;
        private final long ttlMs;

        private boolean valid;
        private long stampedAt;
        private String value;

        Cache(IpSource source, Clock clock, long ttlMs) {
            this.source = source;
            this.clock = clock;
            this.ttlMs = ttlMs;
        }

        synchronized String get() {
            long now = clock.now();
            if (valid && now - stampedAt < ttlMs) {
                return value;
            }
            value = source.lookup();
            stampedAt = now;
            valid = true;
            return value;
        }

        /** 网络广播来的时候调用：下次访问重新枚举。 */
        synchronized void invalidate() {
            valid = false;
            value = null;
        }

        synchronized boolean isValid() {
            return valid;
        }
    }

    private static final Cache CACHE = new Cache(new IpSource() {
        @Override
        public String lookup() {
            return enumerateIpv4();
        }
    }, new Clock() {
        @Override
        public long now() {
            // 单调时钟：系统时间被改动时缓存不会长驻/立刻过期。
            return System.nanoTime() / 1_000_000L;
        }
    }, CACHE_TTL_MS);

    private LanAddress() {
    }

    /** 本机局域网 IPv4；没有可用地址（未连 WiFi）时返回 null。30 秒内复用缓存。 */
    public static String ipv4() {
        return CACHE.get();
    }

    /** WiFi 切换 / 网络广播：丢掉缓存，下次 {@link #ipv4()} 重新枚举。 */
    public static void invalidate() {
        CACHE.invalidate();
    }

    private static String enumerateIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (network == null || !network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address == null || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String text = address.getHostAddress();
                    if (isPrivateIpv4(text)) {
                        return text;
                    }
                }
            }
        } catch (Exception ignored) {
            // 权限/系统限制时按未连接处理。
        }
        return null;
    }

    /** 来源 IP 是否允许访问手机页（局域网/本机）。 */
    public static boolean isLanClient(String ip) {
        if (ip == null) {
            return false;
        }
        String text = ip.trim().toLowerCase(Locale.US);
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("::ffff:")) {
            text = text.substring("::ffff:".length());
        }
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.equals("::1") || text.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (text.indexOf(':') >= 0) {
            // IPv6：fe80::/10 link-local、fc00::/7 ULA 视为局域网。
            String first = text;
            int cut = first.indexOf(':');
            if (cut > 0) {
                first = first.substring(0, cut);
            }
            int group;
            try {
                group = Integer.parseInt(first, 16);
            } catch (NumberFormatException e) {
                return false;
            }
            return (group & 0xffc0) == 0xfe80 || (group & 0xfe00) == 0xfc00;
        }
        return isPrivateIpv4(text);
    }

    /** 10/8、172.16/12、192.168/16、169.254/16、127/8。 */
    static boolean isPrivateIpv4(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int a = octets[0];
        int b = octets[1];
        return a == 10
                || a == 127
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168)
                || (a == 169 && b == 254);
    }
}
