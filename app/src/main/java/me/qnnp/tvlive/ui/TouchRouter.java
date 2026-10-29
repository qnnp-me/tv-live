package me.qnnp.tvlive.ui;

/**
 * 频道列表触摸判定（纯 Java，可在 JVM 单测）：点行 / 再点同一行（双击）/ 长按。
 *
 * <p>与 {@link KeyRouter} 并列而不是互相替代：遥控、键盘、返回键仍然全部走 {@link KeyRouter}；
 * 本类只回答「这次触摸该预览、该全屏，还是什么都不做」，由 {@code MainActivity} 落地。
 * 触摸不改变视图焦点，所以两者不会抢焦点。</p>
 *
 * <p>规则（批次 5a）：</p>
 * <ul>
 *   <li>点一行：选中 + 切预览；</li>
 *   <li>同一行在 {@code doubleTapMs} 内再点，或点正在播的那一行：全屏；</li>
 *   <li>长按：打开底部操作条，并清掉点按链（长按结束后不误判成双击）。</li>
 * </ul>
 */
public final class TouchRouter {

    /** 一次点按的结论。 */
    public enum Tap {
        /** 选中该行并切到预览（视频区立即起播）。 */
        PREVIEW,
        /** 进入全屏播放。 */
        FULLSCREEN
    }

    private final long doubleTapMs;

    private int lastPosition = -1;
    private long lastTapAt = Long.MIN_VALUE / 2;

    public TouchRouter(long doubleTapMs) {
        this.doubleTapMs = doubleTapMs;
    }

    /**
     * 列表行被点一下。
     *
     * @param position      行下标
     * @param alreadyPlaying 该行就是正在预览/播放的台
     * @param now           当前时刻（毫秒，单调时钟）
     */
    public Tap tap(int position, boolean alreadyPlaying, long now) {
        boolean doubleTap = position == lastPosition && now - lastTapAt <= doubleTapMs;
        lastPosition = position;
        lastTapAt = now;
        if (doubleTap) {
            // 双击的第二下：不再区分是否在播，直接进全屏。
            return Tap.FULLSCREEN;
        }
        return alreadyPlaying ? Tap.FULLSCREEN : Tap.PREVIEW;
    }

    /**
     * 长按某行：打开操作条。返回 false 表示这次长按没有对应行（调用方忽略）。
     * 无论结果如何都清掉点按链，避免长按后的第一下点按被当成双击。
     */
    public boolean longPress(int position) {
        lastPosition = -1;
        lastTapAt = Long.MIN_VALUE / 2;
        return position >= 0;
    }

    /** 切组 / 进出全屏 / 页面切换后调用，避免跨场景误判双击。 */
    public void reset() {
        lastPosition = -1;
        lastTapAt = Long.MIN_VALUE / 2;
    }

    /** 上一次点按的行（诊断/测试用）；-1 表示没有。 */
    public int lastPosition() {
        return lastPosition;
    }

    public long doubleTapMs() {
        return doubleTapMs;
    }
}
