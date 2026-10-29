package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.prefs.Aspect;

/**
 * 手机竖屏布局（批次 5a）：
 *
 * <pre>
 * [ 16:9 视频（全宽，当前台） ]
 * [ 横向分组 tab：收藏 央视 卫视 … ]
 * [ 频道列表（56dp 行：台号 + 名称 + 收藏星） ]
 * [ 底部：当前台 / 分组 + 触摸提示 ]
 * </pre>
 *
 * <p>与 {@link TvLayout} 的分工：视频仍然是同一个 {@code SurfaceView}（由 Activity 创建，
 * 通过 {@link #attachSurface} 交给本类），本类只改它的父容器尺寸（竖屏 16:9 / 全屏
 * 16:9 居中黑边），不 reparent 到别的窗口、不重建播放器 → 切布局不掉播放器。
 * 触摸事件（点行/双击/长按、切组、操作条、底栏设置）在这里收口，通过 {@link Listener}
 * 交给 {@code MainActivity}；遥控/键盘仍走 {@code KeyRouter}，本类不抢视图焦点。</p>
 *
 * <p>全屏只隐藏 tab/列表/底栏，视频保持 16:9 居中（上下黑边）；点击画面由 Activity
 * 出 2.5s OSD + 半透明返回按钮（批次 5b）。</p>
 */
public final class PhoneLayout extends LiveLayout {

    private static final String TAG = "TvLive";

    /** 竖屏视频区高宽比：16:9。 */
    private static final float VIDEO_ASPECT = 9f / 16f;

    /** 触摸动作落地：全部由 Activity 执行，本类不持有播放/分组状态。 */
    public interface Listener {

        /** 点分组 tab（下标与 {@link GroupBar.Item} 顺序一致）。 */
        void onGroupSelected(int barIndex);

        /** 点频道行：选中 + 预览（或按 {@link TouchRouter} 结论全屏）。 */
        void onChannelTap(int position);

        /** 长按频道行：底部操作条。 */
        void onChannelLongPress(int position);

        /** 点操作条按钮，参数为 {@link ActionColumn} 的 ITEM_*。 */
        void onActionSelected(int action);

        /** 点视频区（全屏时用于出 OSD / 收起 OSD；竖屏列表态无动作）。 */
        void onVideoTap();

        /** 点击操作条以外区域 / 关闭按钮。 */
        void onDismissActions();
    }

    private final FrameLayout videoStage;

    private final LinearLayout panels;
    private final HorizontalScrollView tabScroll;
    private final LinearLayout tabsRow;
    private final FrameLayout listHost;
    private final ListView channelList;
    private final TextView listEmptyView;

    private final LinearLayout bottomBar;
    private final TextView nowNumber;
    private final TextView nowName;
    private final TextView nowMeta;
    private final TextView hintView;
    private TextView settingsButton;
    private Runnable settingsAction;
    private boolean settingsVisible;

    private final LinearLayout actionBar;
    private TextView actionTitle;
    private final List<TextView> actionButtons = new ArrayList<TextView>();
    private final List<Tab> tabs = new ArrayList<Tab>();

    private final int colorText;
    private final int colorMuted;
    private final int colorTally;
    private final int colorRowActive;
    private final int colorSurface;
    private final int colorHairline;

    private Listener listener;
    private boolean fullscreen;
    private boolean actionsShown;
    private int variantCount;
    private int actionFocusIndex = ActionColumn.ITEM_FAVORITE;
    private int activeTab = -1;
    /** Activity 的共享视频视图；attachSurface 之前为 null（构造时只按屏幕宽给高度）。 */
    private SurfaceView surfaceView;
    /** 批次 10：KEEP/DUAL 的双 TextureView 层；与 SurfaceView 互斥显示。 */
    private final TextureVideoLayer textureLayer;
    /**
     * 全屏触摸层：视频区铺满时铺在最上面接点按（SurfaceView 会抢走 videoStage 的 click），
     * 只点击 → {@link Listener#onVideoTap()}；列表态 GONE，不挡列表/操作条。
     */
    private final FrameLayout videoTapLayer;
    /** Activity 注册的统一视频点按回调（竖屏/横屏同一份）。 */
    private OnClickListener videoTapListener;
    /** 布局层点按判定（全屏兜底，不依赖子视图点击分发）。 */
    private final TapTracker tapTracker;

    public PhoneLayout(Context context) {
        super(context);
        colorText = Res.color(context, R.color.text);
        colorMuted = Res.color(context, R.color.muted);
        colorTally = Res.color(context, R.color.tally);
        colorRowActive = Res.color(context, R.color.row_active);
        colorSurface = Res.color(context, R.color.surface);
        colorHairline = Res.color(context, R.color.hairline);

        setBackgroundColor(Res.color(context, R.color.ink));

        // ------------------------------------------------------------- 画面
        // 视频区固定为 root 的顶部子视图：竖屏 16:9，全屏铺满 + 16:9 居中；父容器不变。
        // 首帧前也先按屏幕宽度给出 16:9 高度（不是 0），避免 measure 之前的空白/错位。
        // 真正的 SurfaceView 由 Activity 在 createLayout 后 attachSurface 挂进来。
        videoStage = new FrameLayout(context);
        videoStage.setBackgroundColor(Res.color(context, R.color.preview_bg));
        videoStage.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                fireVideoTap();
            }
        });
        int initialWidth = getResources().getDisplayMetrics().widthPixels;
        addView(videoStage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeightFor(initialWidth)));

        // 双 TextureView 层：竖屏列表态 / 全屏都在 videoStage 内，默认隐藏（DIRECT 用 SurfaceView）。
        textureLayer = new TextureVideoLayer(context);
        videoStage.addView(textureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // -------------------------------------------------------- 列表面板
        panels = new LinearLayout(context);
        panels.setOrientation(LinearLayout.VERTICAL);
        panels.setBackgroundColor(Res.color(context, R.color.ink));

        tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setVerticalScrollBarEnabled(false);
        tabScroll.setFillViewport(false);
        tabScroll.setBackgroundColor(colorSurface);
        tabsRow = new LinearLayout(context);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabsRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panels.addView(tabScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.phone_tab_height)));

        View tabDivider = new View(context);
        tabDivider.setBackgroundColor(colorHairline);
        panels.addView(tabDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        listHost = new FrameLayout(context);
        channelList = new ListView(context);
        channelList.setDivider(null);
        // 触摸反馈由焦点行底色承担；ListView 自身选择器透明，避免点一下闪一条系统高亮。
        channelList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        channelList.setVerticalScrollBarEnabled(false);
        channelList.setHorizontalScrollBarEnabled(false);
        channelList.setCacheColorHint(Color.TRANSPARENT);
        // 不拿视图焦点：遥控/键盘统一由 Activity 的 KeyRouter 处理，触摸只改播放/选中。
        channelList.setFocusable(false);
        channelList.setFocusableInTouchMode(false);
        channelList.setItemsCanFocus(false);
        channelList.setClipToPadding(false);
        channelList.setPadding(0, dimen(R.dimen.phone_bottom_pad_v), 0,
                dimen(R.dimen.list_pad_bottom));
        listHost.addView(channelList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 触摸收口在布局里：点/双击/长按的判定由 Activity 的 TouchRouter 做，
        // ListView 只负责原生滚动、惯性与长按计时（不抢视图焦点）。
        channelList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (listener != null) {
                    listener.onChannelTap(position);
                }
            }
        });
        channelList.setOnItemLongClickListener(
                new android.widget.AdapterView.OnItemLongClickListener() {
                    @Override
                    public boolean onItemLongClick(android.widget.AdapterView<?> parent,
                            View view, int position, long id) {
                        if (listener != null) {
                            listener.onChannelLongPress(position);
                        }
                        return true;
                    }
                });
        channelList.setLongClickable(true);

        listEmptyView = new TextView(context);
        listEmptyView.setTextColor(colorMuted);
        listEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_row_name_text));
        listEmptyView.setGravity(Gravity.CENTER);
        listEmptyView.setPadding(dimen(R.dimen.phone_pad_side), 0,
                dimen(R.dimen.phone_pad_side), 0);
        listEmptyView.setVisibility(GONE);
        listHost.addView(listEmptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panels.addView(listHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ------------------------------------------------------------ 底栏
        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(colorSurface);
        bottomBar.setPadding(dimen(R.dimen.phone_pad_side),
                dimen(R.dimen.phone_bottom_pad_v),
                dimen(R.dimen.phone_pad_side),
                dimen(R.dimen.phone_bottom_pad_v));

        LinearLayout nowRow = new LinearLayout(context);
        nowRow.setOrientation(LinearLayout.HORIZONTAL);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);

        nowNumber = new TextView(context);
        nowNumber.setTextColor(colorTally);
        nowNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_now_num_text));
        nowNumber.setSingleLine(true);
        LinearLayout.LayoutParams nowNumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowNumLp.rightMargin = dimen(R.dimen.now_gap);
        nowRow.addView(nowNumber, nowNumLp);

        nowName = new TextView(context);
        nowName.setTextColor(colorText);
        nowName.setTypeface(Typeface.DEFAULT_BOLD);
        nowName.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_now_name_text));
        nowName.setSingleLine(true);
        nowName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowRow.addView(nowName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowMeta = new TextView(context);
        nowMeta.setTextColor(colorMuted);
        nowMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_now_meta_text));
        nowMeta.setSingleLine(true);
        LinearLayout.LayoutParams nowMetaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowMetaLp.leftMargin = dimen(R.dimen.now_gap);
        nowRow.addView(nowMeta, nowMetaLp);

        bottomBar.addView(nowRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hintView = new TextView(context);
        hintView.setText(R.string.phone_hint_preview);
        hintView.setTextColor(Res.color(context, R.color.hint));
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.phone_hint_text));
        hintView.setSingleLine(true);
        hintView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // 底栏第二行：触摸提示 +（右侧）设置入口。设置是手机主界面唯一的触摸入口，
        // 48dp 高、右侧内边距保证 ≥44dp 触摸宽度；电视没有这个按钮。
        LinearLayout hintRow = new LinearLayout(context);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.addView(hintView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        settingsButton = new TextView(context);
        settingsButton.setText(R.string.phone_settings);
        settingsButton.setTextColor(colorMuted);
        settingsButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_action_text));
        settingsButton.setSingleLine(true);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setPadding(dimen(R.dimen.phone_settings_pad_h), 0,
                dimen(R.dimen.phone_settings_pad_h), 0);
        settingsButton.setMinWidth(dimen(R.dimen.phone_action_height));
        settingsButton.setBackgroundColor(colorRowActive);
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.phone_action_height));
        settingsLp.leftMargin = dimen(R.dimen.phone_bottom_pad_v);
        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (settingsAction != null) {
                    settingsAction.run();
                }
            }
        });
        // 默认隐藏：可见性统一由 Activity 的 updatePhoneChrome() 驱动（首启/设置页不显示）。
        settingsButton.setVisibility(GONE);
        hintRow.addView(settingsButton, settingsLp);

        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dimen(R.dimen.phone_bottom_pad_v);
        bottomBar.addView(hintRow, hintLp);

        panels.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(panels, panelParams(initialWidth));

        // -------------------------------------------------------- 底部操作条
        actionBar = buildActionBar(context);
        actionBar.setVisibility(GONE);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        addView(actionBar, barLp);

        // ----------------------------------------------------------- 全屏触摸层
        // 放最后 = 在视频/面板之上：全屏时 SurfaceView 铺满会抢走 videoStage 的 click，
        // 这一层负责稳定收到点按（只出/收 OSD + 返回按钮，不做手势）。
        videoTapLayer = new FrameLayout(context);
        videoTapLayer.setBackgroundColor(Color.TRANSPARENT);
        videoTapLayer.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                fireVideoTap();
            }
        });
        videoTapLayer.setVisibility(GONE);
        addView(videoTapLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tapTracker = new TapTracker(
                ViewConfiguration.get(context).getScaledTouchSlop());
    }

    private void fireVideoTap() {
        // 统一入口：Activity 通过 setVideoTapListener 注册（竖屏/横屏同一个回调），
        // 5a 的 Listener.onVideoTap 保留作兜底。
        if (videoTapListener != null) {
            videoTapListener.onClick(this);
        } else if (listener != null) {
            listener.onVideoTap();
        }
    }

    @Override
    public void setVideoTapListener(OnClickListener listener) {
        videoTapListener = listener;
    }

    /**
     * 全屏时在布局层先判点按：SurfaceView / 子视图的 click 分发不可靠，
     * 父层是唯一稳定入口（面板已 GONE，整屏没有别的可交互子视图）。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (fullscreen) {
            if (tapTracker.track(event)) {
                fireVideoTap();
            }
            return true;
        }
        tapTracker.reset();
        return super.dispatchTouchEvent(event);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------- measure

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveWidth(widthMeasureSpec);
        FrameLayout.LayoutParams videoLp = (FrameLayout.LayoutParams) videoStage.getLayoutParams();
        FrameLayout.LayoutParams panelLp = (FrameLayout.LayoutParams) panels.getLayoutParams();
        if (fullscreen) {
            // 竖屏全屏：画面铺满但保持 16:9（上下留黑边），避免把直播画面拉变形。
            videoLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            panelLp.topMargin = 0;
        } else {
            int videoHeight = videoHeightFor(width);
            videoLp.height = videoHeight;
            // 面板从视频下缘开始，占满剩余高度；列表在面板内用 weight 吃掉剩余空间。
            panelLp.topMargin = videoHeight;
        }
        applySurfaceFit(fullscreen, width, MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * 共享 SurfaceView 的几何：竖屏列表态由 videoStage 的 16:9 高度决定（MATCH_PARENT）；
     * 全屏时按「画面比例」计算（批次 18）：fit 16:9 居中留黑边 / stretch 铺满 /
     * crop 放大后居中裁边。
     */
    private void applySurfaceFit(boolean fullscreen, int width, int height) {
        if (surfaceView == null) {
            return;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp == null) {
            return;
        }
        int targetWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        int targetHeight = ViewGroup.LayoutParams.MATCH_PARENT;
        if (fullscreen && width > 0 && height > 0) {
            VideoGeometry.Size size = VideoGeometry.size(videoAspect(), width, height);
            targetWidth = size.width;
            targetHeight = size.height;
        }
        if (lp.width != targetWidth || lp.height != targetHeight || lp.gravity != Gravity.CENTER) {
            lp.width = targetWidth;
            lp.height = targetHeight;
            lp.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(lp);
        }
    }

    /** 双 TextureView 层与共享 SurfaceView 用同一套画面比例。 */
    @Override
    protected void onVideoAspectChanged(Aspect aspect) {
        textureLayer.setAspect(aspect);
        applySurfaceFit(fullscreen, currentWidth(), currentHeight());
    }

    /**
     * 视频区高度 = 宽度 × 9/16。宽度取本 View 的测量宽度（不是屏幕宽度）：
     * 手机可能被系统放进窄窗口，按实际宽度算才不会出现「tab 不在视频下缘」。
     */
    static int videoHeightFor(int widthPx) {
        return widthPx <= 0 ? 0 : Math.round(widthPx * VIDEO_ASPECT);
    }

    private int resolveWidth(int widthMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            width = getWidth();
        }
        if (width <= 0) {
            width = getResources().getDisplayMetrics().widthPixels;
        }
        return width;
    }

    private static FrameLayout.LayoutParams panelParams(int widthPx) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = videoHeightFor(widthPx);
        return lp;
    }

    /**
     * 布局尺寸落定后打一行几何日志：竖屏排查「内容没铺满」时，
     * 对比这一行与 {@code adb shell wm size} / 截图即可判断是布局窄还是系统缩放了窗口。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Log.i(TAG, "phone layout " + w + "x" + h
                + " video=" + videoHeightFor(w)
                + " dp=" + Math.round(w / dm.density) + "x" + Math.round(h / dm.density)
                + " display=" + dm.widthPixels + "x" + dm.heightPixels
                + "@" + dm.densityDpi + (oldw != w || oldh != h
                        ? " (was " + oldw + "x" + oldh + ")" : ""));
    }

    // ---------------------------------------------------------------- 公共

    @Override
    public SurfaceHolder surfaceHolder() {
        return surfaceView == null ? null : surfaceView.getHolder();
    }

    /**
     * 挂载 Activity 的共享 SurfaceView。旋转切布局时同一个实例会搬到这里，
     * 播放器继续解码，surfaceCreated 回来时由 Activity 重绑（不重起播）。
     */
    @Override
    public void attachSurface(SurfaceView view) {
        if (view.getParent() instanceof ViewGroup) {
            // 兜底：先摘掉旧父容器（正常路径 switchLayout 已 detachSurface）。
            ((ViewGroup) view.getParent()).removeView(view);
        }
        surfaceView = view;
        // 只摘旧 SurfaceView，双 TextureView 层必须保留（同一个 videoStage 内互斥显示）。
        videoStage.removeAllViews();
        videoStage.addView(textureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoStage.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // SurfaceView 自己也会吃到触摸：直接挂点按识别，避免只有 loading 角标、点画面无反应。
        view.setOnTouchListener(new VideoTapTouch(getContext(), new Runnable() {
            @Override
            public void run() {
                fireVideoTap();
            }
        }));
        setTextureMode(textureLayer.getVisibility() == VISIBLE);
        applySurfaceFit(fullscreen, currentWidth(), currentHeight());
        requestLayout();
    }

    /** 旋转切布局前先摘下共享视频视图（同一个 SurfaceView 不能同时挂在两个布局上）。 */
    @Override
    public void detachSurface() {
        if (surfaceView != null && surfaceView.getParent() == videoStage) {
            videoStage.removeView(surfaceView);
        }
        surfaceView = null;
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
            videoStage.bringChildToFront(textureLayer);
        } else if (surfaceView != null) {
            videoStage.bringChildToFront(surfaceView);
        }
    }

    private int currentWidth() {
        int width = getWidth();
        return width > 0 ? width : getResources().getDisplayMetrics().widthPixels;
    }

    private int currentHeight() {
        int height = getHeight();
        return height > 0 ? height : getResources().getDisplayMetrics().heightPixels;
    }

    @Override
    public void setSettingsEntry(boolean visible, Runnable action) {
        settingsAction = action;
        if (settingsButton == null || settingsVisible == visible) {
            return;
        }
        settingsVisible = visible;
        settingsButton.setVisibility(visible ? VISIBLE : GONE);
    }

    @Override
    public ListView channelList() {
        return channelList;
    }

    @Override
    public void setGroupItems(List<GroupBar.Item> items) {
        tabsRow.removeAllViews();
        tabs.clear();
        activeTab = -1;
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            GroupBar.Item item = items.get(i);
            Tab tab = createTab(item.name(), i);
            tabs.add(tab);
            tabsRow.addView(tab.root);
        }
    }

    /** 一个分组 tab：48dp 高文本 + 底部 2dp 下划线（active 才显示）。 */
    private Tab createTab(CharSequence name, final int index) {
        Tab tab = new Tab();
        tab.root = new LinearLayout(getContext());
        tab.root.setOrientation(LinearLayout.VERTICAL);
        tab.root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.phone_tab_height)));

        tab.label = new TextView(getContext());
        tab.label.setText(name);
        tab.label.setSingleLine(true);
        tab.label.setGravity(Gravity.CENTER);
        tab.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.phone_tab_text));
        tab.label.setTextColor(colorMuted);
        tab.label.setPadding(dimen(R.dimen.phone_tab_pad_h), 0,
                dimen(R.dimen.phone_tab_pad_h), 0);
        tab.root.addView(tab.label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));

        tab.underline = new View(getContext());
        tab.underline.setBackgroundColor(colorTally);
        tab.underline.setVisibility(INVISIBLE);
        tab.root.addView(tab.underline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.phone_tab_accent_height)));

        tab.root.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onGroupSelected(index);
                }
            }
        });
        // 整块 48dp 高、至少 44dp 宽（短组名补最小宽度），是合格触摸目标。
        tab.root.setMinimumWidth(dimen(R.dimen.phone_action_height));
        tab.root.setClickable(true);
        return tab;
    }

    @Override
    public void setGroupState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes) {
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            boolean active = i == activeIndex;
            tab.label.setTextColor(active ? colorText : colorMuted);
            tab.label.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tab.underline.setVisibility(active ? VISIBLE : INVISIBLE);
        }
        if (activeIndex >= 0 && activeIndex != activeTab) {
            activeTab = activeIndex;
            ensureTabVisible(activeIndex);
        }
    }

    private static final class Tab {

        LinearLayout root;
        TextView label;
        View underline;
    }

    @Override
    public void setHint(CharSequence hint) {
        hintView.setText(hint);
    }

    @Override
    public void setHintVisible(boolean visible) {
        hintView.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    @Override
    public void showListEmpty(CharSequence text) {
        listEmptyView.setText(text);
        listEmptyView.setVisibility(VISIBLE);
    }

    @Override
    public void hideListEmpty() {
        listEmptyView.setVisibility(GONE);
    }

    @Override
    public void updateNow(int number, String name, String meta) {
        nowNumber.setText(number <= 0 ? "" : ChannelListAdapter.formatNumber(number));
        nowName.setText(name == null ? getContext().getString(R.string.no_channel) : name);
        nowMeta.setText(meta == null ? "" : meta);
        if (actionsShown) {
            actionTitle.setText(name == null ? "" : name);
        }
    }

    @Override
    public void clearNow() {
        nowNumber.setText("");
        nowName.setText(R.string.no_channel);
        nowMeta.setText("");
        if (actionsShown) {
            actionTitle.setText("");
        }
    }

    @Override
    public void showNav(boolean animate) {
        if (fullscreen) {
            return;
        }
        // 手机竖屏列表常驻：弹导航 = 确保面板可见（不做位移动画，避免与列表滚动打架）。
        panels.setVisibility(VISIBLE);
        requestLayout();
    }

    @Override
    public void hideNav(boolean animate) {
        if (fullscreen) {
            return;
        }
        // 手机没有「纯画面」态：列表始终可见，这里只保证面板不被误隐藏。
        panels.setVisibility(VISIBLE);
        requestLayout();
    }

    @Override
    public void enterFullscreen() {
        fullscreen = true;
        actionsShown = false;
        actionBar.setVisibility(GONE);
        panels.setVisibility(GONE);
        videoStage.setBackgroundColor(Color.BLACK);
        videoStage.setClickable(true);
        // 全屏触摸层接管点按（SurfaceView 会吃掉 videoStage 的 click）。
        videoTapLayer.setVisibility(VISIBLE);
        requestLayout();
    }

    @Override
    public void exitFullscreen() {
        fullscreen = false;
        videoTapLayer.setVisibility(GONE);
        panels.setVisibility(VISIBLE);
        videoStage.setBackgroundColor(Res.color(getContext(), R.color.preview_bg));
        requestLayout();
    }

    /** 全屏态（供 Activity 做「点画面退出全屏」判定与诊断）。 */
    public boolean isFullscreen() {
        return fullscreen;
    }

    // -------------------------------------------------------------- 操作条

    @Override
    public void updateActions(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex) {
        this.variantCount = variantCount;
        this.actionFocusIndex = focusIndex;
        actionTitle.setText(name == null ? "" : name);
        actionButtons.get(ActionColumn.ITEM_FAVORITE).setText(favorite
                ? R.string.menu_unfavorite : R.string.menu_favorite);
        TextView variants = actionButtons.get(ActionColumn.ITEM_VARIANTS);
        variants.setAlpha(variantCount > 1 ? 1f : 0.4f);
        variants.setEnabled(variantCount > 1);
        applyActionState();
    }

    @Override
    public void setActionColumnVisible(boolean visible) {
        actionsShown = visible;
        actionBar.setVisibility(visible ? VISIBLE : GONE);
    }

    /** 操作条是否显示（Activity 在 Back 时据此先关操作条）。 */
    public boolean isActionColumnShown() {
        return actionsShown;
    }

    private LinearLayout buildActionBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(colorSurface);
        bar.setPadding(dimen(R.dimen.phone_action_pad), dimen(R.dimen.phone_action_pad),
                dimen(R.dimen.phone_action_pad), dimen(R.dimen.phone_action_pad));

        View topLine = new View(context);
        topLine.setBackgroundColor(colorTally);
        bar.addView(topLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.phone_tab_accent_height)));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        actionTitle = new TextView(context);
        actionTitle.setTextColor(colorText);
        actionTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_action_title_text));
        actionTitle.setSingleLine(true);
        actionTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(actionTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(context);
        close.setText(R.string.phone_actions_close);
        close.setTextColor(colorMuted);
        close.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.phone_action_text));
        close.setGravity(Gravity.CENTER);
        // 关闭也是触摸目标：48dp 高、右侧 ≥44dp 宽。
        close.setPadding(dimen(R.dimen.phone_action_pad), 0,
                dimen(R.dimen.phone_action_pad), 0);
        close.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.phone_action_height)));
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDismissActions();
                }
            }
        });
        titleRow.addView(close);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.addView(titleRow, titleLp);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        addActionButton(buttons, ActionColumn.ITEM_FAVORITE, R.string.menu_favorite);
        addActionButton(buttons, ActionColumn.ITEM_VARIANTS, R.string.menu_switch_source);
        addActionButton(buttons, ActionColumn.ITEM_INFO, R.string.menu_channel_info);
        addActionButton(buttons, ActionColumn.ITEM_FULLSCREEN, R.string.menu_fullscreen);
        bar.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void addActionButton(LinearLayout parent, final int action, int labelRes) {
        TextView button = new TextView(getContext());
        button.setText(labelRes);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.phone_action_text));
        button.setTextColor(colorMuted);
        button.setPadding(dimen(R.dimen.phone_action_pad), 0,
                dimen(R.dimen.phone_action_pad), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dimen(R.dimen.phone_action_height), 1f);
        button.setLayoutParams(lp);
        // 按钮之间用细线分隔，保持 TV 的「一列 action」信息层级。
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onActionSelected(action);
                }
            }
        });
        actionButtons.add(button);
        parent.addView(button);
    }

    /** 操作条高亮：当前动作用 tally 色文字 + 弱底色（触摸下没有方向焦点，仅给反馈）。 */
    private void applyActionState() {
        for (int i = 0; i < actionButtons.size(); i++) {
            TextView button = actionButtons.get(i);
            boolean active = i == actionFocusIndex;
            boolean enabled = i != ActionColumn.ITEM_VARIANTS || variantCount > 1;
            button.setTextColor(active && enabled ? colorText : colorMuted);
            button.setBackgroundColor(active && enabled ? colorRowActive : Color.TRANSPARENT);
        }
    }

    /** active tab 滚进可视区（tab 条可能比屏幕宽）。 */
    private void ensureTabVisible(int index) {
        final int target = index;
        tabScroll.post(new Runnable() {
            @Override
            public void run() {
                if (target < 0 || target >= tabsRow.getChildCount()) {
                    return;
                }
                View tab = tabsRow.getChildAt(target);
                int maxScroll = Math.max(0, tabsRow.getWidth() - tabScroll.getWidth());
                // 目标尽量居中，避免切到靠右的组时 tab 贴边看不出前后还有分组。
                int centered = tab.getLeft() - (tabScroll.getWidth() - tab.getWidth()) / 2;
                tabScroll.scrollTo(Math.max(0, Math.min(maxScroll, centered)), 0);
            }
        });
    }

    private int dimen(int resId) {
        return Res.dimen(getContext(), resId);
    }
}
