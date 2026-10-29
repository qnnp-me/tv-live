package me.qnnp.tvlive.boot;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.EnumMap;
import java.util.Map;

import me.qnnp.tvlive.prefs.AppPrefs;

/**
 * 开机信号广播入口：Manifest 静态注册，收到任意 {@link BootActions} 里的 action
 * 都只做「解析来源 → {@link BootCoordinator} 判定 → 记日志 → 必要时 {@link BootLauncher}」。
 *
 * <p>不在这里直接 startActivity，也不做播放/UI 逻辑；判定逻辑全部在纯 Java 的
 * {@code BootCoordinator} 里，便于 JVM 单测和以后加厂商来源。真正拉起时机由
 * {@link BootLauncher} 决定：批次 23 默认排一次 1 秒后直接拉起的 alarm；
 * 开关关闭时退回 {@link BootWaitPlan} 的等桌面就绪链路。</p>
 *
 * <p>去重记录只放进程内静态表：elapsedRealtime 重启归零，跨进程/重启没有意义；
 * 持久化的最近 10 条见 {@link BootLog}。</p>
 *
 * <p>批次 28：每个 action 先取 {@link BootActionPolicy}，窗口按开机时长
 * （{@code elapsedRealtime}）算，去重按策略分类而不是单个 action；拉起延迟也由
 * 策略决定（BOOT 1s / 网络 6s / 用户唤醒 4s / USB+存储 15s）。</p>
 */
public final class BootSignalReceiver extends BroadcastReceiver {

    public static final String TAG = "TvLive";

    /** 去重键用策略分类：USB 与 MEDIA 同类，不会重复拉起。 */
    private static final Map<BootActionPolicy.Kind, Long> LAST_SIGNALS =
            new EnumMap<BootActionPolicy.Kind, Long>(BootActionPolicy.Kind.class);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        BootActionPolicy policy = BootActions.policy(action);
        if (policy == null) {
            // Manifest 只注册表内 action；仍防御一下（厂商/系统可能带别名）。
            Log.w(TAG, "BootSignal 未知 action，忽略: " + action);
            return;
        }
        BootSource source = policy.source();

        Context app = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(app != null ? app : context);
        boolean userExited = prefs.userExited();
        if (userExited && prefs.userExitedAtWall() > 0L
                && prefs.userExitedAtWall() < AppPrefs.bootWallClock()) {
            // 退出发生在上一次开机；TCL 收不到 BOOT_COMPLETED 时也要靠这次写清掉。
            prefs.setUserExited(false);
            userExited = false;
        }
        long now = SystemClock.elapsedRealtime();
        long lastSignal = lastSignal(policy.kind());
        BootCoordinator.Decision decision = BootCoordinator.decide(
                policy, now, prefs.bootAutostart(), userExited, lastSignal);

        LAST_SIGNALS.put(policy.kind(), now);
        record(prefs, source, action, now, decision);

        Log.i(TAG, "BootSignal " + source.name() + "(" + source.label() + ")"
                + " kind=" + policy.kind().name() + " delay=" + policy.launchDelayMs() + "ms"
                + " action=" + action + " -> " + decision.name() + "(" + decision.label() + ")");

        if (decision == BootCoordinator.Decision.LAUNCH) {
            // 批次 23：直接拉起；延迟由策略决定（批次 28）。DIRECT_LAUNCH=false 时仍走等桌面。
            boolean scheduled = BootLauncher.launch(app != null ? app : context, policy);
            if (scheduled) {
                BootSignalReceiver.record(app != null ? app : context, source,
                        BootLaunchPlan.DIRECT_LAUNCH ? "SCHEDULE_DIRECT" : "SCHEDULE_WAIT");
            }
        }
    }

    private static void record(AppPrefs prefs, BootSource source, String action, long now,
                               BootCoordinator.Decision decision) {
        BootLog log = BootLog.parse(prefs.bootLog());
        log.record(source, action, now, System.currentTimeMillis(), decision);
        prefs.setBootLog(log.serialize());
    }

    /** 等待/拉起阶段的追加记录（无信号判定，沿用 LAUNCH 决定）。 */
    static void record(Context context, BootSource source, String action) {
        Context app = context == null ? null : context.getApplicationContext();
        Context target = app != null ? app : context;
        if (target == null) {
            return;
        }
        record(new AppPrefs(target), source == null ? BootSource.VENDOR : source, action,
                SystemClock.elapsedRealtime(), BootCoordinator.Decision.LAUNCH);
    }

    private static long lastSignal(BootActionPolicy.Kind kind) {
        Long value = LAST_SIGNALS.get(kind);
        return value == null ? BootCoordinator.NO_SIGNAL : value.longValue();
    }

    /**
     * 设置页开关的状态来源。receiver 始终静态注册（关闭时也要记 SKIP_DISABLED 日志），
     * 因此这里读持久化开关，而不是组件 enabled 状态。
     */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return new AppPrefs(context).bootAutostart();
    }

    /**
     * 写开关；同时确保组件是 enabled 的（升级前可能被旧版本 disable 过）。
     * 关闭时不 disable 组件：否则收不到信号，诊断页看不到 SKIP_DISABLED。
     */
    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        new AppPrefs(context).setBootAutostart(enabled);
        ComponentName component = new ComponentName(context, BootSignalReceiver.class);
        try {
            context.getPackageManager().setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            Log.w(TAG, "BootSignal 启用 receiver 失败", e);
        }
    }
}
