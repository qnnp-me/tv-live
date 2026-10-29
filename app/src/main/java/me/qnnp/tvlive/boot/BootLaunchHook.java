package me.qnnp.tvlive.boot;

import android.content.Context;

/**
 * 批次 26：「拉起 App 之前要做的厂商动作」钩子。
 *
 * <p>{@link BootActions} 管「哪些广播算开机信号」，本接口管「开机后拉起前要做什么」，
 * 两张表分开：加厂商开机 action 改 {@link BootActions}，加厂商前置动作改
 * {@link BootHooks#table()}。</p>
 *
 * <p>实现必须是幂等、可重入的：{@link BootHooks#runAll} 每次开机只跑一遍，但异常/重复
 * 信号不影响正确性。</p>
 */
public interface BootLaunchHook {

    /** 日志用的短名字，例如 {@code tcl-home}。 */
    String name();

    /**
     * 执行动作。
     *
     * @return true 表示做了异步动作（信源切换/服务重启等），需要延迟再拉起；
     *         false 表示这台设备不需要延迟，直接拉起
     */
    boolean run(Context context);
}
