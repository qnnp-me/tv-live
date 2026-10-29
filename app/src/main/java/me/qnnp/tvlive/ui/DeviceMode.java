package me.qnnp.tvlive.ui;

/**
 * 设备判定（纯 Java，可在 JVM 单测）：按最小屏幕宽度决定「手机」还是「电视」。
 *
 * <p>批次 5a 起同一个 APK 同时跑手机和电视：{@code smallestScreenWidthDp < 600} 判为手机
 * （手机竖屏普遍 sw≈360–430dp），否则沿用电视布局（电视通常 sw720dp）。判定只看最小宽度，
 * 不依赖 orientation / 是否有触摸，因此旋转不会改变设备类型；旋转只决定手机用竖屏布局
 * 还是复用电视布局（见 {@link #isPhonePortrait(int, boolean)}）。</p>
 */
public final class DeviceMode {

    /** 手机与电视的分界：sw < 600dp 为手机。 */
    public static final int PHONE_MAX_SW_DP = 600;

    private DeviceMode() {
    }

    /** sw 是 {@code Configuration.smallestScreenWidthDp}。 */
    public static boolean isPhone(int smallestScreenWidthDp) {
        return smallestScreenWidthDp < PHONE_MAX_SW_DP;
    }

    /** 手机会话里，只有竖屏用 {@code PhoneLayout}；横屏手机复用电视布局。 */
    public static boolean isPhonePortrait(int smallestScreenWidthDp, boolean portrait) {
        return portrait && isPhone(smallestScreenWidthDp);
    }

    /**
     * 是否需要独立全屏态：批次 5c 起只有手机竖屏保留 {@code UiState.FULLSCREEN}。
     * 电视与手机横屏的「全屏」就是导航隐藏（画面铺满），不再设置布局 fullscreen。
     */
    public static boolean usesFullscreen(boolean phoneDevice, boolean phonePortrait) {
        return phoneDevice && phonePortrait;
    }
}
