package me.qnnp.tvlive.ui;

import android.util.Log;
import android.view.KeyEvent;

/**
 * 遥控按键路由（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 4a 起所有遥控键统一从这里进入：按 {@link UiState} 的模式分派，
 * 优先级为「菜单 &gt; 设置/首启 &gt; 全屏 &gt; 导航（组栏/列表/操作列）&gt; 隐藏态」。
 * 首启/设置页只保留页面必要键，其余吞掉；OK 短按/长按、Back、MENU、方向、数字、
 * CH+/−、音量、INFO、红键/书签键全部集中在本类判定。</p>
 *
 * <p>{@link Host} 只做动作落地（切台、开菜单、改焦点等），不参与按键优先级；
 * {@link Timers} 由 Activity 用 Handler 实现，单测用假计时器，因此长按与
 * 「2 秒内再按一次退出」都可以在 JVM 里验证。</p>
 *
 * <p>OK 两段语义（先预览、再全屏）取决于焦点台是否在播，属于 Activity 的播放状态，
 * 由 {@link Host#onSelect()} 实现；本类负责两段的前提：短按只在按键抬起时触发一次，
 * 长按触发后抬起不再触发短按。</p>
 */
public final class KeyRouter {

    /** logcat 统一 tag：{@code adb logcat -s TvLive}。 */
    private static final String TAG = "TvLive";
    /** OK DOWN 已弹过导航后，UP 在此窗口内一律不重复触发 onSelect。 */
    private static final long OK_UP_SUPPRESS_MS = 400L;

    /** 按键动作落地；本类只做模式/优先级/长短按判定。 */
    public interface Host {

        /** OK 短按（含设置页的确定）。 */
        void onSelect();

        /** OK 长按；只在隐藏态/全屏态有意义（导航展开时无动作）。 */
        void onLongSelect();

        /** Back：关菜单 / 设置页返回 / 全屏回隐藏 / 导航收起。隐藏态的退出确认由本类处理。 */
        void onBack();

        /** MENU：主菜单开关；首启页进设置。 */
        void onMenu();

        /**
         * 方向键：导航展开时移动焦点，菜单打开时移动菜单项。
         * 隐藏/全屏态的上下键由本类换成台序：dy=+1 下一台、dy=-1 上一台。
         */
        void onDirection(int dx, int dy);

        /** 数字键 0-9（含 2 秒缓冲的输入方）。 */
        void onDigit(int digit);

        /** CH+/−：delta=+1 / -1。 */
        void onChannelStep(int delta);

        /** 隐藏态/全屏的左右键：delta=+1 / -1。 */
        void onVolume(int delta);

        /** INFO：频道信息。 */
        void onInfo();

        /** 退出确认窗口内的第二次 Back。 */
        void onExit();

        /** 红键 / 书签键：收藏/取消收藏当前台。 */
        void onFavorite();

        /** 任意有效按键按下：取消全屏自动跳台倒计时等。 */
        void onUserInput();

        /**
         * 布局自己的 fullscreen 标志（只用于日志定位 ui/layout 是否不同步）。
         * 纯状态判定不应依赖它。
         */
        boolean isLayoutFullscreen();
    }

    /** 计时/时钟抽象：Activity 用 Handler + elapsedRealtime，单测用假实现。 */
    public interface Timers {
        long now();

        void postDelayed(Runnable runnable, long delayMs);

        void removeCallbacks(Runnable runnable);
    }

    private final UiState ui;
    private final Host host;
    private final Timers timers;
    private final long longPressMs;
    private final long exitWindowMs;

    /** 隐藏态 OK 已在 DOWN 弹出导航：UP 不再重复处理（部分遥控只稳定上报 DOWN）。 */
    private boolean okPoppedNav;
    /**
     * 最近一次「DOWN 已弹导航」的时刻；{@link #handleUp} 在 400ms 内一律消费 OK UP，
     * 覆盖遥控 UP 时序异常或 {@link #okPoppedNav} 被别处清掉的情况。
     */
    private long suppressOkUpAt = -1L;
    private boolean longPressFired;
    private Runnable longPressRunnable;
    private Runnable disarmExitRunnable;

    public KeyRouter(UiState ui, Host host, Timers timers,
            long longPressMs, long exitWindowMs) {
        this.ui = ui;
        this.host = host;
        this.timers = timers;
        this.longPressMs = longPressMs;
        this.exitWindowMs = exitWindowMs;
    }

    /** Activity 主入口：{@code dispatchKeyEvent → handle(event)}。 */
    public boolean handle(KeyEvent event) {
        return handle(event.getKeyCode(), event.getAction(), event.getRepeatCount());
    }

    /** 与 {@link #handle(KeyEvent)} 等价，供 JVM 单测直接传键码/动作/重复次数。 */
    boolean handle(int keyCode, int action, int repeatCount) {
        if (action == KeyEvent.ACTION_DOWN) {
            return handleDown(keyCode, repeatCount);
        }
        if (action == KeyEvent.ACTION_UP) {
            return handleUp(keyCode);
        }
        return false;
    }

    /** 停止长按/退出确认计时（onStop 等场景）；不改变模式。 */
    public void cancel() {
        cancelLongPress();
        cancelDisarm();
        okPoppedNav = false;
        suppressOkUpAt = -1L;
        longPressFired = false;
    }

    // ------------------------------------------------------------------ DOWN

    private boolean handleDown(int code, int repeatCount) {
        if (isOkKey(code) && repeatCount == 0) {
            Log.i(TAG, "ok down mode=" + ui.mode()
                    + " fullscreenLayout=" + host.isLayoutFullscreen());
        }
        // 优先级：设置页/首启页覆盖播放与导航。
        if (ui.isSettings()) {
            return settingsDown(code, repeatCount);
        }
        if (ui.isSetup()) {
            return setupDown(code, repeatCount);
        }

        host.onUserInput();
        ui.noteKey(timers.now());
        if (code != KeyEvent.KEYCODE_BACK && ui.isExitArmed()) {
            // 退出提示期间按了别的键：结束这次「再按一次」窗口。
            cancelDisarm();
            ui.disarmExit();
        }
        return sceneDown(code, repeatCount);
    }

    /** 菜单 / 全屏 / 导航 / 隐藏态：只有子状态差异，优先级由这里保证。 */
    private boolean sceneDown(int code, int repeatCount) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (repeatCount == 0) {
                handleBack();
            }
            return true;
        }
        if (ui.isMenuOpen()) {
            // 菜单打开时：OK 激活菜单项；方向移动/LEFT 关闭；其他播放键吞掉。
            if (isOkKey(code)) {
                if (repeatCount == 0) {
                    scheduleLongPress();
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (repeatCount == 0) {
                    host.onMenu();
                }
                return true;
            }
            if (isDirection(code)) {
                host.onDirection(dx(code), dy(code));
                return true;
            }
            if (isDigitKey(code) || isChannelKey(code)) {
                return true;
            }
            if (code == KeyEvent.KEYCODE_INFO) {
                if (repeatCount == 0) {
                    host.onInfo();
                }
                return true;
            }
            if (isFavoriteKey(code)) {
                if (repeatCount == 0) {
                    host.onFavorite();
                }
                return true;
            }
            return false;
        }

        if (isOkKey(code)) {
            if (repeatCount > 0) {
                return true;
            }
            if (ui.mode() == UiState.Mode.NAV_HIDDEN
                    || ui.mode() == UiState.Mode.FULLSCREEN) {
                // 隐藏/全屏：DOWN 即弹导航，兼容只稳定上报 DOWN 的遥控。
                okPoppedNav = true;
                suppressOkUpAt = timers.now();
                host.onSelect();
            }
            scheduleLongPress();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (repeatCount == 0) {
                host.onMenu();
            }
            return true;
        }
        if (isDigitKey(code)) {
            if (repeatCount == 0) {
                host.onDigit(code - KeyEvent.KEYCODE_0);
            }
            return true;
        }
        if (isDirection(code)) {
            if (ui.isNavShown()) {
                // 导航展开：上下在组/列表/操作列内移动，左右切栏；不调音量。
                host.onDirection(dx(code), dy(code));
            } else if (dy(code) != 0) {
                // 隐藏/全屏：上下换台，按遥控习惯取反——上 = CH+（下一台），下 = CH−（上一台）。
                // CH+/− 键语义不变，导航/焦点移动也不取反。
                host.onDirection(0, -dy(code));
            } else {
                // 隐藏/全屏：左右音量。
                host.onVolume(dx(code));
            }
            return true;
        }
        if (isChannelKey(code)) {
            host.onChannelStep(code == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_INFO) {
            if (repeatCount == 0) {
                host.onInfo();
            }
            return true;
        }
        if (isFavoriteKey(code)) {
            if (repeatCount == 0) {
                host.onFavorite();
            }
            return true;
        }
        return false;
    }

    /**
     * Back 语义（2g）：菜单 → 只关菜单；全屏 → 回导航隐藏；导航展开 → 收起；
     * 隐藏态 → 第一次提示，2 秒内再按退出。
     */
    private void handleBack() {
        if (ui.isMenuOpen()) {
            host.onBack();
            return;
        }
        if (ui.mode() == UiState.Mode.FULLSCREEN) {
            host.onBack();
            return;
        }
        if (ui.mode() == UiState.Mode.NAV_SHOWN) {
            host.onBack();
            return;
        }
        if (ui.isExitArmed()) {
            cancelDisarm();
            ui.disarmExit();
            host.onExit();
            return;
        }
        ui.armExit();
        scheduleDisarm();
    }

    /** 设置页：Back/方向/OK 交给页面，其余播放键吞掉。 */
    private boolean settingsDown(int code, int repeatCount) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (repeatCount == 0) {
                host.onBack();
            }
            return true;
        }
        if (isOkKey(code)) {
            if (repeatCount > 0) {
                return true;
            }
            host.onSelect();
            return true;
        }
        if (isDirection(code)) {
            host.onDirection(dx(code), dy(code));
            return true;
        }
        return isDigitKey(code) || code == KeyEvent.KEYCODE_MENU
                || isChannelKey(code) || code == KeyEvent.KEYCODE_INFO
                || isFavoriteKey(code);
    }

    /** 首启页：OK 执行「在本机输入地址」、MENU 进设置、Back 双击退出，其余吞掉。 */
    private boolean setupDown(int code, int repeatCount) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (repeatCount == 0) {
                handleBack();
            }
            return true;
        }
        if (ui.isExitArmed()) {
            cancelDisarm();
            ui.disarmExit();
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (repeatCount == 0) {
                host.onMenu();
            }
            return true;
        }
        if (isOkKey(code)) {
            if (repeatCount == 0) {
                host.onSelect();
            }
            return true;
        }
        return isDirection(code) || isDigitKey(code) || isChannelKey(code)
                || code == KeyEvent.KEYCODE_INFO || isFavoriteKey(code);
    }

    // -------------------------------------------------------------------- UP

    private boolean handleUp(int code) {
        if (isOkKey(code)) {
            cancelLongPress();
            boolean suppressed = okUpSuppressed();
            Log.i(TAG, "ok up suppressed=" + suppressed + " mode=" + ui.mode());
            if (ui.isSettings() || ui.isSetup()) {
                // 首启/设置页的 OK 在 DOWN 已交给页面，UP 只吞掉。
                okPoppedNav = false;
                suppressOkUpAt = -1L;
                longPressFired = false;
                return true;
            }
            if (okPoppedNav) {
                // DOWN 已经弹过导航 / 出过 OSD，UP 不再重复处理。
                okPoppedNav = false;
            } else if (!suppressed && !longPressFired) {
                host.onSelect();
            }
            longPressFired = false;
            return true;
        }
        return isConsumedKey(code);
    }

    /** 本次 OK UP 是否落在「DOWN 已弹导航」的 400ms 抑制窗口内。 */
    private boolean okUpSuppressed() {
        if (suppressOkUpAt < 0L) {
            return false;
        }
        long elapsed = timers.now() - suppressOkUpAt;
        return elapsed >= 0L && elapsed < OK_UP_SUPPRESS_MS;
    }

    // ---------------------------------------------------------------- 长按/退出

    private void scheduleLongPress() {
        cancelLongPress();
        longPressFired = false;
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                longPressFired = true;
                if (ui.isMenuOpen()) {
                    return;
                }
                if (ui.mode() == UiState.Mode.NAV_HIDDEN
                        || ui.mode() == UiState.Mode.FULLSCREEN) {
                    host.onLongSelect();
                }
            }
        };
        timers.postDelayed(longPressRunnable, longPressMs);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            timers.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void scheduleDisarm() {
        cancelDisarm();
        disarmExitRunnable = new Runnable() {
            @Override
            public void run() {
                ui.disarmExit();
            }
        };
        timers.postDelayed(disarmExitRunnable, exitWindowMs);
    }

    private void cancelDisarm() {
        if (disarmExitRunnable != null) {
            timers.removeCallbacks(disarmExitRunnable);
            disarmExitRunnable = null;
        }
    }

    // ------------------------------------------------------------------ 判定

    private static boolean isOkKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isDigitKey(int code) {
        return code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9;
    }

    private static boolean isDirection(int code) {
        return dy(code) != 0 || dx(code) != 0;
    }

    private static int dx(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            return -1;
        }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return 1;
        }
        return 0;
    }

    private static int dy(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            return -1;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            return 1;
        }
        return 0;
    }

    private static boolean isChannelKey(int code) {
        return code == KeyEvent.KEYCODE_CHANNEL_UP
                || code == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

    private static boolean isFavoriteKey(int code) {
        return code == KeyEvent.KEYCODE_PROG_RED
                || code == KeyEvent.KEYCODE_BOOKMARK;
    }

    /** 本类消费并在 UP 时吞掉的键（避免落到系统默认处理）。 */
    private static boolean isConsumedKey(int code) {
        return isOkKey(code) || isDigitKey(code) || isDirection(code)
                || isChannelKey(code) || isFavoriteKey(code)
                || code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_INFO;
    }
}
