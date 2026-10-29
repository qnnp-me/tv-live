package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.prefs.Aspect;

/**
 * TV 主布局（10-foot）：只负责全宽视频与空态；导航在根层 {@link NavView}。
 *
 * <p>批次 8：导航从本布局搬到 {@code MainActivity.rootView}，成为盖在视频上的根层
 * 覆盖层。本布局只保留 preview（始终全宽）+ 16:9 等比 fit 的画面与 empty；
 * 不再缩视频，也不持有组栏/列表/操作列。</p>
 *
 * <p>TCL 上 {@code SurfaceView} 的合成层会压过 View 层，因此导航不能作为本布局的
 * 兄弟/子视图；它必须在 root 层并在显示时提到最前（见 {@link NavView#showNav}）。
 * 画面在 preview 内按「画面比例」缩放（批次 18，{@link VideoGeometry}）：fit 16:9
 * 居中留黑边 / stretch 铺满 / crop 放大后居中裁边；1920×1080 下 fit 正好铺满。</p>
 *
 * <p>视图全部用 framework 控件在代码里搭，避免引入 AndroidX / 第三方 UI。</p>
 */
public final class TvLayout extends LiveLayout {

    private final FrameLayout preview;
    private SurfaceView surfaceView;
    /** 批次 10：KEEP/DUAL 的双 TextureView 层；与 SurfaceView 互斥显示。 */
    private final TextureVideoLayer textureLayer;
    /** 手机横屏全屏触摸层（电视恒 GONE）：点画面出 OSD/返回按钮。 */
    private final FrameLayout videoTapLayer;
    private View.OnClickListener videoTapListener;
    /** 布局层点按判定（全屏兜底，不依赖子视图点击分发）。 */
    private final TapTracker tapTracker;

    private final LinearLayout emptyView;
    private final TextView emptyTitle;
    private final TextView emptyDetail;

    private boolean fullscreen;

    public TvLayout(Context context) {
        super(context);
        setBackgroundColor(color(R.color.ink));

        // 底层画面：始终全宽全高；SurfaceView 由 Activity 创建并 attachSurface 挂进来。
        preview = new FrameLayout(context);
        preview.setBackgroundColor(Color.TRANSPARENT);
        addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 双 TextureView 层：与 SurfaceView 同一个 preview 容器，默认隐藏（DIRECT 用 SurfaceView）。
        textureLayer = new TextureVideoLayer(context);
        preview.addView(textureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 手机横屏全屏点按层：在预览之上；非全屏/电视恒 GONE。
        videoTapLayer = new FrameLayout(context);
        videoTapLayer.setBackgroundColor(Color.TRANSPARENT);
        videoTapLayer.setVisibility(GONE);
        addView(videoTapLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tapTracker = new TapTracker(ViewConfiguration.get(context).getScaledTouchSlop());

        // --------------------------------------------------------------- 空态
        emptyTitle = new TextView(context);
        emptyTitle.setText(R.string.empty_no_source);
        emptyTitle.setTextColor(color(R.color.text));
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.empty_title_text));
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dimen(R.dimen.empty_gap);

        emptyDetail = new TextView(context);
        emptyDetail.setTextColor(color(R.color.muted));
        emptyDetail.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.empty_detail_text));
        emptyDetail.setGravity(Gravity.CENTER);
        emptyDetail.setLineSpacing(0f, 1.5f);

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dimen(R.dimen.empty_pad_side), 0, dimen(R.dimen.empty_pad_side), 0);
        emptyView.addView(emptyTitle, titleLp);
        emptyView.addView(emptyDetail, wrap());
        emptyView.setVisibility(GONE);
        addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (width > 0 && height > 0 && surfaceView != null) {
            // 画面几何由「画面比例」决定（批次 18）：fit 等比留黑边 / stretch 铺满 /
            // crop 放大后居中裁边。视频容器始终全宽，导航显示时也不缩。
            FrameLayout.LayoutParams surfaceLp =
                    (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
            VideoGeometry.Size size = VideoGeometry.size(videoAspect(), width, height);
            if (surfaceLp.width != size.width || surfaceLp.height != size.height) {
                surfaceLp.width = size.width;
                surfaceLp.height = size.height;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 双 TextureView 层与共享 SurfaceView 用同一套画面比例。 */
    @Override
    protected void onVideoAspectChanged(Aspect aspect) {
        textureLayer.setAspect(aspect);
    }

    @Override
    public SurfaceHolder surfaceHolder() {
        return surfaceView == null ? null : surfaceView.getHolder();
    }

    /**
     * 挂载 Activity 的共享 SurfaceView（旋转时同一个实例从旧布局搬来）。
     * 尺寸/位置由 {@code onMeasure} 按 preview 容器做 16:9 fit + 居中（只改
     * {@code LayoutParams}，不重建 Surface、不重绑播放器）。
     * 保持系统默认 Z 序：显式 setZOrderOnTop/MediaOverlay 在 TCL 4.4 上反而可能
     * 把画面顶到 UI 之上，这里不做任何 z-order 调整。
     */
    @Override
    public void attachSurface(SurfaceView view) {
        if (view.getParent() instanceof ViewGroup) {
            // 兜底：先摘掉旧父容器（正常路径 switchLayout 已 detachSurface）。
            ((ViewGroup) view.getParent()).removeView(view);
        }
        // 先摘掉旧 SurfaceView（removeSurfaceChild 会清空 surfaceView 字段），再保存新实例。
        removeSurfaceChild();
        surfaceView = view;
        // 双 TextureView 层必须保留（同一个 preview 容器内互斥显示）。
        FrameLayout.LayoutParams surfaceLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceLp.gravity = Gravity.CENTER;
        preview.addView(view, surfaceLp);
        // 新增子视图会盖在 TextureView 层之上；按当前模式重新排一下可见性。
        setTextureMode(textureLayer.getVisibility() == VISIBLE);
        if (videoTapListener != null) {
            // 监听先于 attach 注册时也补上（正常路径 attach 后再 setVideoTapListener）。
            view.setOnTouchListener(new VideoTapTouch(getContext(), new Runnable() {
                @Override
                public void run() {
                    fireVideoTap();
                }
            }));
        }
        requestLayout();
    }

    private void removeSurfaceChild() {
        if (surfaceView != null && surfaceView.getParent() == preview) {
            preview.removeView(surfaceView);
        }
        surfaceView = null;
    }

    /** 旋转切布局前先摘下共享视频视图（同一个 SurfaceView 不能同时挂在两个布局上）。 */
    @Override
    public void detachSurface() {
        removeSurfaceChild();
    }

    @Override
    public TextureVideoLayer textureLayer() {
        return textureLayer;
    }

    @Override
    public void setTextureMode(boolean textureMode) {
        textureLayer.setVisible(textureMode);
        if (surfaceView != null) {
            surfaceView.setVisibility(textureMode ? GONE : VISIBLE);
        }
        if (textureMode) {
            preview.bringChildToFront(textureLayer);
        } else if (surfaceView != null) {
            preview.bringChildToFront(surfaceView);
        }
    }

    /** 手机横屏：点画面出全屏 OSD/返回按钮（电视不接这个监听，行为不变）。 */
    @Override
    public void setVideoTapListener(OnClickListener listener) {
        videoTapListener = listener;
        preview.setOnClickListener(listener);
        preview.setClickable(listener != null);
        videoTapLayer.setOnClickListener(listener);
        if (surfaceView != null) {
            surfaceView.setOnTouchListener(listener == null ? null
                    : new VideoTapTouch(getContext(), new Runnable() {
                        @Override
                        public void run() {
                            fireVideoTap();
                        }
                    }));
        }
        updateVideoTapLayer();
    }

    private void fireVideoTap() {
        if (videoTapListener != null) {
            videoTapListener.onClick(this);
        }
    }

    /**
     * 手机全屏：在布局层先判点按（SurfaceView / 子视图的 click 分发不可靠）。
     * 只在「手机（有监听）+ 全屏」时拦截；电视与非全屏的触摸照原样分发。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (fullscreen && videoTapListener != null) {
            if (tapTracker.track(event)) {
                fireVideoTap();
            }
            return true;
        }
        tapTracker.reset();
        return super.dispatchTouchEvent(event);
    }

    /** 触摸层只在手机全屏时出现：非全屏时不在视频区拦截触摸。 */
    private void updateVideoTapLayer() {
        videoTapLayer.setVisibility(fullscreen && videoTapListener != null ? VISIBLE : GONE);
    }

    /**
     * 电视/手机横屏的导航已移到根层 {@link NavView}，本布局不提供频道列表。
     * 调用方必须使用当前设备的导航宿主（手机竖屏 PhoneLayout / 其余 NavView）。
     */
    @Override
    public ListView channelList() {
        return null;
    }

    // -------------------------------------------- 布局契约（导航在 root，故为空实现）

    @Override
    public void updateActions(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex) {
    }

    @Override
    public void setGroupItems(List<GroupBar.Item> items) {
    }

    @Override
    public void setGroupState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes) {
    }

    @Override
    public void setHint(CharSequence hint) {
    }

    @Override
    public void setHintVisible(boolean visible) {
    }

    @Override
    public void showListEmpty(CharSequence text) {
    }

    @Override
    public void hideListEmpty() {
    }

    @Override
    public void updateNow(int number, String name, String meta) {
    }

    @Override
    public void clearNow() {
    }

    @Override
    public void setActionColumnVisible(boolean visible) {
    }

    /** 空态：隐藏画面，显示标题/说明。 */
    public void showEmpty(String title, String detail) {
        emptyTitle.setText(title == null ? getContext().getString(R.string.empty_no_source) : title);
        if (detail == null || detail.isEmpty()) {
            emptyDetail.setVisibility(GONE);
        } else {
            emptyDetail.setVisibility(VISIBLE);
            emptyDetail.setText(detail);
        }
        preview.setVisibility(GONE);
        emptyView.setVisibility(VISIBLE);
    }

    /** 导航显隐由根层 NavView 负责；这里保持布局契约。 */
    @Override
    public void showNav(boolean animate) {
    }

    @Override
    public void hideNav(boolean animate) {
    }

    /** 全屏（手机竖屏才可能出现，本布局用于历史状态兜底）：画面全宽，不重建 Surface。 */
    @Override
    public void enterFullscreen() {
        fullscreen = true;
        emptyView.setVisibility(GONE);
        preview.setVisibility(VISIBLE);
        preview.setBackgroundColor(Color.TRANSPARENT);
        updateVideoTapLayer();
    }

    @Override
    public void exitFullscreen() {
        fullscreen = false;
        updateVideoTapLayer();
        preview.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dimen(int resId) {
        return Res.dimen(getContext(), resId);
    }

    private int color(int resId) {
        return Res.color(getContext(), resId);
    }
}
