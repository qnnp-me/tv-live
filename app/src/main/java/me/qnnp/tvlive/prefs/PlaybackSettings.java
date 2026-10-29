package me.qnnp.tvlive.prefs;

import me.qnnp.tvlive.player.SwitchMode;

/**
 * 设置页的持久化值：播放（切台方式、视频渲染、画面比例、首帧加速、切台提示、显示网速）、
 * 导航/显示（导航宽度、自动收起、时钟角标）以及设置页自身的折叠状态。
 *
 * <p>纯逻辑 + {@link Store} 抽象，单测用内存实现验证默认值、循环顺序与持久化；
 * 生产实现由 {@link AppPrefs} 桥接 SharedPreferences。键名集中在这里，避免页面/Activity
 * 各写一份字符串。导航/显示项也放在本类，避免再拆一层只做转发的 Store。</p>
 */
public final class PlaybackSettings {

    public static final String KEY_SWITCH_MODE = "play_switch_mode";
    public static final String KEY_RENDER_MODE = "play_render_mode";
    public static final String KEY_ASPECT = "play_aspect";
    public static final String KEY_FIRST_FRAME_BOOST = "play_first_frame_boost";
    public static final String KEY_TRANSITION_CARD = "play_transition_card";
    public static final String KEY_SHOW_SPEED = "play_show_speed";
    public static final String KEY_SWITCH_MODE_HINT_SEEN = "play_switch_mode_hint_seen";
    public static final String KEY_NAV_WIDTH = "nav_width";
    public static final String KEY_AUTO_HIDE = "nav_auto_hide";
    public static final String KEY_CLOCK_BADGE = "nav_clock_badge";
    public static final String KEY_ADVANCED_OPEN = "settings_advanced_open";

    public static final SwitchMode DEFAULT_SWITCH_MODE = SwitchMode.KEEP;
    public static final RenderMode DEFAULT_RENDER_MODE = RenderMode.AUTO;
    public static final Aspect DEFAULT_ASPECT = Aspect.FIT;
    public static final boolean DEFAULT_FIRST_FRAME_BOOST = true;
    public static final boolean DEFAULT_TRANSITION_CARD = true;
    public static final boolean DEFAULT_SHOW_SPEED = true;
    public static final NavWidth DEFAULT_NAV_WIDTH = NavWidth.STANDARD;
    public static final AutoHide DEFAULT_AUTO_HIDE = AutoHide.EIGHT;
    public static final boolean DEFAULT_CLOCK_BADGE = true;
    public static final boolean DEFAULT_ADVANCED_OPEN = false;

    /** 最小持久化接口；SharedPreferences 与内存测试实现都走这里。 */
    public interface Store {

        String getString(String key, String fallback);

        boolean getBoolean(String key, boolean fallback);

        void putString(String key, String value);

        void putBoolean(String key, boolean value);
    }

    private final Store store;

    public PlaybackSettings(Store store) {
        if (store == null) {
            throw new IllegalArgumentException("store == null");
        }
        this.store = store;
    }

    public SwitchMode switchMode() {
        return SwitchMode.fromKey(store.getString(KEY_SWITCH_MODE, null));
    }

    public void setSwitchMode(SwitchMode mode) {
        store.putString(KEY_SWITCH_MODE, (mode == null ? DEFAULT_SWITCH_MODE : mode).key());
    }

    /** 设置页 OK：保留 → 极速直切 → 双画面 → 保留，并立即持久化。 */
    public SwitchMode cycleSwitchMode() {
        SwitchMode next = switchMode().next();
        setSwitchMode(next);
        return next;
    }

    public RenderMode renderMode() {
        return RenderMode.fromKey(store.getString(KEY_RENDER_MODE, null));
    }

    public void setRenderMode(RenderMode mode) {
        store.putString(KEY_RENDER_MODE, (mode == null ? DEFAULT_RENDER_MODE : mode).key());
    }

    /** 设置页 OK：自动 → SurfaceView → TextureView → 自动，并立即持久化。 */
    public RenderMode cycleRenderMode() {
        RenderMode next = renderMode().next();
        setRenderMode(next);
        return next;
    }

    public Aspect aspect() {
        return Aspect.fromKey(store.getString(KEY_ASPECT, null));
    }

    public void setAspect(Aspect aspect) {
        store.putString(KEY_ASPECT, (aspect == null ? DEFAULT_ASPECT : aspect).key());
    }

    /** 设置页 OK：适应 → 拉伸 → 裁剪 → 适应，并立即持久化。 */
    public Aspect cycleAspect() {
        Aspect next = aspect().next();
        setAspect(next);
        return next;
    }

    public boolean firstFrameBoost() {
        return store.getBoolean(KEY_FIRST_FRAME_BOOST, DEFAULT_FIRST_FRAME_BOOST);
    }

    public void setFirstFrameBoost(boolean enabled) {
        store.putBoolean(KEY_FIRST_FRAME_BOOST, enabled);
    }

    public boolean transitionCard() {
        return store.getBoolean(KEY_TRANSITION_CARD, DEFAULT_TRANSITION_CARD);
    }

    public void setTransitionCard(boolean enabled) {
        store.putBoolean(KEY_TRANSITION_CARD, enabled);
    }

    /** 台名卡上的实时网速行；默认开。 */
    public boolean showSpeed() {
        return store.getBoolean(KEY_SHOW_SPEED, DEFAULT_SHOW_SPEED);
    }

    public void setShowSpeed(boolean enabled) {
        store.putBoolean(KEY_SHOW_SPEED, enabled);
    }

    /** 设置页 OK：开 → 关 → 开，并立即持久化。 */
    public boolean cycleShowSpeed() {
        boolean next = !showSpeed();
        setShowSpeed(next);
        return next;
    }

    /** 「效果不理想可换一种」是否已经出现过；只出现一次。 */
    public boolean switchModeHintSeen() {
        return store.getBoolean(KEY_SWITCH_MODE_HINT_SEEN, false);
    }

    public void markSwitchModeHintSeen() {
        store.putBoolean(KEY_SWITCH_MODE_HINT_SEEN, true);
    }

    // ------------------------------------------------------------ 导航 / 显示

    /** 导航宽度：窄 / 标准 / 宽；默认标准 44%。 */
    public NavWidth navWidth() {
        return NavWidth.fromKey(store.getString(KEY_NAV_WIDTH, null));
    }

    public void setNavWidth(NavWidth width) {
        store.putString(KEY_NAV_WIDTH, (width == null ? DEFAULT_NAV_WIDTH : width).key());
    }

    /** 设置页 OK：窄 → 标准 → 宽 → 窄，并立即持久化。 */
    public NavWidth cycleNavWidth() {
        NavWidth next = navWidth().next();
        setNavWidth(next);
        return next;
    }

    /** 自动收起：5 秒 / 8 秒 / 15 秒 / 不收起；默认 8 秒（原行为）。 */
    public AutoHide autoHide() {
        return AutoHide.fromKey(store.getString(KEY_AUTO_HIDE, null));
    }

    public void setAutoHide(AutoHide option) {
        store.putString(KEY_AUTO_HIDE, (option == null ? DEFAULT_AUTO_HIDE : option).key());
    }

    /** 设置页 OK：5 → 8 → 15 → 不收起 → 5，并立即持久化。 */
    public AutoHide cycleAutoHide() {
        AutoHide next = autoHide().next();
        setAutoHide(next);
        return next;
    }

    /** 自动收起秒数；0 表示不自动收起（Back/OK 仍可收）。 */
    public int autoHideSeconds() {
        return autoHide().seconds();
    }

    /** 按秒数设置自动收起；只接受 5/8/15/0，其余回退 8 秒。 */
    public void setAutoHideSeconds(int seconds) {
        setAutoHide(AutoHide.fromSeconds(seconds));
    }

    /** 整点/半点前的时钟角标；默认开。 */
    public boolean clockBadge() {
        return store.getBoolean(KEY_CLOCK_BADGE, DEFAULT_CLOCK_BADGE);
    }

    public void setClockBadge(boolean enabled) {
        store.putBoolean(KEY_CLOCK_BADGE, enabled);
    }

    public boolean cycleClockBadge() {
        boolean next = !clockBadge();
        setClockBadge(next);
        return next;
    }

    /** 设置页「高级」分组是否展开；默认收起。 */
    public boolean advancedOpen() {
        return store.getBoolean(KEY_ADVANCED_OPEN, DEFAULT_ADVANCED_OPEN);
    }

    public void setAdvancedOpen(boolean open) {
        store.putBoolean(KEY_ADVANCED_OPEN, open);
    }

    /** 设置页 OK：收起 ⇄ 展开，并立即持久化。 */
    public boolean toggleAdvancedOpen() {
        boolean next = !advancedOpen();
        setAdvancedOpen(next);
        return next;
    }
}
