package me.qnnp.tvlive.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 下载 URL 源（只用于手动「重载」/批次 3 导入；启动永远只读本地缓存）。
 *
 * <p>纯 JDK 实现，不依赖第三方。API 19 的 TLS 只到 1.0，https 失败由调用方提示。</p>
 */
public final class SourceDownloader {

    public static final String USER_AGENT = "TvLive/0.1.0";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private SourceDownloader() {
    }

    /**
     * GET 源内容，最多 maxBytes（默认 5MB）；超过直接失败，不保留半截数据。
     */
    public static byte[] download(String url) throws IOException {
        return download(url, (int) SourceStore.MAX_SOURCE_BYTES);
    }

    public static byte[] download(String url, int maxBytes) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("源地址为空");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IOException("源地址需以 http:// 或 https:// 开头");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes <= 0");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(trimmed).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "*/*");

        InputStream input = null;
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            int declared = connection.getContentLength();
            if (declared > maxBytes) {
                throw new IOException("源文件超过 " + (maxBytes / (1024 * 1024)) + "MB");
            }
            input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declared > 0 ? Math.min(declared, 64 * 1024) : 8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("源文件超过 " + (maxBytes / (1024 * 1024)) + "MB");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new IOException("源文件为空");
            }
            return output.toByteArray();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // 保留原始异常
                }
            }
            connection.disconnect();
        }
    }
}
