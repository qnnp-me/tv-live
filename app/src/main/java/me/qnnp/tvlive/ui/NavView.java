package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

import me.qnnp.tvlive.R;

/**
 * 根层导航覆盖层（批次 8）：位于 {@code MainActivity.rootView} 的 TvLayout 之后、
 * 首启/设置/浮层之前，盖在全宽视频上，不再缩小视频。
 *
 * <p>批次 6 的「导航并排 / 视频缩到右侧 56%」在 TCL 上绕开了 SurfaceView 层级，但把
 * 视频挤窄了。批次 8 把导航从 {@link TvLayout} 内部搬到根层：导航和 OSD/菜单一样是
 * root 的直接子视图，显示时 {@link #ensureOnTop()} 把自己提到父层最后（参考
 * {@link Overlays} 的做法），从而压在全宽 SurfaceView 之上；视频始终全宽。</p>
 *
 * <p>导航保持 44% 宽、90% 黑底 + 右缘 24dp 渐变；内部组栏 24% + 列表 76%，
 * 列表按 → 时展开操作列（组栏 24% + 列表 52% + 操作列 24%）。动画/焦点/组栏/
 * 列表/操作列逻辑与批次 2g 一致。</p>
 */
public final class NavView extends FrameLayout {

    private static final int GROUP_BAR_WEIGHT = 24;
    private static final int CHANNEL_LIST_WEIGHT = 52;
    /** 操作列隐藏时列表占满剩余宽度：24% 组栏 + 76% 列表。 */
    private static final int CHANNEL_LIST_FULL_WEIGHT = 76;
    private static final int ACTION_COLUMN_WEIGHT = 24;
    private static final long NAV_ANIM_MS = 220L;
    /** 动画兜底延迟：动画时长 + 一帧余量。 */
    private static final long SETTLE_SLACK_MS = 80L;

    private final LinearLayout navContent;
    private final GroupBar groupBar;
    private final ActionColumn actionColumn;
    private final FrameLayout listHost;
    private final ListView channelList;
    private final TextView listEmptyView;
    private final TextView hintView;

    private final LinearLayout nowRow;
    private final TextView nowNumber;
    private final TextView nowName;
    private final TextView nowMeta;

    private boolean navShown;
    private boolean actionColumnShown;
    /** 手机横屏的触摸「设置」入口（电视恒 GONE）。 */
    private TextView settingsButton;
    private Runnable settingsAction;
    private boolean settingsVisible;

    public NavView(Context context) {
        super(context);
        setBackgroundColor(RootLayers.scrimColor());
        setVisibility(GONE);

        navContent = new LinearLayout(context);
        navContent.setOrientation(LinearLayout.VERTICAL);
        navContent.setPadding(0, dimen(R.dimen.sidebar_pad_top), 0, 0);
        addView(navContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = new TextView(context);
        brand.setText(R.string.brand);
        brand.setTextColor(color(R.color.text));
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.brand_text));
        brand.setPadding(dimen(R.dimen.sidebar_pad_start), 0, dimen(R.dimen.sidebar_pad_start), 0);
        navContent.addView(brand, wrap());

        // 三列：竖排分组栏 24% + 频道列表 52% + 常驻操作列 24%。
        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);

        groupBar = new GroupBar(context);
        columns.addView(groupBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, GROUP_BAR_WEIGHT));

        listHost = new FrameLayout(context);

        channelList = new ListView(context);
        channelList.setDivider(null);
        channelList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        channelList.setVerticalScrollBarEnabled(false);
        channelList.setHorizontalScrollBarEnabled(false);
        channelList.setCacheColorHint(Color.TRANSPARENT);
        channelList.setFocusable(false);
        channelList.setFocusableInTouchMode(false);
        channelList.setItemsCanFocus(false);
        // padding 是滚动内容的一部分；clipToPadding=false 让首/末行也能完整绘制。
        channelList.setClipToPadding(false);
        channelList.setPadding(0, dimen(R.dimen.list_margin_top), 0,
                dimen(R.dimen.list_pad_bottom));
        listHost.addView(channelList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        listEmptyView = new TextView(context);
        listEmptyView.setTextColor(color(R.color.muted));
        listEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.row_name_text));
        listEmptyView.setGravity(Gravity.CENTER);
        listEmptyView.setPadding(dimen(R.dimen.row_pad_start), 0, dimen(R.dimen.row_pad_end), 0);
        listEmptyView.setVisibility(GONE);
        listHost.addView(listEmptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        columns.addView(listHost, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, CHANNEL_LIST_FULL_WEIGHT));

        // 操作列默认 GONE，列表 → 时才出现并让出 24% 宽度。
        actionColumn = new ActionColumn(context);
        actionColumn.setVisibility(GONE);
        columns.addView(actionColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, ACTION_COLUMN_WEIGHT));

        navContent.addView(columns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 焦点台信息：导航底部，不叠在画面上。
        nowRow = new LinearLayout(context);
        nowRow.setOrientation(LinearLayout.HORIZONTAL);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        nowRow.setPadding(dimen(R.dimen.now_pad_side), dimen(R.dimen.hint_pad_top),
                dimen(R.dimen.nav_grad_width), 0);

        nowNumber = new TextView(context);
        nowNumber.setTextColor(color(R.color.tally));
        nowNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.now_num_text));
        nowNumber.setSingleLine(true);
        LinearLayout.LayoutParams nowNumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowNumLp.rightMargin = dimen(R.dimen.now_gap);
        nowRow.addView(nowNumber, nowNumLp);

        nowName = new TextView(context);
        nowName.setTextColor(color(R.color.text));
        nowName.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.now_name_text));
        nowName.setSingleLine(true);
        nowName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowRow.addView(nowName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowMeta = new TextView(context);
        nowMeta.setTextColor(color(R.color.muted));
        nowMeta.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.now_meta_text));
        nowMeta.setSingleLine(true);
        LinearLayout.LayoutParams nowMetaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nowMetaLp.leftMargin = dimen(R.dimen.now_gap);
        nowRow.addView(nowMeta, nowMetaLp);

        navContent.addView(nowRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hintView = new TextView(context);
        hintView.setText(R.string.list_hint_preview);
        hintView.setTextColor(color(R.color.hint));
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.hint_text));
        hintView.setSingleLine(true);
        hintView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        // 底栏行：按键提示 +（手机横屏才有）触摸「设置」入口；电视只显示提示，
        // 行高/内边距与 4b 完全一致。
        LinearLayout hintRow = new LinearLayout(context);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        hintRow.setBackgroundColor(color(R.color.surface));
        hintRow.setPadding(dimen(R.dimen.sidebar_pad_start), dimen(R.dimen.hint_pad_top),
                dimen(R.dimen.nav_grad_width), dimen(R.dimen.hint_pad_bottom));
        hintRow.addView(hintView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        settingsButton = new TextView(context);
        settingsButton.setText(R.string.phone_settings);
        settingsButton.setTextColor(color(R.color.muted));
        settingsButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.phone_action_text));
        settingsButton.setSingleLine(true);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setPadding(dimen(R.dimen.phone_settings_pad_h), 0,
                dimen(R.dimen.phone_settings_pad_h), 0);
        settingsButton.setMinWidth(dimen(R.dimen.phone_action_height));
        settingsButton.setMinHeight(dimen(R.dimen.phone_action_height));
        settingsButton.setBackgroundColor(color(R.color.row_active));
        settingsButton.setVisibility(GONE);
        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (settingsAction != null) {
                    settingsAction.run();
                }
            }
        });
        hintRow.addView(settingsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.phone_action_height)));

        navContent.addView(hintRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 右缘 24dp 渐变：从导航底色渐到透明，不参与点击。
        View navFade = new View(context);
        navFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{RootLayers.scrimColor(), RootLayers.scrimColor() & 0x00FFFFFF}));
        FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(
                dimen(R.dimen.nav_grad_width), ViewGroup.LayoutParams.MATCH_PARENT);
        fadeLp.gravity = Gravity.RIGHT;
        addView(navFade, fadeLp);
    }

    /** 导航放进独立窗口时由 PopupWindow 给定宽度，不再自己缩成比例宽。 */
    private boolean fillParent;
    /** 导航占整屏宽度的比例（批次 18：窄 38% / 标准 44% / 宽 50%）。 */
    private float navWidthRatio = TvNavGeometry.NAV_WIDTH_RATIO;

    public void setFillParent(boolean fill) {
        this.fillParent = fill;
    }

    /** 导航宽度比例；设置页切换后立即生效（PopupWindow 宽度由 Activity 同步 update）。 */
    public void setNavWidthRatio(float ratio) {
        float safe = ratio <= 0f ? TvNavGeometry.NAV_WIDTH_RATIO : Math.min(ratio, 1f);
        if (navWidthRatio == safe) {
            return;
        }
        navWidthRatio = safe;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 导航宽按设置比例取整（默认 44%：1080p ≈845px，720p 564px）；高度铺满。
        // 自己按比例的精确宽度测量，但保留 lp 的 MATCH_PARENT：父层每次都会以
        // 根宽量进来，旋转/改密度后自动重算，不会二次缩成比例 × 比例。
        if (!fillParent) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width > 0) {
                int target = TvNavGeometry.navWidth(width, navWidthRatio);
                if (width != target) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY);
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * 显示导航：220ms 从左滑入 + 淡入；先把自己提到 root 最后，
     * 保证在任何 SurfaceView 合成层之上（与 {@link Overlays#ensureOnTop()} 同思路）。
     */
    public void showNav(boolean animate) {
        setActionColumnVisible(false);
        navShown = true;
        ensureOnTop();

        animate().cancel();
        removeCallbacks(settleRunnable);
        setVisibility(VISIBLE);
        // TCL 4.4：ViewPropertyAnimator 的 alpha 淡入偶尔不推进，视图停在 alpha=0
        // 就表现为「导航弹了但看不见」。这里 alpha 恒为 1，只做位移；再用兜底
        // Runnable 在动画时长后强制落定终态。
        setAlpha(1f);
        if (animate) {
            setTranslationX(-navWidth());
            animate()
                    .translationX(0f)
                    .setDuration(NAV_ANIM_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            setTranslationX(0f);
        }
        postDelayed(settleRunnable, NAV_ANIM_MS + SETTLE_SLACK_MS);
    }

    /** 收起导航：反向滑出；动画不可靠时由兜底 Runnable 直接置 GONE。 */
    public void hideNav(boolean animate) {
        navShown = false;
        setActionColumnVisible(false);
        animate().cancel();
        removeCallbacks(settleRunnable);
        if (!animate || getVisibility() != VISIBLE) {
            setVisibility(GONE);
            setTranslationX(0f);
            setAlpha(1f);
            return;
        }
        setAlpha(1f);
        animate()
                .translationX(-navWidth())
                .setDuration(NAV_ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(GONE);
                        setTranslationX(0f);
                        setAlpha(1f);
                    }
                })
                .start();
        postDelayed(hideSettleRunnable, NAV_ANIM_MS + SETTLE_SLACK_MS);
    }

    /** 显示动画兜底：无论动画是否推进，最终都必须完整落在屏幕内。 */
    private final Runnable settleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!navShown) {
                return;
            }
            setVisibility(VISIBLE);
            setTranslationX(0f);
            setAlpha(1f);
        }
    };

    /** 收起动画兜底：动画不推进时也要真正隐藏，避免残留半透明面板拦截焦点。 */
    private final Runnable hideSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (navShown) {
                return;
            }
            setVisibility(GONE);
            setTranslationX(0f);
            setAlpha(1f);
        }
    };

    public boolean isNavShown() {
        return navShown;
    }

    public ListView channelList() {
        return channelList;
    }

    /** 导航内的频道操作列（默认隐藏，由列表 → 打开）。 */
    public ActionColumn actionColumn() {
        return actionColumn;
    }

    /**
     * 显示/隐藏频道操作列。隐藏时列表占 76%，显示时列表 52% + 操作列 24%；
     * 直接切布局，不做动画（列表高度/行高不变，焦点行仍可见）。
     */
    public void setActionColumnVisible(boolean visible) {
        if (actionColumnShown == visible) {
            return;
        }
        actionColumnShown = visible;
        actionColumn.setVisibility(visible ? VISIBLE : GONE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) listHost.getLayoutParams();
        int weight = visible ? CHANNEL_LIST_WEIGHT : CHANNEL_LIST_FULL_WEIGHT;
        if (lp.weight != weight) {
            lp.weight = weight;
            listHost.setLayoutParams(lp);
        }
        requestLayout();
    }

    public boolean isActionColumnShown() {
        return actionColumnShown;
    }

    /** 刷新内联操作列内容/焦点。 */
    public void updateActions(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex) {
        actionColumn.update(name, favorite, variantCount, focused, focusIndex);
    }

    /** 重建竖排组栏（组名 + 数量）。 */
    public void setGroupItems(List<GroupBar.Item> items) {
        groupBar.setItems(items);
    }

    /** 组栏状态：active 组、焦点项（焦点在组栏时）、组内播放点（收藏组 + 数据组）。 */
    public void setGroupState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes) {
        groupBar.updateState(activeIndex, focusIndex, groupFocus, playingIndexes);
    }

    /** 底部提示：焦点台在播与否对应「OK 全屏 / OK 切预览」。 */
    public void setHint(CharSequence hint) {
        hintView.setText(hint);
    }

    /** 菜单打开时隐藏按键提示（保留占位，避免列表高度跳动）。 */
    public void setHintVisible(boolean visible) {
        hintView.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    /** 列表空组占位（收藏为空 / 该分组没有频道），显示在列表区域。 */
    public void showListEmpty(CharSequence text) {
        listEmptyView.setText(text);
        listEmptyView.setVisibility(VISIBLE);
    }

    public void hideListEmpty() {
        listEmptyView.setVisibility(GONE);
    }

    public void updateNow(int number, String name, String meta) {
        if (number <= 0) {
            nowNumber.setText("");
        } else {
            nowNumber.setText(ChannelListAdapter.formatNumber(number));
        }
        nowName.setText(name == null ? getContext().getString(R.string.no_channel) : name);
        nowMeta.setText(meta == null ? "" : meta);
    }

    public void clearNow() {
        nowNumber.setText("");
        nowName.setText(R.string.no_channel);
        nowMeta.setText("");
    }

    /** 手机横屏底栏的「设置」入口；电视不显示按钮。 */
    public void setSettingsEntry(boolean visible, Runnable action) {
        settingsAction = action;
        if (settingsButton == null || settingsVisible == visible) {
            return;
        }
        settingsVisible = visible;
        settingsButton.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 提到 root 父层的最后，压住全宽 SurfaceView；随后 OSD/菜单等浮层显示时
     * {@link Overlays} 会再把自己提上来，因此导航不会盖住菜单/OSD。
     */
    private void ensureOnTop() {
        // uiLayer 内顺序固定为「导航 → 首启 → 设置 → 浮层」，导航不需要也不能
        // bringChildToFront（会反过来盖住菜单/OSD）。整体的层级由 uiLayer 保证。
        invalidate();
    }

    private int navWidth() {
        int width = getWidth();
        if (width > 0) {
            return width;
        }
        return TvNavGeometry.navWidth(getResources().getDisplayMetrics().widthPixels,
                navWidthRatio);
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
