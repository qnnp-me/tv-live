package me.qnnp.tvlive.data;

/**
 * 播放列表解析/校验失败。消息面向用户（中文），不含内部细节。
 */
public class PlaylistParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public PlaylistParseException(String message) {
        super(message);
    }

    public PlaylistParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
