package me.qnnp.tvlive.ui;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;

import java.util.List;

import me.qnnp.tvlive.prefs.Aspect;

/**
 * 主场景布局的公共契约（电视 / 手机共用同一套 {@code MainActivity} 调用）。
 *
 * <p>批次 5a 引入：{@link TvLayout} 负责电视（全宽视频 + 44% 导航浮层），
 * {@link PhoneLayout} 负责手机竖屏（16:9 视频 + 分组 tab + 列表 + 底部当前台）。
 * 两者都不是「两套业务」：播放、焦点、分组、提示仍由 {@code MainActivity} 驱动，
 * 这里只暴露布局动作，避免 Activity 里出现大量 {@code instanceof}。</p>
 *
 * <p>视频始终是同一个 {@code SurfaceView}（极速直切）或同一组双 {@code TextureView}
 * （保留上一画面 / 双画面）：布局只改它们的父容器尺寸/位置，不重建视频层本身。
 * 批次 5b 起这个 {@code SurfaceView} 由 {@code MainActivity} 创建，通过
 * {@link #attachSurface(SurfaceView)} 交给当前布局；旋转切布局时把同一个实例搬到新布局，
 * 播放器不重建、不重起播（见 {@link RotationResume}）。</p>
 *
 * <p>批次 10：{@link #textureLayer()} 提供两个 {@code TextureView}（KEEP 用 A、DUAL 用 A/B），
 * {@link #setTextureMode(boolean)} 在 {@code SurfaceView} 与纹理层之间互斥切换。</p>
 */
public abstract class LiveLayout extends FrameLayout implements LayoutModeSync.Target {

    /** 画面比例（批次 18）；默认 16:9 等比适应。 */
    private Aspect videoAspect = Aspect.FIT;

    LiveLayout(Context context) {
        super(context);
    }

    /**
     * 应用画面比例：适应 / 拉伸 / 裁剪。只改视频层几何，不重建 Surface、不重起播；
     * 具体尺寸由 {@link VideoGeometry} 计算，子类在 measure/layout 时套用。
     */
    public void setVideoAspect(Aspect aspect) {
        Aspect next = aspect == null ? Aspect.FIT : aspect;
        if (videoAspect == next) {
            return;
        }
        videoAspect = next;
        onVideoAspectChanged(next);
        requestLayout();
        invalidate();
    }

    public Aspect videoAspect() {
        return videoAspect;
    }

    /** 子类套用比例（改 TextureView 层 / SurfaceView 的 LayoutParams）。 */
    protected void onVideoAspectChanged(Aspect aspect) {
    }

    // ------------------------------------------------------------------ 组件

    /** 视频 Surface（电视与手机都只用一个 SurfaceView）。 */
    public abstract SurfaceHolder surfaceHolder();

    /** 挂载 Activity 的共享视频视图；布局只决定它的位置与尺寸。 */
    public abstract void attachSurface(SurfaceView surfaceView);

    /**
     * 把共享视频视图从本布局摘下（旋转切布局前必须调用）：
     * 同一个 {@code SurfaceView} 只能有一个父容器，先 detach 才能 attach 到新布局，
     * 否则会抛 {@code IllegalStateException: The specified child already has a parent}。
     */
    public abstract void detachSurface();

    /**
     * 批次 10 的双 TextureView 视频层（KEEP/DUAL 用）。布局构造时创建，Activity 负责给
     * 两个 {@code TextureView} 设 SurfaceTextureListener 并把 Surface 交给播放器。
     */
    public abstract TextureVideoLayer textureLayer();

    /**
     * 视频层互斥切换：{@code true} 隐藏共享 {@code SurfaceView}、显示
     * {@link #textureLayer()}；{@code false} 反之。
     *
     * <p>不能同时显示两套视频层（两路都会被合成，既浪费也会互相遮挡）。
     * 极速直切（DIRECT）仍用 SurfaceView。</p>
     */
    public abstract void setTextureMode(boolean textureMode);

    /** 频道列表；两种布局都是 ListView，共用同一份 adapter 与滚动定位逻辑。 */
    public abstract ListView channelList();

    // -------------------------------------------------- 手机专属入口（电视忽略）

    /**
     * 视频区点击：手机全屏用来出 2.5s OSD/返回按钮；电视没有触摸，默认不处理。
     */
    public void setVideoTapListener(View.OnClickListener listener) {
    }

    /**
     * 手机主界面的「设置」入口（底栏按钮），触摸可进设置页；电视默认不显示。
     *
     * @param visible 是否显示；false 时隐藏
     * @param action  点击动作；null 表示不处理
     */
    public void setSettingsEntry(boolean visible, Runnable action) {
    }

    // ------------------------------------------------------------ 组栏 / 文字

    /** 重建分组项（电视竖排组栏 / 手机横向 tab）。 */
    public abstract void setGroupItems(List<GroupBar.Item> items);

    /** 组状态：active 组、焦点项（电视焦点在组栏时）、组内播放点。 */
    public abstract void setGroupState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes);

    /** 底部提示（电视按键提示 / 手机触摸提示）。 */
    public abstract void setHint(CharSequence hint);

    /** 菜单打开时隐藏提示，保留占位避免高度跳动。 */
    public abstract void setHintVisible(boolean visible);

    /** 列表空组占位（收藏为空 / 该分组没有频道）。 */
    public abstract void showListEmpty(CharSequence text);

    public abstract void hideListEmpty();

    /** 底部当前台信息。 */
    public abstract void updateNow(int number, String name, String meta);

    public abstract void clearNow();

    // ------------------------------------------------------------------ 场景

    /** 显示列表场景（电视：导航浮层；手机：列表常驻，重复调用无副作用）。 */
    public abstract void showNav(boolean animate);

    public abstract void hideNav(boolean animate);

    /** 全屏：电视整块导航 GONE，手机隐藏 tab/列表/底栏；视频都不重建。 */
    public abstract void enterFullscreen();

    public abstract void exitFullscreen();

    /**
     * 布局自己的全屏标志。批次 5c 起它只跟随 {@link UiState} 同步：
     * 只有手机竖屏才会有 {@code true}，电视/手机横屏恒为 {@code false}。
     */
    public abstract boolean isFullscreen();

    // --------------------------------------------------------------- 操作列

    /**
     * 刷新频道操作（电视：导航内常驻操作列；手机：长按弹出的底部操作条）。
     *
     * @param name         目标台名称（空则留空）
     * @param favorite     是否已收藏（决定「收藏/取消收藏」文案）
     * @param variantCount 线路数（<=1 时「切换线路」不可用）
     * @param focused      是否有焦点（手机操作条恒为 true）
     * @param focusIndex   {@link ActionColumn} 的 ITEM_* 下标
     */
    public abstract void updateActions(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex);

    /** 电视：列表让出 24% 显示内联操作列；手机：显示/收起底部操作条。 */
    public abstract void setActionColumnVisible(boolean visible);
}
