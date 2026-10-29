package me.qnnp.tvlive.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.data.PlaylistRepository.SourceState;
import me.qnnp.tvlive.data.SourceRecord;

/**
 * 设置页（v1.0 §7）：源列表与操作（启用/停用、重载、改名、上移、下移、删除、
 * 重新加载全部）、开机自启、返回系统桌面、关于。
 *
 * <p>纯遥控操作：↑↓ 选行、OK 执行。源行 OK 进入该源的操作区，←/Back 回到源列表。
 * 添加源留批次 3（手机页）。View 只负责显示与焦点，所有文件/网络动作回调 Activity。</p>
 *
 * <p>批次 8：设置页是 root 层的全屏半透明（90% 黑）覆盖层，盖在全宽视频上播放不停；
 * 内容居中占 62% 宽（窄屏单列全宽），不再把视频缩到右侧。显示时调用
 * {@link #ensureOnTop()} 提到 root 最后，避免 TCL 的 SurfaceView 合成层压住页面。</p>
 */
public final class SettingsView extends FrameLayout {

    public static final int ACTION_RELOAD_ALL = 1;
    public static final int ACTION_ADD_SOURCE = 2;
    public static final int ACTION_BOOT = 3;
    public static final int ACTION_OPEN_HOME = 4;
    /** 源区「手机控制」行：OK 弹出大二维码浮层（批次 3b）。 */
    public static final int ACTION_CONTROL_QR = 5;

    public static final int ACTION_SOURCE_TOGGLE = 10;
    public static final int ACTION_SOURCE_RELOAD = 11;
    public static final int ACTION_SOURCE_RENAME = 12;
    public static final int ACTION_SOURCE_UP = 13;
    public static final int ACTION_SOURCE_DOWN = 14;
    public static final int ACTION_SOURCE_DELETE = 15;
    public static final int ACTION_SOURCE_LOCATION = 16;

    /**
     * 一级「播放」分组：切台方式 / 视频渲染 / 画面比例 / 切台提示 / 显示网速（批次 9、15、17、18）。
     */
    public static final int ACTION_PLAY_SWITCH_MODE = PlayRowSpec.ACTION_SWITCH_MODE;
    public static final int ACTION_PLAY_BOOST = AdvancedRowSpec.ACTION_BOOST;
    public static final int ACTION_PLAY_CARD = PlayRowSpec.ACTION_CARD;
    public static final int ACTION_PLAY_SPEED = PlayRowSpec.ACTION_SPEED;
    public static final int ACTION_PLAY_RENDER = PlayRowSpec.ACTION_RENDER;
    public static final int ACTION_PLAY_ASPECT = PlayRowSpec.ACTION_ASPECT;

    /** 「高级」折叠标题 + 子项（批次 18）；首帧加速也从一级移入这里。 */
    public static final int ACTION_ADVANCED = AdvancedRowSpec.ACTION_TOGGLE;
    public static final int ACTION_ADV_NAV_WIDTH = AdvancedRowSpec.ACTION_NAV_WIDTH;
    public static final int ACTION_ADV_AUTO_HIDE = AdvancedRowSpec.ACTION_AUTO_HIDE;
    public static final int ACTION_ADV_CLOCK = AdvancedRowSpec.ACTION_CLOCK;

    public interface Listener {

        /** 「重新加载全部」：重读全部本地缓存，不下载。 */
        void onReloadSources();

        /** 「添加源」：批次 3。 */
        void onAddSource();

        void onToggleSource(SourceRecord record);

        /** 重载单个源：URL 重新下载，FILE 重读缓存。 */
        void onReloadSource(SourceRecord record);

        void onRenameSource(SourceRecord record);

        /** 改源地址：URL 重新下载/校验；FILE 源会转为 URL 源。 */
        void onChangeSourceLocation(SourceRecord record);

        /** delta = -1 上移，+1 下移。 */
        void onMoveSource(SourceRecord record, int delta);

        void onDeleteSource(SourceRecord record);

        void onToggleBoot(boolean enabled);

        void onOpenSystemHome();

        /** 切台方式循环后的序号：0 保留 / 1 极速直切 / 2 双画面。 */
        void onSwitchModeChanged(int mode);

        /** 视频渲染循环后的序号：0 自动 / 1 SurfaceView / 2 TextureView。 */
        void onRenderModeChanged(int mode);

        void onFirstFrameBoostChanged(boolean enabled);

        void onTransitionCardChanged(boolean enabled);

        /** 「显示网速」开关：切台/缓冲时是否在台名卡上显示实时网速。 */
        void onShowSpeedChanged(boolean enabled);

        /** 画面比例循环后的序号：0 适应 / 1 拉伸 / 2 裁剪。 */
        void onAspectChanged(int aspect);

        /** 导航宽度循环后的序号：0 窄 / 1 标准 / 2 宽。 */
        void onNavWidthChanged(int width);

        /** 自动收起秒数：5 / 8 / 15 / 0（不收起）。 */
        void onAutoHideChanged(int seconds);

        /** 整点/半点时钟角标开关。 */
        void onClockBadgeChanged(boolean enabled);

        /** 「高级」分组展开状态；Activity 落库，下次打开设置页恢复。 */
        void onAdvancedExpandedChanged(boolean expanded);

        /** 「效果不理想可换一种」只出现一次，出现时通知 Activity 落库。 */
        void onSwitchModeHintShown();
    }

    private final ScrollView scrollView;
    private final LinearLayout content;

    private final TextView sourceNote;
    private final LinearLayout sourceContainer;
    private final TextView addressValue;

    /** 源区「手机控制」行：显示地址/PIN，OK 弹二维码浮层。 */
    private final Row controlRow;
    /** 行下方常驻小二维码（批次 6）：离线时灰显 + 提示。 */
    private FrameLayout inlineQrBox;
    private ImageView inlineQrImage;
    private TextView inlineQrPlaceholder;
    private TextView inlineQrHint;
    private int inlineQrPixelSize;
    private String shownInlineQrContent;
    private final FrameLayout qrOverlay;
    private ImageView qrImage;
    private TextView qrAddress;
    private TextView qrPin;
    private TextView qrNote;
    private String controlAddress;
    private String controlPin = "";
    private String qrContent;
    private boolean qrOverlayVisible;

    private final List<Row> staticRows = new ArrayList<Row>();
    /** 源区顶部固定行（手机控制），视觉与焦点顺序都在源列表之前。 */
    private final List<Row> headerRows = new ArrayList<Row>();
    private final List<Row> sourceRows = new ArrayList<Row>();
    private final List<Row> actionRows = new ArrayList<Row>();
    private final List<Row> focusable = new ArrayList<Row>();

    private final Row bootRow;
    private final TextView lastErrorValue;
    private Row switchModeRow;
    private Row renderRow;
    private Row aspectRow;
    private Row boostRow;
    private Row cardRow;
    private Row speedRow;
    /** 「高级」折叠标题 + 子项（批次 18）。 */
    private Row advancedRow;
    private Row navWidthRow;
    private Row autoHideRow;
    private Row clockRow;
    private final List<Row> advancedRows = new ArrayList<Row>();
    /** 播放设置当前值（由 Activity 在打开设置页时下发）。 */
    private int switchMode;
    private int renderMode;
    private int aspect;
    private boolean firstFrameBoost = true;
    private boolean transitionCard = true;
    private boolean showSpeed = true;
    private boolean switchModeHintSeen;
    /** 导航/显示设置（高级分组）；默认值与 prefs 保持一致。 */
    private int navWidth = 1;
    private int autoHideSeconds = 8;
    private boolean clockBadge = true;
    /** 「高级」是否展开；由 Activity 从 prefs 下发，OK 切换后回调落库。 */
    private boolean advancedOpen;
    /** 一次性提示正在右侧显示（焦点离开/按键后恢复模式值）。 */
    private boolean switchModeHintVisible;
    private int qrPixelSize;
    /** 手机窄屏（批次 5a）：单列全宽、行高 ≥44dp、提示改成触摸文案。 */
    private final boolean narrow;

    private List<SourceState> states = Collections.emptyList();
    private SourceRecord selected;
    private boolean actionMode;
    private boolean loading = true;
    private int lastActionFocus = ACTION_SOURCE_TOGGLE;
    private String focusedSourceId;

    private int focusIndex = -1;
    private Listener listener;

    public SettingsView(Context context) {
        super(context);
        // 90% 黑：视频全宽在下面继续播放，设置内容保持可读。
        setBackgroundColor(RootLayers.scrimColor());

        int padSide = dimen(R.dimen.setup_pad_side);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        narrow = DeviceMode.isPhone(
                getResources().getConfiguration().smallestScreenWidthDp);
        int phonePad = dimen(R.dimen.phone_pad_side);

        TextView brand = new TextView(context);
        brand.setText(R.string.brand);
        brand.setTextColor(color(R.color.text));
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.brand_text));
        if (!narrow) {
            FrameLayout.LayoutParams brandLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            brandLp.gravity = Gravity.TOP | Gravity.LEFT;
            brandLp.leftMargin = padSide;
            brandLp.topMargin = dimen(R.dimen.setup_pad_top);
            addView(brand, brandLp);
        }

        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(false);
        FrameLayout.LayoutParams scrollLp;
        if (narrow) {
            // 窄屏：单列占满宽度，只留左右/上下安全边距；内容自身滚动。
            // 底部给固定提示行留出空间，否则横屏时最后一行会被提示压住。
            scrollLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            scrollLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            scrollLp.topMargin = phonePad;
            scrollLp.bottomMargin = phonePad + dimen(R.dimen.settings_hint_gap)
                    + dimen(R.dimen.hint_text);
            scrollView.setPadding(phonePad, 0, phonePad, 0);
        } else {
            scrollLp = new FrameLayout.LayoutParams(
                    Math.round(screenWidth * 0.62f), ViewGroup.LayoutParams.MATCH_PARENT);
            scrollLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            scrollLp.topMargin = dimen(R.dimen.settings_pad_top) + dimen(R.dimen.setup_pad_top);
            scrollLp.bottomMargin = dimen(R.dimen.setup_pad_side)
                    + dimen(R.dimen.settings_hint_gap) + dimen(R.dimen.hint_text);
        }
        addView(scrollView, scrollLp);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (narrow) {
            // 窄屏把 brand 放进滚动内容顶部，避免和列表头重叠。
            LinearLayout.LayoutParams brandLp = wrap();
            brandLp.bottomMargin = dimen(R.dimen.settings_section_gap_bottom);
            content.addView(brand, brandLp);
        }

        TextView title = new TextView(context);
        title.setText(R.string.settings_title);
        title.setTextColor(color(R.color.text));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_title_text));
        content.addView(title, wrap());

        // ---------------------------------------------------------------- 源
        SectionTitle.addTo(content, R.string.settings_section_source);

        // 手机控制地址 + PIN：源区第一行，左侧文本 + 右侧常驻小二维码；整块 OK 弹大图。
        controlRow = addControlRow();

        sourceNote = new TextView(context);
        sourceNote.setTextColor(color(R.color.muted));
        sourceNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        sourceNote.setSingleLine(true);
        sourceNote.setEllipsize(TextUtils.TruncateAt.END);
        sourceNote.setVisibility(GONE);
        LinearLayout.LayoutParams noteLp = wrap();
        noteLp.bottomMargin = dimen(R.dimen.settings_section_gap_bottom);
        content.addView(sourceNote, noteLp);

        sourceContainer = new LinearLayout(context);
        sourceContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(sourceContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addStaticRow(R.string.settings_reload_all, ACTION_RELOAD_ALL, true);
        addStaticRow(R.string.settings_add_source, ACTION_ADD_SOURCE, false);

        // -------------------------------------------------------------- 播放
        // 批次 9：源列表之后、系统区之前；OK 循环切值，行下方按焦点显示一句说明。
        // 批次 18：一级只放常用项（PlayRowSpec），进阶项收进下方可折叠的「高级」。
        SectionTitle.addTo(content, R.string.settings_section_play);
        for (PlayRowSpec.Row spec : PlayRowSpec.rows()) {
            Row row = addPlayRow(spec.labelRes, spec.detailRes, spec.action);
            if (spec.action == ACTION_PLAY_SWITCH_MODE) {
                switchModeRow = row;
            } else if (spec.action == ACTION_PLAY_RENDER) {
                renderRow = row;
            } else if (spec.action == ACTION_PLAY_ASPECT) {
                aspectRow = row;
            } else if (spec.action == ACTION_PLAY_CARD) {
                cardRow = row;
            } else if (spec.action == ACTION_PLAY_SPEED) {
                speedRow = row;
            }
        }

        // -------------------------------------------------------------- 高级
        // 折叠标题始终可聚焦；子项缩进一级，收起时整行 GONE（焦点/滚动自动跳过）。
        advancedRow = createRow("", ACTION_ADVANCED, true);
        staticRows.add(advancedRow);
        content.addView(advancedRow.view.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight()));
        for (AdvancedRowSpec.Row spec : AdvancedRowSpec.rows()) {
            Row row = addPlayRow(spec.labelRes, spec.detailRes, spec.action, true);
            if (spec.action == ACTION_PLAY_BOOST) {
                boostRow = row;
            } else if (spec.action == ACTION_ADV_NAV_WIDTH) {
                navWidthRow = row;
            } else if (spec.action == ACTION_ADV_AUTO_HIDE) {
                autoHideRow = row;
            } else if (spec.action == ACTION_ADV_CLOCK) {
                clockRow = row;
            }
            advancedRows.add(row);
        }
        applyAdvancedVisibility();
        bindPlayValues();

        // -------------------------------------------------------------- 系统
        SectionTitle.addTo(content, R.string.settings_section_system);
        bootRow = addStaticRow(R.string.settings_boot, ACTION_BOOT, true);
        addStaticRow(R.string.settings_open_home, ACTION_OPEN_HOME, true);

        // -------------------------------------------------------------- 关于
        SectionTitle.addTo(content, R.string.settings_section_about);
        addInfo(R.string.settings_version, versionName());
        addressValue = addInfo(R.string.settings_address,
                getContext().getString(R.string.settings_control_offline));
        lastErrorValue = addInfo(R.string.settings_last_error,
                getContext().getString(R.string.settings_no_error));

        // 固定在底部，不随内容滚动：始终提示按键。
        TextView hint = new TextView(context);
        hint.setText(narrow ? R.string.settings_hint_touch : R.string.settings_hint);
        hint.setTextColor(color(R.color.hint));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.hint_text));
        hint.setSingleLine(true);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        hintLp.leftMargin = narrow ? phonePad
                : Math.round(screenWidth * 0.19f) + dimen(R.dimen.settings_row_pad_side);
        hintLp.bottomMargin = narrow ? phonePad : dimen(R.dimen.setup_pad_side);
        addView(hint, hintLp);

        setBootEnabled(false);
        rebuildSourceArea();
        rebuildFocusables();

        qrOverlay = buildQrOverlay();
        qrOverlay.setVisibility(GONE);
        addView(qrOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 设置页在 uiLayer 内固定排在导航之后、浮层之前；层级由 uiLayer 整体压住视频，
     * 这里只需重绘，不能再 bringChildToFront（否则会盖住菜单/OSD）。
     */
    public void ensureOnTop() {
        invalidate();
    }

    /** 打开/显示前调用：重置焦点到第一项可操作行。 */
    public void resetFocus() {
        actionMode = false;
        selected = null;
        focusedSourceId = null;
        focusIndex = -1;
        hideQrOverlay();
        rebuildSourceArea();
        rebuildFocusables();
        setFocusIndex(firstFocusable());
    }

    // ------------------------------------------------------------------ 数据

    public void setSourceLoading() {
        loading = true;
        states = Collections.emptyList();
        selected = null;
        actionMode = false;
        showNote(null, false);
        rebuildSourceArea();
        rebuildFocusables();
        setFocusIndex(firstFocusable());
    }

    /** 索引损坏等整体错误。 */
    public void setSourceError(String detail) {
        loading = false;
        states = Collections.emptyList();
        selected = null;
        actionMode = false;
        showNote(getContext().getString(R.string.settings_source_error,
                detail == null ? "" : detail), true);
        rebuildSourceArea();
        rebuildFocusables();
        setFocusIndex(firstFocusable());
    }

    /**
     * 源列表快照（含停用/失败）。调用时尽量保持选中源与焦点；源被删掉则退出操作区。
     */
    public void setSources(List<SourceState> next) {
        loading = false;
        states = next == null ? Collections.<SourceState>emptyList() : next;

        String selectedId = selected == null ? null : selected.id();
        selected = null;
        if (selectedId != null) {
            for (SourceState state : states) {
                if (selectedId.equals(state.record().id())) {
                    selected = state.record();
                    break;
                }
            }
        }
        if (selected == null) {
            actionMode = false;
        }

        int failed = 0;
        for (SourceState state : states) {
            if (state.error() != null) {
                failed++;
            }
        }
        if (failed > 0) {
            showNote(getContext().getString(R.string.settings_sources_failed, failed,
                    states.size()), true);
        } else {
            showNote(null, false);
        }

        rebuildSourceArea();
        rebuildFocusables();
        restoreFocus();
    }

    public void setBootEnabled(boolean enabled) {
        bindSwitch(bootRow, enabled);
    }

    /**
     * 播放设置快照：打开设置页时由 Activity 从 prefs 下发。
     * switchMode 0 保留 / 1 极速直切 / 2 双画面；renderMode 0 自动 / 1 SurfaceView /
     * 2 TextureView；hintSeen 控制一次性提示。
     */
    public void setPlaybackSettings(int switchMode, int renderMode, boolean firstFrameBoost,
            boolean transitionCard, boolean showSpeed, boolean switchModeHintSeen) {
        this.switchMode = Math.max(0, Math.min(2, switchMode));
        this.renderMode = Math.max(0, Math.min(2, renderMode));
        this.firstFrameBoost = firstFrameBoost;
        this.transitionCard = transitionCard;
        this.showSpeed = showSpeed;
        this.switchModeHintSeen = switchModeHintSeen;
        bindPlayValues();
    }

    /**
     * 导航/显示设置快照（批次 18）：画面比例、导航宽度、自动收起与时钟角标。
     * 参数越界时回退默认值，保证从旧版本升级后页面不会显示非法档位。
     */
    public void setDisplaySettings(int aspect, int navWidth, int autoHideSeconds,
            boolean clockBadge) {
        this.aspect = Math.max(0, Math.min(2, aspect));
        this.navWidth = Math.max(0, Math.min(2, navWidth));
        this.autoHideSeconds = AdvancedRowSpec.normalizeAutoHideSeconds(autoHideSeconds);
        this.clockBadge = clockBadge;
        bindPlayValues();
    }

    /** 「高级」分组展开状态：打开设置页时由 Activity 从 prefs 下发。 */
    public void setAdvancedExpanded(boolean expanded) {
        this.advancedOpen = expanded;
        applyAdvancedVisibility();
    }

    /**
     * 手机控制地址、PIN 与二维码内容；address == null 表示服务未启动/没有 WiFi。
     * 二维码内容含 10 分钟 token，Activity 在网络/端口变化时重新下发。
     */
    public void setControlInfo(String address, String pin, String qrContent) {
        controlAddress = address;
        controlPin = pin == null ? "" : pin;
        this.qrContent = qrContent;
        boolean online = address != null && !address.isEmpty();
        controlRow.enabled = online;
        controlRow.view.root.setAlpha(online ? 1f : 0.4f);
        String value = online
                ? getContext().getString(R.string.settings_control_value, address, controlPin)
                : getContext().getString(R.string.settings_control_offline);
        controlRow.view.value.setText(value);
        controlRow.view.value.setTextColor(color(online ? R.color.text : R.color.muted));
        addressValue.setText(online ? value
                : getContext().getString(R.string.settings_control_offline));
        updateInlineQr();
        if (!online && qrOverlayVisible) {
            hideQrOverlay();
        }
    }

    public void setLastError(String error) {
        if (error == null || error.isEmpty()) {
            lastErrorValue.setText(R.string.settings_no_error);
            lastErrorValue.setTextColor(color(R.color.muted));
        } else {
            lastErrorValue.setText(error);
            lastErrorValue.setTextColor(color(R.color.text));
        }
    }

    // ------------------------------------------------------------------ 按键

    /** ↑↓ 移动 / OK 执行 / ← 退出操作区；Back 由 {@link #handleBack()} 处理。 */
    public boolean handleKey(int keyCode) {
        if (qrOverlayVisible) {
            // 浮层打开时吞掉方向/OK，只留 Back（由 handleBack 关闭）。
            return true;
        }
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(-1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                if (actionMode) {
                    leaveActionMode();
                } else {
                    moveFocus(-1);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!actionMode && isSourceRowFocused()) {
                    enterActionMode(focusedSource());
                } else {
                    moveFocus(1);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                activateFocused();
                return true;
            default:
                return false;
        }
    }

    /** Back：二维码浮层 → 操作区 → 返回 false 时由 Activity 关闭设置页。 */
    public boolean handleBack() {
        if (qrOverlayVisible) {
            hideQrOverlay();
            return true;
        }
        if (actionMode) {
            leaveActionMode();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 焦点

    private void moveFocus(int delta) {
        if (focusable.isEmpty() || delta == 0) {
            return;
        }
        int start = focusIndex < 0 ? (delta > 0 ? -1 : 0) : focusIndex;
        int index = start;
        for (int step = 0; step < focusable.size(); step++) {
            index = (index + delta + focusable.size()) % focusable.size();
            Row row = focusable.get(index);
            if (row.enabled && row.view.root.getVisibility() == VISIBLE) {
                setFocusIndex(index);
                return;
            }
        }
    }

    private void setFocusIndex(int index) {
        focusIndex = index;
        for (int i = 0; i < focusable.size(); i++) {
            bindRowFocus(focusable.get(i), i == index);
        }
        if (index >= 0 && index < focusable.size()) {
            Row row = focusable.get(index);
            boolean onSwitchMode = row.action == ACTION_PLAY_SWITCH_MODE;
            if (onSwitchMode && !switchModeHintSeen) {
                // 第一次聚焦「切台方式」：右侧提示一次「效果不理想可换一种」。
                switchModeHintSeen = true;
                switchModeHintVisible = true;
                Listener l = listener;
                if (l != null) {
                    l.onSwitchModeHintShown();
                }
            } else if (!onSwitchMode) {
                switchModeHintVisible = false;
            }
            bindPlayValues();
            if (row.sourceRow && row.record != null) {
                focusedSourceId = row.record.id();
            } else if (row.action >= ACTION_SOURCE_TOGGLE
                    && row.action < ACTION_PLAY_SWITCH_MODE) {
                lastActionFocus = row.action;
            }
            ensureVisible(row.view.root);
        }
    }

    private int firstFocusable() {
        for (int i = 0; i < focusable.size(); i++) {
            Row row = focusable.get(i);
            if (row.enabled && row.view.root.getVisibility() == VISIBLE) {
                return i;
            }
        }
        return -1;
    }

    private void restoreFocus() {
        int index = -1;
        if (actionMode && selected != null) {
            for (int i = 0; i < actionRows.size(); i++) {
                if (actionRows.get(i).action == lastActionFocus) {
                    index = focusable.indexOf(actionRows.get(i));
                    break;
                }
            }
        } else if (selected != null || focusedSourceId != null) {
            String targetId = focusedSourceId != null
                    ? focusedSourceId : selected.id();
            for (int i = 0; i < sourceRows.size(); i++) {
                if (sourceRows.get(i).record != null
                        && sourceRows.get(i).record.id().equals(targetId)) {
                    index = focusable.indexOf(sourceRows.get(i));
                    break;
                }
            }
        }
        if (index < 0 || index >= focusable.size()) {
            index = firstFocusable();
        }
        setFocusIndex(index);
    }

    private boolean isSourceRowFocused() {
        return focusIndex >= 0 && focusIndex < focusable.size()
                && focusable.get(focusIndex).sourceRow;
    }

    private SourceRecord focusedSource() {
        if (focusIndex < 0 || focusIndex >= focusable.size()) {
            return null;
        }
        Row row = focusable.get(focusIndex);
        return row.sourceRow ? row.record : null;
    }

    private void enterActionMode(SourceRecord record) {
        if (record == null) {
            return;
        }
        selected = record;
        actionMode = true;
        lastActionFocus = ACTION_SOURCE_TOGGLE;
        rebuildSourceArea();
        rebuildFocusables();
        restoreFocus();
    }

    private void leaveActionMode() {
        actionMode = false;
        rebuildSourceArea();
        rebuildFocusables();
        restoreFocus();
    }

    private void activateFocused() {
        if (focusIndex < 0 || focusIndex >= focusable.size()) {
            return;
        }
        Row row = focusable.get(focusIndex);
        if (!row.enabled) {
            return;
        }
        if (row.sourceRow) {
            enterActionMode(row.record);
            return;
        }
        fire(row);
    }

    private void fire(Row row) {
        if (row.action == ACTION_CONTROL_QR) {
            // 纯 View 行为（二维码浮层），不依赖 Activity。
            showQrOverlay();
            return;
        }
        if (row.action == ACTION_ADVANCED) {
            // 纯 View 行为（展开/收起），展开状态再回调 Activity 落库。
            toggleAdvanced();
            return;
        }
        Listener target = listener;
        if (target == null) {
            return;
        }
        switch (row.action) {
            case ACTION_RELOAD_ALL:
                target.onReloadSources();
                break;
            case ACTION_ADD_SOURCE:
                target.onAddSource();
                break;
            case ACTION_BOOT:
                target.onToggleBoot(!bootRow.on);
                break;
            case ACTION_OPEN_HOME:
                target.onOpenSystemHome();
                break;
            case ACTION_PLAY_SWITCH_MODE:
                switchMode = (switchMode + 1) % 3;
                switchModeHintVisible = false;
                bindPlayValues();
                target.onSwitchModeChanged(switchMode);
                break;
            case ACTION_PLAY_RENDER:
                renderMode = (renderMode + 1) % 3;
                bindPlayValues();
                target.onRenderModeChanged(renderMode);
                break;
            case ACTION_PLAY_ASPECT:
                aspect = (aspect + 1) % 3;
                bindPlayValues();
                target.onAspectChanged(aspect);
                break;
            case ACTION_ADV_NAV_WIDTH:
                navWidth = (navWidth + 1) % 3;
                bindPlayValues();
                target.onNavWidthChanged(navWidth);
                break;
            case ACTION_ADV_AUTO_HIDE:
                autoHideSeconds = AdvancedRowSpec.nextAutoHideSeconds(autoHideSeconds);
                bindPlayValues();
                target.onAutoHideChanged(autoHideSeconds);
                break;
            case ACTION_ADV_CLOCK:
                clockBadge = !clockBadge;
                bindPlayValues();
                target.onClockBadgeChanged(clockBadge);
                break;
            case ACTION_PLAY_BOOST:
                firstFrameBoost = !firstFrameBoost;
                bindPlayValues();
                target.onFirstFrameBoostChanged(firstFrameBoost);
                break;
            case ACTION_PLAY_CARD:
                transitionCard = !transitionCard;
                bindPlayValues();
                target.onTransitionCardChanged(transitionCard);
                break;
            case ACTION_PLAY_SPEED:
                showSpeed = !showSpeed;
                bindPlayValues();
                target.onShowSpeedChanged(showSpeed);
                break;
            case ACTION_SOURCE_TOGGLE:
                if (selected != null) {
                    target.onToggleSource(selected);
                }
                break;
            case ACTION_SOURCE_RELOAD:
                if (selected != null) {
                    target.onReloadSource(selected);
                }
                break;
            case ACTION_SOURCE_RENAME:
                if (selected != null) {
                    target.onRenameSource(selected);
                }
                break;
            case ACTION_SOURCE_LOCATION:
                if (selected != null) {
                    target.onChangeSourceLocation(selected);
                }
                break;
            case ACTION_SOURCE_UP:
                if (selected != null) {
                    target.onMoveSource(selected, -1);
                }
                break;
            case ACTION_SOURCE_DOWN:
                if (selected != null) {
                    target.onMoveSource(selected, 1);
                }
                break;
            case ACTION_SOURCE_DELETE:
                if (selected != null) {
                    target.onDeleteSource(selected);
                }
                break;
            default:
                break;
        }
    }

    private void ensureVisible(final View row) {
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                int top = contentTop(row);
                int bottom = top + row.getHeight();
                if (top < scrollView.getScrollY()) {
                    scrollView.smoothScrollTo(0, top);
                } else if (bottom > scrollView.getScrollY() + scrollView.getHeight()) {
                    scrollView.smoothScrollTo(0, bottom - scrollView.getHeight());
                }
            }
        });
    }

    /** 行（可能嵌在 sourceContainer 里）相对滚动内容顶部的偏移。 */
    private int contentTop(View row) {
        int top = 0;
        View current = row;
        while (current != null && current != content) {
            top += current.getTop();
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return top;
    }

    // ------------------------------------------------------------------ 源区域

    private void rebuildSourceArea() {
        sourceContainer.removeAllViews();
        sourceRows.clear();
        actionRows.clear();

        if (loading) {
            sourceContainer.addView(hintText(getContext().getString(R.string.settings_source_loading),
                    color(R.color.muted)), wrap());
            return;
        }
        if (states.isEmpty()) {
            sourceContainer.addView(hintText(getContext().getString(R.string.settings_source_none),
                    color(R.color.muted)), wrap());
            return;
        }

        for (SourceState state : states) {
            Row row = buildSourceRow(state);
            sourceRows.add(row);
            sourceContainer.addView(row.view.root, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, sourceRowHeight()));
        }

        if (actionMode && selected != null) {
            addActionCaption();
            boolean first = isFirst(selected);
            boolean last = isLast(selected);
            addActionRow(ACTION_SOURCE_TOGGLE, selected.enabled()
                    ? R.string.settings_source_disable : R.string.settings_source_enable, true);
            addActionRow(ACTION_SOURCE_RELOAD, R.string.settings_source_reload, true);
            addActionRow(ACTION_SOURCE_RENAME, R.string.settings_source_rename, true);
            addActionRow(ACTION_SOURCE_LOCATION, R.string.settings_source_location, true);
            addActionRow(ACTION_SOURCE_UP, R.string.settings_source_up, !first);
            addActionRow(ACTION_SOURCE_DOWN, R.string.settings_source_down, !last);
            addActionRow(ACTION_SOURCE_DELETE, R.string.settings_source_delete, true);
        }
    }

    private void addActionCaption() {
        TextView caption = hintText(getContext().getString(
                R.string.settings_source_actions, selected.name()), color(R.color.muted));
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dimen(R.dimen.settings_section_gap_bottom);
        lp.bottomMargin = dimen(R.dimen.settings_section_gap_bottom);
        sourceContainer.addView(caption, lp);
    }

    private void addActionRow(int action, int labelRes, boolean enabled) {
        Row row = createRow(getContext().getString(labelRes), action, enabled);
        actionRows.add(row);
        sourceContainer.addView(row.view.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight()));
    }

    private Row buildSourceRow(SourceState state) {
        SourceRecord record = state.record();
        Row row = new Row();
        row.sourceRow = true;
        row.record = record;
        row.enabled = true;
        row.action = -1;

        int detailColor = state.error() != null && record.enabled()
                ? color(R.color.tally) : color(R.color.muted);
        row.view = SettingRow.sourceRow(getContext(), record.name(), sourceDetail(state),
                detailColor);
        row.view.value.setText(record.enabled() ? R.string.settings_on : R.string.settings_off);
        row.view.value.setTextColor(record.enabled()
                ? color(R.color.tally) : color(R.color.muted));

        row.view.root.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Row clicked = (Row) v.getTag();
                if (clicked != null) {
                    enterActionMode(clicked.record);
                }
            }
        });
        row.view.root.setTag(row);
        row.view.setFocused(false);
        return row;
    }

    private String sourceDetail(SourceState state) {
        SourceRecord record = state.record();
        if (!record.enabled()) {
            return getContext().getString(R.string.settings_source_detail_off,
                    state.channelCount());
        }
        if (state.error() != null) {
            return getContext().getString(R.string.settings_source_detail_failed,
                    state.channelCount(), state.error());
        }
        return getContext().getString(R.string.settings_source_detail_ok, state.channelCount());
    }

    private boolean isFirst(SourceRecord record) {
        return !states.isEmpty() && sameId(states.get(0).record(), record);
    }

    private boolean isLast(SourceRecord record) {
        return !states.isEmpty() && sameId(states.get(states.size() - 1).record(), record);
    }

    private static boolean sameId(SourceRecord left, SourceRecord right) {
        return left != null && right != null && left.id().equals(right.id());
    }

    private void showNote(String text, boolean error) {
        if (text == null || text.isEmpty()) {
            sourceNote.setText("");
            sourceNote.setVisibility(GONE);
        } else {
            sourceNote.setText(text);
            sourceNote.setTextColor(color(error ? R.color.tally : R.color.muted));
            sourceNote.setVisibility(VISIBLE);
        }
    }

    // ------------------------------------------------------------------ 构建

    private Row addStaticRow(int labelRes, int action, boolean enabled) {
        Row row = createRow(getContext().getString(labelRes), action, enabled);
        staticRows.add(row);
        content.addView(row.view.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight()));
        return row;
    }

    /** 一级播放设置行：两行样式（标签 + 说明），OK 循环/切换值，说明只在选中时显示。 */
    private Row addPlayRow(int labelRes, int detailRes, int action) {
        return addPlayRow(labelRes, detailRes, action, false);
    }

    /** 播放设置行；indent = true 时左侧再缩进一级（高级分组子项）。 */
    private Row addPlayRow(int labelRes, int detailRes, int action, boolean indent) {
        Row row = new Row();
        row.action = action;
        row.enabled = true;
        row.playRow = true;
        row.view = SettingRow.playRow(getContext(), getContext().getString(labelRes),
                getContext().getString(detailRes));
        row.view.detail.setVisibility(GONE);
        if (indent) {
            int pad = dimen(R.dimen.settings_advanced_indent);
            row.view.label.setPadding(pad, 0, 0, 0);
            row.view.detail.setPadding(pad, 0, 0, 0);
        }

        row.view.root.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Row clicked = (Row) v.getTag();
                if (clicked == null || !clicked.enabled) {
                    return;
                }
                fire(clicked);
            }
        });
        row.view.root.setTag(row);

        staticRows.add(row);
        content.addView(row.view.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sourceRowHeight()));
        return row;
    }

    /** 刷新「播放」与「高级」分组的右值；页面上只显示当前值，说明行由焦点控制。 */
    private void bindPlayValues() {
        if (switchModeRow == null || renderRow == null || aspectRow == null
                || boostRow == null || cardRow == null || speedRow == null
                || navWidthRow == null || autoHideRow == null || clockRow == null) {
            return;
        }
        TextView modeValue = switchModeRow.view.value;
        if (switchModeHintVisible) {
            modeValue.setText(R.string.settings_play_switch_hint);
            modeValue.setTextColor(color(R.color.tally));
        } else {
            modeValue.setText(switchModeLabel(switchMode));
            modeValue.setTextColor(color(R.color.text));
        }
        switchModeRow.view.detail.setText(switchModeDetail(switchMode));
        renderRow.view.value.setText(PlayRowSpec.renderLabelRes(renderMode));
        renderRow.view.value.setTextColor(color(R.color.text));
        renderRow.view.detail.setText(PlayRowSpec.renderDetailRes(renderMode));
        aspectRow.view.value.setText(PlayRowSpec.aspectLabelRes(aspect));
        aspectRow.view.value.setTextColor(color(R.color.text));
        aspectRow.view.detail.setText(PlayRowSpec.aspectDetailRes(aspect));

        bindSwitch(boostRow, firstFrameBoost);
        bindSwitch(cardRow, transitionCard);
        bindSwitch(speedRow, showSpeed);
        navWidthRow.view.value.setText(AdvancedRowSpec.navWidthLabelRes(navWidth));
        navWidthRow.view.value.setTextColor(color(R.color.text));
        navWidthRow.view.detail.setText(AdvancedRowSpec.navWidthDetailRes(navWidth));
        autoHideRow.view.value.setText(AdvancedRowSpec.autoHideLabelRes(autoHideSeconds));
        autoHideRow.view.value.setTextColor(color(R.color.text));
        bindSwitch(clockRow, clockBadge);
        bindAdvancedHeader();
    }

    /** 折叠标题：▸/▾ 高级设置 · N 项；N 由 {@link AdvancedRowSpec#itemCount()} 动态给出。 */
    private void bindAdvancedHeader() {
        if (advancedRow == null) {
            return;
        }
        advancedRow.view.label.setText(getContext().getString(
                AdvancedRowSpec.titleRes(advancedOpen), AdvancedRowSpec.itemCount()));
    }

    /** 收起时隐藏全部子项（焦点/滚动逻辑按 visibility 自动跳过）。 */
    private void applyAdvancedVisibility() {
        int visibility = advancedOpen ? VISIBLE : GONE;
        for (int i = 0; i < advancedRows.size(); i++) {
            advancedRows.get(i).view.root.setVisibility(visibility);
        }
        bindAdvancedHeader();
        if (!advancedOpen && focusIndex >= 0 && focusIndex < focusable.size()
                && advancedRows.contains(focusable.get(focusIndex))
                && advancedRow != null) {
            int header = focusable.indexOf(advancedRow);
            if (header >= 0) {
                setFocusIndex(header);
            }
        }
    }

    /** OK 切换高级分组；焦点始终停在折叠标题，展开后内容在标题下方出现。 */
    private void toggleAdvanced() {
        advancedOpen = !advancedOpen;
        applyAdvancedVisibility();
        Listener target = listener;
        if (target != null) {
            target.onAdvancedExpandedChanged(advancedOpen);
        }
        if (advancedRow != null) {
            int header = focusable.indexOf(advancedRow);
            if (header >= 0) {
                setFocusIndex(header);
            }
            ensureVisible(advancedRow.view.root);
        }
    }

    private int switchModeLabel(int mode) {
        switch (mode) {
            case 1:
                return R.string.settings_play_switch_direct;
            case 2:
                return R.string.settings_play_switch_dual;
            default:
                return R.string.settings_play_switch_keep;
        }
    }

    private int switchModeDetail(int mode) {
        switch (mode) {
            case 1:
                return R.string.settings_play_switch_direct_detail;
            case 2:
                return R.string.settings_play_switch_dual_detail;
            default:
                return R.string.settings_play_switch_keep_detail;
        }
    }

    /** 源区「手机控制」区块：左地址/PIN + 提示，右常驻小二维码；整块 OK 弹大图；离线置灰。 */
    private Row addControlRow() {
        Row row = createRow(getContext().getString(R.string.settings_control_label),
                ACTION_CONTROL_QR, false);
        SettingRow view = row.view;
        LinearLayout contentRow = view.focus.content;
        contentRow.removeAllViews();

        // 左：标签 + 地址/PIN + 提示（二维码不再单独占一整行）。
        LinearLayout textColumn = new LinearLayout(getContext());
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        contentRow.addView(textColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        textColumn.addView(view.label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        view.value.setSingleLine(false);
        view.value.setMaxLines(2);
        view.value.setEllipsize(TextUtils.TruncateAt.END);
        view.value.setTextColor(color(R.color.muted));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.topMargin = dimen(R.dimen.settings_section_gap_bottom);
        textColumn.addView(view.value, valueLp);

        inlineQrHint = new TextView(getContext());
        inlineQrHint.setText(R.string.settings_qr_inline_wifi);
        inlineQrHint.setTextColor(color(R.color.muted));
        inlineQrHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        inlineQrHint.setSingleLine(true);
        inlineQrHint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dimen(R.dimen.settings_section_gap_bottom);
        textColumn.addView(inlineQrHint, hintLp);

        // 右：常驻小二维码（约 100dp 手机 / 120dp TV），白底留白不裁码。
        int qrSize = dimen(R.dimen.settings_control_qr_size);
        int qrPad = dimen(R.dimen.setup_qr_pad);
        inlineQrPixelSize = Math.max(96, qrSize - qrPad * 2);

        inlineQrBox = new FrameLayout(getContext());
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dimen(R.dimen.setup_qr_radius));
        inlineQrBox.setBackground(background);
        inlineQrBox.setPadding(qrPad, qrPad, qrPad, qrPad);

        inlineQrPlaceholder = new TextView(getContext());
        inlineQrPlaceholder.setText(R.string.settings_qr_inline_placeholder);
        inlineQrPlaceholder.setTextColor(0xFF7E908B);
        inlineQrPlaceholder.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dimen(R.dimen.setup_qr_label_text));
        inlineQrPlaceholder.setGravity(Gravity.CENTER);
        inlineQrBox.addView(inlineQrPlaceholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        inlineQrImage = new ImageView(getContext());
        inlineQrImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        inlineQrImage.setVisibility(GONE);
        inlineQrBox.addView(inlineQrImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrLp.leftMargin = dimen(R.dimen.settings_row_value_gap);
        qrLp.rightMargin = dimen(R.dimen.settings_row_pad_side);
        contentRow.addView(inlineQrBox, qrLp);

        headerRows.add(row);
        content.addView(row.view.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.settings_control_height)));
        inlineQrBox.setAlpha(0.35f);
        return row;
    }

    private void updateInlineQr() {
        boolean online = controlAddress != null && !controlAddress.isEmpty();
        String content = qrContent == null || qrContent.isEmpty() ? null : qrContent;
        boolean showable = online && content != null;
        inlineQrBox.setAlpha(showable ? 1f : 0.35f);
        if (showable) {
            if (!content.equals(shownInlineQrContent)) {
                android.graphics.Bitmap bitmap = QrCode.bitmap(content, inlineQrPixelSize);
                if (bitmap != null) {
                    inlineQrImage.setImageBitmap(bitmap);
                    shownInlineQrContent = content;
                } else {
                    inlineQrImage.setImageDrawable(null);
                    shownInlineQrContent = null;
                }
            }
            boolean ready = shownInlineQrContent != null;
            inlineQrImage.setVisibility(ready ? VISIBLE : GONE);
            inlineQrPlaceholder.setVisibility(ready ? GONE : VISIBLE);
            inlineQrHint.setText(R.string.settings_qr_inline_hint);
        } else {
            shownInlineQrContent = null;
            inlineQrImage.setImageDrawable(null);
            inlineQrImage.setVisibility(GONE);
            inlineQrPlaceholder.setVisibility(VISIBLE);
            inlineQrHint.setText(online || SetupView.isNetworkAvailable(getContext())
                    ? R.string.settings_qr_inline_service
                    : R.string.settings_qr_inline_wifi);
        }
    }

    /** 二维码浮层：半透明遮罩 + 大图 + 地址 + PIN；Back 关闭。 */
    private FrameLayout buildQrOverlay() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        // 横竖屏都用短边算尺寸：横屏时按屏宽算会让卡片高过屏幕（裁字）。
        int minSide = Math.min(screenWidth, metrics.heightPixels);
        int pad = dimen(R.dimen.setup_url_gap);

        FrameLayout overlay = new FrameLayout(getContext());
        overlay.setBackgroundColor(color(R.color.menu_scrim));
        overlay.setClickable(true);
        // 触摸：点遮罩关闭二维码（遥控仍是 Back）。
        overlay.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideQrOverlay();
            }
        });

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable cardBackground =
                new android.graphics.drawable.GradientDrawable();
        cardBackground.setColor(color(R.color.surface));
        cardBackground.setCornerRadius(dimen(R.dimen.setup_qr_radius));
        card.setBackground(cardBackground);

        qrPixelSize = Math.min(narrow ? 260 : 420,
                Math.round(minSide * (narrow ? 0.6f : 0.34f)));
        FrameLayout white = new FrameLayout(getContext());
        android.graphics.drawable.GradientDrawable whiteBackground =
                new android.graphics.drawable.GradientDrawable();
        whiteBackground.setColor(Color.WHITE);
        whiteBackground.setCornerRadius(dimen(R.dimen.setup_qr_radius));
        white.setBackground(whiteBackground);
        int whitePad = dimen(R.dimen.setup_qr_pad);
        white.setPadding(whitePad, whitePad, whitePad, whitePad);

        qrImage = new ImageView(getContext());
        qrImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        white.addView(qrImage, new FrameLayout.LayoutParams(qrPixelSize, qrPixelSize));
        LinearLayout.LayoutParams whiteLp = wrap();
        card.addView(white, whiteLp);

        qrAddress = new TextView(getContext());
        qrAddress.setTextColor(color(R.color.text));
        qrAddress.setTypeface(Typeface.MONOSPACE);
        qrAddress.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_url_text));
        qrAddress.setSingleLine(true);
        qrAddress.setEllipsize(TextUtils.TruncateAt.END);
        // 窄屏地址可能很长：限制宽度，避免卡片撑出屏幕。
        qrAddress.setMaxWidth(Math.round(minSide * (narrow ? 0.8f : 0.5f)));
        LinearLayout.LayoutParams addressLp = wrap();
        addressLp.topMargin = dimen(R.dimen.setup_sub_gap);
        card.addView(qrAddress, addressLp);

        qrPin = new TextView(getContext());
        qrPin.setTextColor(color(R.color.tally));
        qrPin.setTypeface(Typeface.MONOSPACE);
        qrPin.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.setup_url_text));
        qrPin.setSingleLine(true);
        LinearLayout.LayoutParams pinLp = wrap();
        pinLp.topMargin = dimen(R.dimen.settings_section_gap_bottom);
        card.addView(qrPin, pinLp);

        qrNote = new TextView(getContext());
        qrNote.setText(R.string.settings_qr_hint);
        qrNote.setTextColor(color(R.color.muted));
        qrNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        qrNote.setSingleLine(true);
        LinearLayout.LayoutParams noteLp = wrap();
        noteLp.topMargin = dimen(R.dimen.setup_sub_gap);
        card.addView(qrNote, noteLp);

        // 卡片外层套 ScrollView：竖屏/横屏、窄屏/大字号下卡片比屏幕高时内部可滚动，
        // 不裁地址/PIN/提示文字；点遮罩仍关闭。
        ScrollView cardScroll = new ScrollView(getContext());
        cardScroll.setFillViewport(false);
        cardScroll.setVerticalScrollBarEnabled(false);
        cardScroll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hideQrOverlay();
            }
        });
        LinearLayout cardHolder = new LinearLayout(getContext());
        cardHolder.setOrientation(LinearLayout.VERTICAL);
        cardHolder.setGravity(Gravity.CENTER_HORIZONTAL);
        cardHolder.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardScroll.addView(cardHolder, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(cardScroll, cardLp);
        return overlay;
    }

    private void showQrOverlay() {
        if (controlAddress == null || controlAddress.isEmpty()) {
            return;
        }
        String content = qrContent == null || qrContent.isEmpty()
                ? controlAddress : qrContent;
        android.graphics.Bitmap bitmap = QrCode.bitmap(content, qrPixelSize);
        if (bitmap == null) {
            return;
        }
        qrImage.setImageBitmap(bitmap);
        qrAddress.setText(controlAddress);
        qrPin.setText(getContext().getString(R.string.settings_qr_pin, controlPin));
        qrOverlay.setVisibility(VISIBLE);
        qrOverlayVisible = true;
    }

    private void hideQrOverlay() {
        if (!qrOverlayVisible) {
            return;
        }
        qrOverlayVisible = false;
        qrOverlay.setVisibility(GONE);
    }

    private Row createRow(CharSequence label, int action, boolean enabled) {
        Row row = new Row();
        row.action = action;
        row.enabled = enabled;
        row.view = SettingRow.menuRow(getContext(), label);

        row.view.root.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Row clicked = (Row) v.getTag();
                if (clicked == null || !clicked.enabled) {
                    return;
                }
                if (clicked.sourceRow) {
                    enterActionMode(clicked.record);
                } else {
                    fire(clicked);
                }
            }
        });
        row.view.root.setTag(row);

        row.view.root.setAlpha(enabled ? 1f : 0.4f);
        row.view.setFocused(false);
        return row;
    }

    private void rebuildFocusables() {
        focusable.clear();
        focusable.addAll(headerRows);
        if (!actionMode) {
            focusable.addAll(sourceRows);
        } else {
            focusable.addAll(actionRows);
        }
        focusable.addAll(staticRows);
    }

    private TextView addInfo(int labelRes, String initial) {
        LinearLayout block = new LinearLayout(getContext());
        block.setOrientation(LinearLayout.HORIZONTAL);
        block.setGravity(Gravity.CENTER_VERTICAL);
        block.setPadding(dimen(R.dimen.settings_row_pad_side),
                dimen(R.dimen.settings_section_gap_bottom),
                dimen(R.dimen.settings_row_pad_side),
                dimen(R.dimen.settings_section_gap_bottom));

        TextView label = new TextView(getContext());
        label.setText(labelRes);
        label.setTextColor(color(R.color.muted));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.rightMargin = dimen(R.dimen.settings_row_value_gap);
        block.addView(label, labelLp);

        TextView value = new TextView(getContext());
        value.setText(initial == null ? "" : initial);
        value.setTextColor(color(R.color.text));
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        value.setSingleLine(true);
        value.setGravity(Gravity.END);
        value.setEllipsize(TextUtils.TruncateAt.END);
        block.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        content.addView(block, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private TextView hintText(String text, int textColor) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(textColor);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(R.dimen.settings_info_text));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private void bindSwitch(Row row, boolean enabled) {
        row.on = enabled;
        row.view.value.setText(enabled ? R.string.settings_on : R.string.settings_off);
        row.view.value.setTextColor(enabled ? color(R.color.tally) : color(R.color.muted));
    }

    private void bindRowFocus(Row row, boolean focused) {
        row.view.setFocused(focused && row.enabled);
        if (row.playRow) {
            // 说明只在选中行显示，未选中行保留单行高度（行高固定，不跳动）。
            row.view.detail.setVisibility(focused && row.enabled ? VISIBLE : GONE);
        }
    }

    private String versionName() {
        try {
            return getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.1.0";
        } catch (RuntimeException e) {
            return "0.1.0";
        }
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 单行操作行高：窄屏用 48dp 触摸热区（≥44dp），电视保持原 token。 */
    private int rowHeight() {
        return dimen(narrow ? R.dimen.phone_settings_row_height : R.dimen.settings_row_height);
    }

    /** 两行源行高：窄屏 56dp，方便手指点。 */
    private int sourceRowHeight() {
        return dimen(narrow
                ? R.dimen.phone_settings_source_row_height
                : R.dimen.settings_source_row_height);
    }

    private int dimen(int resId) {
        return Res.dimen(getContext(), resId);
    }

    private int color(int resId) {
        return Res.color(getContext(), resId);
    }

    private static final class Row {

        SettingRow view;
        int action;
        boolean enabled;
        boolean on;
        boolean sourceRow;
        boolean playRow;
        SourceRecord record;
    }
}
