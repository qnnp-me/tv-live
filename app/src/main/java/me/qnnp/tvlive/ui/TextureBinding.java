package me.qnnp.tvlive.ui;

/**
 * 一个 {@code TextureView} 的 SurfaceTexture → Surface 绑定状态（纯 Java，可 JVM 单测）。
 *
 * <p>批次 10：{@code TextureView} 的 {@code onSurfaceTextureAvailable} 可能因为重新 attach
 * 再回调一次，重复建 Surface / 重复绑定播放器会打断播放；而
 * {@code onSurfaceTextureDestroyed} 也可能来自旧 SurfaceTexture 的迟到回调。
 * 这里用 SurfaceTexture 身份做令牌，只让「当前这一份」完成绑定与释放。</p>
 */
public final class TextureBinding {

    /** 调用方要执行的动作。 */
    public enum Result {
        /** 建 Surface 并交给播放器。 */
        BIND,
        /** 不是当前 SurfaceTexture 或重复回调，忽略。 */
        IGNORE,
        /** 释放 Surface 并解绑播放器。 */
        RELEASE
    }

    private Object token;

    /** 新 SurfaceTexture 可用：同一份只允许绑定一次。 */
    public Result onAvailable(Object surfaceTexture) {
        if (surfaceTexture == null || surfaceTexture == token) {
            return Result.IGNORE;
        }
        token = surfaceTexture;
        return Result.BIND;
    }

    /** SurfaceTexture 销毁：只认当前绑定，旧令牌迟到回调忽略。 */
    public Result onDestroyed(Object surfaceTexture) {
        if (surfaceTexture == null || surfaceTexture != token) {
            return Result.IGNORE;
        }
        token = null;
        return Result.RELEASE;
    }

    /** 主动解绑（切回 SurfaceView / 销毁布局）：确有绑定才需要释放 Surface。 */
    public Result detach() {
        if (token == null) {
            return Result.IGNORE;
        }
        token = null;
        return Result.RELEASE;
    }

    public boolean isBound() {
        return token != null;
    }
}
