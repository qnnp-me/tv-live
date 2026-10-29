package me.qnnp.tvlive.ui;

import android.content.Context;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.prefs.Aspect;

/**
 * 两个 {@code TextureView} 的视频层（批次 10）。
 *
 * <p>不用 {@code SurfaceView}：TCL 4.4 上 SurfaceView 走 HWC 合成，{@code player.stop()}
 * 后最后一帧会被清掉；{@code TextureView} 是普通 View 合成，SurfaceTexture 会保留最后
 * 一帧，且两路之间可以用 alpha 交换而不用重建 View。</p>
 *
 * <p>KEEP / 极速以外（KEEP、DUAL）都显示本层：KEEP 只用 {@link #a()}；DUAL 才 attach
 * 第二路 {@link #b()}，首帧后 {@link #swap()} 交换 alpha。两路都按 16:9 等比 fit 并居中，
 * 黑边用 {@code preview_bg}。</p>
 *
 * <p>批次 12：KEEP/DIRECT 不再常驻第二路全屏 TextureView（白占一份 1080p
 * SurfaceTexture，约 8MB）。{@link #setDualEnabled(boolean)} 在 DUAL 时 attach B，其余
 * 模式 removeView(B) 触发 SurfaceTexture 销毁，由 Activity 释放在用的 Surface。</p>
 *
 * <p>SurfaceTexture 回调由 {@code MainActivity} 设置并负责把 Surface 交给播放器；
 * 本类只做显示、几何与 alpha。</p>
 */
public final class TextureVideoLayer extends FrameLayout {

    private final TextureView textureA;
    private final TextureView textureB;
    /** 前台槽位；不要只存在 View 里，Activity 重建布局后会重新套用。 */
    private final DualVideoState state = new DualVideoState();
    /** 画面比例（批次 18）；默认 16:9 等比适应。 */
    private Aspect aspect = Aspect.FIT;

    public TextureVideoLayer(Context context) {
        super(context);
        setBackgroundColor(Res.color(context, R.color.preview_bg));

        textureA = new TextureView(context);
        textureB = new TextureView(context);
        // 只 attach A：KEEP 是默认模式，B 等切到 DUAL 再由 setDualEnabled(true) 挂上。
        addView(textureA, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        applyAlpha();
        setVisibility(GONE);
    }

    /** 前台/主路；KEEP 只用它。 */
    public TextureView a() {
        return textureA;
    }

    /** 备用路；DUAL 的 standby 用它（其它模式可能未 attach）。 */
    public TextureView b() {
        return textureB;
    }

    /** 双画面是否启用（B 是否已 attach）。 */
    public boolean isDualEnabled() {
        return state.isDual();
    }

    /**
     * 切换双画面：DUAL 时把 B 挂回本层并显示；KEEP/DIRECT 时 detach B。
     *
     * <p>detach 会触发 {@code onSurfaceTextureDestroyed}，Activity 在那里释放 B 的
     * Surface（不会碰正在用的 A）。重复调用同一值是幂等的。</p>
     *
     * @return 状态是否真的变化
     */
    public boolean setDualEnabled(boolean enabled) {
        if (state.isDual() == enabled) {
            return false;
        }
        state.setDual(enabled);
        if (enabled) {
            if (textureB.getParent() == null) {
                addView(textureB, new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            textureB.setVisibility(VISIBLE);
        } else if (textureB.getParent() == this) {
            removeView(textureB);
        }
        applyAlpha();
        // 挂了/摘了一个全屏子视图：本层几何需要重新测量。
        requestLayout();
        invalidate();
        return true;
    }

    public boolean isActiveA() {
        return state.isActiveA();
    }

    /** 由 Activity 在重建布局或退出双画面时把记住的槽位套回来。 */
    public void setActiveA(boolean activeA) {
        state.setActiveA(activeA);
        applyAlpha();
    }

    /** 首帧到达：前台与备用交换 alpha。 */
    public void swap() {
        state.swap();
        applyAlpha();
    }

    /** 回到 A 前台。 */
    public void reset() {
        state.reset();
        applyAlpha();
    }

    /** 整层显示/隐藏（模式切换时与 SurfaceView 互斥）。 */
    public void setVisible(boolean visible) {
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 画面比例：两路 TextureView 都按同一套尺寸缩放/居中。 */
    public void setAspect(Aspect aspect) {
        Aspect next = aspect == null ? Aspect.FIT : aspect;
        if (this.aspect == next) {
            return;
        }
        this.aspect = next;
        requestLayout();
        invalidate();
    }

    public Aspect aspect() {
        return aspect;
    }

    private void applyAlpha() {
        textureA.setAlpha(state.alphaA());
        textureB.setAlpha(state.alphaB());
    }

    // ------------------------------------------------------------------ 几何

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        VideoGeometry.Size size = VideoGeometry.size(aspect, width, height);
        if (size.isEmpty()) {
            // 尚未测量（或容器为 0）：两路先按 0 尺寸量，避免沿用旧画面尺寸。
            size = new VideoGeometry.Size(0, 0);
        }
        int childWidth = MeasureSpec.makeMeasureSpec(size.width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(size.height, MeasureSpec.EXACTLY);
        textureA.measure(childWidth, childHeight);
        if (state.isDual()) {
            textureB.measure(childWidth, childHeight);
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        VideoGeometry.Size size = VideoGeometry.size(aspect, width, height);
        int childLeft = VideoGeometry.centerOffset(width, size.width);
        int childTop = VideoGeometry.centerOffset(height, size.height);
        textureA.layout(childLeft, childTop, childLeft + size.width, childTop + size.height);
        if (state.isDual()) {
            textureB.layout(childLeft, childTop, childLeft + size.width, childTop + size.height);
        }
    }
}
