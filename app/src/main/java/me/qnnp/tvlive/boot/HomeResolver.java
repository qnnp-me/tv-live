package me.qnnp.tvlive.boot;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * 当前默认桌面（HOME）的包名；不硬编码任何厂商包名。
 *
 * <p>用 {@code Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)} 问 PackageManager，
 * 得到的就是系统此刻认定的桌面，TCL / AOSP / 第三方桌面都适用。查询失败、
 * 没有默认桌面、解析结果为空一律返回 {@code null}，由调用方走「不可判定」兜底。</p>
 *
 * <p>{@link HomeQuery} 抽出查询动作，JVM 单测可直接注入假结果验证成功/为空/抛异常
 * 三条路径，不依赖 Robolectric。</p>
 */
public final class HomeResolver {

    private HomeResolver() {
    }

    /** 包内可见：把 PackageManager 查询抽成一行，便于单测替换。 */
    interface HomeQuery {
        String resolveHomePackage();
    }

    /** 默认桌面包名；无法判定返回 null。 */
    public static String homePackage(PackageManager pm) {
        if (pm == null) {
            return null;
        }
        return homePackage(new HomeQuery() {
            @Override
            public String resolveHomePackage() {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo info = pm.resolveActivity(home, 0);
                if (info == null || info.activityInfo == null) {
                    return null;
                }
                return info.activityInfo.packageName;
            }
        });
    }

    static String homePackage(HomeQuery query) {
        if (query == null) {
            return null;
        }
        try {
            String pkg = query.resolveHomePackage();
            if (pkg == null) {
                return null;
            }
            pkg = pkg.trim();
            return pkg.isEmpty() ? null : pkg;
        } catch (RuntimeException e) {
            // 厂商 PackageManager 抛 SecurityException 等：当作不可判定，绝不崩开机流程。
            return null;
        }
    }
}
