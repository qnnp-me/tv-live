package me.qnnp.tvlive.player;

/**
 * 播放失败分类 + 中文提示。
 *
 * <p>纯 Java，不 import Android，可在 JVM 单测中直接构造和比较。</p>
 *
 * <p>分类口径（见 docs/specs/v1.0.md §6、batch-2a.md）：</p>
 * <ul>
 *   <li>网络族：NETWORK / TIMEOUT / HTTP / HTTP_AUTH / HTTP_NOT_FOUND / TLS；
 *       NETWORK/TIMEOUT/UNKNOWN 由控制器先原线路重试（1s/3s），HTTP 与 TLS 直接换下一个 variant。</li>
 *   <li>解码族：DECODER / CONTAINER；不重试，同一 variant 可以换引擎再试一次。</li>
 * </ul>
 */
public final class PlaybackError {

    /** 错误类别；UI 的 OSD 用它区分文案（网络 / 403 / 解码器 / 格式 / TLS）。 */
    public enum Kind {
        /** 连接失败、DNS 失败、连接被拒等。 */
        NETWORK,
        /** 连接或读取超时。 */
        TIMEOUT,
        /** HTTP 非 2xx（401/403/404 之外的码）。 */
        HTTP,
        /** HTTP 401/403，需要授权。 */
        HTTP_AUTH,
        /** HTTP 404，地址不存在。 */
        HTTP_NOT_FOUND,
        /** TLS/SSL 握手失败。 */
        TLS,
        /** 本机没有对应解码器，或解码器初始化/解码失败。 */
        DECODER,
        /** 本机能力/清晰度不支持（例如 1080p 电视上的 4K-only 源）。 */
        UNSUPPORTED,
        /** 容器/码流无法解析，或该格式不支持渐进式播放。 */
        CONTAINER,
        /** 内存不足：双画面第二路开不起来，已退回保留画面。 */
        MEMORY,
        /** 其他原因。 */
        UNKNOWN
    }

    private final Kind kind;
    private final String message;
    private final Throwable cause;

    public PlaybackError(Kind kind, String message) {
        this(kind, message, null);
    }

    public PlaybackError(Kind kind, String message, Throwable cause) {
        if (kind == null) {
            throw new IllegalArgumentException("kind == null");
        }
        this.kind = kind;
        this.message = message == null || message.isEmpty() ? defaultMessage(kind) : message;
        this.cause = cause;
    }

    public static PlaybackError of(Kind kind, String message) {
        return new PlaybackError(kind, message, null);
    }

    public static PlaybackError of(Kind kind, String message, Throwable cause) {
        return new PlaybackError(kind, message, cause);
    }

    public Kind kind() {
        return kind;
    }

    /** 中文提示，可直接上 OSD；不会为 null/空。 */
    public String message() {
        return message;
    }

    /** 原始异常，便于日志；可能为 null。 */
    public Throwable cause() {
        return cause;
    }

    /**
     * 网络类抖动：同一 variant 原线路值得延迟重试（NETWORK / TIMEOUT / UNKNOWN）。
     *
     * <p>HTTP 状态码 / TLS / 404 / 解码器 / 容器错误不在其中：这些换线路或换引擎才有意义，
     * 重试同一地址只会拖慢失败判定。</p>
     */
    public boolean isRetryableNetworkError() {
        return kind == Kind.NETWORK || kind == Kind.TIMEOUT || kind == Kind.UNKNOWN;
    }

    /** 网络/HTTP/TLS 类：同一 variant 换引擎不会成功，应直接换 variant。 */
    public boolean isNetworkFamily() {
        switch (kind) {
            case NETWORK:
            case TIMEOUT:
            case HTTP:
            case HTTP_AUTH:
            case HTTP_NOT_FOUND:
            case TLS:
                return true;
            default:
                return false;
        }
    }

    /** 解码器/容器类：同一 variant 可以换另一个引擎再试。 */
    public boolean isDecoderOrContainer() {
        return kind == Kind.DECODER || kind == Kind.CONTAINER;
    }

    @Override
    public String toString() {
        return "PlaybackError{" + kind + ": " + message + "}";
    }

    private static String defaultMessage(Kind kind) {
        switch (kind) {
            case NETWORK:
                return "网络连接失败";
            case TIMEOUT:
                return "连接超时";
            case HTTP:
                return "服务器返回错误";
            case HTTP_AUTH:
                return "地址需要授权（401/403）";
            case HTTP_NOT_FOUND:
                return "地址不存在（404）";
            case TLS:
                return "安全连接失败（TLS）";
            case DECODER:
                return "本机无法解码该视频";
            case UNSUPPORTED:
                return "本机不支持该清晰度";
            case CONTAINER:
                return "无法识别的视频格式";
            case MEMORY:
                return "设备内存不足，已使用保留画面";
            case UNKNOWN:
            default:
                return "播放失败";
        }
    }
}
