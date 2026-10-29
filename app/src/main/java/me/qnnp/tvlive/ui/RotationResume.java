package me.qnnp.tvlive.ui;

/**
 * 旋转续播判定（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 5b：竖屏↔横屏只切布局。播放器（{@code PlaybackController}）、当前台、
 * 播放状态、列表位置全部沿用，<b>不</b>重新 {@code play()}——重起播会重新握手、
 * 首帧黑一下、播放进度归零。</p>
 *
 * <p>唯一需要重绑的是 Surface：{@code SurfaceView} 在新旧布局之间搬移时，框架会
 * destroy/create 一次 surface，所以旋转路径是「先解绑 → 切布局 → surfaceCreated
 * 时重绑」，期间引擎继续解码（音频不断）。只有播放器已经被释放/清空（{@code onStop}、
 * 清空列表）才回退到重新起播。</p>
 */
public final class RotationResume {

    /** 旋转时对播放器采取的动作。 */
    public enum Action {
        /** 播放器在线：只重绑 Surface，不重起播（正常路径）。 */
        REBIND_SURFACE,
        /** 播放器已被释放/没有当前台：按当前播放台重新起播（兜底）。 */
        RESTART
    }

    private RotationResume() {
    }

    /**
     * @param playerAlive {@code controller.getCurrentChannel() != null}
     *                    （controller.stop()/release() 会清掉它）
     */
    public static Action plan(boolean playerAlive) {
        return playerAlive ? Action.REBIND_SURFACE : Action.RESTART;
    }
}
