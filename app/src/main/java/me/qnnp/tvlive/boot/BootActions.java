package me.qnnp.tvlive.boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开机信号 action 表：action 字符串 → {@link BootActionPolicy} 的唯一映射。
 *
 * <p>批次 28 起，表里不只是「来源」，还带分类、拉起延迟、有效窗口与去重窗口。
 * 加新 action 只改这张表；Manifest 的 intent-filter 必须同步列出
 * {@link #allActions()}（静态注册无法在运行时补 action）。</p>
 *
 * <p>未识别的 action 返回 null，由调用方忽略。</p>
 */
public final class BootActions {

    // 通用 action（AOSP 与历史厂商别名）。
    public static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    public static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    public static final String ACTION_QUICKBOOT_POWERON_HTC = "com.htc.intent.action.QUICKBOOT_POWERON";
    public static final String ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE";
    public static final String ACTION_USER_PRESENT = "android.intent.action.USER_PRESENT";
    public static final String ACTION_MEDIA_MOUNTED = "android.intent.action.MEDIA_MOUNTED";
    public static final String ACTION_MEDIA_SCANNER_FINISHED = "android.intent.action.MEDIA_SCANNER_FINISHED";
    public static final String ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";

    // 厂商候选：联发科与 TCL 常见私有开机 action。
    public static final String ACTION_MEDIATEK_BOOT_COMPLETED = "mediatek.intent.action.BOOT_COMPLETED";
    public static final String ACTION_TCL_BOOT_COMPLETED = "com.tcl.intent.action.BOOT_COMPLETED";
    public static final String ACTION_TCL_TV_BOOT_COMPLETED = "com.tcl.tv.action.BOOT_COMPLETED";
    public static final String ACTION_TCL_LEGACY_BOOT_COMPLETED = "com.tcl.action.BOOT_COMPLETED";

    private static final BootActionPolicy POLICY_BOOT_COMPLETED = new BootActionPolicy(
            BootSource.BOOT_COMPLETED, BootActionPolicy.Kind.BOOT,
            BootActionPolicy.BOOT_DELAY_MS, BootActionPolicy.BOOT_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_QUICKBOOT = new BootActionPolicy(
            BootSource.QUICKBOOT_POWERON, BootActionPolicy.Kind.BOOT,
            BootActionPolicy.BOOT_DELAY_MS, BootActionPolicy.BOOT_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_CONNECTIVITY = new BootActionPolicy(
            BootSource.CONNECTIVITY_CHANGE, BootActionPolicy.Kind.NETWORK,
            BootActionPolicy.NETWORK_DELAY_MS, BootActionPolicy.BOOT_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_USER_PRESENT = new BootActionPolicy(
            BootSource.USER_PRESENT, BootActionPolicy.Kind.USER,
            BootActionPolicy.USER_DELAY_MS, BootActionPolicy.BOOT_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_MEDIA_MOUNTED = new BootActionPolicy(
            BootSource.MEDIA_MOUNTED, BootActionPolicy.Kind.DEVICE,
            BootActionPolicy.DEVICE_DELAY_MS, BootActionPolicy.DEVICE_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_MEDIA_SCANNER = new BootActionPolicy(
            BootSource.MEDIA_SCANNER_FINISHED, BootActionPolicy.Kind.DEVICE,
            BootActionPolicy.DEVICE_DELAY_MS, BootActionPolicy.DEVICE_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_USB = new BootActionPolicy(
            BootSource.USB_DEVICE_ATTACHED, BootActionPolicy.Kind.DEVICE,
            BootActionPolicy.DEVICE_DELAY_MS, BootActionPolicy.DEVICE_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);
    private static final BootActionPolicy POLICY_VENDOR = new BootActionPolicy(
            BootSource.VENDOR, BootActionPolicy.Kind.BOOT,
            BootActionPolicy.BOOT_DELAY_MS, BootActionPolicy.BOOT_WINDOW_MS,
            BootActionPolicy.DEDUP_WINDOW_MS);

    /** 必须在所有 POLICY_* 之后初始化，否则 buildTable() 读到 null。 */
    private static final Map<String, BootActionPolicy> TABLE = buildTable();
    private static final List<String> ALL = Collections.unmodifiableList(
            new ArrayList<String>(TABLE.keySet()));

    private BootActions() {
    }

    /** 单一策略表；加厂商只改这里，并同步 Manifest。 */
    private static Map<String, BootActionPolicy> buildTable() {
        Map<String, BootActionPolicy> table = new LinkedHashMap<String, BootActionPolicy>();
        // 通用：系统开机动作直接拉起；网络/用户唤醒留出系统稳定时间；
        // 外设/存储只在开机早期算数，且延迟最长。
        table.put(ACTION_BOOT_COMPLETED, POLICY_BOOT_COMPLETED);
        table.put(ACTION_QUICKBOOT_POWERON, POLICY_QUICKBOOT);
        table.put(ACTION_QUICKBOOT_POWERON_HTC, POLICY_QUICKBOOT);
        table.put(ACTION_CONNECTIVITY_CHANGE, POLICY_CONNECTIVITY);
        table.put(ACTION_USER_PRESENT, POLICY_USER_PRESENT);
        table.put(ACTION_MEDIA_MOUNTED, POLICY_MEDIA_MOUNTED);
        table.put(ACTION_MEDIA_SCANNER_FINISHED, POLICY_MEDIA_SCANNER);
        table.put(ACTION_USB_DEVICE_ATTACHED, POLICY_USB);
        // 厂商；新增厂商在这里加行，Manifest 同步 intent-filter。
        table.put(ACTION_MEDIATEK_BOOT_COMPLETED, POLICY_VENDOR);
        table.put(ACTION_TCL_BOOT_COMPLETED, POLICY_VENDOR);
        table.put(ACTION_TCL_TV_BOOT_COMPLETED, POLICY_VENDOR);
        table.put(ACTION_TCL_LEGACY_BOOT_COMPLETED, POLICY_VENDOR);
        return Collections.unmodifiableMap(table);
    }

    /** 解析 action 的完整策略；未知/null 返回 null。 */
    public static BootActionPolicy policy(String action) {
        if (action == null) {
            return null;
        }
        return TABLE.get(action);
    }

    /** 解析 action 的来源；未知/null 返回 null（诊断/兼容旧调用）。 */
    public static BootSource resolve(String action) {
        BootActionPolicy policy = policy(action);
        return policy == null ? null : policy.source();
    }

    public static boolean knows(String action) {
        return resolve(action) != null;
    }

    /** Manifest 需要静态注册的全部 action，顺序与表一致。 */
    public static List<String> allActions() {
        return ALL;
    }

    /** 某个来源对应的全部 action（诊断/测试用）。 */
    public static List<String> actionsFor(BootSource source) {
        List<String> result = new ArrayList<String>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, BootActionPolicy> entry : TABLE.entrySet()) {
            if (entry.getValue().source() == source) {
                result.add(entry.getKey());
            }
        }
        return Collections.unmodifiableList(result);
    }
}
