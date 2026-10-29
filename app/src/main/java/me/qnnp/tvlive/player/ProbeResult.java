package me.qnnp.tvlive.player;

/**
 * 起播前探测结果（纯 Java）。
 *
 * <p>{@link Status#SUPPORTED} 只表示“探测到的 Format 在设备能力内”，不代表厂商解码器
 * 一定能稳定工作；还需要 {@code MainActivity} 的起播看门狗兜底。</p>
 */
public final class ProbeResult {

    public enum Status {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    private final Status status;
    private final int width;
    private final int height;
    private final String mime;
    private final String method;
    private final String message;

    private ProbeResult(Status status, int width, int height, String mime,
                        String method, String message) {
        this.status = status;
        this.width = width;
        this.height = height;
        this.mime = mime;
        this.method = method;
        this.message = message;
    }

    public static ProbeResult supported(int width, int height, String mime, String method) {
        return new ProbeResult(Status.SUPPORTED, width, height, mime, method, "");
    }

    public static ProbeResult unsupported(String message, String method) {
        return new ProbeResult(Status.UNSUPPORTED, 0, 0, null, method, message);
    }

    public static ProbeResult unknown(String method) {
        return new ProbeResult(Status.UNKNOWN, 0, 0, null, method, "");
    }

    public Status status() {
        return status;
    }

    public boolean isUnsupported() {
        return status == Status.UNSUPPORTED;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String mime() {
        return mime;
    }

    public String method() {
        return method;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "ProbeResult{" + status + " " + width + "x" + height
                + " mime=" + mime + " method=" + method
                + (message.isEmpty() ? "" : " msg=" + message) + "}";
    }
}
