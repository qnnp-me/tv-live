package me.qnnp.tvlive.control;

/**
 * 手机设置页地址（批次 3b）：把「本机 IPv4 + 实际端口 + 二维码 token」拼成页面地址。
 *
 * <p>纯 Java、无 Android 依赖：无 WiFi（{@link LanAddress#ipv4()} 返回 null）时
 * {@link #available} 为 false，首启页据此显示「请先连接 WiFi」并禁用地址/二维码。
 * 二维码内容为 {@code http://<ip>:<port>/?v=5#t=<token>}（版本参数用于绕开旧页面
 * 缓存；token 放在 fragment，不会出现在电视端访问日志/Referer 里）。</p>
 */
public final class ControlAddress {

    /** 二维码 URL 里的 token 片段前缀。 */
    public static final String TOKEN_FRAGMENT = "#t=";
    /**
     * 手机页版本参数：二维码地址带 {@code ?v=5}，强制浏览器为页面换缓存键。
     *
     * <p>HTTP 缓存键不含 fragment，旧版页面一旦被浏览器缓存，换 token 也不会重新
     * 拉取；带版本参数后首次扫码就能拿到支持 {@code #t=} 自动登录的新页面。</p>
     */
    public static final String PAGE_VERSION = "5";

    private ControlAddress() {
    }

    /** IP/端口是否可用（连上 WiFi 且 HTTP 服务已监听）。 */
    public static boolean available(String ip, int port) {
        return ip != null && !ip.trim().isEmpty() && port > 0 && port <= 65535;
    }

    /** 手机页地址 {@code http://ip:port}；无网络时返回 null。 */
    public static String page(String ip, int port) {
        if (!available(ip, port)) {
            return null;
        }
        return "http://" + ip.trim() + ":" + port;
    }

    /**
     * 二维码内容 {@code http://ip:port/?v=5#t=<token>}；无网络返回 null。
     * token 为空时退回带版本参数的页面地址（页面要求输入 PIN）。
     */
    public static String qr(String ip, int port, String token) {
        String base = page(ip, port);
        if (base == null) {
            return null;
        }
        String url = base + "/?v=" + PAGE_VERSION;
        if (token == null || token.trim().isEmpty()) {
            return url;
        }
        return url + TOKEN_FRAGMENT + token.trim();
    }

    /** 从二维码/页面地址里取 token（支持 {@code #t=}、{@code ?t=}、{@code &t=}）；没有返回 null。 */
    public static String tokenOf(String url) {
        if (url == null) {
            return null;
        }
        String text = url.trim();
        int start = text.indexOf(TOKEN_FRAGMENT);
        if (start < 0) {
            start = text.indexOf("?t=");
        }
        if (start < 0) {
            start = text.indexOf("&t=");
        }
        if (start < 0) {
            return null;
        }
        int from = start + "?t=".length();
        int end = text.length();
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' || c == '#') {
                end = i;
                break;
            }
        }
        String token = text.substring(from, end).trim();
        return token.isEmpty() ? null : token;
    }
}
