package me.qnnp.tvlive.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import me.qnnp.tvlive.R;

/**
 * 浮层集合：换台/错误 OSD、右侧菜单、全屏时钟、退出提示。
 *
 * <p>全部内嵌 framework 视图，浮在根布局上；播放不因菜单/OSD 中断。
 * 平时整层不拦截触摸（子视图 GONE 且自身不 clickable）。</p>
 */
public final class Overlays extends FrameLayout {

    public static final int ACTION_FAVORITE = 1;
    public static final int ACTION_VARIANTS = 2;
    public static final int ACTION_INFO = 3;
    public static final int ACTION_RELOAD = 4;
    public static final int ACTION_SETTINGS = 5;
    public static final int ACTION_EXIT = 6;
    public static final int ACTION_FULLSCREEN = 7;

    /** 右侧菜单面板宽度：电视（及手机横屏复用电视布局）为容器宽的 31%。 */
    private static final float MENU_PANEL_WIDTH_RATIO = 0.31f;

    /** 手机竖屏：面板太窄会把「重新加载列表」截断，放宽到 52%。 */
    private static final float MENU_PANEL_WIDTH_RATIO_PHONE = 0.52f;

    /** 一条菜单项；label / enabled 可被 Activity 就地更新（收藏文案）。 */
    public static final class MenuItem {

        private final int action;
        private String label;
        private String count;
        private boolean enabled;
        /** 在这一项之前画一条分组分隔（频道组 / 全局组）；首项忽略。 */
        private final boolean section;

        public MenuItem(int action, String label, String count, boolean enabled) {
            this(action, label, count, enabled, false);
        }

        public MenuItem(int action, String label, String count, boolean enabled,
                boolean section) {
            this.action = action;
            this.label = label;
            this.count = count;
            this.enabled = enabled;
            this.section = section;
        }

        public int action() {
            return action;
        }

        public String label() {
            return label;
        }

        public String count() {
            return count;
        }

        public boolean enabled() {
            return enabled;
        }

        public boolean section() {
            return section;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout osd;
    private final TextView osdNumber;
    private final TextView osdName;
    private final TextView osdMeta;

    private final TextView clockView;

    private final FrameLayout menuLayer;
    private final View scrim;
    private final LinearLayout menuPanel;
    private final TextView menuKicker;
    private final TextView menuSub;
    private final LinearLayout menuItemsBox;

    private final TextView toastView;
    private final TextView loadingView;
    /** 切台台名卡：台号 + 台名 + 「正在连接」，替代纯加载角标。 */
    private final LinearLayout transitionCard;
    private final TextView transitionNumber;
    private final TextView transitionName;
    private final TextView transitionStatus;
    /** 台名卡网速行（批次 15）：切台/缓冲时刷新，首包前显示「—」。 */
    private final TextView transitionSpeed;
    /** 设置页「显示网速」；关闭时不占行也不刷新。 */
    private boolean speedEnabled = true;
    /** 错误 OSD 消失后常驻右下角；有错误时优先于「加载中…」。 */
    private final TextView errorBadgeView;

    private final LinearLayout volumeBox;
    private final TextView volumeText;
    private final View volumeFill;
    private final int volumeTrackWidth;

    /** 手机全屏的返回按钮（半透明，左上）；与 OSD 同一个 2.5s 可见窗口。 */
    private TextView backChrome;
    private Runnable backChromeListener;

    private final List<ItemView> menuItems = new ArrayList<ItemView>();
    private int menuIndex = -1;
    private boolean menuOpen;
    private Runnable menuDismissListener;

    private final Runnable hideOsdRunnable = new Runnable() {
        @Override
        public void run() {
            osd.setVisibility(GONE);
        }
    };

    private final Runnable hideToastRunnable = new Runnable() {
        @Override
        public void run() {
            toastView.setVisibility(GONE);
        }
    };

    private final Runnable hideVolumeRunnable = new Runnable() {
        @Override
        public void run() {
            volumeBox.setVisibility(GONE);
        }
    };

    public Overlays(Context context) {
        super(context);

        // ---------------------------------------------------------------- OSD
        osd = new LinearLayout(context);
        osd.setOrientation(LinearLayout.HORIZONTAL);
        osd.setGravity(Gravity.CENTER_VERTICAL);
        osd.setPadding(dimen(R.dimen.osd_pad_h), dimen(R.dimen.osd_pad_v),
                dimen(R.dimen.osd_pad_h), dimen(R.dimen.osd_pad_v));
        osd.setBackground(box(R.color.osd_bg, R.dimen.osd_radius, R.color.osd_border));

        osdNumber = text(R.color.tally, R.dimen.osd_num_text);
        LinearLayout.LayoutParams osdNumLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osdNumLp.rightMargin = dimen(R.dimen.osd_gap);
        osd.addView(osdNumber, osdNumLp);

        LinearLayout osdText = new LinearLayout(context);
        osdText.setOrientation(LinearLayout.VERTICAL);

        osdName = text(R.color.text, R.dimen.osd_name_text);
        osdText.addView(osdName, wrap());

        osdMeta = text(R.color.muted, R.dimen.osd_meta_text);
        LinearLayout.LayoutParams osdMetaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osdMetaLp.topMargin = dimen(R.dimen.osd_meta_gap);
        osdText.addView(osdMeta, osdMetaLp);

        osd.addView(osdText, wrap());

        FrameLayout.LayoutParams osdLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osdLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        osdLp.leftMargin = dimen(R.dimen.osd_margin_side);
        osdLp.bottomMargin = dimen(R.dimen.osd_margin_bottom);
        addView(osd, osdLp);
        osd.setVisibility(GONE);

        // ---------------------------------------------------------------- clock
        clockView = text(R.color.clock, R.dimen.clock_text);
        clockView.setTypeface(Typeface.MONOSPACE);
        shadow(clockView);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.TOP | Gravity.RIGHT;
        clockLp.topMargin = dimen(R.dimen.clock_margin_top);
        clockLp.rightMargin = dimen(R.dimen.clock_margin_end);
        addView(clockView, clockLp);
        clockView.setVisibility(GONE);

        // --------------------------------------------------------------- loading
        // 右下角小角标：不遮 OSD（左下）/时钟（右上）；菜单打开时由 Activity 隐藏。
        loadingView = badge();
        loadingView.setText(R.string.loading);

        // ------------------------------------------------------- transition card
        // 切台反馈：居中台名卡（台号 + 台名 + 正在连接）。属于 loading 反馈，
        // 导航展开时也可显示；不替代频道 OSD。
        transitionCard = new LinearLayout(context);
        transitionCard.setOrientation(LinearLayout.VERTICAL);
        transitionCard.setGravity(Gravity.CENTER_HORIZONTAL);
        transitionCard.setPadding(dimen(R.dimen.osd_pad_h), dimen(R.dimen.osd_pad_v),
                dimen(R.dimen.osd_pad_h), dimen(R.dimen.osd_pad_v));
        transitionCard.setBackground(box(R.color.osd_bg, R.dimen.osd_radius,
                R.color.osd_border));

        transitionNumber = text(R.color.tally, R.dimen.osd_num_text);
        transitionNumber.setTypeface(Typeface.DEFAULT_BOLD);
        transitionCard.addView(transitionNumber, wrap());

        transitionName = text(R.color.text, R.dimen.osd_name_text);
        transitionName.setTypeface(Typeface.DEFAULT_BOLD);
        transitionName.setSingleLine(false);
        transitionName.setMaxLines(2);
        transitionName.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f));
        transitionName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = wrap();
        nameLp.topMargin = dimen(R.dimen.osd_meta_gap);
        transitionCard.addView(transitionName, nameLp);

        transitionStatus = text(R.color.muted, R.dimen.osd_meta_text);
        transitionStatus.setText(R.string.transition_connecting);
        LinearLayout.LayoutParams statusLp = wrap();
        statusLp.topMargin = dimen(R.dimen.osd_meta_gap);
        transitionCard.addView(transitionStatus, statusLp);

        // 网速小字：单独一行，首包前显示「—」；同一行文字只换文本，不重建卡片。
        transitionSpeed = text(R.color.muted, R.dimen.osd_meta_text);
        transitionSpeed.setText(SpeedFormat.NO_DATA);
        LinearLayout.LayoutParams speedLp = wrap();
        speedLp.topMargin = dimen(R.dimen.osd_meta_gap);
        transitionCard.addView(transitionSpeed, speedLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        addView(transitionCard, cardLp);
        transitionCard.setVisibility(GONE);

        // ----------------------------------------------------------- error badge
        // 与加载角标同位同款；两者互斥，错误优先（showLoading 会跳过）。
        errorBadgeView = badge();

        // ---------------------------------------------------------------- menu
        menuLayer = new FrameLayout(context);
        menuLayer.setVisibility(GONE);

        scrim = new View(context);
        scrim.setBackgroundColor(color(R.color.menu_scrim));
        scrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Runnable listener = menuDismissListener;
                if (listener != null) {
                    listener.run();
                }
            }
        });
        menuLayer.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        menuPanel = new LinearLayout(context);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        menuPanel.setBackgroundColor(color(R.color.menu_bg));
        menuPanel.setPadding(0, dimen(R.dimen.menu_pad_top), 0, dimen(R.dimen.menu_pad_bottom));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                Math.round(getResources().getDisplayMetrics().widthPixels * MENU_PANEL_WIDTH_RATIO),
                ViewGroup.LayoutParams.MATCH_PARENT);
        // 实际宽度在 showMenu 里按容器宽度重算（31%）。
        panelLp.gravity = Gravity.RIGHT;
        menuLayer.addView(menuPanel, panelLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dimen(R.dimen.menu_pad_side), 0, dimen(R.dimen.menu_pad_side), 0);

        menuKicker = text(R.color.text, R.dimen.menu_kicker_text);
        menuKicker.setTypeface(Typeface.DEFAULT_BOLD);
        menuKicker.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(menuKicker, wrap());

        menuSub = text(R.color.muted, R.dimen.menu_sub_text);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dimen(R.dimen.menu_sub_gap);
        header.addView(menuSub, subLp);

        menuPanel.addView(header, wrap());

        menuItemsBox = new LinearLayout(context);
        menuItemsBox.setOrientation(LinearLayout.VERTICAL);
        menuItemsBox.setPadding(0, dimen(R.dimen.menu_top_gap), 0, 0);
        menuPanel.addView(menuItemsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addView(menuLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---------------------------------------------------------------- toast
        toastView = text(R.color.text, R.dimen.toast_text);
        toastView.setPadding(dimen(R.dimen.toast_pad_h), dimen(R.dimen.toast_pad_v),
                dimen(R.dimen.toast_pad_h), dimen(R.dimen.toast_pad_v));
        toastView.setBackground(box(R.color.toast_bg, R.dimen.toast_radius, R.color.toast_border));
        FrameLayout.LayoutParams toastLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toastLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        toastLp.topMargin = dimen(R.dimen.toast_margin_top);
        addView(toastView, toastLp);
        toastView.setVisibility(GONE);

        // --------------------------------------------------------------- volume
        // 自绘音量条（不用系统音量 UI）：底部居中，2s 消失；不挡 OSD/时钟/菜单/加载角标。
        volumeTrackWidth = dimen(R.dimen.volume_width);
        volumeBox = new LinearLayout(context);
        volumeBox.setOrientation(LinearLayout.VERTICAL);
        volumeBox.setGravity(Gravity.CENTER_HORIZONTAL);

        volumeText = text(R.color.text, R.dimen.volume_text);
        shadow(volumeText);
        volumeText.setGravity(Gravity.CENTER);
        volumeBox.addView(volumeText, wrap());

        FrameLayout volumeTrack = new FrameLayout(context);
        volumeTrack.setBackgroundColor(color(R.color.hairline));
        volumeFill = new View(context);
        volumeFill.setBackgroundColor(color(R.color.tally));
        volumeTrack.addView(volumeFill, new FrameLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                volumeTrackWidth, dimen(R.dimen.volume_bar_height));
        trackLp.topMargin = dimen(R.dimen.volume_gap);
        volumeBox.addView(volumeTrack, trackLp);

        FrameLayout.LayoutParams volumeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        volumeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        volumeLp.bottomMargin = dimen(R.dimen.volume_margin_bottom);
        addView(volumeBox, volumeLp);
        volumeBox.setVisibility(GONE);

        // ------------------------------------------------- 手机全屏返回按钮
        // 半透明底 + 48dp 高（≥44dp 触摸目标），放左上角；overlays 在 root 最上层，
        // 不会被视频/列表盖住。电视从不显示（Activity 只在手机全屏时打开）。
        backChrome = text(R.color.text, R.dimen.phone_action_text);
        backChrome.setText(R.string.fullscreen_back);
        backChrome.setGravity(Gravity.CENTER);
        backChrome.setPadding(dimen(R.dimen.phone_settings_pad_h), 0,
                dimen(R.dimen.phone_settings_pad_h), 0);
        backChrome.setMinWidth(dimen(R.dimen.fullscreen_back_size));
        backChrome.setBackground(box(R.color.toast_bg, R.dimen.osd_radius,
                R.color.toast_border));
        backChrome.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (backChromeListener != null) {
                    backChromeListener.run();
                }
            }
        });
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.fullscreen_back_size));
        backLp.gravity = Gravity.TOP | Gravity.LEFT;
        backLp.leftMargin = dimen(R.dimen.osd_margin_side);
        backLp.topMargin = dimen(R.dimen.fullscreen_back_margin_top);
        addView(backChrome, backLp);
        backChrome.setVisibility(GONE);
    }

    // ------------------------------------------------------------------ OSD

    /** 换台 OSD：台号 + 名称 + 分组/源信息；2.5s 自动消失。 */
    public void showChannel(String number, String name, String meta) {
        showOsd(number, name, meta, 2500L);
    }

    public void showMessage(String primary, String secondary) {
        showOsd(null, primary, secondary, 2500L);
    }

    /** durationMs <= 0 表示不自动消失（倒计时用）。 */
    public void showMessage(String primary, String secondary, long durationMs) {
        showOsd(null, primary, secondary, durationMs);
    }

    private void showOsd(String number, String primary, String secondary, long durationMs) {
        ensureOnTop();
        handler.removeCallbacks(hideOsdRunnable);
        boolean hasNumber = number != null && !number.isEmpty();
        osdNumber.setText(hasNumber ? number : "");
        osdNumber.setVisibility(hasNumber ? VISIBLE : GONE);
        osdName.setText(primary == null ? "" : primary);
        boolean hasSecondary = secondary != null && !secondary.isEmpty();
        osdMeta.setText(hasSecondary ? secondary : "");
        osdMeta.setVisibility(hasSecondary ? VISIBLE : GONE);
        osd.setVisibility(VISIBLE);
        if (durationMs > 0) {
            handler.postDelayed(hideOsdRunnable, durationMs);
        }
    }

    /**
     * 只更新 OSD 文字（倒计时 tick 用）：不重新显示、不 ensureOnTop、不重排。
     *
     * <p>文本长度不变时只重绘；长度变化才 requestLayout（台名卡/倒计时位数变化）。</p>
     */
    public void updateOsdText(CharSequence primary, CharSequence secondary) {
        boolean relayout = OverlayRefresh.needsLayout(osdName.getText(), primary)
                || OverlayRefresh.needsLayout(osdMeta.getText(), secondary);
        osdName.setText(primary == null ? "" : primary);
        osdMeta.setText(secondary == null ? "" : secondary);
        if (relayout) {
            osd.requestLayout();
        }
    }

    public void hideOsd() {
        handler.removeCallbacks(hideOsdRunnable);
        osd.setVisibility(GONE);
    }

    public boolean isOsdVisible() {
        return osd.getVisibility() == VISIBLE;
    }

    // ---------------------------------------------------------------- clock

    /**
     * 全屏且无菜单时调用；距最近整点/半点 ≤30s 显示 HH:mm:ss，其余隐藏。
     * Activity 每秒调一次。
     */
    public void updateClock(boolean allowed) {
        if (!allowed) {
            clockView.setVisibility(GONE);
            return;
        }
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int secondOfHalfHour = (hour * 3600 + minute * 60 + second) % 1800;
        boolean nearBoundary = secondOfHalfHour <= 30 || secondOfHalfHour >= 1770;
        if (!nearBoundary) {
            clockView.setVisibility(GONE);
            return;
        }
        clockView.setText(String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second));
        if (clockView.getVisibility() != VISIBLE) {
            ensureOnTop();
        }
        clockView.setVisibility(VISIBLE);
    }

    // ----------------------------------------------------------------- menu

    public boolean isMenuOpen() {
        return menuOpen;
    }

    public void setMenuDismissListener(Runnable listener) {
        this.menuDismissListener = listener;
    }

    /** 打开右侧菜单；items 的最后一项固定在底部（退出）。 */
    public void showMenu(String kicker, String sub, List<MenuItem> items) {
        ensureOnTop();
        int containerWidth = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams panelLp = menuPanel.getLayoutParams();
        int panelWidth = Math.round(containerWidth * menuPanelRatio());
        if (panelLp.width != panelWidth) {
            panelLp.width = panelWidth;
            menuPanel.setLayoutParams(panelLp);
        }

        menuKicker.setText(kicker == null ? "" : kicker);
        menuSub.setText(sub == null ? "" : sub);

        // 7 项菜单在手机横屏（复用电视布局、屏高只 360dp）会超出面板，按可用高度
        // 压缩行高；电视/手机竖屏高度足够时仍是正常行高。
        int itemHeight = menuItemHeight(items == null ? 0 : items.size());

        menuItemsBox.removeAllViews();
        menuItems.clear();
        menuIndex = -1;
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                MenuItem item = items.get(i);
                // 只有主菜单的「退出」固定在底部；频道操作菜单不保留这个分隔。
                boolean last = i == items.size() - 1 && item.action() == ACTION_EXIT;
                if (last && items.size() > 1) {
                    // 退出项固定底部：先占满剩余高度，再用一条分隔线隔开。
                    View spacer = new View(getContext());
                    menuItemsBox.addView(spacer, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                    menuItemsBox.addView(hairline(), new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                } else if (i > 0) {
                    if (item.section()) {
                        // 分组分隔：留一点空白再画线，与普通项间细线区分。
                        View gap = new View(getContext());
                        menuItemsBox.addView(gap, new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dimen(R.dimen.menu_section_gap)));
                    }
                    menuItemsBox.addView(hairline(), new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 1));
                }
                ItemView view = createItemView(item, itemHeight);
                menuItems.add(view);
                menuItemsBox.addView(view.focus.root, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, itemHeight));
            }
        }

        menuOpen = true;
        menuLayer.setVisibility(VISIBLE);
        setMenuIndex(firstEnabledIndex());
    }

    public void hideMenu() {
        if (!menuOpen) {
            return;
        }
        menuOpen = false;
        menuLayer.setVisibility(GONE);
        menuItems.clear();
        menuItemsBox.removeAllViews();
        menuIndex = -1;
    }

    public void moveMenuSelection(int delta) {
        int size = menuItems.size();
        if (size == 0) {
            return;
        }
        int start = menuIndex < 0 ? (delta > 0 ? -1 : 0) : menuIndex;
        int index = start;
        for (int step = 0; step < size; step++) {
            index = (index + delta + size) % size;
            if (menuItems.get(index).item.enabled) {
                setMenuIndex(index);
                return;
            }
        }
    }

    /** 当前菜单项 action；没有可选项返回 -1。 */
    public int selectedMenuAction() {
        if (menuIndex < 0 || menuIndex >= menuItems.size()) {
            return -1;
        }
        ItemView view = menuItems.get(menuIndex);
        return view.item.enabled ? view.item.action : -1;
    }

    public void setMenuItemLabel(int action, String label) {
        for (int i = 0; i < menuItems.size(); i++) {
            ItemView view = menuItems.get(i);
            if (view.item.action == action) {
                view.item.label = label;
                bindItem(view, i == menuIndex);
            }
        }
    }

    public void setMenuItemEnabled(int action, boolean enabled) {
        for (int i = 0; i < menuItems.size(); i++) {
            ItemView view = menuItems.get(i);
            if (view.item.action == action) {
                view.item.enabled = enabled;
                bindItem(view, i == menuIndex);
            }
        }
        if (menuIndex >= 0 && menuIndex < menuItems.size()
                && !menuItems.get(menuIndex).item.enabled) {
            setMenuIndex(firstEnabledIndex());
        }
    }

    // --------------------------------------------------------------- volume

    /**
     * 自绘音量条：0 显示「静音」，100 显示「最大」，其余「音量 N%」；2s 后自动消失。
     */
    public void showVolume(int percent) {
        // 音量更新不碰父层：每次 ensureOnTop()（invalidate 整个 Overlays）都会让 1080p
        // 全屏重绘一次，TCL 上和视频合成抢资源，表现就是调音量时画面卡一下。
        int value = Math.max(0, Math.min(100, percent));
        handler.removeCallbacks(hideVolumeRunnable);
        if (value <= 0) {
            volumeText.setText(R.string.volume_muted);
        } else if (value >= 100) {
            volumeText.setText(R.string.volume_max);
        } else {
            volumeText.setText(getContext().getString(R.string.volume_percent, value));
        }
        // 用 scaleX 改宽度，避免 setLayoutParams 触发 volumeBox 重新 measure/layout。
        if (volumeFill.getPivotX() != 0f) {
            volumeFill.setPivotX(0f);
        }
        volumeFill.setScaleX(value / 100f);
        if (volumeBox.getVisibility() != VISIBLE) {
            volumeBox.setVisibility(VISIBLE);
        }
        handler.postDelayed(hideVolumeRunnable, 2000L);
    }

    public void hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable);
        volumeBox.setVisibility(GONE);
    }

    public boolean isVolumeVisible() {
        return volumeBox.getVisibility() == VISIBLE;
    }

    // ------------------------------------------------------------- back chrome

    /** 手机全屏返回按钮：与 OSD 一起显示/收起（Activity 用 FullscreenOsd 计时）。 */
    public void setBackChromeVisible(boolean visible) {
        if (visible) {
            ensureOnTop();
        }
        backChrome.setVisibility(visible ? VISIBLE : GONE);
    }

    public boolean isBackChromeVisible() {
        return backChrome.getVisibility() == VISIBLE;
    }

    /** 点返回按钮：退全屏回列表（由 Activity 落地）。 */
    public void setBackChromeListener(Runnable listener) {
        backChromeListener = listener;
    }

    // ---------------------------------------------------------------- toast

    public void showToast(String text, long durationMs) {
        ensureOnTop();
        handler.removeCallbacks(hideToastRunnable);
        toastView.setText(text == null ? "" : text);
        toastView.setVisibility(VISIBLE);
        if (durationMs > 0) {
            handler.postDelayed(hideToastRunnable, durationMs);
        }
    }

    public void hideToast() {
        handler.removeCallbacks(hideToastRunnable);
        toastView.setVisibility(GONE);
    }

    // -------------------------------------------------------------- loading

    /** 错误角标可见时不显示加载角标（错误优先，避免两个角标叠在右下角）。 */
    public void showLoading() {
        showLoading(null);
    }

    /** 自定义加载文案（启动读源时用「读取中…」）；null 恢复默认「加载中…」。 */
    public void showLoading(CharSequence text) {
        if (errorBadgeView.getVisibility() == VISIBLE) {
            return;
        }
        hideTransitionCard();
        ensureOnTop();
        loadingView.setText(text == null ? getContext().getString(R.string.loading) : text);
        loadingView.setVisibility(VISIBLE);
    }

    public void hideLoading() {
        loadingView.setVisibility(GONE);
    }

    /** 切台台名卡：台号 + 台名 + 「正在连接」；错误角标可见时不覆盖错误提示。 */
    public void showTransitionCard(CharSequence number, CharSequence name) {
        if (errorBadgeView.getVisibility() == VISIBLE) {
            return;
        }
        hideLoading();
        ensureOnTop();
        boolean hasNumber = number != null && number.length() > 0;
        transitionNumber.setText(hasNumber ? number : "");
        transitionNumber.setVisibility(hasNumber ? VISIBLE : GONE);
        transitionName.setText(name == null ? "" : name);
        transitionStatus.setText(R.string.transition_connecting);
        // 新台重新起算：首包到达前固定显示「—」，由引擎的 500ms 回调替换。
        transitionSpeed.setText(SpeedFormat.NO_DATA);
        transitionSpeed.setVisibility(speedEnabled ? VISIBLE : GONE);
        transitionCard.setVisibility(VISIBLE);
    }

    /**
     * 设置页「显示网速」：关闭时连行一起收起，之后引擎回调也一律丢弃。
     */
    public void setSpeedEnabled(boolean enabled) {
        speedEnabled = enabled;
        transitionSpeed.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            transitionSpeed.setText(SpeedFormat.NO_DATA);
        }
    }

    public boolean isSpeedEnabled() {
        return speedEnabled;
    }

    /**
     * 只更新网速行文字：台名卡不在显示时直接丢弃；文案没变则不碰 View。
     *
     * <p>沿用批次 12 的 {@link #updateOsdText} 思路：不走 showTransitionCard，
     * 只有文本长度变化才 requestLayout。</p>
     */
    public void updateTransitionSpeed(CharSequence text) {
        if (!speedEnabled || transitionCard.getVisibility() != VISIBLE) {
            return;
        }
        CharSequence next = text == null ? "" : text;
        if (next.toString().contentEquals(transitionSpeed.getText())) {
            return;
        }
        boolean relayout = OverlayRefresh.needsLayout(transitionSpeed.getText(), next);
        transitionSpeed.setText(next);
        if (relayout) {
            transitionSpeed.requestLayout();
        }
    }

    public void hideTransitionCard() {
        transitionCard.setVisibility(GONE);
    }

    public boolean isTransitionCardVisible() {
        return transitionCard.getVisibility() == VISIBLE;
    }

    // ----------------------------------------------------------- error badge

    /** 失败后常驻右下角的轻量角标；不自动消失，由 Activity 在换台成功/回列表/菜单时清。 */
    public void showErrorBadge(String text) {
        // 菜单打开时保持隐藏（规格：打开菜单隐藏角标）。
        if (menuOpen) {
            return;
        }
        ensureOnTop();
        hideLoading();
        hideTransitionCard();
        errorBadgeView.setText(text == null ? "" : text);
        errorBadgeView.setVisibility(VISIBLE);
    }

    public void hideErrorBadge() {
        errorBadgeView.setVisibility(GONE);
    }

    public boolean isErrorBadgeVisible() {
        return errorBadgeView.getVisibility() == VISIBLE;
    }

    // --------------------------------------------------------------- 内部

    /**
     * 浮层由隐藏转为可见时只强制重绘。
     *
     * <p>批次 12：不再 {@code bringChildToFront}。uiLayer 的子视图顺序在
     * {@code MainActivity} 构造时固定为 setup → settings → overlays，浮层本来就是最后一个
     * 子视图；重排父层会让 FrameLayout 对整个覆盖层 {@code requestLayout}，长按音量时
     * 每秒 20–30 次全树 layout（见 {@link OverlayRefresh#overlaysLast()}）。</p>
     */
    private void ensureOnTop() {
        invalidate();
    }

    /** 手机竖屏菜单更宽，避免中文项被截断；电视/手机横屏保持 31%。 */
    private float menuPanelRatio() {
        android.content.res.Configuration config = getResources().getConfiguration();
        boolean phonePortrait = DeviceMode.isPhonePortrait(
                config.smallestScreenWidthDp,
                config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT);
        return phonePortrait ? MENU_PANEL_WIDTH_RATIO_PHONE : MENU_PANEL_WIDTH_RATIO;
    }

    /** 按面板可用高度压缩菜单行高（不低于可点按的最低高度）。 */
    private int menuItemHeight(int count) {
        int full = dimen(R.dimen.menu_item_height);
        if (count <= 0 || getHeight() <= 0) {
            return full;
        }
        // 头部（标题 + 副标题）按文字大小的 1.5 倍估算行高，留出上下内边距。
        int fixed = dimen(R.dimen.menu_pad_top) + dimen(R.dimen.menu_pad_bottom)
                + dimen(R.dimen.menu_top_gap) + dimen(R.dimen.menu_sub_gap)
                + (dimen(R.dimen.menu_kicker_text) + dimen(R.dimen.menu_sub_text)) * 3 / 2;
        int per = (getHeight() - fixed) / count;
        return Math.max(Math.min(full, per), dimen(R.dimen.menu_item_min_height));
    }

    private int firstEnabledIndex() {
        for (int i = 0; i < menuItems.size(); i++) {
            if (menuItems.get(i).item.enabled) {
                return i;
            }
        }
        return -1;
    }

    private void setMenuIndex(int index) {
        menuIndex = index;
        for (int i = 0; i < menuItems.size(); i++) {
            bindItem(menuItems.get(i), i == index);
        }
    }

    private void bindItem(ItemView view, boolean active) {
        view.label.setText(view.item.label);
        boolean hasCount = view.item.count != null && !view.item.count.isEmpty();
        view.count.setText(hasCount ? view.item.count : "");
        view.count.setVisibility(hasCount ? VISIBLE : GONE);
        view.focus.setActiveBackground(active, color(R.color.menu_active_bg));
        view.label.setTextColor(active ? color(R.color.text) : color(R.color.menu_item));
        view.focus.setAccentVisible(active);
        view.focus.root.setAlpha(view.item.enabled ? 1f : 0.4f);
    }

    private ItemView createItemView(MenuItem item, int itemHeight) {
        ItemView view = new ItemView();
        view.item = item;

        view.focus = new FocusRow(getContext(),
                dimen(R.dimen.menu_pad_side), dimen(R.dimen.menu_pad_side),
                dimen(R.dimen.menu_accent_width), FocusRow.inset(itemHeight));

        view.label = new TextView(getContext());
        view.label.setTextColor(color(R.color.menu_item));
        view.label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.menu_item_text));
        view.label.setSingleLine(true);
        view.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.focus.content.addView(view.label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        view.count = new TextView(getContext());
        view.count.setTextColor(color(R.color.muted));
        view.count.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.menu_count_text));
        view.count.setSingleLine(true);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = dimen(R.dimen.menu_count_gap);
        view.focus.content.addView(view.count, countLp);

        bindItem(view, false);
        return view;
    }

    private View hairline() {
        View line = new View(getContext());
        line.setBackgroundColor(color(R.color.hairline));
        return line;
    }

    /** 右下角角标（加载 / 错误）：同款底色、圆角、内边距与位置，互斥显示。 */
    private TextView badge() {
        TextView view = text(R.color.text, R.dimen.loading_text);
        view.setPadding(dimen(R.dimen.loading_pad_h), dimen(R.dimen.loading_pad_v),
                dimen(R.dimen.loading_pad_h), dimen(R.dimen.loading_pad_v));
        view.setBackground(box(R.color.toast_bg, R.dimen.loading_radius, R.color.toast_border));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.rightMargin = dimen(R.dimen.loading_margin_end);
        lp.bottomMargin = dimen(R.dimen.loading_margin_bottom);
        addView(view, lp);
        view.setVisibility(GONE);
        return view;
    }

    /** 浮层文字统一样式：颜色 + 字号（px），单行。 */
    private TextView text(int colorRes, int textSizeDimen) {
        TextView view = new TextView(getContext());
        view.setTextColor(color(colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(textSizeDimen));
        view.setSingleLine(true);
        return view;
    }

    /** 压在全屏画面上的文字（时钟 / 音量）统一深色阴影，保证浅色画面上可读。 */
    private static void shadow(TextView view) {
        view.setShadowLayer(4f, 0f, 1f, 0x8C000000);
    }

    private GradientDrawable box(int fillColorRes, int radiusDimenRes, int strokeColorRes) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fillColorRes));
        drawable.setCornerRadius(dimen(radiusDimenRes));
        drawable.setStroke(1, color(strokeColorRes));
        return drawable;
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

    private static final class ItemView {

        FocusRow focus;
        TextView label;
        TextView count;
        MenuItem item;
    }
}
