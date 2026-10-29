package me.qnnp.tvlive.ui;

import android.content.Context;

/**
 * UI 构建共用的资源查表：颜色 / 尺寸只在这里读一遍。
 *
 * <p>各 View 以前各写一份私有 {@code dimen}/{@code color}，实现完全相同；统一后
 * 新增行/浮层只需复用这两个入口。</p>
 */
final class Res {

    private Res() {
    }

    static int dimen(Context context, int resId) {
        return context.getResources().getDimensionPixelSize(resId);
    }

    static int color(Context context, int resId) {
        return context.getResources().getColor(resId);
    }
}
