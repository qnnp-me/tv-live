package me.qnnp.tvlive.boot;

/**
 * 开机信号的逻辑来源：诊断日志显示 {@link #label()}，判定/去重按枚举值分组。
 *
 * <p>一个来源可以对应多个 action（例如 QUICKBOOT 有 AOSP 与 HTC 两个历史 action），
 * 具体映射只在 {@link BootActions} 那张表里维护。</p>
 */
public enum BootSource {

    BOOT_COMPLETED("系统开机完成"),
    QUICKBOOT_POWERON("快速开机"),
    CONNECTIVITY_CHANGE("网络变化"),
    USER_PRESENT("用户唤醒"),
    MEDIA_MOUNTED("存储挂载"),
    MEDIA_SCANNER_FINISHED("媒体扫描完成"),
    USB_DEVICE_ATTACHED("USB 设备接入"),
    /** 厂商私有 action；具体是哪一个看日志里的 action 字段。 */
    VENDOR("厂商自定义");

    private final String label;

    BootSource(String label) {
        this.label = label;
    }

    /** 诊断/日志用中文名。 */
    public String label() {
        return label;
    }
}
