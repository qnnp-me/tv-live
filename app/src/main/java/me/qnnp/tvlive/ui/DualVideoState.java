package me.qnnp.tvlive.ui;

/**
 * 两个 {@code TextureView} 的显示状态（纯 Java，可在 JVM 单测）。
 *
 * <p>批次 10：{@link TextureVideoLayer} 用 alpha 交换两路画面，谁是「前台」只由这里决定。
 * KEEP 模式恒为 A；DUAL 模式首帧后 {@link #swap()} 翻到另一路。
 * 状态必须独立于 View 存在（旋转/重建布局时由 Activity 重新套用），不能只存在 View 里。</p>
 */
public final class DualVideoState {

    private boolean activeA = true;
    /** 双画面是否启用：false（KEEP/DIRECT）只 attach A，不占 B 的 SurfaceTexture。 */
    private boolean dual;

    /** 前台是否 textureA。 */
    public boolean isActiveA() {
        return activeA;
    }

    /** 双画面是否启用（true 才 attach/显示 textureB）。 */
    public boolean isDual() {
        return dual;
    }

    /**
     * 批次 12：KEEP/DIRECT 只有 A 一路，关闭双画面时 B 可能正前台，必须归位到 A，
     * 否则 detach B 后会显示一路已释放的黑色画面。
     */
    public void setDual(boolean dual) {
        this.dual = dual;
        if (!dual) {
            activeA = true;
        }
    }

    /**
     * 指定前台；切换布局/进入双画面时把 Activity 记住的槽位重新套到新 View 上。
     * 重复设置同一值是幂等的。单画面模式下恒为 A（B 没有 Surface，不能当前台）。
     */
    public void setActiveA(boolean activeA) {
        this.activeA = dual ? activeA : true;
    }

    /** 首帧到达：前台切到另一路，返回切换后前台是否为 A。单画面模式不交换。 */
    public boolean swap() {
        if (!dual) {
            return true;
        }
        activeA = !activeA;
        return activeA;
    }

    /** 回到 A 前台（退出双画面 / 从极速直切切回纹理模式）。 */
    public void reset() {
        activeA = true;
    }

    public float alphaA() {
        return activeA ? 1f : 0f;
    }

    public float alphaB() {
        return activeA ? 0f : 1f;
    }
}
