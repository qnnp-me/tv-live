package me.qnnp.tvlive.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

import me.qnnp.tvlive.R;
import me.qnnp.tvlive.boot.BootAudioPlan;
import me.qnnp.tvlive.boot.BootLaunchPlan;
import me.qnnp.tvlive.boot.BootLauncher;
import me.qnnp.tvlive.boot.BootSignalReceiver;
import me.qnnp.tvlive.boot.HomeController;
import me.qnnp.tvlive.boot.TclSourceSwitch;
import me.qnnp.tvlive.control.ControlAddress;
import me.qnnp.tvlive.control.SimpleHttpServer;
import me.qnnp.tvlive.control.LanAddress;
import me.qnnp.tvlive.control.SourceApi;
import me.qnnp.tvlive.control.StateProvider;
import me.qnnp.tvlive.control.TokenStore;
import me.qnnp.tvlive.data.ChannelNavigator;
import me.qnnp.tvlive.data.ChannelSet;
import me.qnnp.tvlive.data.PlaylistModel.Channel;
import me.qnnp.tvlive.data.PlaylistModel.Group;
import me.qnnp.tvlive.data.PlaylistModel.Playlist;
import me.qnnp.tvlive.data.PlaylistModel.Variant;
import me.qnnp.tvlive.data.PlaylistParser;
import me.qnnp.tvlive.data.PlaylistParseException;
import me.qnnp.tvlive.data.PlaylistRepository;
import me.qnnp.tvlive.data.PlaylistRepository.MultiPlaylist;
import me.qnnp.tvlive.data.PlaylistRepository.SourceState;
import me.qnnp.tvlive.data.SourceDownloader;
import me.qnnp.tvlive.data.SourceRecord;
import me.qnnp.tvlive.data.SourceStore;
import me.qnnp.tvlive.player.DnsPrefetch;
import me.qnnp.tvlive.player.DualMemoryGate;
import me.qnnp.tvlive.player.DualPlayback;
import me.qnnp.tvlive.player.EngineFactory;
import me.qnnp.tvlive.player.PlaybackController;
import me.qnnp.tvlive.player.PlaybackController.PlaybackState;
import me.qnnp.tvlive.player.PlaybackError;
import me.qnnp.tvlive.player.StreamProbe;
import me.qnnp.tvlive.player.SwitchMode;
import me.qnnp.tvlive.prefs.AppPrefs;
import me.qnnp.tvlive.prefs.Aspect;
import me.qnnp.tvlive.prefs.AutoHide;
import me.qnnp.tvlive.prefs.NavWidth;
import me.qnnp.tvlive.prefs.PlaybackSettings;
import me.qnnp.tvlive.prefs.RenderMode;

/**
 * 单 Activity TV UI：竖排分组栏 + 列表 + 预览 / 全屏 / OSD / 菜单 / 收藏 / 上次频道 / 自动跳台。
 *
 * <p>批次 4a 起按键全部交给 {@link KeyRouter}：{@link #dispatchKeyEvent} 只做
 * {@code keyRouter.handle(event)}；本类实现 {@link KeyRouter.Host} 的动作落地
 * （切台/开菜单/移动焦点等）与 {@link KeyRouter.Timers}。模式与菜单/焦点/退出确认
 * 统一由 {@link UiState} 保存，导航自动收起由 {@link NavVisibility} 计时（5/8/15 秒或不收）。</p>
 *
 * <p>批次 2d 起焦点与播放分离：{@code focusInGroups/groupIndex/focusIndex} 是焦点，
 * {@code playingChannelId} 是正在预览/全屏播放的台。上下/数字/CH/切组只移动焦点，
 * OK 两段（先切预览、再全屏），→ 进入导航内的频道操作列；MENU 打开主菜单。</p>
 *
 * <p>批次 5a 起同一个 APK 跑手机：{@link DeviceMode} 按 sw&lt;600dp 判手机，竖屏用
 * {@link PhoneLayout}（16:9 视频 + tab + 列表 + 底栏，触摸走 {@link TouchRouter}），
 * 横屏手机复用 {@link TvLayout}。遥控/键盘仍然只经过 {@link KeyRouter}，触摸只改
 * 选中/播放，不抢视图焦点。</p>
 */
public final class MainActivity extends Activity
        implements SurfaceHolder.Callback, KeyRouter.Host, KeyRouter.Timers,
        PhoneLayout.Listener {

    private static final String TAG = "TvLive";

    private static final long OSD_MS = 2500L;
    private static final long ERROR_OSD_MS = 2500L;
    private static final long EXIT_HINT_MS = 2000L;
    private static final long DIGIT_TIMEOUT_MS = 2000L;
    private static final long STALL_TIMEOUT_MS = 10000L;
    /** 起播超过这个时间还没进入 PLAYING/首帧，先重试一次；再超时按失败处理。 */
    private static final long PLAYBACK_START_TIMEOUT_MS = 15000L;
    /** PLAYING 但连续这么久没有新视频帧：位置可能还在走，必须另设看门狗。 */
    private static final long VIDEO_FREEZE_TIMEOUT_MS = 12000L;
    private static final long TICK_MS = 1000L;
    private static final long TOAST_SHORT_MS = 1500L;
    private static final long SOURCE_NOTE_MS = 3000L;
    private static final long LOADING_DELAY_MS = 120L;
    private static final int AUTO_NEXT_SECONDS = 3;
    private static final int MAX_DIGITS = 3;
    private static final int FAVORITES_GROUP = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AppPrefs prefs;
    private PlaybackSettings playbackSettings;
    /** 前台控制器别名（= playback.active()；双画面交换后由回调更新）。 */
    private PlaybackController controller;
    /** 批次 10：KEEP 单引擎 / DUAL 双引擎编排（Surface 绑定与音量规则都在这里）。 */
    private DualPlayback playback;
    /** 批次 15：台名卡网速行的开关/格式化/去重；引擎回调先进这里。 */
    private final BandwidthIndicator bandwidthIndicator = new BandwidthIndicator();
    /** 视频层是否用 TextureView（KEEP/DUAL）；极速直切仍用 SurfaceView。 */
    private boolean textureMode;
    /** 两个 TextureView 各一份 Surface；退出纹理模式时释放。 */
    private Surface textureSurfaceA;
    private Surface textureSurfaceB;
    /** SurfaceTexture → Surface 绑定状态（同令牌只绑定一次；旧回调丢弃）。 */
    private final TextureBinding textureBindingA = new TextureBinding();
    private final TextureBinding textureBindingB = new TextureBinding();
    /** 已应用的切台方式；null 表示还没应用过（onCreate 首次）。 */
    private SwitchMode appliedSwitchMode;
    /** 已应用的视频渲染方式（批次 17）：与切台方式一起决定视频层。 */
    private RenderMode appliedRenderMode;
    /** 已应用的视频层：true = TextureView，false = SurfaceView。 */
    private boolean appliedTextureMode;
    private LiveLayout layout;
    private NavView navView;

    /** 所有 UI 共用的覆盖层：视频层之上的唯一 root 直接子，保证能压住 SurfaceView。 */
    private FrameLayout uiLayer;

    /**
     * 电视/手机横屏的导航窗口。TCL 4.4 上全宽 SurfaceView 会被 HWC 直接合成，
     * 与它重叠的同窗口 View 会被压住（菜单偶尔可见是合成器碰巧让位，不可依赖）；
     * 独立窗口的 z 序由 WindowManager 保证在 Activity 窗口之上，才能稳定覆盖。
     */
    private android.widget.PopupWindow navPopup;

    /** 菜单/设置/首启/OSD 的独立覆盖窗口；触摸按模式开关。 */
    private android.widget.PopupWindow overlayPopup;
    private Overlays overlays;
    private ChannelListAdapter adapter;
    private FrameLayout rootView;
    /**
     * 设置页/首启页惰性创建：启动时只占一个空槽，首次进入对应页面才构建
     * （SettingsView 内有 50+ View + 二维码浮层）。
     */
    private LazyPage<SetupView> setupPage;
    private LazyPage<SettingsView> settingsPage;

    /** sw&lt;600dp：手机设备（触摸、列表常驻、不做 8 秒自动收起）。 */
    private boolean phoneDevice;
    /** 手机且竖屏：用 PhoneLayout；横屏手机复用 TvLayout。 */
    private boolean phonePortrait;
    /** 点行/双击/长按的判定（遥控不经过这里）。 */
    private final TouchRouter touchRouter = new TouchRouter(300L);

    /**
     * 全机唯一视频 Surface：旋转只切布局，把这个实例搬到新布局，
     * 播放器与播放进度都不重建（见 {@link RotationResume}）。
     */
    private SurfaceView videoSurface;
    /** 手机全屏 OSD/返回按钮的 2.5s 计时（电视不用）。 */
    private final FullscreenOsd fullscreenOsd = new FullscreenOsd();
    /** 最近一次应用的系统栏/手势条 inset，避免重复 setPadding 触发布局。 */
    private SystemInsets lastInsets = SystemInsets.NONE;

    /** 手机设置页 HTTP 服务（批次 3a）：随 Activity 启停，不后台常驻。 */
    private SimpleHttpServer controlServer;
    /** 手机页业务接口；「在本机输入地址」也复用它（下载/校验/保存/刷新）。 */
    private SourceApi controlApi;
    private TokenStore tokenStore;
    /** PIN 首次生成可能写文件：放后台，UI 先占位再回填。 */
    private final ExecutorService controlIo = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "TvLive-control-io");
            thread.setDaemon(true);
            return thread;
        }
    });
    /** 后台 PIN 生成进行中（主线程读写）。 */
    private boolean controlPinLoading;
    private String controlAddress;
    private String controlLanIp;
    private String controlToken;
    /** 首启页本机地址导入中：防重复提交。 */
    private boolean localImportRunning;
    private boolean controlReceiverRegistered;
    /** 手机页写操作触发的重载被正在进行的加载挡下时，加载完成后补一次。 */
    private boolean controlReloadPending;
    /**
     * 手机页状态快照：只含内存元数据（源/台数/当前台），HTTP 线程只读，
     * 避免每次 /api/state 解析全部源缓存（TCL 上要 21 秒）。
     */
    private volatile StateProvider.Snapshot controlSnapshot;
    /** 手机页搜索/切台用的频道快照（不可变，HTTP 线程只读）。 */
    private volatile List<SourceApi.ChannelRef> controlChannels =
            Collections.emptyList();
    private String appVersion = "0.1.0";

    private final StateProvider controlStateProvider = new StateProvider() {
        @Override
        public StateProvider.Snapshot snapshot() {
            StateProvider.Snapshot snapshot = controlSnapshot;
            return snapshot == null ? StateProvider.Snapshot.notReady() : snapshot;
        }
    };

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // WiFi 切换后 IP 可能立刻变化：主动失效 30 秒缓存，下次读取重新枚举。
            LanAddress.invalidate();
            refreshControlInfoIfChanged();
        }
    };

    /** 模式/菜单/焦点/退出确认的唯一状态源；TvLayout 与浮层由这里的迁移驱动。 */
    private final UiState ui = new UiState();
    private KeyRouter keyRouter;

    private Playlist playlist;
    private ChannelNavigator navigator;

    /** 最近一次多源加载快照（启动缓存，设置页直接复用，不再重复解析）。 */
    private List<SourceState> sourceStates = Collections.emptyList();
    private List<SourceRecord> sourceRecords = new ArrayList<SourceRecord>();
    private boolean sourcesReady;
    private String sourceFatalError;
    /** 无可用频道时列表占位文案（区分普通空组）。 */
    private String listEmptyOverride;
    /** 源操作触发的重载不额外弹「列表已重新加载」。 */
    private boolean suppressReloadToast;
    /** 含收藏虚拟组（索引 0）；数据组从 1 开始。 */
    private final List<String> groupNames = new ArrayList<String>();
    /** 组栏实际显示的项（一级组 + 「其他」展开时的「全部」/子组）；索引用于焦点/active。 */
    private final List<BarSpec> barSpecs = new ArrayList<BarSpec>();
    /** 组栏当前是否展开了「其他」子组，用于避免每次移动都重建视图。 */
    private boolean barOtherExpanded;
    /** 「其他」当前子组过滤；null = 全部。切到别的组时清空。 */
    private String subGroupFilter;
    private final List<Channel> visibleChannels = new ArrayList<Channel>();
    private final Set<String> favorites = new HashSet<String>();
    private final Map<String, Integer> variantOffsets = new HashMap<String, Integer>();

    /** 列表当前显示的组（含收藏组）。 */
    private int groupIndex;
    /** 组栏焦点组；只在 {@link UiState#isFocusInGroups()} 时有意义。 */
    private int groupFocusIndex;
    /** 操作列焦点项（0..ActionColumn.ITEM_COUNT-1），记住上次位置。 */
    private int actionFocusIndex = ActionColumn.ITEM_FAVORITE;
    /** 焦点行在 {@link #visibleChannels} 里的下标；-1 表示空组。 */
    private int focusIndex = -1;
    /** 正在预览/全屏播放的台；只由 OK、全屏、数字/CH（全屏）、恢复改变。 */
    private String playingChannelId;

    private boolean loading;
    private boolean started;
    private boolean paused;
    private boolean surfaceReady;

    private String reloadTargetId;
    /** onStart 恢复时顺带静默重载源：失败不打扰、同一台不打断播放。 */
    private boolean silentReload;
    /**
     * onStart 待恢复台：先只恢复引擎/Surface，起播等静默重载落定后再做，
     * 避免「恢复播放 + 全量重载」各起一次解码器。
     */
    private final StartupResume startupResume = new StartupResume();

    /** 本次启动由开机 alarm 拉起（Intent extra from_boot）。 */
    private boolean bootFromLaunch;
    /** 开机拉起后等待 3 秒才起播的台；非 null 表示还在缓起播窗口内。 */
    private Channel bootDeferredChannel;

    /** 开机音频自检：TCL legacy 开机首播后，等 BOOT_COMPLETED 再重播一次。 */
    private boolean bootAudioCheckPending;
    /** 运行时 TCL 信源/音频路由自检：防止 App 在 HDMI 信源上跑。 */
    private boolean audioRouteReplayScheduled;
    private int audioRouteCheckTicks;
    private long lastAudioRouteSwitchAt;
    private boolean bootAudioReplayScheduled;
    private boolean bootCompletedReceiverRegistered;
    private final BroadcastReceiver bootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                scheduleBootAudioReplay("boot-completed");
            }
        }
    };
    private final Runnable bootAudioReplayRunnable = new Runnable() {
        @Override
        public void run() {
            replayBootAudio("delayed");
        }
    };
    private final Runnable audioRouteReplayRunnable = new Runnable() {
        @Override
        public void run() {
            replayAudioRoute("source-switch");
        }
    };

    private String digitBuffer = "";

    /** 起播/切台后 120ms 仍无 onPrepared/首帧 → 加载反馈（台名卡或原角标）。 */
    private boolean loadingPending;
    private boolean loadingDelayElapsed;
    /** 台名卡/加载角标显示条件（开关、首帧、页面态）；导航态不阻止切台反馈。 */
    private final SwitchFeedback switchFeedback = new SwitchFeedback();

    /** 全屏自动跳失败链计数（列表预览失败不计）。 */
    private final FailureStreak failureStreak = new FailureStreak();
    private boolean autoNextPending;
    private Channel autoNextTarget;
    private String autoNextReason;
    private int autoNextSecondsLeft;

    private long lastPosition = -1L;
    private long lastPositionChangeAt;
    private boolean stallRetryDone;
    /** 起播超时重试次数：同一目标台最多重试一次，再失败就交给自动跳台。 */
    private int startTimeoutRetries;
    /** 视频帧卡住重试：同一目标台最多一次。 */
    private boolean videoFreezeRetryDone;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();

        prefs = new AppPrefs(this);
        // 正常启动：清掉「用户主动退出」标记，本次开机重新允许开机信号拉起。
        prefs.setUserExited(false);
        BootLauncher.onActivityCreated();
        Intent launchIntent = getIntent();
        bootFromLaunch = launchIntent != null
                && launchIntent.getBooleanExtra(BootLauncher.EXTRA_FROM_BOOT, false);
        bootAudioCheckPending = BootAudioPlan.shouldArm(bootFromLaunch, isTclLegacyDevice());
        Log.i(TAG, "onCreate fromBoot=" + bootFromLaunch
                + " bootAudioCheck=" + bootAudioCheckPending);
        playbackSettings = prefs.playbackSettings();
        favorites.addAll(prefs.favorites());
        controlApi = new SourceApi(new ControlHost());
        startControlServer();

        Configuration config = getResources().getConfiguration();
        phoneDevice = DeviceMode.isPhone(config.smallestScreenWidthDp);
        phonePortrait = DeviceMode.isPhonePortrait(config.smallestScreenWidthDp,
                config.orientation == Configuration.ORIENTATION_PORTRAIT);
        logDeviceProfile("onCreate");

        // 根层只分两层：视频最底，所有 UI 收进同一个覆盖层 uiLayer。
        // TCL 4.4 只保证「root 最后一个直接子」能压过全宽 SurfaceView，因此导航/设置/
        // 首启/浮层必须是同一个容器的子视图，不能各自做 root 的直接子（否则时钟等浮层
        // 一刷新就会把导航重新压到视频下面）。uiLayer 内部顺序固定：
        // 导航 → 首启 → 设置 → 浮层，语义是「浮层盖设置、设置盖导航、导航盖视频」。
        rootView = new FrameLayout(this);
        // 共享视频视图：只创建一次，旋转时在 PhoneLayout/TvLayout 之间搬移。
        videoSurface = new SurfaceView(this);
        navView = new NavView(this);
        layout = createLayout();
        rootView.addView(layout, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        // 导航/菜单/设置/OSD 都在独立窗口里：TCL 4.4 的 SurfaceView 走 HWC 直接合成，
        // 同一 Activity 窗口内与它重叠的 View 会被压住，只有独立窗口的 z 序稳定在上。
        uiLayer = new FrameLayout(this);
        // 导航不再放进 uiLayer：它要放在独立窗口里才能稳定压过视频 Surface。
        navView.setFillParent(true);

        // 设置页/首启页惰性创建：这里只建空槽，首次 openSettings()/showSetup() 才真正 new，
        // 并插到浮层之前（uiLayer 顺序：setup → settings → overlays）。
        setupPage = new LazyPage<SetupView>(new LazyPage.Builder<SetupView>() {
            @Override
            public SetupView build() {
                return createSetupView();
            }
        });
        settingsPage = new LazyPage<SettingsView>(new LazyPage.Builder<SettingsView>() {
            @Override
            public SettingsView build() {
                return createSettingsView();
            }
        });

        // 浮层是 uiLayer 的最后一个子：OSD/角标/时钟/菜单永远在导航与设置页之上。
        overlays = new Overlays(this);
        overlays.setMenuDismissListener(new Runnable() {
            @Override
            public void run() {
                closeMenu();
            }
        });
        overlays.setBackChromeListener(new Runnable() {
            @Override
            public void run() {
                // 全屏左上「返回」：退全屏回列表（与系统返回/手势一致）。
                exitFullscreen();
            }
        });
        uiLayer.addView(overlays, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        bandwidthIndicator.setListener(new BandwidthIndicator.Listener() {
            @Override
            public void onSpeedText(String text) {
                overlays.updateTransitionSpeed(text);
            }
        });

        overlayPopup = new android.widget.PopupWindow(uiLayer,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, false);
        overlayPopup.setFocusable(false);
        // 默认不接收触摸：日常点画面仍要能到 Activity（OSD/全屏手势）；
        // 只有菜单/设置/首启这类模态页才打开触摸。
        overlayPopup.setTouchable(false);
        overlayPopup.setOutsideTouchable(false);
        overlayPopup.setClippingEnabled(false);
        overlayPopup.setBackgroundDrawable(null);
        overlayPopup.setAnimationStyle(0);

        // 导航独立窗口：不抢焦点（按键仍由 Activity 处理），只负责显示与触摸。
        navPopup = new android.widget.PopupWindow(navView, navWidthPx(),
                ViewGroup.LayoutParams.MATCH_PARENT, false);
        navPopup.setFocusable(false);
        navPopup.setTouchable(true);
        navPopup.setOutsideTouchable(false);
        navPopup.setClippingEnabled(false);
        navPopup.setBackgroundDrawable(null);
        navPopup.setAnimationStyle(0);

        // uiLayer 挂在 overlayPopup 里，不再加到 rootView：它要在独立窗口里才能压住视频。

        ui.setExitListener(new UiState.ExitListener() {
            @Override
            public void onExitArmedChanged(boolean armed) {
                if (armed) {
                    overlays.showToast(getString(R.string.exit_hint), EXIT_HINT_MS);
                } else {
                    overlays.hideToast();
                }
            }
        });
        keyRouter = new KeyRouter(ui, this, this,
                ViewConfiguration.getLongPressTimeout(), EXIT_HINT_MS);

        setContentView(rootView);
        // 手机：状态栏/刘海/手势条 inset 避让（电视不装监听，保持 0 与 4b 一致）。
        installInsetsListener();

        adapter = new ChannelListAdapter(this, phoneDevice);
        adapter.setFavorites(favorites);
        channelList().setAdapter(adapter);
        adapter.attachList(channelList());

        playback = new DualPlayback(new EngineFactory.Default(getApplicationContext()), playbackListener,
                DualMemoryGate.probe(getApplicationContext()));
        playback.setBandwidthListener(bandwidthIndicator);
        controller = playback.active();
        installTextureListeners();
        applyPlaybackSettings();

        SurfaceHolder holder = layout.surfaceHolder();
        if (holder != null) {
            // 共享 SurfaceView 的回调只注册一次；旋转切布局不重注册。
            holder.addCallback(this);
            if (!textureMode && holder.getSurface() != null && holder.getSurface().isValid()) {
                surfaceReady = true;
                applyVideoSurfaces();
            }
        }

        showInitialLoading();
        updatePhoneChrome();
        loadPlaylist(true);
    }

    // ------------------------------------------------------- 布局 / 设备判定

    /**
     * 设备/窗口画像一行日志：排查「竖屏没铺满」时对比
     * {@code adb shell wm size}、截图尺寸与 PhoneLayout 的 onSizeChanged 日志，
     * 就能区分「布局窄了」和「系统把窗口放进面板/尺寸兼容沙箱」。
     */
    private void logDeviceProfile(String where) {
        Configuration config = getResources().getConfiguration();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        Log.i(TAG, "device@" + where + " phone=" + phoneDevice + " portraitLayout=" + phonePortrait
                + " sw=" + config.smallestScreenWidthDp + "dp"
                + " config=" + config.screenWidthDp + "x" + config.screenHeightDp + "dp"
                + " orientation=" + (config.orientation == Configuration.ORIENTATION_PORTRAIT
                        ? "portrait" : config.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? "landscape" : String.valueOf(config.orientation))
                + " display=" + dm.widthPixels + "x" + dm.heightPixels + "@" + dm.densityDpi);
    }

    /** 手机竖屏用 {@link PhoneLayout}，电视与横屏手机用 {@link TvLayout}。 */
    private LiveLayout createLayout() {
        if (phonePortrait) {
            PhoneLayout phone = new PhoneLayout(this);
            phone.setListener(this);
            // 共享视频视图搬进竖屏布局；底栏「设置」入口由 updatePhoneChrome() 按状态显示。
            phone.attachSurface(videoSurface);
            // 竖屏/横屏用同一个视频点按回调（全屏 → OSD + 返回按钮）。
            phone.setVideoTapListener(videoTapListener);
            phone.setSettingsEntry(false, settingsEntryAction);
            return phone;
        }
        TvLayout tv = new TvLayout(this);
        tv.attachSurface(videoSurface);
        if (phoneDevice) {
            // 横屏手机复用电视布局，但导航列表要能用手指：点行预览、双击/再点全屏、
            // 长按操作列。导航在 root 层的 NavView，不在 TvLayout 里。
            attachTouchToList(navView.channelList());
            // 操作列同样可点（规格：横屏「→ 操作列可点」）。
            navView.actionColumn().setListener(new ActionColumn.Listener() {
                @Override
                public void onActionSelected(int index) {
                    MainActivity.this.onActionSelected(index);
                }
            });
            // 横屏手机：点画面出全屏 OSD/返回按钮；底栏「设置」入口在 NavView，
            // 可见性由 updatePhoneChrome() 统一驱动。
            tv.setVideoTapListener(videoTapListener);
        }
        return tv;
    }

    /** 手机视频区点按（竖屏/横屏同一份）：全屏时出/收 OSD + 返回按钮。 */
    private final View.OnClickListener videoTapListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            onVideoTap();
        }
    };

    /** 手机主界面底栏「设置」：触摸进设置页（不再只能靠遥控 MENU）。 */
    private final Runnable settingsEntryAction = new Runnable() {
        @Override
        public void run() {
            openSettings();
        }
    };

    /** 电视布局下的触摸列表（横屏手机）：与手机竖屏同一套 {@link TouchRouter} 判定。 */
    private void attachTouchToList(android.widget.ListView list) {
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onChannelTap(position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                onChannelLongPress(position);
                return true;
            }
        });
        list.setLongClickable(true);
        // 列表不拿视图焦点：焦点仍由 KeyRouter/Activity 状态驱动。
        list.setFocusable(false);
        list.setFocusableInTouchMode(false);
    }

    /**
     * 手机竖屏 ↔ 横屏切换：只切布局。播放器、当前台、播放状态、列表滚动位置全部沿用；
     * 共享 SurfaceView 搬到新布局（框架会 destroy/create 一次 surface），
     * 走「先解绑 → surfaceCreated 重绑」，不重起播、不出现「加载中」黑屏。
     *
     * <p>批次 5b 修复：`wm size`/`wm density` 改变 {@code smallestScreenWidthDp} 时
     * 设备类型可能跨过 600dp（411dp ↔ 720dp），这时也要在本回调里换线程外的布局
     * （手机 ↔ 电视）而不是让 Activity 重建；列表行高随设备类型变，所以换一份 adapter。</p>
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean phone = newConfig.smallestScreenWidthDp > 0
                ? DeviceMode.isPhone(newConfig.smallestScreenWidthDp) : phoneDevice;
        boolean portrait = phone
                && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean deviceTypeChanged = phone != phoneDevice;
        boolean orientationChanged = portrait != phonePortrait;
        logDeviceProfile("configChanged");
        // 系统栏/手势条在横竖屏落点不同（竖屏 top/bottom、横屏可能在侧边），重新问一次。
        requestInsetsRefresh();
        if (!deviceTypeChanged && !orientationChanged) {
            // 同类型布局内的尺寸/密度变化：Surface 尺寸由布局自己跟，onSizeChanged 会打日志。
            Log.i(TAG, "config changed but layout unchanged: phone=" + phone
                    + " portraitLayout=" + portrait);
            return;
        }
        phoneDevice = phone;
        phonePortrait = portrait;
        if (deviceTypeChanged) {
            // 手机 ↔ 电视：行高/字号按设备类型在构造时定，必须换 adapter；
            // 手机底部操作条也不会出现在电视布局里，先收起。
            dismissActions();
            rebuildAdapterForDevice();
        }
        switchLayout();
    }

    /** 设备类型变化（手机 ↔ 电视）后重建列表 adapter：行高/字号由 phoneDevice 决定。 */
    private void rebuildAdapterForDevice() {
        adapter = new ChannelListAdapter(this, phoneDevice);
        adapter.setFavorites(favorites);
        adapter.setFocusIndex(focusIndex);
        adapter.setPlayingId(playingChannelId);
    }

    /**
     * 是否需要独立全屏态：只有手机竖屏需要（点视频进全屏 + OSD/返回按钮）。
     * 电视和手机横屏的「全屏」就是导航隐藏（{@code NAV_HIDDEN}，画面铺满），
     * 不进入 {@link UiState.Mode#FULLSCREEN}，也不再同步布局的 fullscreen。
     */
    private boolean usesFullscreen() {
        return DeviceMode.usesFullscreen(phoneDevice, phonePortrait);
    }

    /** 以 {@link UiState} 为唯一状态源同步布局（导航显隐/手机全屏），不带导航动画。 */
    private void applyModeToLayout() {
        applyModeToLayout(false);
    }

    /**
     * 模式迁移后统一调用：把 {@code ui.mode()} 落到当前布局，并同步根层导航。
     *
     * @param animateNav 导航显隐是否走动画（OK 弹导航/Back 收起为 true）
     */
    private void applyModeToLayout(boolean animateNav) {
        LayoutModeSync.apply(ui, layout, animateNav);
        syncNavLayer(animateNav);
    }

    /**
     * 根层导航（电视/手机横屏）：非手机竖屏、非设置/首启页、且 {@code ui} 处于导航态时
     * 显示并提到最前；其余（手机竖屏由 PhoneLayout 自带列表、全屏、设置/首启）隐藏。
     */
    private void syncNavLayer(boolean animateNav) {
        if (navView == null || navPopup == null) {
            return;
        }
        boolean show = !phonePortrait && !ui.isSettings() && !ui.isSetup()
                && !ui.isFullscreen() && ui.isNavShown();
        if (show) {
            if (!navView.isNavShown()) {
                showNavPopup();
                navView.showNav(animateNav);
            }
        } else if (navView.isNavShown() || navView.getVisibility() == View.VISIBLE) {
            // 窗口常驻：只收起内容，不再 dismiss。每次显隐都 add/remove 窗口会让
            // SurfaceFlinger 重排图层，TCL 上视频会卡一下。
            navView.hideNav(animateNav);
        }
    }

    /** 导航窗口上屏并常驻；只有宽度变化（旋转/密度）时才 update，避免每次显隐都动窗口。 */
    private void showNavPopup() {
        if (navPopup == null || isFinishing() || isDestroyed()) {
            return;
        }
        int width = navWidthPx();
        if (navPopup.isShowing()) {
            // 窗口已 dismiss/正在销毁时 update 会抛 view not attached；
            // 双检查 isShowing + 内容视图的 window token。
            if (navView.getWindowToken() == null || navView.getWidth() == width) {
                return;
            }
            navPopup.update(width, ViewGroup.LayoutParams.MATCH_PARENT);
            return;
        }
        if (rootView == null || rootView.getWindowToken() == null) {
            return;
        }
        navPopup.setWidth(width);
        navPopup.showAtLocation(rootView, android.view.Gravity.NO_GRAVITY, 0, 0);
        // 电视没有触摸，窗口常驻但整块区域不消费触摸；手机横屏才需要点列表。
        navPopup.setTouchable(phoneDevice);
    }

    /** 覆盖窗口上屏；rootView 必须已 attach，onStart 后由 post 保证。 */
    private void showOverlayPopup() {
        if (overlayPopup == null || overlayPopup.isShowing()) {
            return;
        }
        rootView.post(new Runnable() {
            @Override
            public void run() {
                if (!started || overlayPopup == null || overlayPopup.isShowing()) {
                    return;
                }
                overlayPopup.showAtLocation(rootView, android.view.Gravity.NO_GRAVITY, 0, 0);
                applyOverlayTouchable();
            }
        });
    }

    private void dismissOverlayPopup() {
        if (overlayPopup != null && overlayPopup.isShowing()) {
            overlayPopup.dismiss();
        }
    }

    /** 菜单/设置/首启是模态，需要触摸；其余时刻让触摸穿透到 Activity 的视频手势。 */
    private void applyOverlayTouchable() {
        if (overlayPopup == null) {
            return;
        }
        boolean touchable = ui.isSettings() || ui.isSetup() || ui.isMenuOpen();
        overlayPopup.setTouchable(touchable);
        if (overlayPopup.isShowing()) {
            overlayPopup.update(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** 收起动画期间窗口仍在，动画结束再 dismiss，避免导航被瞬间切断。 */
    private void hideNavPopupDelayed() {
        if (navPopup == null || isFinishing() || isDestroyed()) {
            return;
        }
        handler.removeCallbacks(navPopupHideRunnable);
        handler.postDelayed(navPopupHideRunnable, 320L);
    }

    private final Runnable navPopupHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (navPopup == null || isFinishing() || isDestroyed()) {
                return;
            }
            if (navView.isNavShown()) {
                return;
            }
            // 双检查：isShowing 可能滞后于 WindowManager，token 为空说明窗口已不在。
            if (navPopup.isShowing() && navView.getWindowToken() != null) {
                navPopup.dismiss();
            }
        }
    };

    /**
     * 当前设备的导航宿主：手机竖屏用 PhoneLayout 内置列表；电视/手机横屏用 root 层
     * {@link NavView}。所有列表/组栏/提示/操作列调用都经这里，避免引用 TvLayout。
     */
    private android.widget.ListView channelList() {
        return phonePortrait ? layout.channelList() : navView.channelList();
    }

    private void setGroupItems(List<GroupBar.Item> items) {
        if (phonePortrait) {
            layout.setGroupItems(items);
        } else {
            navView.setGroupItems(items);
        }
    }

    private void setGroupState(int activeIndex, int focusIndex, boolean groupFocus,
            int... playingIndexes) {
        if (phonePortrait) {
            layout.setGroupState(activeIndex, focusIndex, groupFocus, playingIndexes);
        } else {
            navView.setGroupState(activeIndex, focusIndex, groupFocus, playingIndexes);
        }
    }

    private void setHint(CharSequence hint) {
        if (phonePortrait) {
            layout.setHint(hint);
        } else {
            navView.setHint(hint);
        }
    }

    private void setHintVisible(boolean visible) {
        if (phonePortrait) {
            layout.setHintVisible(visible);
        } else {
            navView.setHintVisible(visible);
        }
    }

    private void showListEmpty(CharSequence text) {
        if (phonePortrait) {
            layout.showListEmpty(text);
        } else {
            navView.showListEmpty(text);
        }
    }

    private void hideListEmpty() {
        if (phonePortrait) {
            layout.hideListEmpty();
        } else {
            navView.hideListEmpty();
        }
    }

    private void updateNow(int number, String name, String meta) {
        if (phonePortrait) {
            layout.updateNow(number, name, meta);
        } else {
            navView.updateNow(number, name, meta);
        }
    }

    private void clearNow() {
        if (phonePortrait) {
            layout.clearNow();
        } else {
            navView.clearNow();
        }
    }

    private void updateActions(String name, boolean favorite, int variantCount,
            boolean focused, int focusIndex) {
        if (phonePortrait) {
            layout.updateActions(name, favorite, variantCount, focused, focusIndex);
        } else {
            navView.updateActions(name, favorite, variantCount, focused, focusIndex);
        }
    }

    private void setActionColumnVisible(boolean visible) {
        if (phonePortrait) {
            layout.setActionColumnVisible(visible);
        } else {
            navView.setActionColumnVisible(visible);
        }
    }

    /** 手机底栏/横屏导航底栏的触摸「设置」入口；电视不显示。 */
    private void setSettingsEntry(boolean visible, Runnable action) {
        if (phonePortrait) {
            layout.setSettingsEntry(visible, action);
        } else {
            navView.setSettingsEntry(visible, action);
        }
    }

    /**
     * 切布局（旋转用）：
     * <ol>
     *   <li>记下列表滚动锚点与全屏态；</li>
     *   <li>显式解绑 Surface（旧布局 detach 也会回调 surfaceDestroyed，先解绑让顺序确定）；</li>
     *   <li>新布局 attachSurface 同一个 SurfaceView；surfaceCreated 回来时重绑；</li>
     *   <li>恢复分组/焦点/全屏态/列表位置；播放器不动（不 play()）。</li>
     * </ol>
     */
    private void switchLayout() {
        Channel playing = playingChannel();
        boolean wasFullscreen = ui.isFullscreen();
        if (wasFullscreen && !usesFullscreen()) {
            // 手机竖屏全屏 → 横屏/TV 布局：目标布局没有独立全屏态，回隐藏态（画面铺满）。
            ui.exitFullscreen();
        }
        LiveLayout old = layout;
        // 锚点取自「旧布局真正在用的列表」：手机竖屏在 PhoneLayout，电视/横屏在 root
        // NavView。切换时 phonePortrait 已是新值，不能直接用 channelList()。
        android.widget.ListView oldList = old.channelList() != null
                ? old.channelList() : navView.channelList();
        ListAnchor anchor = captureListAnchor(oldList);
        boolean restart = RotationResume.plan(controller.getCurrentChannel() != null)
                == RotationResume.Action.RESTART;

        // 旧布局的 TextureView 即将 detach：先解绑，destroy 回调再释放 Surface。
        if (playback != null) {
            playback.setSurfaces(null, null);
        }
        surfaceReady = false;
        // 同一个 SurfaceView 只能有一个父容器：先从旧布局摘下，再切布局，
        // 否则 TvLayout/PhoneLayout.attachSurface 会抛 "child already has a parent"。
        old.detachSurface();
        if (videoSurface.getParent() instanceof ViewGroup) {
            ((ViewGroup) videoSurface.getParent()).removeView(videoSurface);
        }
        rootView.removeView(old);

        layout = createLayout();
        if (layout != null && playbackSettings != null) {
            // 旋转/切布局后新布局默认 FIT，按设置重新套用画面比例。
            layout.setVideoAspect(playbackSettings.aspect());
        }
        // 必须在 addView 之前设 SurfaceTextureListener：rootView 已 attached，
        // addView 会同步 attach 新 TextureView 并回调 onSurfaceTextureAvailable；
        // 之后再设 listener 就再也收不到这一次回调（SurfaceTexture 不会被重新创建）。
        installTextureListeners();
        if (layout != null) {
            layout.setTextureMode(textureMode);
        }
        syncTextureLayerState();
        rootView.addView(layout, 0, new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        channelList().setAdapter(adapter);
        adapter.attachList(channelList());
        if (!textureMode) {
            SurfaceHolder holder = layout.surfaceHolder();
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                surfaceReady = true;
            }
        }
        applyVideoSurfaces();

        refreshGroupBar();
        rebuildVisibleChannels();
        adapter.setFocusIndex(focusIndex);
        // 以 ui.mode() 为准恢复新布局：全屏只可能出现在手机竖屏，TV/横屏恒为导航态。
        applyModeToLayout();
        updateListState();
        restoreListAnchor(channelList(), anchor);
        touchRouter.reset();
        hideFullscreenChrome();
        if (wasFullscreen && phoneDevice) {
            // 旋转后给一次频道 OSD（返回按钮仍由点画面唤出）。
            showFullscreenOsdOnly();
        }
        Log.i(TAG, "layout switched: portrait=" + phonePortrait
                + " channel=" + (playing == null ? "-" : playing.id())
                + " fullscreen=" + wasFullscreen
                + " anchor=" + anchor
                + (restart ? " (playback restart fallback)" : " (surface rebind only)"));
        if (restart && playing != null && !paused) {
            // 兜底：播放器已被释放（onStop/清空列表）才重新起播。
            playChannel(playing, false, true);
        }
    }

    /** 列表滚动锚点：首个可见行 + 它距列表顶部的偏移；列表不在布局中时为 NONE。 */
    private static ListAnchor captureListAnchor(android.widget.ListView list) {
        if (list == null || list.getChildCount() == 0) {
            return ListAnchor.NONE;
        }
        int first = list.getFirstVisiblePosition();
        android.view.View child = list.getChildAt(0);
        if (first < 0 || child == null) {
            return ListAnchor.NONE;
        }
        return ListAnchor.of(first, Math.max(0, child.getTop() - list.getPaddingTop()));
    }

    private static void restoreListAnchor(android.widget.ListView list, ListAnchor anchor) {
        if (list == null || anchor == null || !anchor.valid()) {
            return;
        }
        ListAnchor target = anchor;
        android.widget.ListAdapter adapter = list.getAdapter();
        if (adapter != null) {
            target = anchor.clampTo(adapter.getCount());
        }
        if (target.valid()) {
            // 与 captureListAnchor 对称：锚点偏移是「相对列表顶部内边距」的量。
            list.setSelectionFromTop(target.position(),
                    list.getPaddingTop() + target.offset());
        }
    }

    /** 启动先给「读取中」占位，解析完成后再出列表/直接全屏。 */
    private void showInitialLoading() {
        showListEmpty(getString(R.string.source_loading));
        overlays.showLoading(getString(R.string.source_loading));
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (paused) {
            // 后台期间收不到网络广播（onStop 已注销）：回前台重新枚举一次网卡，
            // 避免用后台前的 30 秒缓存把旧 IP 显示在设置页/首启页。
            LanAddress.invalidate();
        }
        showOverlayPopup();
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
        updateAutoHideBlock();
        // 后台期间不计时：回前台若导航仍展开，重新给满 8 秒。
        ui.noteKey(SystemClock.elapsedRealtime());
        registerNetworkReceiver();
        registerBootCompletedReceiver();
        refreshControlInfoIfChanged();

        if (isTclLegacyDevice()) {
            Boolean source = TclSourceSwitch.isAndroidSource(this);
            Log.i(TAG, "audio route onStart android=" + source);
        }
        if (paused) {
            // onStop 里 release 过：恢复两路 listener/surface，再恢复当前播放台（不闪列表）。
            paused = false;
            playback.restore();
            controller = playback.active();
            if (!textureMode) {
                SurfaceHolder holder = layout.surfaceHolder();
                Surface surface = surfaceReady && holder != null ? holder.getSurface() : null;
                if (surface == null || !surface.isValid()) {
                    surfaceReady = false;
                }
            }
            applyVideoSurfaces();
            Channel current = playingChannel();
            // 顺序：先恢复播放（同一台不 force，避免解码器被重建），再把「重载完成后
            // 是否需要补播」交给 onPlaylistLoaded 判断；重载保持同一台时不会再起解码器。
            String resumeId = current != null ? current.id() : prefs.lastChannelId();
            startupResume.defer(resumeId);
            if (current != null) {
                Log.i(TAG, "onStart restore: resume play without force id=" + resumeId);
                playChannel(current, false, false);
            }
            if (!loading) {
                // v1.0 §4：onStart 重新加载并恢复；静默重载，不打断同一台。
                silentReload = true;
                reloadTargetId = current != null ? current.id() : prefs.lastChannelId();
                loadPlaylist(false);
            }
        }
    }

    @Override
    protected void onStop() {
        started = false;
        paused = true;
        // 回后台：上一次 onStart 留下的恢复意图作废，下次 onStart 重新决定。
        startupResume.discard();
        cancelDeferredPlayback();
        endLoading();
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(digitTimeoutRunnable);
        cancelAutoNext();
        keyRouter.cancel();
        handler.removeCallbacks(phoneDisarmRunnable);
        ui.disarmExit();
        closeMenu();
        hideFullscreenChrome();
        overlays.hideErrorBadge();
        unregisterNetworkReceiver();
        unregisterBootCompletedReceiver();
        handler.removeCallbacks(bootAudioReplayRunnable);
        handler.removeCallbacks(audioRouteReplayRunnable);
        bootAudioReplayScheduled = false;
        audioRouteReplayScheduled = false;

        saveLastChannel();
        if (playback != null) {
            playback.release();
        }
        dismissOverlayPopup();
        dismissNavPopupNow();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BootLauncher.onActivityDestroyed();
        handler.removeCallbacksAndMessages(null);
        stopControlServer();
        controlIo.shutdownNow();
        dismissOverlayPopup();
        dismissNavPopupNow();
        if (playback != null) {
            playback.release();
        }
        super.onDestroy();
    }

    private void dismissNavPopupNow() {
        if (navPopup != null && navPopup.isShowing()) {
            navPopup.dismiss();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
            requestInsetsRefresh();
        }
    }

    /** 模拟器/部分盒子会显示底部三键：隐藏导航栏 + 沉浸粘性 + 全屏。 */
    private void applyImmersiveMode() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (phoneDevice) {
            // 手机：状态栏/导航栏保持黑底（API 19 无图标明暗控制，至少不出现浅色底压字）；
            // 电视沿用主题的 ink 背景，不改动 4b 的观感。
            getWindow().setBackgroundDrawableResource(android.R.color.black);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ------------------------------------------------------------ 系统栏 inset

    /**
     * 手机：按窗口 inset 给 root 加 padding（状态栏/刘海在上、手势条/三键栏在下或侧边），
     * 视频不压状态栏、底栏不压手势条。电视不装监听 → 恒 0，布局与 4b 完全一致。
     */
    private void installInsetsListener() {
        if (!phoneDevice || rootView == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) {
            return;
        }
        rootView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                int left = insets.getSystemWindowInsetLeft();
                int right = insets.getSystemWindowInsetRight();
                int gTop = 0;
                int gBottom = 0;
                int gLeft = 0;
                int gRight = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 手势导航：沉浸式下 systemWindowInset 可能为 0，但手势条区域仍要避让。
                    android.graphics.Insets gestures = insets.getSystemGestureInsets();
                    gTop = gestures.top;
                    gBottom = gestures.bottom;
                    gLeft = gestures.left;
                    gRight = gestures.right;
                }
                SystemInsets target = SystemInsets.forWindow(true,
                        top, bottom, left, right, gTop, gBottom, gLeft, gRight);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // 刘海/打孔：沉浸式铺满时窗口可能伸进挖孔区，单独把安全区并进来。
                    android.view.DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        target = target.merge(cutout.getSafeInsetTop(), cutout.getSafeInsetBottom(),
                                cutout.getSafeInsetLeft(), cutout.getSafeInsetRight());
                    }
                }
                applyInsets(target);
                return insets;
            }
        });
        requestInsetsRefresh();
    }

    /** 沉浸式栏隐藏/显示会改变 inset；旋转后也重新问一次。 */
    private void requestInsetsRefresh() {
        if (rootView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            rootView.requestApplyInsets();
        }
    }

    private void applyInsets(SystemInsets insets) {
        if (rootView == null || insets == null || insets.equals(lastInsets)) {
            return;
        }
        lastInsets = insets;
        rootView.setPadding(insets.left(), insets.top(), insets.right(), insets.bottom());
        Log.i(TAG, "insets " + insets);
    }

    // ------------------------------------------------------------------ 首启 / 设置

    /**
     * 「在本机输入地址」：复用手机页的 {@link SourceApi#add} 下载/校验/保存，
     * 后台线程执行；成功后 Host.requestReload 会刷新列表并自动关掉首启页。
     */
    private void importLocalUrl(final String url) {
        final SourceApi api = controlApi;
        if (api == null) {
            overlays.showToast(getString(R.string.setup_import_failed,
                    getString(R.string.settings_control_offline)), SOURCE_NOTE_MS);
            return;
        }
        if (localImportRunning) {
            overlays.showToast(getString(R.string.setup_import_running), TOAST_SHORT_MS);
            return;
        }
        localImportRunning = true;
        setupView().setNote(getString(R.string.setup_import_running));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SourceApi.Result result = api.add(url, null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        localImportRunning = false;
                        if (result.ok()) {
                            setupView().setNote(null);
                            overlays.showToast(getString(R.string.setup_import_done),
                                    SOURCE_NOTE_MS);
                            return;
                        }
                        String reason = result.errorMessage();
                        setupView().setNote(getString(R.string.setup_import_failed, reason));
                        overlays.showToast(getString(R.string.setup_import_failed, reason),
                                SOURCE_NOTE_MS);
                    }
                });
            }
        }, "local-url-import").start();
    }

    /** 首次进入首启页才构建；插到设置页之下、浮层之前。 */
    private SetupView createSetupView() {
        Log.i(TAG, "setup page built (lazy, first open)");
        SetupView view = new SetupView(this);
        view.setListener(new SetupView.Listener() {
            @Override
            public void onLocalUrl(String url) {
                importLocalUrl(url);
            }
        });
        view.setVisibility(View.GONE);
        // uiLayer 顺序固定 setup → settings → overlays：设置页若已构建就插在它前面。
        SettingsView builtSettings = settingsPage == null ? null : settingsPage.peek();
        int index = builtSettings == null ? -1 : uiLayer.indexOfChild(builtSettings);
        addUiPage(view, index);
        return view;
    }

    /** 首次进设置页才构建（50+ View + 二维码浮层）。 */
    private SettingsView createSettingsView() {
        Log.i(TAG, "settings page built (lazy, first open)");
        SettingsView view = new SettingsView(this);
        view.setListener(settingsListener);
        view.setVisibility(View.GONE);
        addUiPage(view, -1);
        return view;
    }

    /** 惰性页要排在浮层之前，保证 OSD/菜单仍盖在设置页上。 */
    private void addUiPage(View page, int index) {
        if (index < 0 && overlays != null) {
            index = uiLayer.indexOfChild(overlays);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        if (index < 0) {
            uiLayer.addView(page, params);
        } else {
            uiLayer.addView(page, index, params);
        }
    }

    private SetupView setupView() {
        return setupPage.get();
    }

    private SettingsView settingsView() {
        return settingsPage.get();
    }

    /**
     * 无源后的首启页；note 为读取失败原因等附加说明。
     */
    private void showSetup(String note) {
        ui.enterSetup();
        pausePlaybackUi();
        // 设置页若已构建（例如从设置页退回无源），先隐藏；未构建就不为它付构造成本。
        SettingsView hiddenSettings = settingsPage.peek();
        if (hiddenSettings != null) {
            hiddenSettings.setVisibility(View.GONE);
        }
        setupView().setNote(note);
        setupView().setActionFocused(true);
        refreshControlInfo();
        setupView().setVisibility(View.VISIBLE);
        applyModeToLayout();
        updateAutoHideBlock();
    }

    private void hideSetup() {
        ui.dismissSetup();
        // 常规加载完成也会调用：没构建过首启页就什么都不做（启动不构建它）。
        SetupView view = setupPage.peek();
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        applyModeToLayout();
        updateAutoHideBlock();
    }

    private void openSettings() {
        if (ui.isSettings()) {
            return;
        }
        closeMenu();
        ui.enterSettings();
        // 设置页是半透明覆盖层：先把导航收起，避免透过 90% 黑底看到导航；
        // 关闭设置后 applyModeToLayout 会按底层模式恢复导航。
        syncNavLayer(false);
        // 设置页期间不自动跳台、不显示播放相关浮层；关闭后也不恢复旧倒计时。
        pausePlaybackUi();
        overlays.updateClock(false);

        settingsView().setBootEnabled(BootSignalReceiver.isEnabled(this));
        settingsView().setLastError(prefs.lastError());
        settingsView().setPlaybackSettings(playbackSettings.switchMode().ordinal(),
                playbackSettings.renderMode().ordinal(), playbackSettings.firstFrameBoost(),
                playbackSettings.transitionCard(), playbackSettings.showSpeed(),
                playbackSettings.switchModeHintSeen());
        settingsView().setDisplaySettings(playbackSettings.aspect().ordinal(),
                playbackSettings.navWidth().ordinal(), playbackSettings.autoHideSeconds(),
                playbackSettings.clockBadge());
        settingsView().setAdvancedExpanded(playbackSettings.advancedOpen());
        ensureControlToken();
        refreshControlInfoIfChanged();
        settingsView().setSourceLoading();
        settingsView().resetFocus();
        settingsView().setVisibility(View.VISIBLE);
        // 覆盖层要压在全宽 SurfaceView 上（TCL 的合成层优先于普通 View）。
        settingsView().ensureOnTop();
        updateAutoHideBlock();
        refreshSettingsSources();
    }

    /**
     * 进入设置页/首启页：取消自动跳台倒计时并清掉播放相关浮层。
     * 不记 pending，关闭页面后不恢复（用户手动操作）。
     */
    private void pausePlaybackUi() {
        autoNextPending = false;
        autoNextTarget = null;
        autoNextReason = null;
        autoNextSecondsLeft = 0;
        handler.removeCallbacks(autoNextTickRunnable);
        endLoading();
        hideFullscreenChrome();
        overlays.hideToast();
        overlays.hideErrorBadge();
        overlays.hideLoading();
        overlays.hideVolume();
    }

    /** 设置页源列表：启动/重载已解析过快照时直接显示，否则等后台加载完成。 */
    private void refreshSettingsSources() {
        if (!ui.isSettings()) {
            return;
        }
        if (!sourcesReady) {
            settingsView().setSourceLoading();
            return;
        }
        if (sourceFatalError != null) {
            settingsView().setSourceError(sourceFatalError);
            return;
        }
        settingsView().setSources(sourceStates);
    }

    /** 画面态显示整/半点对时角标：手机竖屏看全屏，电视/横屏看导航是否收起。 */
    private void updateClockVisibility() {
        boolean enabled = playbackSettings == null || playbackSettings.clockBadge();
        overlays.updateClock(ClockPolicy.allowed(enabled, ui.isMenuOpen(), ui.isSettings(),
                ui.isSetup(), ui.isFullscreen(), ui.isNavShown()));
    }

    private void closeSettings() {
        if (!ui.isSettings()) {
            return;
        }
        settingsView().setVisibility(View.GONE);
        UiState.Mode restored = ui.leaveSettings(SystemClock.elapsedRealtime());
        if (restored == UiState.Mode.SETUP) {
            setupView().setActionFocused(true);
            updateAutoHideBlock();
            return;
        }
        updateClockVisibility();
        if (started && loadingPending && loadingDelayElapsed) {
            showLoadingFeedback();
        }
        applyModeToLayout();
        // 设置页期间不计时；关闭后从当前时刻重新给满 8 秒。
        updateAutoHideBlock();
    }

    private void toggleBootAutostart(boolean enabled) {
        prefs.setBootAutostart(enabled);
        BootSignalReceiver.setEnabled(this, enabled);
        settingsView().setBootEnabled(enabled);
        overlays.showToast(getString(enabled
                ? R.string.settings_boot_enabled : R.string.settings_boot_disabled), SOURCE_NOTE_MS);
    }

    /** 把持久化的播放设置应用到播放编排/加载反馈；未播放时只影响下一次建引擎。 */
    private void applyPlaybackSettings() {
        PlaybackSettings settings = playbackSettings;
        if (settings == null) {
            return;
        }
        switchFeedback.setCardEnabled(settings.transitionCard());
        boolean showSpeed = settings.showSpeed();
        bandwidthIndicator.setEnabled(showSpeed);
        overlays.setSpeedEnabled(showSpeed);
        applyDisplaySettings();
        if (playback != null) {
            playback.setFirstFrameBoost(settings.firstFrameBoost());
            applyPlaybackMode();
        }
    }

    /**
     * 应用导航/显示设置（批次 18）：画面比例、导航宽度、自动收起与时钟角标。
     *
     * <p>全部是即时生效的纯显示项：不重建播放器、不重绑 Surface；画面比例只改视频层
     * 尺寸，导航宽度同步更新常驻 PopupWindow 的宽度。</p>
     */
    private void applyDisplaySettings() {
        PlaybackSettings settings = playbackSettings;
        if (settings == null) {
            return;
        }
        Aspect aspect = settings.aspect();
        if (layout != null) {
            layout.setVideoAspect(aspect);
        }
        if (navView != null) {
            navView.setNavWidthRatio(settings.navWidth().ratio());
        }
        updateNavPopupWidth();
        ui.setAutoHideMs(settings.autoHide().millis());
        updateClockVisibility();
    }

    /** 导航窗口宽度跟随「导航宽度」设置；窗口已上屏时立即 update，不重新 show。 */
    private void updateNavPopupWidth() {
        if (navView == null || navPopup == null || playbackSettings == null) {
            return;
        }
        int width = navWidthPx();
        if (!navPopup.isShowing() || navView.getWindowToken() == null) {
            return;
        }
        if (navView.getWidth() != width) {
            navPopup.update(width, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    /** 当前设置下的导航像素宽度（PopupWindow 与 NavView 共用）。 */
    private int navWidthPx() {
        int total = getResources().getDisplayMetrics().widthPixels;
        float ratio = playbackSettings == null
                ? TvNavGeometry.NAV_WIDTH_RATIO : playbackSettings.navWidth().ratio();
        return TvNavGeometry.navWidth(total, ratio);
    }

    /**
     * 按「视频渲染 + 切台方式」应用视频编排（批次 17）。
     *
     * <p>SurfaceView 无法可靠地同时合成两路画面，请求双画面时降级为 KEEP 并提示一次；
     * 同时把设置里的切台方式落回 KEEP，避免每次打开设置页/切台都重复提示。</p>
     */
    private void applyPlaybackMode() {
        PlaybackSettings settings = playbackSettings;
        if (settings == null) {
            return;
        }
        RenderMode render = settings.renderMode();
        SwitchMode requested = settings.switchMode();
        if (RenderDecision.degraded(render, requested)) {
            Log.i(TAG, "render SURFACE cannot do DUAL, fall back to KEEP");
            settings.setSwitchMode(SwitchMode.KEEP);
            refreshSettingsPlaybackValues();
            overlays.showToast(getString(R.string.settings_play_render_dual_degraded),
                    SOURCE_NOTE_MS);
        }
        SwitchMode target = RenderDecision.effectiveSwitch(render, requested);
        applySwitchMode(target, RenderDecision.useTexture(render, target), render);
    }

    /** 设置页已构建时，把 Activity 侧（可能被降级修正过的）播放设置重新下发一遍。 */
    private void refreshSettingsPlaybackValues() {
        SettingsView view = settingsPage.peek();
        if (view == null) {
            return;
        }
        view.setPlaybackSettings(playbackSettings.switchMode().ordinal(),
                playbackSettings.renderMode().ordinal(), playbackSettings.firstFrameBoost(),
                playbackSettings.transitionCard(), playbackSettings.showSpeed(),
                playbackSettings.switchModeHintSeen());
        view.setDisplaySettings(playbackSettings.aspect().ordinal(),
                playbackSettings.navWidth().ordinal(), playbackSettings.autoHideSeconds(),
                playbackSettings.clockBadge());
        view.setAdvancedExpanded(playbackSettings.advancedOpen());
    }

    /**
     * 应用切台方式与视频层：视频层由 {@link RenderDecision#useTexture} 决定（自动模式跟随
     * 切台方式：KEEP/DUAL 走 TextureView，极速直切走 SurfaceView）。
     * 组合真正变化时按目标重新绑定 Surface，并把当前台重播一次（可接受一次短暂黑屏）。
     */
    private void applySwitchMode(SwitchMode target, boolean texture, RenderMode render) {
        if (playback == null) {
            return;
        }
        SwitchMode mode = target == null ? SwitchMode.KEEP : target;
        RenderMode renderMode = render == null ? RenderMode.AUTO : render;
        boolean first = appliedSwitchMode == null;
        if (!first && appliedSwitchMode == mode && appliedTextureMode == texture
                && appliedRenderMode == renderMode) {
            return;
        }
        appliedSwitchMode = mode;
        appliedTextureMode = texture;
        appliedRenderMode = renderMode;
        Log.i(TAG, "switch mode -> " + mode + " render=" + renderMode + " texture=" + texture);

        // 先让编排层停掉/建立 standby 并归位槽位，再切视频层，最后统一重绑 Surface。
        playback.setMode(mode);
        controller = playback.active();
        // 先确定 B 是否 attach，再 setTextureMode（它会补绑已 attach 的 Surface）。
        syncDualTexture();
        setTextureMode(texture);
        if (first) {
            return;
        }
        Channel current = playingChannel();
        if (current == null) {
            current = playback.active().getCurrentChannel();
        }
        if (current != null && !paused && playlist != null) {
            // 按 UI 当前台重播：DUAL→KEEP 时前台可能已从 B 归位到 A（A 槽位没有台）。
            // 切到 SurfaceView 时先强制重建一次 Surface：TCL 上偶发「黑屏有声」，
            // 只重播播放器不会重新绑 Surface，和切渲染方式（会重建视频层）效果不同。
            if (!texture) {
                recreateSurfaceView();
            }
            playChannel(current, false, true);
        }
    }

    /**
     * 强制 SurfaceView 重建（GONE → 1s → reparent + VISIBLE）。
     *
     * <p>TCL 上 SurfaceView 从 GONE 回到 VISIBLE 时，Surface 会重建、Exo 也能绑上，
     * 但 HWC 合成层不刷新，画面一直黑。只做 INVISIBLE/VISIBLE 不够，这里直接
     * detach 再 attach（reparent），强制 SurfaceFlinger 重新分配 layer。</p>
     *
     * <p>代价：切换时黑约 1 秒；只用于「切台方式切到 SurfaceView」和看门狗兜底。</p>
     */
    private void recreateSurfaceView() {
        final SurfaceView view = videoSurface;
        if (view == null) {
            return;
        }
        Log.i(TAG, "recreate SurfaceView (reparent)");
        if (layout != null) {
            layout.detachSurface();
        }
        view.setVisibility(View.GONE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (layout == null) {
                    return;
                }
                if (view.getParent() == null) {
                    layout.attachSurface(view);
                }
                view.setVisibility(View.VISIBLE);
            }
        }, 1000L);
    }

    /**
     * 视频层互斥：{@code true} 隐藏 SurfaceView、显示双 TextureView；{@code false} 反之。
     * 切回 SurfaceView 时释放两个 TextureView 的 Surface（不释放会让旧 Surface 一直占着）。
     */
    private void setTextureMode(boolean enabled) {
        if (textureMode != enabled) {
            textureMode = enabled;
            if (!enabled) {
                releaseTextureSurface(true);
                releaseTextureSurface(false);
            }
        }
        if (layout != null) {
            layout.setTextureMode(enabled);
            syncTextureLayerState();
        }
        if (enabled) {
            // 从极速直切切回纹理模式：TextureView 一直 attached，不会再有 available 回调，
            // 直接用它现有的 SurfaceTexture 恢复绑定。
            attachExistingTextureSurfaces();
        }
        applyVideoSurfaces();
    }

    /** 把两个 TextureView 当前已有的 SurfaceTexture 重新包成 Surface（仅缺绑定时）。 */
    private void attachExistingTextureSurfaces() {
        TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
        if (layer == null) {
            return;
        }
        if (textureSurfaceA == null) {
            bindTextureSurface(true, layer.a().getSurfaceTexture());
        }
        if (textureSurfaceB == null) {
            bindTextureSurface(false, layer.b().getSurfaceTexture());
        }
    }

    /**
     * 批次 12：只有 DUAL 才 attach 第二路 TextureView；KEEP/DIRECT 释放 B 的 Surface
     * 并 detach，避免白占一份 1080p SurfaceTexture（约 8MB）。
     *
     * <p>先解绑 B 再 detach：正在用的 A 不受影响。</p>
     */
    private void syncDualTexture() {
        TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
        if (layer == null) {
            return;
        }
        boolean dual = appliedSwitchMode == SwitchMode.DUAL;
        if (dual) {
            layer.setDualEnabled(true);
            return;
        }
        releaseTextureSurface(false);
        layer.setDualEnabled(false);
    }

    /** TextureView → Surface 的唯一绑定入口；同一 SurfaceTexture 只绑定一次。 */
    private void bindTextureSurface(boolean primary, SurfaceTexture surfaceTexture) {
        if (!primary) {
            // 批次 12：KEEP/DIRECT 不持有 B。迟到的 available 回调（detach 排队中）不绑定，
            // 否则会重新占一份 SurfaceTexture。
            TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
            if (layer == null || !layer.isDualEnabled()) {
                return;
            }
        }
        TextureBinding binding = primary ? textureBindingA : textureBindingB;
        if (surfaceTexture == null || binding.onAvailable(surfaceTexture) != TextureBinding.Result.BIND) {
            return;
        }
        // 同一 TextureView 换了一份 SurfaceTexture：先释放旧包装再建新的。
        dropTextureSurface(primary);
        Surface created = new Surface(surfaceTexture);
        if (primary) {
            textureSurfaceA = created;
        } else {
            textureSurfaceB = created;
        }
        applyVideoSurfaces();
    }

    /** TextureView 的 alpha 槽位跟随编排层（旋转重建布局后必须重新套用）。 */
    private void syncTextureLayerState() {
        TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
        if (layer == null) {
            return;
        }
        layer.setActiveA(playback == null || playback.activeIsA());
    }

    /** 给两个 TextureView 设 SurfaceTextureListener；旋转重建布局后要重新设一次。 */
    private void installTextureListeners() {
        TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
        if (layer == null) {
            return;
        }
        layer.a().setSurfaceTextureListener(new TextureListener(true));
        layer.b().setSurfaceTextureListener(new TextureListener(false));
        // KEEP/DIRECT 下 B 不 attach；DUAL 才挂回第二路（监听已设好，attach 即可回调）。
        syncDualTexture();
        syncTextureLayerState();
    }

    /** TextureView → Surface 的唯一入口；两个 View 各一份，令牌防重复绑定/迟到回调。 */
    private final class TextureListener implements TextureView.SurfaceTextureListener {

        private final boolean primary;

        TextureListener(boolean primary) {
            this.primary = primary;
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            bindTextureSurface(primary, surfaceTexture);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            // 只改尺寸，不需要重新绑定（与 SurfaceView.surfaceChanged 一致）。
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            TextureBinding binding = primary ? textureBindingA : textureBindingB;
            if (binding.onDestroyed(surfaceTexture) != TextureBinding.Result.RELEASE) {
                return true;
            }
            dropTextureSurface(primary);
            applyVideoSurfaces();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }
    }

    private void releaseTextureSurface(boolean primary) {
        TextureBinding binding = primary ? textureBindingA : textureBindingB;
        binding.detach();
        dropTextureSurface(primary);
    }

    /** 只释放 Surface 包装并清空引用；SurfaceTexture 本身由 TextureView 管理。 */
    private void dropTextureSurface(boolean primary) {
        Surface surface = primary ? textureSurfaceA : textureSurfaceB;
        if (primary) {
            textureSurfaceA = null;
        } else {
            textureSurfaceB = null;
        }
        if (surface == null) {
            return;
        }
        try {
            surface.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "释放 TextureView Surface 失败", e);
        }
    }

    /** 当前应该交给播放器的 Surface：纹理模式用 A/B，SurfaceView 模式只给 A。 */
    private void applyVideoSurfaces() {
        if (playback == null) {
            return;
        }
        Surface a = textureMode ? textureSurfaceA : surfaceViewSurface();
        Surface b = textureMode ? textureSurfaceB : null;
        Log.i(TAG, "bind surfaces texture=" + textureMode
                + " a=" + describeSurface(a) + " b=" + describeSurface(b));
        playback.setSurfaces(a, b);
    }

    private static String describeSurface(Surface surface) {
        if (surface == null) {
            return "null";
        }
        try {
            return surface.isValid() ? "valid" : "invalid";
        } catch (RuntimeException e) {
            return "error";
        }
    }

    private Surface surfaceViewSurface() {
        if (!surfaceReady || layout == null) {
            return null;
        }
        SurfaceHolder holder = layout.surfaceHolder();
        if (holder == null) {
            return null;
        }
        Surface surface = holder.getSurface();
        return surface != null && surface.isValid() ? surface : null;
    }

    // ------------------------------------------------------- 手机控制服务（3a）

    private void startControlServer() {
        if (controlServer != null) {
            return;
        }
        appVersion = versionName();
        tokenStore = new TokenStore(getFilesDir());
        if (controlApi == null) {
            controlApi = new SourceApi(new ControlHost());
        }
        // 先起服务再绑定快照：首帧请求（页面）不依赖 provider 是否就绪。
        controlServer = new SimpleHttpServer(controlApi, tokenStore, new ControlAssets());
        int port = controlServer.startServer();
        controlServer.setStateProvider(controlStateProvider);
        if (port < 0) {
            Log.w(TAG, "手机控制服务启动失败：端口 18088-18098 均被占用");
        } else {
            Log.i(TAG, "手机控制服务已启动，端口 " + port);
        }
        refreshControlInfo();
    }

    private void stopControlServer() {
        if (controlServer == null) {
            return;
        }
        controlServer.shutdown();
        controlServer = null;
        controlAddress = null;
        controlToken = null;
    }

    /** 手机控制地址 http://IP:端口；服务未启动或没有 WiFi 时返回 null。 */
    private String controlAddress() {
        if (controlServer == null || controlServer.port() <= 0) {
            return null;
        }
        String ip = LanAddress.ipv4();
        return ControlAddress.page(ip, controlServer.port());
    }

    /**
     * UI 线程只读已生成的 PIN；未生成时返回空并触发后台生成。
     *
     * <p>首次生成要读/写 {@code control_pin.txt}，不能让 refreshControlInfo
     * （启动、onStart、打开设置页）在主线程碰磁盘。</p>
     */
    private String controlPinOrRequest() {
        if (tokenStore == null) {
            return "";
        }
        String cached = tokenStore.cachedPin();
        if (cached != null) {
            return cached;
        }
        requestControlPinInBackground();
        return "";
    }

    private void requestControlPinInBackground() {
        if (controlPinLoading || tokenStore == null) {
            return;
        }
        controlPinLoading = true;
        final TokenStore store = tokenStore;
        try {
            controlIo.execute(new Runnable() {
                @Override
                public void run() {
                    store.pin();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            controlPinLoading = false;
                            if (tokenStore != store || isFinishing() || isDestroyed()) {
                                return;
                            }
                            refreshControlInfo();
                        }
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            controlPinLoading = false;
        }
    }

    /** 二维码 token 过 10 分钟自动轮换；IP/端口变化时重算地址。 */
    private String controlQrUrl() {
        if (controlServer == null || controlServer.port() <= 0 || tokenStore == null) {
            return null;
        }
        String ip = LanAddress.ipv4();
        if (!ControlAddress.available(ip, controlServer.port())) {
            return null;
        }
        ensureControlToken();
        if (controlToken == null) {
            return null;
        }
        return ControlAddress.qr(ip, controlServer.port(), controlToken);
    }

    /** 进入设置页：复用仍有效的二维码 token，没有/已过期才新签。 */
    private void ensureControlToken() {
        if (controlServer == null || controlServer.port() <= 0 || tokenStore == null) {
            return;
        }
        String current = tokenStore.currentToken();
        controlToken = current != null ? current : tokenStore.issueToken();
    }

    private void refreshControlInfo() {
        controlLanIp = LanAddress.ipv4();
        controlAddress = controlAddress();
        String pin = controlPinOrRequest();
        String qr = controlQrUrl();
        if (ui.isSettings()) {
            settingsView().setControlInfo(controlAddress, pin, qr);
        }
        if (ui.isSetup()) {
            setupView().setControlInfo(controlAddress, pin, qr);
        }
    }

    /** 网络切换后 IP 可能变化：变了才刷新设置页/首启页显示。 */
    private void refreshControlInfoIfChanged() {
        String nextIp = LanAddress.ipv4();
        boolean changed = nextIp == null ? controlLanIp != null : !nextIp.equals(controlLanIp);
        if (changed || ui.isSettings() || ui.isSetup()) {
            refreshControlInfo();
        }
    }

    private void registerNetworkReceiver() {
        if (controlReceiverRegistered) {
            return;
        }
        try {
            registerReceiver(networkReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            controlReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "监听网络变化失败：" + e.getMessage());
        }
    }

    private void unregisterNetworkReceiver() {
        if (!controlReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(networkReceiver);
        } catch (RuntimeException ignored) {
            // Activity 已销毁/未注册，忽略。
        }
        controlReceiverRegistered = false;
    }

    /** 重载完成后刷新手机页快照：只拷贝内存元数据，不解析源文件。 */
    private void updateControlSnapshot(MultiPlaylist result, boolean silent) {
        Playlist loaded = result == null ? null : result.playlist();
        if (loaded != null) {
            controlChannels = snapshotChannels(loaded);
        } else if (!silent) {
            controlChannels = Collections.emptyList();
        }
        if (!sourcesReady) {
            controlSnapshot = null;
            return;
        }
        int channels;
        if (result != null && result.playlist() != null) {
            channels = result.playlist().channelCount();
        } else if (silent) {
            // 静默重载没有可用频道时保留旧列表，快照也保留旧的台数。
            StateProvider.Snapshot previous = controlSnapshot;
            channels = previous == null ? 0 : previous.channels();
        } else {
            channels = 0;
        }
        List<StateProvider.SourceEntry> entries =
                new ArrayList<StateProvider.SourceEntry>(sourceStates.size());
        for (SourceState state : sourceStates) {
            SourceRecord record = state.record();
            entries.add(new StateProvider.SourceEntry(record.id(), record.name(),
                    record.kind().name(), record.enabled(), record.order(),
                    state.channelCount(), state.error(),
                    record.isUrl() ? record.location() : null));
        }
        Channel current = playingChannel();
        controlSnapshot = new StateProvider.Snapshot(true, channels,
                current == null ? null : current.id(),
                current == null ? null : current.name(),
                sourceFatalError, entries);
    }

    /** 换台后同步快照里的当前台（只替换字段，不重解析）。 */
    private void refreshControlSnapshotCurrent() {
        StateProvider.Snapshot snapshot = controlSnapshot;
        if (snapshot == null || !snapshot.ready()) {
            return;
        }
        Channel current = playingChannel();
        String id = current == null ? null : current.id();
        boolean same = id == null ? snapshot.currentId() == null : id.equals(snapshot.currentId());
        if (same) {
            return;
        }
        controlSnapshot = snapshot.withCurrent(id, current == null ? null : current.name());
    }

    /** 手机页写操作成功：主线程重新合并列表并刷新设置页；加载中则排队一次。 */
    private void onControlSourcesChanged() {
        if (loading) {
            controlReloadPending = true;
            return;
        }
        suppressReloadToast = true;
        reloadPlaylist();
    }

    /** 频道快照：只拷贝 id/名称/分组/源名，HTTP 线程只读。 */
    private static List<SourceApi.ChannelRef> snapshotChannels(Playlist playlist) {
        List<Channel> channels = playlist == null ? null : playlist.channels();
        if (channels == null || channels.isEmpty()) {
            return Collections.emptyList();
        }
        List<SourceApi.ChannelRef> refs = new ArrayList<SourceApi.ChannelRef>(channels.size());
        for (Channel channel : channels) {
            if (channel == null) {
                continue;
            }
            refs.add(new SourceApi.ChannelRef(channel.id(), channel.name(),
                    channel.group(), channel.sourceName()));
        }
        return Collections.unmodifiableList(refs);
    }

    /**
     * 手机点搜索结果切台（主线程）：隐藏态直接播、全屏保持全屏；菜单/设置/首启先关掉。
     */
    private void playFromPhone(String id) {
        if (navigator == null) {
            return;
        }
        Channel target = navigator.byId(id);
        if (target == null) {
            return;
        }
        boolean wasOverlay = ui.isSettings() || ui.isSetup() || ui.isMenuOpen();
        if (ui.isMenuOpen()) {
            closeMenu();
        }
        if (ui.isSettings()) {
            closeSettings();
        }
        if (ui.isSetup()) {
            hideSetup();
        }
        boolean fullscreen = ui.isFullscreen();
        ui.setActionColumnVisible(false);
        if (fullscreen) {
            applyModeToLayout();
        } else if (!ui.isNavShown() || wasOverlay) {
            ui.hideNav();
            applyModeToLayout();
            overlays.updateClock(false);
        }
        playingChannelId = target.id();
        focusChannelInList(target);
        playChannel(target, true, false);
        showChannelOsd(target);
        refreshControlSnapshotCurrent();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "0.1.0";
        } catch (RuntimeException e) {
            return "0.1.0";
        }
    }

    private final class ControlHost implements SourceApi.Host {

        @Override
        public java.io.File filesDir() {
            return getFilesDir();
        }

        @Override
        public String appVersion() {
            return appVersion;
        }

        @Override
        public void requestReload() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    onControlSourcesChanged();
                }
            });
        }

        @Override
        public List<SourceApi.ChannelRef> channels() {
            return controlChannels;
        }

        @Override
        public boolean play(String id) {
            List<SourceApi.ChannelRef> channels = controlChannels;
            if (id == null || channels == null) {
                return false;
            }
            boolean found = false;
            for (SourceApi.ChannelRef channel : channels) {
                if (id.equals(channel.id())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    playFromPhone(id);
                }
            });
            return true;
        }
    }

    private final class ControlAssets implements SimpleHttpServer.AssetSource {

        @Override
        public byte[] read(String path) throws IOException {
            InputStream input = getAssets().open(path);
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } finally {
                input.close();
            }
        }
    }

    // ------------------------------------------------------------- 源管理动作

    private List<SourceRecord> replaceSource(SourceRecord updated) {
        List<SourceRecord> next = new ArrayList<SourceRecord>(sourceRecords.size());
        for (SourceRecord record : sourceRecords) {
            next.add(record.id().equals(updated.id()) ? updated : record);
        }
        return next;
    }

    private int indexOfSource(String id) {
        for (int i = 0; i < sourceRecords.size(); i++) {
            if (sourceRecords.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 保存源列表（后台写 sources.json），成功后重读缓存并合并。
     */
    private void applySources(final List<SourceRecord> next, final String successToast) {
        final List<SourceRecord> renumbered = SourceStore.renumber(next);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    SourceStore.save(getFilesDir(), renumbered);
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
                final String reason = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reason != null) {
                            overlays.showToast(getString(
                                    R.string.settings_source_save_failed, reason), SOURCE_NOTE_MS);
                            refreshSettingsSources();
                            return;
                        }
                        sourceRecords = renumbered;
                        if (successToast != null) {
                            overlays.showToast(successToast, TOAST_SHORT_MS);
                        }
                        suppressReloadToast = true;
                        reloadPlaylist();
                    }
                });
            }
        }, "source-save").start();
    }

    private void deleteSource(final SourceRecord record) {
        final boolean last = sourceRecords.size() <= 1;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    SourceStore.deleteSource(getFilesDir(), record.id());
                    if (last) {
                        SourceStore.clearAll(getFilesDir());
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
                final String reason = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (reason != null) {
                            overlays.showToast(getString(
                                    R.string.settings_source_save_failed, reason), SOURCE_NOTE_MS);
                            refreshSettingsSources();
                            return;
                        }
                        if (last) {
                            prefs.setLastChannelId(null);
                            sourceRecords = new ArrayList<SourceRecord>();
                            sourceStates = Collections.emptyList();
                            sourcesReady = true;
                            sourceFatalError = null;
                            listEmptyOverride = null;
                            closeSettings();
                            showSourceUnavailable(null);
                            overlays.showToast(getString(R.string.settings_source_deleted_last),
                                    TOAST_SHORT_MS);
                            return;
                        }
                        overlays.showToast(getString(R.string.settings_source_deleted,
                                record.name()), TOAST_SHORT_MS);
                        suppressReloadToast = true;
                        reloadPlaylist();
                    }
                });
            }
        }, "source-delete").start();
    }

    /** URL 源重载：下载 → 校验 → 保存缓存；失败保留旧缓存。 */
    private void reloadUrlSource(final SourceRecord record) {
        overlays.showToast(getString(R.string.settings_source_reloading, record.name()),
                SOURCE_NOTE_MS);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    byte[] data = SourceDownloader.download(record.location());
                    PlaylistParser.parse(data, record.location());
                    SourceStore.writeCacheFile(getFilesDir(), record, data);
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (PlaylistParseException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
                final String reason = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onUrlSourceReloaded(record, reason);
                    }
                });
            }
        }, "source-download").start();
    }

    private void onUrlSourceReloaded(SourceRecord record, String error) {
        long now = System.currentTimeMillis();
        List<SourceRecord> next = new ArrayList<SourceRecord>(sourceRecords);
        for (int i = 0; i < next.size(); i++) {
            SourceRecord current = next.get(i);
            if (!current.id().equals(record.id())) {
                continue;
            }
            next.set(i, error == null
                    ? current.withLastUpdated(now).withLastError(null)
                    : current.withLastError(error));
        }
        sourceRecords = next;
        if (error == null) {
            overlays.showToast(getString(R.string.settings_source_reloaded, record.name()),
                    TOAST_SHORT_MS);
            // 先落盘再重载，避免旧状态写回覆盖新的 channelCount。
            persistSourcesThenReload(next);
        } else {
            persistSourcesQuietly(next);
            overlays.showToast(getString(R.string.settings_source_reload_failed, error),
                    SOURCE_NOTE_MS);
            refreshSettingsSources();
        }
    }

    private void persistSourcesThenReload(final List<SourceRecord> records) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SourceStore.save(getFilesDir(), records);
                } catch (IOException ignored) {
                    // 即使状态写回失败也继续重读缓存。
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        suppressReloadToast = true;
                        reloadPlaylist();
                    }
                });
            }
        }, "source-persist-reload").start();
    }

    /** 轻量状态写回（频道数/错误），失败不提示。 */
    private void persistSourcesQuietly(final List<SourceRecord> records) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SourceStore.save(getFilesDir(), records);
                } catch (IOException ignored) {
                    // 状态写回失败只影响下次启动的显示。
                }
            }
        }, "source-persist").start();
    }

    private void promptRenameSource(final SourceRecord record) {        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(record.name());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_source_rename_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            overlays.showToast(getString(R.string.settings_source_name_empty),
                                    TOAST_SHORT_MS);
                            return;
                        }
                        applySources(replaceSource(record.withName(name)),
                                getString(R.string.settings_source_renamed, name));
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 「改地址」：预填当前 URL；FILE 源留空，保存后转为 URL 源。 */
    private void promptChangeLocation(final SourceRecord record) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.setup_url_dialog_hint);
        if (record.isUrl()) {
            input.setText(record.location());
            input.setSelection(input.getText().length());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_source_location_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        if (url.isEmpty()) {
                            overlays.showToast(getString(R.string.setup_url_empty),
                                    TOAST_SHORT_MS);
                            return;
                        }
                        changeSourceLocation(record, url);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 后台下载/校验新地址，不阻塞设置页；成功后 Host.requestReload 刷新列表。 */
    private void changeSourceLocation(final SourceRecord record, final String url) {
        final SourceApi api = controlApi;
        if (api == null) {
            overlays.showToast(getString(R.string.settings_source_location_failed,
                    getString(R.string.settings_control_offline)), SOURCE_NOTE_MS);
            return;
        }
        overlays.showToast(getString(R.string.settings_source_location_running),
                TOAST_SHORT_MS);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SourceApi.Result result = api.changeLocation(record.id(), url);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result.ok()) {
                            overlays.showToast(getString(R.string.settings_source_location_done),
                                    SOURCE_NOTE_MS);
                            return;
                        }
                        overlays.showToast(getString(R.string.settings_source_location_failed,
                                result.errorMessage()), SOURCE_NOTE_MS);
                    }
                });
            }
        }, "source-location").start();
    }

    private void confirmDeleteSource(final SourceRecord record) {        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.settings_source_delete_confirm, record.name()))
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteSource(record);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void moveSource(SourceRecord record, int delta) {
        int index = indexOfSource(record.id());
        int target = index + delta;
        if (index < 0 || target < 0 || target >= sourceRecords.size()) {
            overlays.showToast(getString(R.string.settings_source_move_edge), TOAST_SHORT_MS);
            return;
        }
        List<SourceRecord> next = new ArrayList<SourceRecord>(sourceRecords);
        SourceRecord other = next.get(target);
        next.set(target, next.get(index));
        next.set(index, other);
        applySources(next, null);
    }

    private final SettingsView.Listener settingsListener = new SettingsView.Listener() {
        @Override
        public void onReloadSources() {
            if (loading) {
                overlays.showToast(getString(R.string.source_loading), TOAST_SHORT_MS);
                return;
            }
            suppressReloadToast = false;
            reloadPlaylist();
        }

        @Override
        public void onAddSource() {
            String address = controlAddress;
            overlays.showToast(getString(R.string.settings_source_add_later,
                    address == null ? getString(R.string.settings_control_offline) : address),
                    SOURCE_NOTE_MS);
        }

        @Override
        public void onToggleSource(SourceRecord record) {
            boolean enabled = !record.enabled();
            applySources(replaceSource(record.withEnabled(enabled)),
                    getString(enabled ? R.string.settings_source_enabled
                            : R.string.settings_source_disabled, record.name()));
        }

        @Override
        public void onReloadSource(SourceRecord record) {
            if (record.isUrl()) {
                reloadUrlSource(record);
                return;
            }
            overlays.showToast(getString(R.string.settings_source_reloaded, record.name()),
                    TOAST_SHORT_MS);
            suppressReloadToast = true;
            reloadPlaylist();
        }

        @Override
        public void onRenameSource(SourceRecord record) {
            promptRenameSource(record);
        }

        @Override
        public void onChangeSourceLocation(SourceRecord record) {
            promptChangeLocation(record);
        }

        @Override
        public void onMoveSource(SourceRecord record, int delta) {
            moveSource(record, delta);
        }

        @Override
        public void onDeleteSource(SourceRecord record) {
            confirmDeleteSource(record);
        }

        @Override
        public void onToggleBoot(boolean enabled) {
            toggleBootAutostart(enabled);
        }

        @Override
        public void onOpenSystemHome() {
            if (!HomeController.launchSystemHome(MainActivity.this)) {
                overlays.showToast(getString(R.string.settings_system_home_missing),
                        SOURCE_NOTE_MS);
            }
        }

        @Override
        public void onSwitchModeChanged(int mode) {
            SwitchMode[] modes = SwitchMode.values();
            playbackSettings.setSwitchMode(modes[Math.max(0, Math.min(modes.length - 1, mode))]);
            applyPlaybackSettings();
        }

        @Override
        public void onRenderModeChanged(int mode) {
            RenderMode[] modes = RenderMode.values();
            playbackSettings.setRenderMode(modes[Math.max(0, Math.min(modes.length - 1, mode))]);
            applyPlaybackSettings();
        }

        @Override
        public void onFirstFrameBoostChanged(boolean enabled) {
            playbackSettings.setFirstFrameBoost(enabled);
            applyPlaybackSettings();
        }

        @Override
        public void onTransitionCardChanged(boolean enabled) {
            playbackSettings.setTransitionCard(enabled);
            applyPlaybackSettings();
        }

        @Override
        public void onShowSpeedChanged(boolean enabled) {
            playbackSettings.setShowSpeed(enabled);
            applyPlaybackSettings();
        }

        @Override
        public void onAspectChanged(int aspect) {
            Aspect[] values = Aspect.values();
            playbackSettings.setAspect(values[Math.max(0, Math.min(values.length - 1, aspect))]);
            applyDisplaySettings();
        }

        @Override
        public void onNavWidthChanged(int width) {
            NavWidth[] values = NavWidth.values();
            playbackSettings.setNavWidth(values[Math.max(0, Math.min(values.length - 1, width))]);
            applyDisplaySettings();
        }

        @Override
        public void onAutoHideChanged(int seconds) {
            playbackSettings.setAutoHide(AutoHide.fromSeconds(seconds));
            applyDisplaySettings();
        }

        @Override
        public void onClockBadgeChanged(boolean enabled) {
            playbackSettings.setClockBadge(enabled);
            if (!enabled) {
                // 关掉时立即隐藏，不等下一次 tick（时钟只在整/半点附近才可见）。
                overlays.updateClock(false);
            } else {
                updateClockVisibility();
            }
        }

        @Override
        public void onAdvancedExpandedChanged(boolean expanded) {
            playbackSettings.setAdvancedOpen(expanded);
        }

        @Override
        public void onSwitchModeHintShown() {
            playbackSettings.markSwitchModeHintSeen();
        }
    };

    // ------------------------------------------------------------------ 源加载

    private void loadPlaylist(final boolean initial) {
        if (loading) {
            return;
        }
        loading = true;
        if (initial) {
            overlays.showLoading(getString(R.string.source_loading));
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final MultiPlaylist result = PlaylistRepository.loadAll(getFilesDir());
                // 源未变时回前台应为 cache 命中（parses 不增长），用于现场核对。
                Log.d(TAG, "loadAll sources=" + result.sources().size()
                        + " failed=" + result.failedCount() + " "
                        + PlaylistRepository.cacheStats());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean silentNow = silentReload;
                        onPlaylistLoaded(result, initial);
                        updateControlSnapshot(result, silentNow);
                        // 手机页写操作在加载期间到达：这次结束后补一次，避免刚导入的源不显示。
                        if (controlReloadPending) {
                            controlReloadPending = false;
                            suppressReloadToast = true;
                            reloadPlaylist();
                        }
                    }
                });
            }
        }, "playlist-load").start();
    }

    private void onPlaylistLoaded(MultiPlaylist result, boolean initial) {
        loading = false;
        sourcesReady = true;
        sourceStates = result.sources();
        sourceRecords = result.records();
        sourceFatalError = result.fatalError();
        if (initial) {
            overlays.hideLoading();
        }
        if (ui.isSettings()) {
            if (sourceFatalError != null) {
                settingsView().setSourceError(sourceFatalError);
            } else {
                settingsView().setSources(sourceStates);
            }
        }

        boolean silent = silentReload;
        silentReload = false;
        boolean suppressToast = suppressReloadToast;
        suppressReloadToast = false;
        // 开机拉起：首次起播缓 3 秒；只影响这一次 initial 加载，按键可提前。
        final boolean deferBootPlayback =
                initial && BootLaunchPlan.shouldDeferPlayback(bootFromLaunch);

        Playlist loaded = result.playlist();
        if (loaded == null || loaded.channels().isEmpty()) {
            if (silent) {
                Log.w(TAG, "静默重载没有可用频道，保留当前列表");
                resumePlaybackIfPending();
                return;
            }
            if (!result.hasSources()) {
                // 真·无源：首启设置页；索引损坏在 detail 里说明。
                showSourceUnavailable(result.fatalError() == null
                        ? null : getString(R.string.empty_load_detail, result.fatalError()));
                resumePlaybackIfPending();
                return;
            }
            // 有源但全部停用/全部失败：留在列表（导航可见），不弹回首启页，设置仍可达。
            showSourcesUnavailable(result);
            resumePlaybackIfPending();
            return;
        }

        listEmptyOverride = null;
        playlist = loaded;
        navigator = new ChannelNavigator(loaded.channels(), loaded.groups());
        failureStreak.reset();
        overlays.hideErrorBadge();
        buildGroups();
        hideSetup();

        String lastId = prefs.lastChannelId();
        Channel target = reloadTargetId != null ? navigator.byId(reloadTargetId) : null;
        if (target == null && playingChannelId != null) {
            target = navigator.byId(playingChannelId);
        }
        if (target == null) {
            target = navigator.byId(lastId);
        }
        if (target == null) {
            target = navigator.first();
        }
        reloadTargetId = null;

        if (initial) {
            playingChannelId = target != null ? target.id() : null;
            focusChannelInList(target);
            if (phoneDevice) {
                // 手机（竖屏/横屏）列表常驻：进来就是「列表 + 预览」，没有隐藏导航态。
                updateAutoHideBlock();
                ui.showNav(SystemClock.elapsedRealtime());
                applyModeToLayout();
            } else {
                // 2g：有上次台播上次台，没有上次台播第一台；两种都直接进画面、导航默认隐藏。
                ui.hideNav();
                applyModeToLayout();
                overlays.updateClock(false);
            }
            if (target != null) {
                if (deferBootPlayback) {
                    deferBootPlayback(target);
                } else {
                    playChannel(target, false, false);
                }
            } else {
                // 兜底（空列表已提前返回）：至少显示导航，避免纯黑。
                updateAutoHideBlock();
                ui.showNav(SystemClock.elapsedRealtime());
                applyModeToLayout();
            }
        } else {
            playingChannelId = target != null ? target.id() : null;
            focusChannelInList(target);
            ui.setActionColumnVisible(false);
            applyModeToLayout();
            // 同一台在播时保持不打断；换台/从占位恢复才起播。
            playChannel(target, true, false);
            if (!silent && !suppressToast) {
                overlays.showToast(getString(R.string.reload_done), TOAST_SHORT_MS);
            }
        }

        // 重载已按 reloadTargetId/playingChannelId 恢复或保持了播放：同一台在播时
        // resumePlaybackIfPending() 会 SKIP_SAME，不会再起一次解码器。
        if (!silent) {
            showSourceProblems(result);
        }
        resumePlaybackIfPending();
    }

    /**
     * onStart 的恢复：等静默重载落定后再起播。
     * 同一台已被重载起播时跳过，force=false，只起一次解码器。
     */
    private void resumePlaybackIfPending() {
        String id = startupResume.pendingId();
        Channel channel = navigator == null ? null : navigator.byId(id);
        Channel active = controller == null ? null : controller.getCurrentChannel();
        StartupResume.Action action = startupResume.settle(paused, channel != null,
                active == null ? null : active.id());
        switch (action) {
            case PLAY:
                Log.i(TAG, "resume after reload: " + id);
                playChannel(channel, false, false);
                return;
            case SKIP_SAME:
                Log.i(TAG, "resume skipped: already playing " + id);
                return;
            case DROP:
                Log.i(TAG, "resume skipped: channel gone or backgrounded " + id);
                return;
            default:
                // NONE：没有待恢复台。
        }
    }

    /**
     * 开机拉起：首个频道缓 3 秒起播（{@link BootLaunchPlan}）。
     *
     * <p>开机瞬间解码器/内存最紧张，先让桌面和系统稳定；等待期内用户按任意键
     * 由 {@link #startDeferredPlayback(String)} 立即起播。</p>
     */
    private void deferBootPlayback(Channel target) {
        bootDeferredChannel = target;
        handler.removeCallbacks(bootDeferredPlayRunnable);
        handler.postDelayed(bootDeferredPlayRunnable, BootLaunchPlan.BOOT_PLAYBACK_DELAY_MS);
        // onPlaylistLoaded 已把 loading 关掉，这里补回占位，缓起播期间不出现纯黑。
        overlays.showLoading(getString(R.string.source_loading));
        Log.i(TAG, "BootLaunch playback deferred " + BootLaunchPlan.BOOT_PLAYBACK_DELAY_MS
                + "ms channel=" + target.id());
    }

    private final Runnable bootDeferredPlayRunnable = new Runnable() {
        @Override
        public void run() {
            startDeferredPlayback("timer");
        }
    };

    /** 结束缓起播窗口并起播：timer（3 秒到）/ key（用户按键）/ 其它诊断原因。 */
    private void startDeferredPlayback(String reason) {
        Channel target = bootDeferredChannel;
        if (target == null) {
            return;
        }
        bootDeferredChannel = null;
        handler.removeCallbacks(bootDeferredPlayRunnable);
        Log.i(TAG, "BootLaunch playback start reason=" + reason + " channel=" + target.id());
        playChannel(target, false, false);
    }

    /** 进后台就放弃缓起播：回到前台走 onStart 的恢复流程，避免延迟任务在后台起解码器。 */
    private void cancelDeferredPlayback() {
        if (bootDeferredChannel == null) {
            return;
        }
        bootDeferredChannel = null;
        handler.removeCallbacks(bootDeferredPlayRunnable);
        Log.i(TAG, "BootLaunch playback cancelled (backgrounded)");
    }

    /** 部分源失败/超限：顶部提示；单源失败不阻塞其余源。 */
    private void showSourceProblems(MultiPlaylist result) {
        if (result.failedCount() > 0) {
            overlays.showToast(getString(R.string.source_partial_failed,
                    result.failedCount(), result.sources().size()), SOURCE_NOTE_MS);
            return;
        }
        if (!result.warnings().isEmpty()) {
            overlays.showToast(result.warnings().get(0), SOURCE_NOTE_MS);
            return;
        }
        showSkippedIfAny(result.skipped());
    }

    /** 有源、但没有任何可用频道（全部停用或全部读取失败）。 */
    private void showSourcesUnavailable(MultiPlaylist result) {
        String reason;
        if (result.fatalError() != null) {
            reason = getString(R.string.empty_load_detail, result.fatalError());
        } else if (result.enabledCount() == 0) {
            reason = getString(R.string.settings_sources_none_enabled);
        } else {
            String detail = null;
            for (SourceState state : result.sources()) {
                if (state.error() != null) {
                    detail = state.error();
                    break;
                }
            }
            reason = getString(R.string.settings_sources_all_failed,
                    detail == null ? "" : detail);
        }
        clearPlaylistState();
        hideSetup();
        updateAutoHideBlock();
        ui.showNav(SystemClock.elapsedRealtime());
        applyModeToLayout();
        listEmptyOverride = reason;
        updateListState();
        overlays.showToast(reason, SOURCE_NOTE_MS);
    }

    /** 源里有非 http 地址时提示一次（不当作频道，也不静默吞掉）。 */
    private void showSkippedIfAny(int skipped) {
        if (skipped > 0) {
            overlays.showToast(getString(R.string.skipped_sources, skipped), SOURCE_NOTE_MS);
        }
    }

    private void showSourceUnavailable(String detail) {
        listEmptyOverride = null;
        clearPlaylistState();
        showSetup(detail);
    }

    /** 清空列表/播放状态，但不切换页面（首启页或空列表由调用方决定）。 */
    private void clearPlaylistState() {
        endLoading();
        playlist = null;
        navigator = null;
        playingChannelId = null;
        groupNames.clear();
        barSpecs.clear();
        subGroupFilter = null;
        barOtherExpanded = false;
        visibleChannels.clear();
        focusIndex = -1;
        groupIndex = 0;
        groupFocusIndex = 0;
        ui.hidePlaybackScene();
        closeMenu();
        hideFullscreenChrome();
        overlays.hideToast();
        overlays.hideErrorBadge();
        overlays.hideVolume();
        if (playback != null) {
            playback.stop();
            controller = playback.active();
        }
        applyModeToLayout();
        clearNow();
        setGroupItems(new ArrayList<GroupBar.Item>());
        setGroupState(-1, -1, false);
        hideListEmpty();
        adapter.setData(new ArrayList<Channel>(), favorites);
        adapter.setFocusIndex(-1);
        adapter.setPlayingId(null);
        updateActionColumn();
    }

    private void buildGroups() {
        groupNames.clear();
        groupNames.add(getString(R.string.group_favorites));
        if (playlist != null) {
            for (Group group : playlist.groups()) {
                groupNames.add(group.name());
            }
        }
        if (groupIndex < 0 || groupIndex >= groupNames.size()) {
            groupIndex = 0;
        }
        // 重新加载源后「其他」回到「全部」子组。
        subGroupFilter = null;
        barOtherExpanded = false;
        refreshGroupBar();
        int barIndex = barIndexForGroup(groupIndex);
        groupFocusIndex = barIndex >= 0 ? barIndex : 0;
        updateGroupBarState();
    }

    private void reloadPlaylist() {
        reloadTargetId = playingChannelId != null
                ? playingChannelId
                : (focusChannel() != null ? focusChannel().id() : prefs.lastChannelId());
        loadPlaylist(false);
    }

    // ------------------------------------------------------------------ 焦点 / 播放

    /** 首次进列表 / 全屏返回 / 数字/CH 定位：切到频道所在组并移动焦点，不播放。 */
    private void focusChannelInList(Channel channel) {
        if (channel == null || navigator == null) {
            focusIndex = -1;
            adapter.setFocusIndex(-1);
            updateListState();
            return;
        }
        groupIndex = groupIndexFor(channel);
        // 定位具体频道时回到「全部」，否则目标可能被当前子组过滤掉。
        subGroupFilter = null;
        refreshGroupBarIfExpansionChanged();
        rebuildVisibleChannels();
        int index = indexOfChannel(visibleChannels, channel.id());
        if (index < 0 && !visibleChannels.isEmpty()) {
            // 兜底：分组对不上时至少保证焦点落在当前组第一台。
            index = 0;
        }
        focusIndex = index;
        ui.setFocusInGroups(false);
        ui.setFocusInActions(false);
        adapter.setFocusIndex(focusIndex);
        updateListState();
        if (focusIndex >= 0) {
            ensureChannelVisible(focusIndex);
        }
    }

    private int groupIndexFor(Channel channel) {
        if (channel == null || navigator == null) {
            return groupNames.isEmpty() ? -1 : 0;
        }
        int dataIndex = navigator.groupIndexOf(channel);
        return dataIndex >= 0 ? dataIndex + 1 : 0;
    }

    private void rebuildVisibleChannels() {
        visibleChannels.clear();
        visibleChannels.addAll(channelsForGroup(groupIndex));
        if (focusIndex >= visibleChannels.size()) {
            focusIndex = visibleChannels.size() - 1;
        }
        if (focusIndex < -1) {
            focusIndex = -1;
        }
        adapter.setData(visibleChannels, favorites);
        adapter.setFocusIndex(focusIndex);
    }

    /** 组筛选：0 为收藏虚拟组，其余按数据组名；「其他」再按子组过滤。不改动 adapter。 */
    private List<Channel> channelsForGroup(int index) {
        if (navigator == null) {
            return Collections.emptyList();
        }
        if (index == FAVORITES_GROUP) {
            return navigator.favorites(favorites);
        }
        if (index < 0 || index >= groupNames.size()) {
            return Collections.emptyList();
        }
        String name = groupNames.get(index);
        List<Channel> members = navigator.group(name);
        if (!ChannelSet.GROUP_OTHER.equals(name) || subGroupFilter == null) {
            return members;
        }
        List<Channel> filtered = new ArrayList<Channel>();
        for (Channel channel : members) {
            if (subGroupFilter.equals(ChannelSet.subGroupName(channel))) {
                filtered.add(channel);
            }
        }
        return filtered;
    }

    /** 焦点/组/播放点变化后统一刷新左栏、预览信息与底部提示。 */
    private void updateListState() {
        adapter.setPlayingId(playingChannelId);
        // 焦点在组栏时焦点样式只留在组栏；列表行不再显示焦点（内部 focusIndex 保留）。
        adapter.setFocusVisible(!ui.isFocusInGroups());
        updateGroupBarState();
        updateNowInfo();
        updateActionColumn();
        updateListHint();
        updateListEmpty();
        updatePhoneChrome();
    }

    /**
     * 手机主界面底栏「设置」入口：手机、非全屏、非设置/首启页才显示；
     * 电视恒不显示（遥控仍走 MENU）。布局内部做「值不变不动视图」，可以随状态刷新调用。
     */
    private void updatePhoneChrome() {
        setSettingsEntry(PhoneChrome.settingsEntryVisible(phoneDevice,
                ui.isFullscreen(), ui.isSettings(), ui.isSetup()), settingsEntryAction);
    }

    private void updateGroupBarState() {
        if (navigator == null || barSpecs.isEmpty()) {
            setGroupState(-1, -1, false);
            return;
        }
        setGroupState(activeBarIndex(), groupFocusIndex, ui.isFocusInGroups(),
                playingBarIndexes());
    }

    /** 播放台所在组栏项：收藏组、数据组一级项，以及「其他」展开时对应的子组项。 */
    private int[] playingBarIndexes() {
        Channel playing = playingChannel();
        if (playing == null || navigator == null) {
            return new int[0];
        }
        List<Integer> indexes = new ArrayList<Integer>(2);
        if (favorites.contains(playing.id())) {
            indexes.add(Integer.valueOf(0));
        }
        int dataIndex = navigator.groupIndexOf(playing);
        if (dataIndex >= 0) {
            int group = dataIndex + 1;
            int barIndex = barIndexForGroup(group);
            if (barIndex >= 0 && (indexes.isEmpty() || indexes.get(0).intValue() != barIndex)) {
                indexes.add(Integer.valueOf(barIndex));
            }
            if (ChannelSet.GROUP_OTHER.equals(playing.group())) {
                int subIndex = barIndexForSubGroup(ChannelSet.subGroupName(playing));
                if (subIndex >= 0) {
                    indexes.add(Integer.valueOf(subIndex));
                }
            }
        }
        int[] result = new int[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) {
            result[i] = indexes.get(i).intValue();
        }
        return result;
    }

    /** 列表当前筛选对应的组栏 active 项；「其他」优先落在「全部」/当前子组。 */
    private int activeBarIndex() {
        if (barSpecs.isEmpty()) {
            return -1;
        }
        if (groupIndex == otherGroupIndex()) {
            int subIndex = barIndexForSubGroup(subGroupFilter);
            if (subIndex >= 0) {
                return subIndex;
            }
        }
        return barIndexForGroup(groupIndex);
    }

    /** 一级组项在组栏的下标；没有返回 -1。 */
    private int barIndexForGroup(int dataGroupIndex) {
        for (int i = 0; i < barSpecs.size(); i++) {
            BarSpec spec = barSpecs.get(i);
            if (!spec.subGroup && spec.groupIndex == dataGroupIndex) {
                return i;
            }
        }
        return -1;
    }

    /** 「其他」子组项下标；name 为 null 时找「全部」。 */
    private int barIndexForSubGroup(String name) {
        for (int i = 0; i < barSpecs.size(); i++) {
            BarSpec spec = barSpecs.get(i);
            if (spec.subGroup && eq(spec.subFilter, name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private int otherGroupIndex() {
        if (navigator == null) {
            return -1;
        }
        int index = navigator.groups().indexOf(ChannelSet.GROUP_OTHER);
        return index < 0 ? -1 : index + 1;
    }

    /** 重建组栏；数量变化 / 源重载 / 收藏变化时调用。 */
    private void refreshGroupBar() {
        List<GroupBar.Item> items = new ArrayList<GroupBar.Item>();
        barSpecs.clear();

        int favoriteCount = navigator == null
                ? favorites.size() : navigator.favorites(favorites).size();
        items.add(new GroupBar.Item(getString(R.string.group_favorites), favoriteCount));
        barSpecs.add(new BarSpec(getString(R.string.group_favorites), favoriteCount,
                0, false, null));

        boolean expandOther = groupIndex == otherGroupIndex();
        barOtherExpanded = expandOther;
        if (navigator != null) {
            for (int i = 0; i < navigator.groups().size(); i++) {
                String name = navigator.groups().get(i);
                int count = navigator.groupCount(name);
                items.add(new GroupBar.Item(name, count));
                barSpecs.add(new BarSpec(name, count, i + 1, false, null));
                if (expandOther && ChannelSet.GROUP_OTHER.equals(name)) {
                    items.add(new GroupBar.Item(getString(R.string.group_all), count, true));
                    barSpecs.add(new BarSpec(getString(R.string.group_all), count,
                            i + 1, true, null));
                    for (ChannelSet.SubGroup sub : ChannelSet.subGroups(
                            playlist != null ? playlist.channels() : navigator.channels(),
                            ChannelSet.GROUP_OTHER)) {
                        String label = ChannelSet.subGroupLabel(sub.name());
                        items.add(new GroupBar.Item(label, sub.count(), true));
                        barSpecs.add(new BarSpec(label, sub.count(), i + 1, true, sub.name()));
                    }
                }
            }
        }
        if (groupFocusIndex < 0 || groupFocusIndex >= barSpecs.size()) {
            groupFocusIndex = Math.max(0, barIndexForGroup(groupIndex));
        }
        setGroupItems(items);
        updateGroupBarState();
    }

    /** 只在「其他」展开/收起状态变化时重建，避免每次上下移动都重建组栏视图。 */
    private void refreshGroupBarIfExpansionChanged() {
        boolean expand = groupIndex == otherGroupIndex();
        if (expand != barOtherExpanded) {
            refreshGroupBar();
        } else {
            updateGroupBarState();
        }
    }

    private void updateListHint() {
        if (phoneDevice) {
            // 手机没有遥控提示：说清「点行 → 预览 / 再点 → 全屏 / 长按 → 操作条」。
            if (playlist == null) {
                setHint(getString(R.string.phone_hint_preview));
            } else if (visibleChannels.isEmpty()) {
                setHint(getString(R.string.phone_hint_empty));
            } else {
                Channel focus = focusChannel();
                boolean focusPlaying = focus != null && focus.id().equals(playingChannelId);
                setHint(getString(focusPlaying
                        ? R.string.phone_hint_play : R.string.phone_hint_preview));
            }
            return;
        }
        if (playlist == null) {
            setHint(getString(R.string.list_hint_preview));
            return;
        }
        if (visibleChannels.isEmpty()) {
            // 收藏为空（唯一可能的空组）：提示回组栏/长按收藏，而不是「OK 切预览」。
            setHint(getString(R.string.list_hint_empty));
            return;
        }
        if (ui.isFocusInGroups()) {
            setHint(getString(R.string.list_hint_groups));
            return;
        }
        if (ui.isFocusInActions()) {
            setHint(getString(R.string.list_hint_actions));
            return;
        }
        Channel focus = focusChannel();
        boolean focusPlaying = focus != null && focus.id().equals(playingChannelId);
        setHint(getString(focusPlaying
                ? R.string.list_hint_play : R.string.list_hint_preview));
    }

    private void updateListEmpty() {
        if (listEmptyOverride != null) {
            showListEmpty(listEmptyOverride);
            return;
        }
        if (visibleChannels.isEmpty() && playlist != null) {
            showListEmpty(getString(groupIndex == FAVORITES_GROUP
                    ? R.string.list_empty_favorites : R.string.list_empty_group));
        } else {
            hideListEmpty();
        }
    }

    private void updateNowInfo() {
        Channel channel = playingChannel();
        if (channel == null) {
            channel = focusChannel();
        }
        if (channel == null) {
            clearNow();
            return;
        }
        updateNow(channel.number(), channel.name(),
                getString(R.string.meta_join, channel.group(), getString(R.string.now_preview)));
    }

    /** 列表 UP/DOWN：组内循环（首台上→末台、末台下→首台）；单台组原地，不跨组。 */
    private void moveListFocus(int delta) {
        if (visibleChannels.isEmpty() || delta == 0) {
            return;
        }
        int size = visibleChannels.size();
        int next = focusIndex < 0 ? 0 : (focusIndex + delta % size + size) % size;
        if (next == focusIndex) {
            return;
        }
        focusIndex = next;
        adapter.setFocusIndex(next);
        ensureChannelVisible(next);
        updateGroupBarState();
        updateNowInfo();
        updateListHint();
    }

    /** 组栏 UP/DOWN：在 6 个一级组 + 「其他」子组间移动，列表切到该项，不播放。 */
    private void moveGroupFocus(int delta) {
        if (barSpecs.isEmpty() || delta == 0) {
            return;
        }
        int size = barSpecs.size();
        int next = (groupFocusIndex + delta + size) % size;
        applyBarFocus(next);
    }

    /** 焦点落到某个组栏项：更新列表筛选（「其他」一级项 = 全部，子组项 = 该子组）。 */
    private void applyBarFocus(int index) {
        if (index < 0 || index >= barSpecs.size()) {
            return;
        }
        BarSpec spec = barSpecs.get(index);
        groupFocusIndex = index;
        groupIndex = spec.groupIndex;
        if (groupIndex == otherGroupIndex()) {
            subGroupFilter = spec.subGroup ? spec.subFilter : null;
        } else {
            subGroupFilter = null;
        }
        refreshGroupBarIfExpansionChanged();
        rebuildVisibleChannels();
        ui.setFocusInActions(false);
        focusIndex = focusIndexForGroup();
        adapter.setFocusIndex(focusIndex);
        if (focusIndex >= 0) {
            // 列表数据刚换过，直接选中焦点行，避免用旧行的可见位置算滚动。
            channelList().setSelection(focusIndex);
        }
        updateListState();
    }

    /** 组栏 RIGHT/OK：进入列表，焦点落到播放台（若在本组）或第一台。 */
    private void enterListFromGroups() {
        ui.setFocusInGroups(false);
        ui.setFocusInActions(false);
        focusIndex = focusIndexForGroup();
        adapter.setFocusIndex(focusIndex);
        if (focusIndex >= 0) {
            ensureChannelVisible(focusIndex);
        }
        updateListState();
    }

    /**
     * 切组/进入列表的焦点落点：当前组包含播放台则落播放台，否则第一台。
     * 只移焦点，不播放、不触发加载。
     */
    private int focusIndexForGroup() {
        if (visibleChannels.isEmpty()) {
            return -1;
        }
        Channel playing = playingChannel();
        if (playing != null) {
            int index = indexOfChannel(visibleChannels, playing.id());
            if (index >= 0) {
                return index;
            }
        }
        return 0;
    }

    /** 列表 RIGHT：打开内联操作列，焦点到第一项（不播放、不改预览）。 */
    private void enterActions() {
        Channel channel = focusChannel() != null ? focusChannel() : playingChannel();
        if (channel == null) {
            return;
        }
        ui.setFocusInGroups(false);
        ui.setFocusInActions(true);
        actionFocusIndex = firstEnabledAction();
        ui.setActionColumnVisible(true);
        setActionColumnVisible(true);
        if (focusIndex >= 0) {
            ensureChannelVisible(focusIndex);
        }
        updateActionColumn();
        updateListHint();
    }

    /** 操作列 LEFT：收起操作列并回列表，焦点行不变。 */
    private void leaveActions() {
        ui.setFocusInActions(false);
        ui.setActionColumnVisible(false);
        setActionColumnVisible(false);
        if (focusIndex >= 0) {
            ensureChannelVisible(focusIndex);
        }
        updateActionColumn();
        updateListHint();
    }

    /** 操作列上下：跳过不可用项（单线路时的「切换线路」）。 */
    private void moveActionFocus(int delta) {
        if (delta == 0) {
            return;
        }
        int next = actionFocusIndex;
        for (int i = 0; i < ActionColumn.ITEM_COUNT; i++) {
            next = (next + delta + ActionColumn.ITEM_COUNT) % ActionColumn.ITEM_COUNT;
            if (isActionEnabled(next)) {
                actionFocusIndex = next;
                break;
            }
        }
        updateActionColumn();
        updateListHint();
    }

    private boolean isActionEnabled(int index) {
        if (index != ActionColumn.ITEM_VARIANTS) {
            return true;
        }
        Channel channel = focusChannel() != null ? focusChannel() : playingChannel();
        return channel != null && channel.variants().size() > 1;
    }

    private int firstEnabledAction() {
        for (int i = 0; i < ActionColumn.ITEM_COUNT; i++) {
            if (isActionEnabled(i)) {
                return i;
            }
        }
        return ActionColumn.ITEM_FAVORITE;
    }

    /** 操作列 OK：只作用于焦点台；不播放、不改预览（全屏播放除外）。 */
    private void activateAction() {
        Channel channel = focusChannel() != null ? focusChannel() : playingChannel();
        if (channel == null) {
            return;
        }
        switch (actionFocusIndex) {
            case ActionColumn.ITEM_FAVORITE:
                applyFavoriteToggle(channel);
                break;
            case ActionColumn.ITEM_VARIANTS:
                if (channel.variants().size() <= 1) {
                    overlays.showMessage(getString(R.string.menu_source_single), null, OSD_MS);
                } else {
                    cycleVariant(channel);
                }
                break;
            case ActionColumn.ITEM_INFO:
                showChannelInfo(channel);
                break;
            case ActionColumn.ITEM_FULLSCREEN:
                enterFullscreen(true);
                break;
            default:
                break;
        }
        updateListHint();
    }

    /** 刷新操作列内容（焦点台名称、收藏文案、线路可用性）与高亮。 */
    private void updateActionColumn() {
        Channel channel = focusChannel() != null ? focusChannel() : playingChannel();
        int variants = channel == null ? 0 : channel.variants().size();
        boolean favorite = channel != null && favorites.contains(channel.id());
        if (ui.isFocusInActions() && !isActionEnabled(actionFocusIndex)) {
            actionFocusIndex = firstEnabledAction();
        }
        updateActions(channel == null ? null : channel.name(), favorite,
                variants, ui.isFocusInActions(), actionFocusIndex);
    }

    /** 列表 LEFT：回到组栏，焦点停当前筛选（「其他」落在「全部」/当前子组）。 */
    private void enterGroups() {
        ui.setFocusInGroups(true);
        ui.setFocusInActions(false);
        int active = activeBarIndex();
        groupFocusIndex = active >= 0 ? active : Math.max(0, barIndexForGroup(groupIndex));
        // updateListState 会按 isFocusInGroups 清掉列表行焦点样式并刷新组栏。
        updateListState();
    }

    private void changeChannel(Channel target, boolean showOsd, boolean userInitiated) {
        if (target == null) {
            return;
        }
        // playingChannelId 由 playChannel 在真正起播时更新：快速切台合并窗口内保持原台，
        // 列表播放点/画面继续停在原频道，只有 OSD 和焦点跟着目标台走。
        groupIndex = groupIndexFor(target);
        if (ui.isFullscreen()) {
            // 全屏切台/切组不保留列表的子组过滤；回列表时统一回到「全部」。
            subGroupFilter = null;
            refreshGroupBarIfExpansionChanged();
        } else {
            focusChannelInList(target);
        }
        playChannel(target, userInitiated, false);
        if (showOsd) {
            showChannelOsd(target);
        }
    }

    /**
     * @param force true 时即使同一频道也在播也重新起播（重试 / 切源 / 卡顿恢复）。
     */
    /** 连续切台合并窗口：350ms 内只播最后一个，避免 Exo 连续换源卡死视频轨。 */
    private static final long USER_PLAY_MERGE_MS = 550L;
    private long lastUserPlayAt;
    private Channel pendingUserChannel;
    private boolean pendingUserForce;

    /** 用户明确操作（OK/MENU/Back）时取消排队中的快速切台。 */
    private void cancelPendingUserPlay() {
        handler.removeCallbacks(pendingUserPlayRunnable);
        pendingUserChannel = null;
    }

    private final Runnable pendingUserPlayRunnable = new Runnable() {
        @Override
        public void run() {
            Channel channel = pendingUserChannel;
            pendingUserChannel = null;
            if (channel == null) {
                return;
            }
            lastUserPlayAt = SystemClock.elapsedRealtime();
            playChannel(channel, false, pendingUserForce);
        }
    };

    private void playChannel(Channel channel, boolean userInitiated, boolean force) {
        if (channel == null || playlist == null || paused) {
            // 后台（onStop 之后）不启动解码器，回到前台由 onStart 恢复。
            return;
        }
        // 连续切台合并：350ms 内只播最后一个。TCL 上 Exo 在上一路源还没停稳时被连续
        // setMediaSource，视频轨会卡死不恢复（有画无声/黑屏有声）；合并后每次 prepare
        // 都能拿到干净的上一状态。force（重试/切源/旋转重播）不合并。
        // 导航里按 OK 是明确选台，立即播；画面态 CH+/− 才合并，避免连续 flush 解码器。
        boolean mergeRapid = userInitiated && !force && !ui.isNavShown();
        if (mergeRapid) {
            // 画面态 CH+/− 一律先不播：旧台继续播，只更新焦点/OSD。
            // 每次按键都把 800ms 计时器往后推，手停下来才播最后落点；
            // 落点回到原台时由下面 same 检查直接跳过。
            lastUserPlayAt = SystemClock.elapsedRealtime();
            pendingUserChannel = channel;
            pendingUserForce = force;
            handler.removeCallbacks(pendingUserPlayRunnable);
            handler.postDelayed(pendingUserPlayRunnable, USER_PLAY_MERGE_MS);
            return;
        } else {
            // 导航选台 / 重试 / force：取消排队中的快速切台，避免旧台把新选择覆盖。
            handler.removeCallbacks(pendingUserPlayRunnable);
            pendingUserChannel = null;
        }
        if (userInitiated) {
            // 手动换台 / 重试 / 切组：清零全屏失败链，并收起上一次失败角标。
            failureStreak.reset();
            overlays.hideErrorBadge();
        }
        String previousPlayingId = playingChannelId;
        playingChannelId = channel.id();
        if (!channel.id().equals(previousPlayingId) || userInitiated) {
            stallRetryDone = false;
            startTimeoutRetries = 0;
            videoFreezeRetryDone = false;
        }

        Channel current = controller.getCurrentChannel();
        boolean same = current != null && current.id().equals(channel.id());
        PlaybackState state = controller.getState();
        if (!force && same
                && (state == PlaybackState.PLAYING || state == PlaybackState.PREPARING)) {
            updateListState();
            return;
        }
        lastPosition = -1L;
        lastPositionChangeAt = SystemClock.elapsedRealtime();
        beginLoading();
        Channel target = withVariantOffset(channel);
        if (playbackSettings.firstFrameBoost() && !target.variants().isEmpty()) {
            // 后台 DNS 预解析，不阻塞主线程；失败静默，关闭首帧加速时不发。
            DnsPrefetch.prefetchUrl(target.variants().get(0).url());
        }
        // FALLBACK 会先释放 standby、可能把前台槽位换回 A；记下切换前的当前台。
        Channel frontBefore = controller.getCurrentChannel();
        playStartedAt = SystemClock.elapsedRealtime();
        videoReconfigures = 0;
        DualPlayback.Result result = playback.play(target, force);
        controller = playback.active();
        if (result == DualPlayback.Result.FALLBACK) {
            // 双画面不可用：编排层已退回单路，本次没有起播目标台；保持当前台。
            Channel front = controller.getCurrentChannel();
            if (front == null) {
                front = frontBefore;
            }
            playingChannelId = front != null ? front.id() : previousPlayingId;
            endLoading();
            updateListState();
            return;
        }
        // DUAL：加载提示保留到 standby 首帧（onDualSwitched）或失败（onDualAborted）。
        Log.i(TAG, "play " + channel.id() + " result=" + result
                + " dual=" + playback.isDual() + " texture=" + textureMode);
        updateListState();
    }

    // ------------------------------------------------------------------ 开机音频自检

    /** TCL legacy：开机音频/信源时序问题只在这类设备上处理。 */
    private boolean isTclLegacyDevice() {
        if (Build.VERSION.SDK_INT > 19) {
            return false;
        }
        String names = (Build.MANUFACTURER + " " + Build.BRAND).toUpperCase(Locale.US);
        if (names.contains("TCL")) {
            return true;
        }
        try {
            getPackageManager().getPackageInfo("com.tcl.sourcemananger", 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void registerBootCompletedReceiver() {
        if (!bootAudioCheckPending || bootCompletedReceiverRegistered) {
            return;
        }
        try {
            registerReceiver(bootCompletedReceiver,
                    new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
            bootCompletedReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "注册 BOOT_COMPLETED 音频自检失败: " + e);
        }
    }

    private void unregisterBootCompletedReceiver() {
        if (!bootCompletedReceiverRegistered) {
            return;
        }
        bootCompletedReceiverRegistered = false;
        try {
            unregisterReceiver(bootCompletedReceiver);
        } catch (RuntimeException e) {
            Log.w(TAG, "注销 BOOT_COMPLETED 音频自检失败: " + e);
        }
    }

    private void checkBootAudioFallback() {
        if (BootAudioPlan.shouldFallback(SystemClock.elapsedRealtime(), bootAudioCheckPending)) {
            scheduleBootAudioReplay("fallback");
        }
    }

    /**
     * 安排一次开机音频自检：先确保 TCL 信源回到 Android，再等系统完全起来后重播。
     * 只在 from_boot + TCL legacy 的首次播放会话执行一次。
     */
    private void scheduleBootAudioReplay(String reason) {
        if (!bootAudioCheckPending || bootAudioReplayScheduled) {
            return;
        }
        if (isTclLegacyDevice()) {
            Boolean source = TclSourceSwitch.isAndroidSource(this);
            if (Boolean.TRUE.equals(source)) {
                // 信源已经是 Android 时不做无意义的重播；运行时路由自检会处理异常。
                bootAudioCheckPending = false;
                Log.i(TAG, "boot audio check skipped: source=Android");
                return;
            }
            try {
                TclSourceSwitch.setAndroidSource(this);
            } catch (RuntimeException e) {
                Log.w(TAG, "boot audio source switch failed: " + e);
            }
        }
        bootAudioReplayScheduled = true;
        handler.removeCallbacks(bootAudioReplayRunnable);
        handler.postDelayed(bootAudioReplayRunnable, BootAudioPlan.REPLAY_DELAY_MS);
        Log.i(TAG, "boot audio check scheduled reason=" + reason);
    }

    private void replayBootAudio(String reason) {
        bootAudioReplayScheduled = false;
        if (!bootAudioCheckPending || !started || paused) {
            return;
        }
        Channel channel = playingChannel();
        if (channel == null) {
            Log.i(TAG, "boot audio check skip: no channel");
            return;
        }
        bootAudioCheckPending = false;
        Log.i(TAG, "boot audio check replay reason=" + reason + " channel=" + channel.id());
        // 强制新引擎/新 AudioTrack；只在开机首播会话做一次。
        if (playback != null) {
            playback.rebuildOnNextPlay();
        }
        playChannel(channel, false, true);
    }

    private void checkTclAudioRoute() {
        if (!started || paused || controller == null || !isTclLegacyDevice()) {
            return;
        }
        // 2 秒一次；反射查询失败/不支持时静默放行。
        if ((audioRouteCheckTicks++ & 1) != 0) {
            return;
        }
        Boolean android = TclSourceSwitch.isAndroidSource(this);
        if (android == null || android.booleanValue()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAudioRouteSwitchAt < 10_000L || audioRouteReplayScheduled) {
            return;
        }
        lastAudioRouteSwitchAt = now;
        Log.w(TAG, "audio route source != Android -> switch");
        boolean switched;
        try {
            switched = TclSourceSwitch.setAndroidSource(this);
        } catch (RuntimeException e) {
            switched = false;
        }
        Log.i(TAG, "audio route switch result=" + switched);
        if (switched) {
            scheduleAudioRouteReplay();
        }
    }

    private void scheduleAudioRouteReplay() {
        if (audioRouteReplayScheduled) {
            return;
        }
        audioRouteReplayScheduled = true;
        // 运行时路由自检已经做了更强的处理，取消开机那一次，避免连续重播。
        bootAudioCheckPending = false;
        bootAudioReplayScheduled = false;
        handler.removeCallbacks(bootAudioReplayRunnable);
        handler.removeCallbacks(audioRouteReplayRunnable);
        handler.postDelayed(audioRouteReplayRunnable, 2_500L);
        Log.i(TAG, "audio route replay scheduled");
    }

    private void replayAudioRoute(String reason) {
        audioRouteReplayScheduled = false;
        if (!started || paused) {
            return;
        }
        Channel channel = playingChannel();
        if (channel == null) {
            return;
        }
        Log.i(TAG, "audio route replay reason=" + reason + " channel=" + channel.id());
        if (playback != null) {
            playback.rebuildOnNextPlay();
        }
        playChannel(channel, false, true);
    }

    /** 按“切换线路”的偏移量复制频道（不改动同频道的原始 variant 顺序）。 */
    private Channel withVariantOffset(Channel channel) {
        int count = channel.variants().size();
        if (count <= 1) {
            return channel;
        }
        Integer stored = variantOffsets.get(channel.id());
        int offset = stored == null ? 0 : stored % count;
        if (offset == 0) {
            return channel;
        }
        Channel copy = new Channel(channel);
        List<Variant> variants = channel.variants();
        for (int i = 0; i < count; i++) {
            copy.addVariant(variants.get((i + offset) % count));
        }
        return copy;
    }

    private void cycleVariant(Channel channel) {
        if (channel == null) {
            return;
        }
        int count = channel.variants().size();
        if (count <= 1) {
            overlays.showMessage(getString(R.string.menu_source_single), null, OSD_MS);
            return;
        }
        Integer stored = variantOffsets.get(channel.id());
        int current = stored == null ? 0 : stored % count;
        int next = (current + 1) % count;
        variantOffsets.put(channel.id(), next);
        // 操作列只改焦点台的首选线路：焦点台不是播放台时绝不启动预览/加载。
        if (channel.id().equals(playingChannelId)) {
            playChannel(channel, true, true);
        }
        overlays.showMessage(channel.name(),
                getString(R.string.source_switched, next + 1, count), OSD_MS);
    }

    // ------------------------------------------------------------------ 全屏

    /** 列表/遥控路径：焦点台优先，没有焦点时用播放台。 */
    private void enterFullscreen(boolean showOsd) {
        Channel channel = focusChannel() != null ? focusChannel() : playingChannel();
        enterFullscreen(channel, showOsd);
    }

    /** 指定目标台全屏；MENU 主菜单固定传播放台（不跟随焦点台）。 */
    private void enterFullscreen(Channel channel, boolean showOsd) {
        if (channel == null) {
            return;
        }
        if (!usesFullscreen()) {
            // TV / 手机横屏：没有独立全屏态，全屏 = 收起导航让画面铺满。
            collapseToPlayback(channel, showOsd);
            return;
        }
        if (ui.isFullscreen()) {
            if (showOsd) {
                // 手机已在全屏：只出一次频道 OSD；返回按钮留给点画面唤出。
                showFullscreenOsdOnly();
            }
            return;
        }
        ui.enterFullscreen();
        // 手机竖屏全屏：收起底部操作条，并清掉点按链（退出后第一下不误判双击）。
        touchRouter.reset();
        ui.setActionColumnVisible(false);
        setActionColumnVisible(false);
        playingChannelId = channel.id();
        applyModeToLayout();
        updateClockVisibility();
        playChannel(channel, true, false);
        if (showOsd) {
            // 进全屏只出 2.5s 频道 OSD；返回按钮等用户点画面再出现
            // （否则第一次点画面会把刚自动弹出的控件收掉，看起来像「点画面没反应」）。
            showFullscreenOsdOnly();
        }
    }

    /**
     * 非手机竖屏（TV / 手机横屏）的「全屏播放」：收起导航进入 {@code NAV_HIDDEN}，
     * 画面铺满并出频道 OSD；不设置 {@link UiState.Mode#FULLSCREEN}，也不动布局 fullscreen。
     */
    private void collapseToPlayback(Channel channel, boolean showOsd) {
        if (ui.isFullscreen()) {
            // 旋转/历史状态兜底：非手机竖屏不保留 FULLSCREEN。
            ui.exitFullscreen();
        }
        ui.hideNav();
        playingChannelId = channel.id();
        applyModeToLayout();
        overlays.updateClock(false);
        playChannel(channel, true, false);
        if (showOsd) {
            showChannelOsd(playingChannel() != null ? playingChannel() : channel);
        }
    }

    // ------------------------------------------------- 手机全屏 OSD / 返回按钮

    /**
     * 手机全屏改点画面：已显示返回按钮 → 收起；否则出 2.5s OSD + 半透明返回按钮。
     * 系统返回/返回手势仍走 {@link #onBack()} → 退全屏回列表。
     */
    private void onFullscreenTap() {
        long now = SystemClock.elapsedRealtime();
        if (fullscreenOsd.isVisible(now) && overlays.isBackChromeVisible()) {
            hideFullscreenChrome();
        } else {
            showFullscreenChrome();
        }
    }

    /** 进全屏/旋转后只出频道 OSD（2.5s），不自动出现返回按钮。 */
    private void showFullscreenOsdOnly() {
        Channel channel = playingChannel() != null ? playingChannel() : focusChannel();
        if (channel != null) {
            showChannelOsd(channel);
        }
    }

    /** 显示 OSD（台号/名称/分组）+ 返回按钮，2.5s 后一起收起。 */
    private void showFullscreenChrome() {
        long now = SystemClock.elapsedRealtime();
        fullscreenOsd.show(now);
        Channel channel = playingChannel() != null ? playingChannel() : focusChannel();
        if (channel != null) {
            showChannelOsd(channel);
        }
        overlays.setBackChromeVisible(PhoneChrome.backChromeVisible(
                phoneDevice, ui.isFullscreen(), true));
        handler.removeCallbacks(fullscreenChromeHideRunnable);
        handler.postDelayed(fullscreenChromeHideRunnable, fullscreenOsd.showMs());
        updateAutoHideBlock();
    }

    private void hideFullscreenChrome() {
        handler.removeCallbacks(fullscreenChromeHideRunnable);
        fullscreenOsd.hide();
        overlays.setBackChromeVisible(false);
        overlays.hideOsd();
        updateAutoHideBlock();
    }

    private final Runnable fullscreenChromeHideRunnable = new Runnable() {
        @Override
        public void run() {
            hideFullscreenChrome();
        }
    };

    // ------------------------------------------------- 导航可见性 / 自动收起 / 音量

    /** NAV_HIDDEN 的 OK：弹出导航，焦点落在播放台。 */
    private void showNav() {
        showNav(playingChannel());
    }

    /** 手机竖屏 FULLSCREEN 的 OK/长按：先退出全屏回 NAV_HIDDEN，再弹导航。 */
    private void popNavFromPlayback() {
        if (ui.isFullscreen()) {
            exitFullscreen();
        }
        showNav();
    }

    /** NAV_SHOWN 的 Back：收起导航（8s 自动收起之外的唯一手动收起）。 */
    private void hideNav(boolean animate) {
        if (!ui.isNavShown()) {
            return;
        }
        ui.hideNav();
        applyModeToLayout(animate);
        overlays.updateClock(false);
    }

    private void showNav(Channel focusTarget) {
        // 导航自己就是主信息层：先收掉播放态的所有临时浮层（频道 OSD、自动跳台
        // 倒计时/提示、错误角标、Toast、音量条），避免「按 OK 先出 OSD、导航像被
        // 抢」。失败路径仍会在弹导航之后重新显示错误 OSD/角标。
        cancelAutoNext();
        overlays.hideOsd();
        overlays.hideErrorBadge();
        overlays.hideToast();
        overlays.hideVolume();
        if (ui.isFullscreen()) {
            // 历史残留（TV/横屏不应有独立全屏）：先退回隐藏态再弹导航，不能直接 return。
            Log.i(TAG, "showNav clears stale fullscreen mode=" + ui.mode()
                    + " layoutFullscreen=" + layout.isFullscreen());
            ui.exitFullscreen();
        }
        if (playlist == null || ui.isMenuOpen()) {
            // 已清掉残留全屏时也要同步布局，避免只有一边变化。
            applyModeToLayout();
            return;
        }
        updateAutoHideBlock();
        ui.showNav(SystemClock.elapsedRealtime());
        Channel target = focusTarget != null ? focusTarget : playingChannel();
        if (target != null) {
            focusChannelInList(target);
        }
        // 若布局还卡在全屏（历史不同步），这里会先退出再弹导航。
        applyModeToLayout(true);
        overlays.updateClock(false);
        updateListState();
    }

    /**
     * 菜单、设置页、首启页、错误 OSD 期间暂停 8s 自动收起；解除后重新计时。
     * NavVisibility 自己忽略重复的阻塞状态，不会每 tick 重置截止时间。
     */
    private void updateAutoHideBlock() {
        boolean blocked = ui.isMenuOpen() || ui.isSettings() || ui.isSetup()
                || overlays.isOsdVisible() || autoNextPending;
        ui.setBlocked(blocked, SystemClock.elapsedRealtime());
    }

    /** 每秒检查：无按键且未阻塞、且设置了自动收起时收起导航（焦点/播放不变）。 */
    private void checkAutoHide() {
        updateAutoHideBlock();
        if (phoneDevice) {
            // 手机列表常驻，不做 8 秒自动收起（收起手势/系统栏见 5b）。
            return;
        }
        if (playlist == null) {
            // 没有可用频道时导航就是唯一内容，不自动收起（否则黑屏）。
            return;
        }
        if (ui.consumeAutoHide(SystemClock.elapsedRealtime())) {
            applyModeToLayout(true);
            overlays.updateClock(false);
        }
    }

    /** 导航隐藏/全屏：LEFT 音量 −、RIGHT 音量 +，每次 1 格，自绘 2s 音量条。 */
    private void adjustVolume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null || direction == 0) {
            return;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) {
            return;
        }
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = current + (direction > 0 ? 1 : -1);
        if (target < 0) {
            target = 0;
        }
        if (target > max) {
            target = max;
        }
        if (target != current) {
            // flags 传 0：不弹系统音量条，只出本应用自绘的音量条。
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
        overlays.showVolume(Math.round(target * 100f / max));
    }

    /** 手机竖屏全屏 Back：回到列表；电视/横屏不会进入 FULLSCREEN（全屏即导航隐藏）。 */
    private void exitFullscreen() {
        if (ui.mode() != UiState.Mode.FULLSCREEN) {
            // 非全屏（或历史不同步）：按当前模式把布局拉回一致，避免卡在全屏。
            applyModeToLayout();
            return;
        }
        ui.exitFullscreen();
        failureStreak.reset();
        overlays.hideErrorBadge();
        if (phoneDevice) {
            // 手机：全屏 OSD/返回按钮随全屏一起收起。
            hideFullscreenChrome();
            // 手机退回列表（列表常驻），焦点仍跟着播放台。
            ui.showNav(SystemClock.elapsedRealtime());
            touchRouter.reset();
        }
        applyModeToLayout();
        overlays.updateClock(false);
        Channel playing = playingChannel();
        if (playing != null) {
            // 焦点仍跟播放台，下次弹出导航直接落在它上面。
            focusChannelInList(playing);
        } else {
            updateListState();
        }
    }

    // ------------------------------------------------------------------ 触摸

    /**
     * 点分组 tab：切组 + 列表回顶，视频继续播当前台（不因切组变化）。
     * 触摸只改 Activity 的选中/分组状态，不 requestFocus。
     */
    @Override
    public void onGroupSelected(int barIndex) {
        if (barIndex < 0 || barIndex >= barSpecs.size()) {
            return;
        }
        touchRouter.reset();
        dismissActions();
        applyBarFocus(barIndex);
        if (visibleChannels.isEmpty()) {
            focusIndex = -1;
            adapter.setFocusIndex(-1);
        } else {
            // 切组回顶：第一行选中，列表滚到顶。
            focusIndex = 0;
            adapter.setFocusIndex(0);
            channelList().setSelection(0);
        }
        updateListState();
    }

    /** 点频道行：选中 + 预览；同一行再点/双击 → 全屏。 */
    @Override
    public void onChannelTap(int position) {
        Channel channel = adapter.channelAt(position);
        if (channel == null) {
            return;
        }
        dismissActions();
        focusIndex = position;
        adapter.setFocusIndex(position);
        boolean alreadyPlaying = channel.id().equals(playingChannelId);
        TouchRouter.Tap tap = touchRouter.tap(position, alreadyPlaying, now());
        if (tap == TouchRouter.Tap.FULLSCREEN) {
            enterFullscreen(true);
        } else {
            // 第一下只切预览：视频区立即起播，列表保持可见。
            playChannel(channel, true, false);
        }
        updateListState();
    }

    /** 长按频道行：底部操作条（收藏 / 切换线路 / 频道信息 / 全屏播放）。 */
    @Override
    public void onChannelLongPress(int position) {
        Channel channel = adapter.channelAt(position);
        if (channel == null) {
            return;
        }
        touchRouter.longPress(position);
        focusIndex = position;
        adapter.setFocusIndex(position);
        ui.setFocusInGroups(false);
        ui.setFocusInActions(true);
        actionFocusIndex = firstEnabledAction();
        ui.setActionColumnVisible(true);
        setActionColumnVisible(true);
        updateListState();
    }

    /** 操作条按钮：复用遥控操作列的同一套动作（收藏/线路/信息/全屏）。 */
    @Override
    public void onActionSelected(int action) {
        if (!isActionEnabled(action)) {
            return;
        }
        actionFocusIndex = action;
        activateAction();
        if (action == ActionColumn.ITEM_FULLSCREEN) {
            dismissActions();
        } else {
            updateActionColumn();
            updateListHint();
        }
    }

    /** 点画面：手机全屏出/收 OSD + 返回按钮；竖屏列表态无动作。 */
    @Override
    public void onVideoTap() {
        if (ui.isSettings() || ui.isSetup()) {
            return;
        }
        if (ui.isFullscreen()) {
            onFullscreenTap();
        }
    }

    @Override
    public void onDismissActions() {
        dismissActions();
    }

    /** 收起底部操作条（手机）/ 内联操作列（电视布局 + 触摸）。 */
    private void dismissActions() {
        ui.setFocusInActions(false);
        ui.setActionColumnVisible(false);
        setActionColumnVisible(false);
    }

    private boolean isPhoneActionsShown() {
        return layout instanceof PhoneLayout && ((PhoneLayout) layout).isActionColumnShown();
    }

    private final Runnable phoneDisarmRunnable = new Runnable() {
        @Override
        public void run() {
            ui.disarmExit();
        }
    };

    // ------------------------------------------------------------------ 按键

    /**
     * 所有遥控键统一交给 {@link KeyRouter}；本类只做动作落地。
     *
     * <p>手机硬件音量键（{@link KeyEvent#KEYCODE_VOLUME_UP}/{@link KeyEvent#KEYCODE_VOLUME_DOWN}）
     * 明确不拦截：直接交回系统调音量（系统音量条），App 只保留电视遥控左右键的自绘音量条。</p>
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 开机缓起播窗口内按任意键：立即起播，按键照常继续路由。
        if (event.getAction() == KeyEvent.ACTION_DOWN && bootDeferredChannel != null) {
            startDeferredPlayback("key");
        }
        if (phoneDevice && isHardwareVolumeKey(event.getKeyCode())) {
            return super.dispatchKeyEvent(event);
        }
        if (keyRouter.handle(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isHardwareVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    // ------------------------------------------------- KeyRouter.Host / Timers

    @Override
    public long now() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public boolean isLayoutFullscreen() {
        return layout != null && layout.isFullscreen();
    }

    @Override
    public void postDelayed(Runnable runnable, long delayMs) {
        handler.postDelayed(runnable, delayMs);
    }

    @Override
    public void removeCallbacks(Runnable runnable) {
        handler.removeCallbacks(runnable);
    }

    /** OK 短按：菜单 > 设置/首启 > 全屏 > 导航（组栏/操作列/列表）> 隐藏态。 */
    @Override
    public void onSelect() {
        cancelPendingUserPlay();
        Log.i(TAG, "onSelect mode=" + ui.mode()
                + " navShown=" + ui.isNavShown()
                + " fullscreen=" + ui.isFullscreen());
        if (ui.isSettings()) {
            settingsView().handleKey(KeyEvent.KEYCODE_DPAD_CENTER);
            return;
        }
        if (ui.isSetup()) {
            setupView().activate();
            return;
        }
        if (ui.isMenuOpen()) {
            activateMenu();
            return;
        }
        if (ui.isFullscreen()) {
            // 手机竖屏全屏：OK 退出全屏回列表后弹出导航。
            popNavFromPlayback();
            return;
        }
        if (!ui.isNavShown()) {
            // NAV_HIDDEN：OK 弹出导航，焦点落在播放台（TV 的「全屏」也就是这个隐藏态）。
            showNav();
            return;
        }
        if (ui.isFocusInGroups()) {
            enterListFromGroups();
            return;
        }
        if (ui.isFocusInActions()) {
            activateAction();
            return;
        }
        Channel focus = focusChannel();
        if (focus == null) {
            return;
        }
        if (focus.id().equals(playingChannelId)) {
            // 焦点台就是当前播放台：手机竖屏进全屏；TV/横屏收起导航回到画面。
            NavOk.Action action = NavOk.decide(ui.isNavShown(), true, usesFullscreen());
            if (action == NavOk.Action.FULLSCREEN) {
                enterFullscreen(true);
            } else if (action == NavOk.Action.HIDE_NAV) {
                hideNav(true);
            }
            return;
        }
        // 焦点台 != 播放台：第一下 OK 只切预览，留在列表。
        playChannel(focus, true, false);
    }

    /** 长按 OK：隐藏/全屏弹导航；导航展开时无动作（操作列按 → 打开）。 */
    @Override
    public void onLongSelect() {
        if (!ui.isMenuOpen() && !ui.isNavShown()) {
            popNavFromPlayback();
        }
    }

    /** Back：菜单只关菜单；设置页先回源列表；全屏回隐藏；导航收起。 */
    @Override
    public void onBack() {
        cancelPendingUserPlay();
        if (ui.isMenuOpen()) {
            closeMenu();
            return;
        }
        if (ui.isSettings()) {
            // 源操作区里 Back 先回到源列表，再按一次才关闭设置。
            if (!settingsView().handleBack()) {
                closeSettings();
            }
            return;
        }
        if (ui.mode() == UiState.Mode.FULLSCREEN) {
            // 全屏 Back → 导航隐藏态（不弹导航、不退出）。
            exitFullscreen();
            return;
        }
        if (ui.isActionColumnVisible() || ui.isFocusInActions() || isPhoneActionsShown()) {
            // 频道 → 打开的操作列只是导航内的一列：Back 先只收起它，导航内容和
            // 频道焦点都保留；再按一次 Back 才走导航收起/退出语义。
            if (phonePortrait) {
                dismissActions();
            } else {
                leaveActions();
            }
            return;
        }
        if (phoneDevice) {
            // 手机列表常驻：Back 按一次退出（沿用「再按一次返回退出」提示）。
            if (ui.isExitArmed()) {
                handler.removeCallbacks(phoneDisarmRunnable);
                ui.disarmExit();
                markUserExited();
                finish();
                return;
            }
            ui.armExit();
            handler.removeCallbacks(phoneDisarmRunnable);
            handler.postDelayed(phoneDisarmRunnable, EXIT_HINT_MS);
            return;
        }
        if (ui.mode() == UiState.Mode.NAV_SHOWN) {
            // 导航展开时 Back 收起导航，不退出、不出退出提示。
            hideNav(true);
        }
    }

    /** MENU：首启页进设置；其余状态开/关主菜单。 */
    @Override
    public void onMenu() {
        cancelPendingUserPlay();
        if (ui.isSetup()) {
            // 全部源失败/停用时也能进设置修源；无源时设置里可看诊断。
            openSettings();
            return;
        }
        if (ui.isMenuOpen()) {
            closeMenu();
            return;
        }
        openMainMenu();
    }

    @Override
    public void onDirection(int dx, int dy) {
        if (ui.isSettings()) {
            // 设置页只认方向/OK，键码由页面自己解释；二维码浮层时页面会吞掉。
            settingsView().handleKey(dy < 0 ? KeyEvent.KEYCODE_DPAD_UP
                    : dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                    : dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            return;
        }
        if (playlist == null) {
            return;
        }
        if (ui.isMenuOpen()) {
            // 菜单 LEFT 关闭；不切组、不关导航（菜单本身就不在导航里）。
            if (dx < 0) {
                closeMenu();
            } else if (dy != 0) {
                overlays.moveMenuSelection(dy);
            }
            return;
        }
        if (!ui.isNavShown()) {
            // 隐藏/全屏：上下换台，左右由 KeyRouter 走 onVolume。
            // dy 已是台序（KeyRouter 按遥控习惯取反：上=+1 下一台，下=-1 上一台）。
            if (dy != 0 && navigator != null) {
                // 基准用「焦点台」而不是「播放台」：快速切台合并期间播放台保持不变，
                // 用播放台做基准每次都会算回同一个「上一个/下一个」。
                Channel base = focusChannel() != null ? focusChannel() : playingChannel();
                Channel target = navigator.stepWrapping(base, dy);
                if (target != null) {
                    changeChannel(target, true, true);
                }
            }
            return;
        }
        if (ui.isFocusInGroups()) {
            if (dy != 0) {
                moveGroupFocus(dy);
            } else if (dx > 0) {
                enterListFromGroups();
            }
            return;
        }
        if (ui.isFocusInActions()) {
            if (dy != 0) {
                moveActionFocus(dy);
            } else if (dx < 0) {
                leaveActions();
            }
            return;
        }
        if (dx < 0) {
            enterGroups();
        } else if (dx > 0) {
            // 列表 RIGHT：进入常驻操作列（不播放、不改预览）。
            enterActions();
        } else if (dy != 0) {
            moveListFocus(dy);
        }
    }

    @Override
    public void onChannelStep(int delta) {
        if (playlist == null || ui.isMenuOpen() || navigator == null) {
            return;
        }
        if (!ui.isNavShown()) {
            // NAV_HIDDEN / FULLSCREEN：CH+/− 直接换台。
            Channel target = navigator.stepWrapping(playingChannel(), delta);
            if (target != null) {
                changeChannel(target, true, true);
            }
            return;
        }
        // NAV_SHOWN：跨组移动焦点，不播放；到首/尾不循环。
        Channel from = focusChannel() != null ? focusChannel() : playingChannel();
        Channel target = navigator.step(from, delta);
        if (target != null) {
            focusChannelInList(target);
        }
    }

    @Override
    public void onDigit(int digit) {
        if (playlist == null || ui.isMenuOpen()) {
            return;
        }
        if (digitBuffer.length() >= MAX_DIGITS) {
            digitBuffer = "";
        }
        digitBuffer += String.valueOf(digit);
        overlays.showToast(getString(R.string.digit_input, digitBuffer), TOAST_SHORT_MS);
        handler.removeCallbacks(digitTimeoutRunnable);
        handler.postDelayed(digitTimeoutRunnable, DIGIT_TIMEOUT_MS);
    }

    @Override
    public void onVolume(int delta) {
        adjustVolume(delta);
    }

    @Override
    public void onInfo() {
        showInfoOsd();
    }

    @Override
    public void onFavorite() {
        toggleFavoriteForCurrent();
    }

    @Override
    public void onExit() {
        markUserExited();
        finish();
    }

    /** 用户主动退出（双击 Back / 菜单退出）：本次开机内不再被开机信号拉起。 */
    private void markUserExited() {
        prefs.setUserExited(true);
    }

    @Override
    public void onUserInput() {
        // 任意键取消全屏自动跳台倒计时。
        cancelAutoNext();
    }

    private final Runnable digitTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            String digits = digitBuffer;
            digitBuffer = "";
            if (digits.isEmpty() || navigator == null) {
                return;
            }
            int number;
            try {
                number = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                return;
            }
            Channel target = navigator.byNumber(number);
            if (target == null) {
                overlays.showMessage(getString(R.string.no_such_channel, digits), null, OSD_MS);
                return;
            }
            if (!ui.isNavShown()) {
                // NAV_HIDDEN / FULLSCREEN：数字直选直接换台。
                changeChannel(target, true, true);
            } else {
                // NAV_SHOWN：切到该台所在组并移动焦点，不播放。
                focusChannelInList(target);
            }
        }
    };

    /** 主菜单：当前播放频道操作（固定作用于播放台）+ 全局项；无播放台时只有全局项。 */
    private void openMainMenu() {
        if (ui.isMenuOpen()) {
            return;
        }
        ui.openMenu();
        prepareMenuLayer();

        Channel playing = playingChannel();
        MainMenu.Model model = MainMenu.build(playing,
                playing == null ? "" : ChannelListAdapter.formatNumber(playing.number()),
                playing != null && favorites.contains(playing.id()),
                playing == null ? null : menuFullscreenLabel(),
                new MenuLabels());

        List<Overlays.MenuItem> items = new ArrayList<Overlays.MenuItem>();
        for (MainMenu.Item item : model.items()) {
            items.add(new Overlays.MenuItem(item.action(), item.label(), null,
                    item.enabled(), item.section()));
        }
        overlays.showMenu(model.kicker(), model.sub(), items);
        applyOverlayTouchable();
    }

    /** 菜单「全屏播放 / 收起导航 / 退出全屏」文案：设备与当前模式决定。 */
    private String menuFullscreenLabel() {
        if (ui.isFullscreen()) {
            return getString(R.string.menu_exit_fullscreen);
        }
        if (!phoneDevice && ui.isNavShown()) {
            return getString(R.string.menu_hide_nav);
        }
        return getString(R.string.menu_fullscreen);
    }

    /** 主菜单文案（资源实现；纯逻辑在 {@link MainMenu} 里可单测）。 */
    private final class MenuLabels implements MainMenu.Labels {

        @Override
        public String favorite(boolean favorite) {
            return getString(favorite ? R.string.menu_unfavorite : R.string.menu_favorite);
        }

        @Override
        public String switchSource() {
            return getString(R.string.menu_switch_source);
        }

        @Override
        public String channelInfo() {
            return getString(R.string.menu_channel_info);
        }

        @Override
        public String reload() {
            return getString(R.string.menu_reload);
        }

        @Override
        public String settings() {
            return getString(R.string.menu_settings);
        }

        @Override
        public String exit() {
            return getString(R.string.menu_exit);
        }

        @Override
        public String globalTitle() {
            return getString(R.string.brand);
        }

        @Override
        public String globalSub() {
            return getString(R.string.menu_main_sub);
        }

        @Override
        public String channelSub(String group, String number) {
            String num = number == null ? "" : number;
            if (group == null || group.isEmpty()) {
                return num;
            }
            if (num.isEmpty()) {
                return group;
            }
            return getString(R.string.meta_join, group, num);
        }
    }

    /** 打开主菜单前：隐藏提示/时钟/角标，暂停自动收起计时。 */
    private void prepareMenuLayer() {
        setHintVisible(false);
        overlays.updateClock(false);
        overlays.hideLoading();
        overlays.hideErrorBadge();
        overlays.hideVolume();
        updateAutoHideBlock();
    }

    private void closeMenu() {
        ui.closeMenu();
        setHintVisible(true);
        updateListHint();
        overlays.hideMenu();
        updateClockVisibility();
        if (started && loadingPending && loadingDelayElapsed) {
            showLoadingFeedback();
        }
        // 菜单期间不计时；关闭后从当前时刻重新给满 8 秒。
        updateAutoHideBlock();
        applyOverlayTouchable();
    }

    private void activateMenu() {
        int action = overlays.selectedMenuAction();
        if (action < 0) {
            return;
        }
        switch (action) {
            case Overlays.ACTION_FAVORITE:
                activateMenuFavorite(action);
                break;
            case Overlays.ACTION_VARIANTS:
                activateMenuVariants(action);
                break;
            case Overlays.ACTION_INFO:
                showChannelInfo(MainMenu.target(action, playingChannel()));
                break;
            case Overlays.ACTION_FULLSCREEN:
                activateMenuFullscreen();
                break;
            case Overlays.ACTION_RELOAD:
                closeMenu();
                reloadPlaylist();
                break;
            case Overlays.ACTION_SETTINGS:
                closeMenu();
                openSettings();
                break;
            case Overlays.ACTION_EXIT:
                closeMenu();
                saveLastChannel();
                markUserExited();
                finish();
                break;
            default:
                break;
        }
    }

    /** 菜单收藏：作用于播放台（不是焦点台），菜单保持打开并就地更新文案。 */
    private void activateMenuFavorite(int action) {
        Channel playing = MainMenu.target(action, playingChannel());
        if (playing == null) {
            return;
        }
        applyFavoriteToggle(playing);
        overlays.setMenuItemLabel(action,
                getString(favorites.contains(playing.id())
                        ? R.string.menu_unfavorite : R.string.menu_favorite));
    }

    /** 菜单切换线路：作用于播放台；单线路时只出提示。 */
    private void activateMenuVariants(int action) {
        Channel playing = MainMenu.target(action, playingChannel());
        if (playing == null) {
            return;
        }
        if (playing.variants().size() <= 1) {
            overlays.showMessage(getString(R.string.menu_source_single), null, OSD_MS);
            return;
        }
        cycleVariant(playing);
    }

    /**
     * 菜单全屏项：全屏中退出全屏；电视导航展开时收起导航回画面；
     * 其余（手机竖屏 / 电视隐藏态）对播放台全屏。
     */
    private void activateMenuFullscreen() {
        Channel playing = playingChannel();
        if (playing == null) {
            return;
        }
        boolean fullscreen = ui.isFullscreen();
        boolean navShown = !phoneDevice && ui.isNavShown();
        closeMenu();
        if (fullscreen) {
            exitFullscreen();
        } else if (navShown) {
            hideNav(true);
        } else {
            enterFullscreen(playing, true);
        }
    }

    // ------------------------------------------------------------------ 收藏

    private void toggleFavoriteForCurrent() {
        Channel channel = uiChannel();
        if (channel != null) {
            applyFavoriteToggle(channel);
        }
    }

    private void applyFavoriteToggle(Channel channel) {
        boolean favorite = prefs.toggleFavorite(channel.id());
        if (favorite) {
            favorites.add(channel.id());
        } else {
            favorites.remove(channel.id());
        }

        if (!ui.isFullscreen() && groupIndex == FAVORITES_GROUP) {
            // 收藏组里增删会直接改变当前列表；焦点 clamp 到最近的项。
            rebuildVisibleChannels();
            if (focusIndex >= visibleChannels.size()) {
                focusIndex = visibleChannels.size() - 1;
            }
            if (focusIndex < -1) {
                focusIndex = -1;
            }
            adapter.setFocusIndex(focusIndex);
        } else {
            adapter.setFavorites(favorites);
        }

        refreshGroupBar();
        updateListState();
        overlays.showToast(getString(favorite
                ? R.string.favorite_added : R.string.favorite_removed), TOAST_SHORT_MS);
    }

    // ------------------------------------------------------------------ OSD / 自动跳台

    private void showChannelOsd(Channel channel) {
        if (channel == null) {
            return;
        }
        if (ui.isNavShown()) {
            // 导航显示期间换台/切组不弹频道 OSD：OSD 只属于 NAV_HIDDEN/FULLSCREEN。
            // 错误 OSD/角标走 showMessage/showErrorBadge，不受这里影响，失败必须可见。
            return;
        }
        String meta = channel.variants().size() > 1
                ? getString(R.string.osd_meta_sources, channel.group(), channel.variants().size())
                : channel.group();
        overlays.showChannel(ChannelListAdapter.formatNumber(channel.number()),
                channel.name(), meta);
    }

    /** 「频道信息」：源名 · 线路 i/n（当前线路优先，未播放时取手动线路偏移）。 */
    private void showChannelInfo(Channel channel) {
        if (channel == null) {
            return;
        }
        int count = Math.max(1, channel.variants().size());
        int index = currentVariantIndex(channel);
        String source = currentSourceName(channel, index);
        String meta = source == null
                ? getString(R.string.channel_info_line, index + 1, count)
                : getString(R.string.channel_info_meta, source, index + 1, count);
        overlays.showChannel(ChannelListAdapter.formatNumber(channel.number()),
                channel.name(), meta);
    }

    private int currentVariantIndex(Channel channel) {
        List<Variant> variants = channel.variants();
        if (variants.isEmpty()) {
            return 0;
        }
        if (channel.id().equals(playingChannelId)) {
            Variant current = controller.getCurrentVariant();
            if (current != null) {
                for (int i = 0; i < variants.size(); i++) {
                    if (variants.get(i).url().equals(current.url())) {
                        return i;
                    }
                }
            }
        }
        Integer stored = variantOffsets.get(channel.id());
        return stored == null ? 0 : stored % variants.size();
    }

    private String currentSourceName(Channel channel, int index) {
        List<Variant> variants = channel.variants();
        if (index >= 0 && index < variants.size()) {
            String name = variants.get(index).sourceName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return channel.sourceName();
    }

    private void showInfoOsd() {
        showChannelInfo(uiChannel());
    }

    private void handlePlaybackFailure(Channel failedChannel, PlaybackError error) {
        // 前台失败：先放弃进行中的双画面切台，避免旧 standby 随后提交到前台。
        if (playback != null) {
            playback.cancelPendingSwitch();
        }
        String reason = error != null ? error.message() : "";
        String message = failureText(reason);
        prefs.setLastError(message);
        if (ui.isSettings() || ui.isSetup()) {
            // 页面打开期间的失败不弹 OSD/角标、不进入自动跳台；回列表后由用户手动操作。
            cancelAutoNext();
            endLoading();
            overlays.hideOsd();
            overlays.hideErrorBadge();
            overlays.hideLoading();
            overlays.hideVolume();
            return;
        }
        if (ui.isNavShown()) {
            // 导航列表里预览失败：清播放点、只提示，不自动跳台、不计入失败链。
            clearFailedPreviewPlayback(failedChannel);
            overlays.showMessage(message, null, ERROR_OSD_MS);
            overlays.showErrorBadge(getString(R.string.badge_preview_retry));
            return;
        }

        int failures = failureStreak.recordFailure();
        if (!prefs.autoNextEnabled()) {
            overlays.showMessage(message, getString(R.string.auto_next_off), ERROR_OSD_MS);
            // 即使自动跳台关闭，隐藏/全屏失败也要有常驻角标，不能只剩黑屏。
            overlays.showErrorBadge(getString(R.string.badge_preview_retry));
            return;
        }
        if (failures >= failureStreak.limit()) {
            stopAfterFailureStreak(failedChannel, reason);
            return;
        }

        Channel failed = failedChannel != null ? failedChannel : playingChannel();
        startAutoNext(failed, reason);
    }

    /** 连续失败到上限：停止自动跳，回导航列表并保留可重试提示。 */
    private void stopAfterFailureStreak(Channel failedChannel, String reason) {
        Channel failed = failedChannel != null ? failedChannel : playingChannel();
        if (ui.isFullscreen()) {
            exitFullscreen();
        }
        clearFailedPreviewPlayback(failed);
        if (ui.isNavHidden()) {
            showNav(failed);
        }
        overlays.showMessage(getString(R.string.failure_streak_stopped), reason, ERROR_OSD_MS);
        overlays.showErrorBadge(getString(R.string.badge_streak_stopped));
    }

    /**
     * 列表里预览失败：清掉播放点，hint 回到「OK 切预览」，避免再按 OK 直接全屏一张黑屏。
     * 全屏失败不走这里（保持 2b 自动跳台）。
     */
    private void clearFailedPreviewPlayback(Channel failedChannel) {
        if (playingChannelId == null) {
            return;
        }
        if (failedChannel != null && !failedChannel.id().equals(playingChannelId)) {
            return;
        }
        playingChannelId = null;
        endLoading();
        updateListState();
    }

    /** 「网络失败 · 播放失败」；消息本身就是「播放失败」时只显示一次。 */
    private String failureText(String message) {
        String playFailed = getString(R.string.play_failed);
        if (message == null || message.isEmpty() || playFailed.equals(message)) {
            return playFailed;
        }
        return getString(R.string.preview_failed, message);
    }

    // ------------------------------------------------------------------ 加载角标

    private final Runnable loadingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!loadingPending) {
                return;
            }
            loadingDelayElapsed = true;
            switchFeedback.delayElapsed();
            showLoadingFeedback();
        }
    };

    /** 每次起播/切台/重试前调用；120ms 内 onPrepared/首帧到达就不会出现在屏幕上。 */
    private void beginLoading() {
        loadingPending = true;
        loadingDelayElapsed = false;
        switchFeedback.begin();
        handler.removeCallbacks(loadingRunnable);
        endLoadingOverlays();
        handler.postDelayed(loadingRunnable, LOADING_DELAY_MS);
    }

    /** 换源重试期间保留加载反馈：已过 120ms 就保持可见，否则让原计时继续。 */
    private void retainLoading() {
        if (!loadingPending) {
            beginLoading();
            return;
        }
        if (loadingDelayElapsed) {
            switchFeedback.delayElapsed();
            showLoadingFeedback();
        }
    }

    private void endLoading() {
        loadingPending = false;
        loadingDelayElapsed = false;
        switchFeedback.end();
        handler.removeCallbacks(loadingRunnable);
        endLoadingOverlays();
    }

    private void endLoadingOverlays() {
        overlays.hideLoading();
        overlays.hideTransitionCard();
    }

    /** 按设置显示台名卡或原「加载中…」角标；菜单/设置/首启页期间不显示。 */
    private void showLoadingFeedback() {
        if (!started || !loadingPending) {
            return;
        }
        boolean pageOpen = ui.isMenuOpen() || ui.isSettings() || ui.isSetup();
        Channel channel = controller == null ? null : controller.getCurrentChannel();
        if (channel == null) {
            channel = playingChannel();
        }
        if (switchFeedback.shouldShowCard(ui.isNavShown(), pageOpen) && channel != null) {
            // 新台从「—」开始：清掉去重缓存，否则首包前的「—」和旧值会打架。
            bandwidthIndicator.reset();
            overlays.showTransitionCard(ChannelListAdapter.formatNumber(channel.number()),
                    channel.name());
        } else if (switchFeedback.shouldShowLoadingBadge(pageOpen)) {
            overlays.showLoading();
        }
    }

    private void startAutoNext(Channel failed, String reason) {
        if (ui.isSettings() || ui.isSetup()) {
            // 设置/首启页期间绝不新起自动跳台，也不让角标盖在页面上。
            overlays.hideOsd();
            overlays.hideErrorBadge();
            return;
        }
        Channel target = navigator == null ? null : navigator.stepWrapping(failed, 1);
        if (target == null) {
            // 没有下一台可跳：保留失败说明 + 常驻角标，避免隐藏态纯黑且无提示。
            overlays.showMessage(reason, null, ERROR_OSD_MS);
            overlays.showErrorBadge(getString(R.string.badge_preview_retry));
            return;
        }
        autoNextPending = true;
        autoNextTarget = target;
        autoNextReason = reason;
        autoNextSecondsLeft = AUTO_NEXT_SECONDS;
        overlays.showMessage(getString(R.string.auto_next_countdown, autoNextSecondsLeft),
                reason, 0L);
        overlays.showErrorBadge(getString(R.string.badge_auto_next));
        handler.removeCallbacks(autoNextTickRunnable);
        handler.postDelayed(autoNextTickRunnable, TICK_MS);
    }

    private final Runnable autoNextTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!autoNextPending) {
                return;
            }
            autoNextSecondsLeft--;
            if (autoNextSecondsLeft > 0) {
                // 只换文字：不再走 showOsd/ensureOnTop，OSD 不闪、不重排整个覆盖层。
                overlays.updateOsdText(
                        getString(R.string.auto_next_countdown, autoNextSecondsLeft),
                        autoNextReason);
                handler.postDelayed(this, TICK_MS);
                return;
            }
            Channel target = autoNextTarget;
            autoNextPending = false;
            autoNextTarget = null;
            autoNextReason = null;
            // 换台期间保留失败角标；新台真正起播由 onChannelStarted 收起，失败则继续保留/更新，
            // 避免 HIDDEN/FULLSCREEN 黑屏时右下角出现一段无提示的空档。
            if (target != null) {
                changeChannel(target, true, false);
            }
        }
    };

    /** 任意键取消倒计时；不在倒计时中则什么也不做。 */
    private void cancelAutoNext() {
        if (!autoNextPending) {
            return;
        }
        autoNextPending = false;
        autoNextTarget = null;
        autoNextReason = null;
        handler.removeCallbacks(autoNextTickRunnable);
        overlays.hideOsd();
        overlays.hideErrorBadge();
    }

    // ------------------------------------------------------------------ 卡顿 / 时钟

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            updateClockVisibility();
            refreshControlSnapshotCurrent();
            checkAutoHide();
            checkStall();
            checkPlaybackStartTimeout();
            checkVideoFreeze();
            checkBootAudioFallback();
            checkTclAudioRoute();
            checkSurfaceWatchdog();
            checkVideoRecovery();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * SurfaceView 看门狗：TCL 上偶发 Surface 被 HWC 回收但 {@code surfaceDestroyed}
     * 不回调，播放器继续出声、画面黑着。
     *
     * <p>每秒检查一次：连续 2 次拿不到有效 Surface 就重建（INVISIBLE → VISIBLE），
     * 重建后 5 秒内不重复；TextureView 模式/后台/未播放时不碰。</p>
     */
    private void checkSurfaceWatchdog() {
        if (textureMode || !started || paused || layout == null
                || controller == null || controller.getState() != PlaybackState.PLAYING) {
            surfaceMissingTicks = 0;
            return;
        }
        Surface surface = surfaceViewSurface();
        if (surface != null && surface.isValid()) {
            surfaceMissingTicks = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSurfaceRecreateAt < SURFACE_RECREATE_COOLDOWN_MS) {
            return;
        }
        surfaceMissingTicks++;
        if (surfaceMissingTicks < 2) {
            return;
        }
        surfaceMissingTicks = 0;
        lastSurfaceRecreateAt = now;
        Log.i(TAG, "surface watchdog: missing -> recreate");
        recreateSurfaceView();
    }

    /**
     * 连续换源后视频不出帧的兜底：起播 2 秒还没等到 first frame，就让引擎把
     * VideoSurface 解绑再绑回去（不销毁 player），最多两次。
     *
     * <p>日志依据：黑屏时 {@code play} 之后没有 {@code first frame rendered}，
     * 解码器 OMX 组件还在但不再输出；重配 renderer 比 release+new 轻，音频不断。</p>
     */
    private void checkVideoRecovery() {
        if (textureMode || controller == null || !started || paused) {
            return;
        }
        if (controller.getState() != PlaybackState.PLAYING) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - playStartedAt < VIDEO_FIRST_FRAME_TIMEOUT_MS) {
            return;
        }
        if (lastFirstFrameAt >= playStartedAt) {
            return;
        }
        if (videoReconfigures >= 2
                || now - lastVideoReconfigureAt < VIDEO_FIRST_FRAME_TIMEOUT_MS) {
            return;
        }
        videoReconfigures++;
        lastVideoReconfigureAt = now;
        Log.i(TAG, "video recovery reconfigure #" + videoReconfigures);
        controller.forceReconfigureVideo();
    }

    /**
     * 起播看门狗：PREPARING/BUFFERING 超过 15 秒还没进入 PLAYING（或首帧）→ 重试一次，
     * 再超时按失败处理。
     *
     * <p>批次 28：TCL 上 4K HEVC 会让厂商 MediaCodec.flush() 永久阻塞，播放线程
     * 卡在 binder 调用里；主线程仍空闲，所以不会有 ANR，只会一直显示加载中。
     * 这里至少给用户一个“卡住”的反馈并尝试重启引擎；真正避免卡死靠
     * {@code StreamProbe} + {@code VideoProfile} 在创建解码器前拦截。</p>
     */
    private void checkPlaybackStartTimeout() {
        if (!started || paused || playlist == null || controller == null
                || ui.isSettings() || ui.isSetup()) {
            return;
        }
        if (controller.getState() == PlaybackState.PLAYING || controller.isRetrying()) {
            return;
        }
        if (playStartedAt <= 0L) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - playStartedAt < PLAYBACK_START_TIMEOUT_MS) {
            return;
        }
        Channel channel = playingChannel();
        if (channel == null) {
            return;
        }
        if (startTimeoutRetries == 0) {
            startTimeoutRetries = 1;
            Variant timedOutVariant = controller.getCurrentVariant();
            if (timedOutVariant != null) {
                StreamProbe.invalidate(this, timedOutVariant.url());
            }
            Log.w(TAG, "playback start timeout channel=" + channel.id()
                    + " state=" + controller.getState() + " -> retry");
            overlays.showMessage(getString(R.string.stalled), null, OSD_MS);
            // 4K-only/厂商解码器失败后 KEEP 复用的 player 可能已经无法再 prepare，
            // 这次重试必须换一个干净的 Exo 实例。
            if (playback != null) {
                playback.rebuildOnNextPlay();
            }
            playChannel(channel, false, true);
            return;
        }
        startTimeoutRetries = 0;
        Variant failedVariant = controller.getCurrentVariant();
        if (failedVariant != null) {
            StreamProbe.invalidate(this, failedVariant.url());
        }
        Log.w(TAG, "playback start timeout again channel=" + channel.id()
                + " state=" + controller.getState() + " -> fail");
        handlePlaybackFailure(channel,
                PlaybackError.of(PlaybackError.Kind.UNKNOWN, getString(R.string.stalled_failed)));
    }

    /**
     * 视频帧看门狗：TCL 的 live 流可能“位置还在走、音频/视频帧已经停了”，
     * {@link #checkStall} 只看 position 会漏掉。这里用 {@code VideoFrameMetadataListener}
     * 最近一帧的时间判断；12 秒无新帧 → 重建重试一次，再失败交给自动跳台。
     */
    private void checkVideoFreeze() {
        if (!started || paused || controller == null
                || controller.getState() != PlaybackState.PLAYING) {
            return;
        }
        long lastFrame = controller.lastVideoFrameAtMs();
        if (lastFrame <= 0L) {
            return; // 音频-only / 引擎不支持
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrame < VIDEO_FREEZE_TIMEOUT_MS) {
            return;
        }
        Channel channel = playingChannel();
        if (channel == null) {
            return;
        }
        if (!videoFreezeRetryDone) {
            videoFreezeRetryDone = true;
            Log.w(TAG, "video freeze channel=" + channel.id()
                    + " lastFrameAgo=" + (now - lastFrame) + "ms -> retry");
            overlays.showMessage(getString(R.string.stalled), null, OSD_MS);
            if (playback != null) {
                playback.rebuildOnNextPlay();
            }
            playChannel(channel, false, true);
            return;
        }
        videoFreezeRetryDone = false;
        Log.w(TAG, "video freeze again channel=" + channel.id()
                + " lastFrameAgo=" + (now - lastFrame) + "ms -> fail");
        handlePlaybackFailure(channel,
                PlaybackError.of(PlaybackError.Kind.UNKNOWN, getString(R.string.stalled_failed)));
    }

    /** 播放位置 10s 不前进 → 重试当前台一次；再卡住按失败处理。 */
    private void checkStall() {        if (playlist == null || controller.getState() != PlaybackState.PLAYING) {
            lastPosition = -1L;
            return;
        }
        if (playback != null && playback.isSwitching()) {
            // 双画面正在缓冲新台：前台还是旧台，位置继续前进，不判卡顿。
            lastPosition = -1L;
            lastPositionChangeAt = SystemClock.elapsedRealtime();
            return;
        }
        if (ui.isSettings() || ui.isSetup()) {
            // 设置/首启页期间不判卡顿、不重试、不出 OSD；关闭后重新计时。
            lastPosition = -1L;
            lastPositionChangeAt = SystemClock.elapsedRealtime();
            return;
        }
        long position = controller.getCurrentPosition();
        long now = SystemClock.elapsedRealtime();
        if (position <= 0L) {
            lastPosition = -1L;
            lastPositionChangeAt = now;
            return;
        }
        if (lastPosition < 0L || position > lastPosition + 200L
                || position < lastPosition - 1000L) {
            lastPosition = position;
            lastPositionChangeAt = now;
            // 不要在这里清 stallRetryDone：重试后只走一小段又卡住会无限重试。
            // 新频道/用户主动操作才在 playChannel 里重置。
            return;
        }
        if (now - lastPositionChangeAt < STALL_TIMEOUT_MS) {
            return;
        }

        Channel channel = playingChannel();
        if (channel == null) {
            return;
        }
        lastPosition = -1L;
        lastPositionChangeAt = now;
        if (!stallRetryDone) {
            stallRetryDone = true;
            Log.w(TAG, "stall retry channel=" + channel.id() + " state=" + controller.getState());
            overlays.showMessage(getString(R.string.stalled), null, OSD_MS);
            playChannel(channel, false, true);
            return;
        }
        Log.w(TAG, "stall again channel=" + channel.id() + " -> fail");
        handlePlaybackFailure(channel,
                PlaybackError.of(PlaybackError.Kind.UNKNOWN, getString(R.string.stalled_failed)));
    }

    // ------------------------------------------------------------------ 播放回调

    private final DualPlayback.Listener playbackListener = new DualPlayback.Listener() {
        @Override
        public void onChannelStarted(Channel channel, Variant variant) {
            endLoading();
            // 起播成功：清零失败链，失败角标消失。
            failureStreak.reset();
            overlays.hideErrorBadge();
            playingChannelId = channel.id();
            prefs.setLastChannelId(channel.id());
            updateListState();
        }

        @Override
        public void onFirstFrame(Channel channel) {
            lastFirstFrameAt = SystemClock.elapsedRealtime();
            endLoading();
            // 不在这里清 stallRetryDone：重试后才渲染一帧又卡住，应判失败而不是无限重试。
            lastPosition = -1L;
            lastPositionChangeAt = SystemClock.elapsedRealtime();
        }

        @Override
        public void onError(PlaybackError error, boolean willRetry) {
            if (willRetry) {
                // 同一频道内换源/重试：不要因为在重试就撤掉「加载中…」，否则慢 TLS/多线路时
                // 会出现长时间黑屏且无任何提示（TV 上尤其明显）。
                retainLoading();
            } else {
                endLoading();
            }
            Log.w(TAG, "播放失败，继续尝试: " + error);
            if (ui.isSettings() || ui.isSetup()) {
                return;
            }
            if (controller.isRetrying()) {
                // 网络抖动：原线路还有重试机会，只提示，不进入自动跳台/清播放点。
                overlays.showMessage(getString(R.string.network_retrying), null, ERROR_OSD_MS);
                return;
            }
            overlays.showToast(getString(R.string.switching_source), TOAST_SHORT_MS);
        }

        @Override
        public void onAllVariantsFailed(Channel channel, PlaybackError lastError) {
            endLoading();
            Log.w(TAG, "频道全部源失败: " + lastError);
            handlePlaybackFailure(channel, lastError);
        }

        @Override
        public void onEnded(Channel channel) {
            endLoading();
            Log.w(TAG, "直播流结束: " + channel);
            handlePlaybackFailure(channel, PlaybackError.of(
                    PlaybackError.Kind.UNKNOWN, getString(R.string.stream_ended)));
        }

        @Override
        public void onDualSwitched(Channel channel) {
            // 双画面已提交：alpha 已交换、前台引擎换人、旧引擎已停。
            TextureVideoLayer layer = layout == null ? null : layout.textureLayer();
            if (layer != null) {
                layer.swap();
            }
            Log.i(TAG, "dual switched -> " + channel.id() + " activeA=" + playback.activeIsA());
            controller = playback.active();
            playingChannelId = channel.id();
            prefs.setLastChannelId(channel.id());
            failureStreak.reset();
            overlays.hideErrorBadge();
            lastPosition = -1L;
            lastPositionChangeAt = SystemClock.elapsedRealtime();
            endLoading();
            updateListState();
            refreshControlSnapshotCurrent();
        }

        @Override
        public void onDualAborted(Channel target, Channel front, PlaybackError error) {
            // 双画面失败：保持当前台，不自动跳台；只提示一次。
            if (front != null) {
                playingChannelId = front.id();
            }
            endLoading();
            updateListState();
            Log.w(TAG, "双画面切台失败，保持当前台: " + error);
            if (ui.isSettings() || ui.isSetup()) {
                return;
            }
            overlays.showToast(getString(R.string.dual_switch_failed), TOAST_SHORT_MS);
        }

        @Override
        public void onDualUnsupported(Channel target, Channel front, PlaybackError error) {
            // 内存/解码器撑不住两路：永久退回保留画面（KEEP），提示一次。
            Log.w(TAG, "双画面不可用，切回保留画面: " + error);
            final boolean memoryShortage = error != null
                    && error.kind() == PlaybackError.Kind.MEMORY;
            playbackSettings.setSwitchMode(SwitchMode.KEEP);
            if (front != null) {
                playingChannelId = front.id();
            }
            endLoading();
            updateListState();
            // 不在引擎回调里直接重建/重播，交给下一轮主线程消息。
            handler.post(new Runnable() {
                @Override
                public void run() {
                    applyPlaybackSettings();
                    overlays.showMessage(getString(memoryShortage
                            ? R.string.dual_memory_low : R.string.dual_unavailable), null, OSD_MS);
                }
            });
        }
    };

    // ------------------------------------------------------------------ Surface

    /** 最后一次绑给播放器的 Surface（旋转搬移时用来识别旧 Surface 的迟到回调）。 */
    private Surface boundSurface;

    /** 看门狗：连续几次 tick 拿不到有效 Surface。 */
    private int surfaceMissingTicks;
    /** 上一次因 Surface 丢失而重建的时刻（elapsedRealtime），用于冷却。 */
    private long lastSurfaceRecreateAt;
    /** 两次 Surface 重建之间的最小间隔：太频繁会让解码器反复重启。 */
    private static final long SURFACE_RECREATE_COOLDOWN_MS = 5000L;

    /** 本次起播时刻；配合首帧时刻判断视频是否真的出帧。 */
    private long playStartedAt;
    private long lastFirstFrameAt;
    /** 本次起播已经强制重配视频渲染器的次数（最多 2 次）。 */
    private int videoReconfigures;
    private long lastVideoReconfigureAt;
    private static final long VIDEO_FIRST_FRAME_TIMEOUT_MS = 2000L;

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        boolean wasReady = surfaceReady;
        boundSurface = surface;
        surfaceReady = true;
        Log.i(TAG, "surfaceCreated textureMode=" + textureMode);
        applyVideoSurfaces();
        replayIfSurfaceRegained(wasReady);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 全屏只改布局：Surface 尺寸变化不需要重新绑定，避免播放器重新 attach。
        // 注意：surfaceChanged 也可能带着一个已失效的 Surface 进来（HWC 回收/窗口重建）。
        // 之前无条件把 surfaceReady 置 true，导致 surfaceViewSurface() 返回 null 之后
        // replayIfSurfaceRegained() 仍认为「之前就绪」，不会重播 —— 表现就是黑屏有声。
        Surface surface = holder.getSurface();
        boolean valid = surface != null && surface.isValid();
        boolean wasReady = boundSurface != null && boundSurface.isValid();
        surfaceReady = valid;
        if (!textureMode) {
            if (valid) {
                boundSurface = surface;
            } else {
                boundSurface = null;
            }
            applyVideoSurfaces();
            replayIfSurfaceRegained(wasReady);
        }
    }

    /**
     * SurfaceView 从「无 Surface」恢复到「有 Surface」时，如果播放器已经在播，
     * Exo 可能是在没有视频输出的状态下起的播（只有声音）。重新 prepare 一次，
     * 让它重新选视频轨；直播重播代价可接受，比「有声无画」好。
     */
    private void replayIfSurfaceRegained(boolean wasReady) {
        if (wasReady || textureMode || controller == null) {
            return;
        }
        Channel current = controller.getCurrentChannel();
        if (current == null || controller.getState() != PlaybackController.PlaybackState.PLAYING) {
            return;
        }
        Log.i(TAG, "surface regained -> replay " + current.name());
        playChannel(current, false, true);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        if (boundSurface != null && surface != null && surface != boundSurface) {
            // 旧 Surface 的迟到回调（旋转把 SurfaceView 搬到新布局）：当前绑定已是新 Surface，
            // 不解绑、不打断续播。
            return;
        }
        surfaceReady = false;
        boundSurface = null;
        applyVideoSurfaces();
    }

    // ------------------------------------------------------------------ 查询 / 工具

    private Channel focusChannel() {
        if (focusIndex < 0 || focusIndex >= visibleChannels.size()) {
            return null;
        }
        return visibleChannels.get(focusIndex);
    }

    private Channel playingChannel() {
        return navigator == null ? null : navigator.byId(playingChannelId);
    }

    /** 按键作用目标：导航隐藏/全屏是播放台，导航展开是焦点台。 */
    private Channel uiChannel() {
        if (!ui.isNavShown()) {
            Channel playing = playingChannel();
            if (playing != null) {
                return playing;
            }
        }
        Channel focus = focusChannel();
        return focus != null ? focus : playingChannel();
    }

    /** 列表选中行最小滚动，避免每次上下移动都把行顶到列表顶部。 */
    private void ensureChannelVisible(int index) {
        if (index < 0) {
            return;
        }
        android.widget.ListView list = channelList();
        int rowHeight = getResources().getDimensionPixelSize(R.dimen.row_height);
        int paddingTop = list.getPaddingTop();
        int visibleBottom = list.getHeight() - list.getPaddingBottom();
        int first = list.getFirstVisiblePosition();
        if (list.getHeight() <= 0 || index < first) {
            list.setSelectionFromTop(index, 0);
            return;
        }
        android.view.View child = list.getChildAt(index - first);
        if (child == null) {
            list.setSelectionFromTop(index, 0);
            return;
        }
        if (child.getTop() < paddingTop) {
            list.setSelectionFromTop(index, 0);
            return;
        }
        if (child.getBottom() > visibleBottom) {
            int overflow = child.getBottom() - visibleBottom;
            int offset = child.getTop() - overflow - paddingTop;
            list.setSelectionFromTop(index, Math.max(0, offset));
        }
    }

    private static int indexOfChannel(List<Channel> channels, String channelId) {
        if (channels == null || channelId == null) {
            return -1;
        }
        for (int i = 0; i < channels.size(); i++) {
            if (channelId.equals(channels.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void saveLastChannel() {
        if (playingChannelId != null) {
            prefs.setLastChannelId(playingChannelId);
        }
    }

    /** 组栏一项：一级组或「其他」子组；groupIndex 指向数据组（含收藏 0）。 */
    private static final class BarSpec {

        final String label;
        final int count;
        final int groupIndex;
        final boolean subGroup;
        /** 子组完整名（过滤用）；「全部」和一级组为 null。 */
        final String subFilter;

        BarSpec(String label, int count, int groupIndex, boolean subGroup, String subFilter) {
            this.label = label;
            this.count = count;
            this.groupIndex = groupIndex;
            this.subGroup = subGroup;
            this.subFilter = subFilter;
        }
    }

}
