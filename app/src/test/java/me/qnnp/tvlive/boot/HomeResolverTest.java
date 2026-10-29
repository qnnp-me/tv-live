package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** 默认桌面解析：有默认桌面 / 没有默认桌面（或空包名）/ 查询异常 / 无 PackageManager。 */
public class HomeResolverTest {

    @Test
    public void returnsDefaultHomePackage() {
        String pkg = HomeResolver.homePackage(new HomeResolver.HomeQuery() {
            @Override
            public String resolveHomePackage() {
                return "com.vendor.home";
            }
        });
        assertEquals("com.vendor.home", pkg);
    }

    @Test
    public void returnsNullWhenNoDefaultHome() {
        assertNull(HomeResolver.homePackage(new HomeResolver.HomeQuery() {
            @Override
            public String resolveHomePackage() {
                return null;
            }
        }));
        assertNull("空包名同样视为不可判定", HomeResolver.homePackage(new HomeResolver.HomeQuery() {
            @Override
            public String resolveHomePackage() {
                return "   ";
            }
        }));
    }

    @Test
    public void returnsNullWhenPackageManagerThrows() {
        assertNull(HomeResolver.homePackage(new HomeResolver.HomeQuery() {
            @Override
            public String resolveHomePackage() {
                throw new SecurityException("no permission");
            }
        }));
    }

    @Test
    public void returnsNullWithoutPackageManagerOrQuery() {
        assertNull(HomeResolver.homePackage((android.content.pm.PackageManager) null));
        assertNull(HomeResolver.homePackage((HomeResolver.HomeQuery) null));
    }

    @Test
    public void trimsPackageName() {
        assertEquals("com.vendor.home", HomeResolver.homePackage(new HomeResolver.HomeQuery() {
            @Override
            public String resolveHomePackage() {
                return "  com.vendor.home  ";
            }
        }));
    }
}
