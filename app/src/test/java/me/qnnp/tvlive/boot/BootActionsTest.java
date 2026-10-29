package me.qnnp.tvlive.boot;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** action → 来源映射表；加厂商只需加一行类目测试。 */
public class BootActionsTest {

    @Test
    public void genericActionsMapToTheirSources() {
        assertEquals(BootSource.BOOT_COMPLETED,
                BootActions.resolve(BootActions.ACTION_BOOT_COMPLETED));
        assertEquals(BootSource.CONNECTIVITY_CHANGE,
                BootActions.resolve(BootActions.ACTION_CONNECTIVITY_CHANGE));
        assertEquals(BootSource.USER_PRESENT,
                BootActions.resolve(BootActions.ACTION_USER_PRESENT));
        assertEquals(BootSource.MEDIA_MOUNTED,
                BootActions.resolve(BootActions.ACTION_MEDIA_MOUNTED));
        assertEquals(BootSource.MEDIA_SCANNER_FINISHED,
                BootActions.resolve(BootActions.ACTION_MEDIA_SCANNER_FINISHED));
        assertEquals(BootSource.USB_DEVICE_ATTACHED,
                BootActions.resolve(BootActions.ACTION_USB_DEVICE_ATTACHED));
    }

    @Test
    public void bothQuickbootActionsShareOneSource() {
        assertEquals(BootSource.QUICKBOOT_POWERON,
                BootActions.resolve(BootActions.ACTION_QUICKBOOT_POWERON));
        assertEquals(BootSource.QUICKBOOT_POWERON,
                BootActions.resolve(BootActions.ACTION_QUICKBOOT_POWERON_HTC));
        assertEquals(2, BootActions.actionsFor(BootSource.QUICKBOOT_POWERON).size());
    }

    @Test
    public void vendorActionsMapToVendorSource() {
        assertEquals(BootSource.VENDOR,
                BootActions.resolve(BootActions.ACTION_MEDIATEK_BOOT_COMPLETED));
        assertEquals(BootSource.VENDOR,
                BootActions.resolve(BootActions.ACTION_TCL_BOOT_COMPLETED));
        assertEquals(BootSource.VENDOR,
                BootActions.resolve(BootActions.ACTION_TCL_TV_BOOT_COMPLETED));
        assertEquals(BootSource.VENDOR,
                BootActions.resolve(BootActions.ACTION_TCL_LEGACY_BOOT_COMPLETED));
    }

    @Test
    public void vendorTableIsExtensibleThroughOneList() {
        List<String> vendor = BootActions.actionsFor(BootSource.VENDOR);
        assertEquals(4, vendor.size());
        for (String action : vendor) {
            assertTrue("vendor action 必须能解析: " + action, BootActions.knows(action));
        }
        // 加入表后自动出现在 Manifest 同步清单里（静态注册由清单镜像）。
        for (String action : vendor) {
            assertTrue(BootActions.allActions().contains(action));
        }
    }

    @Test
    public void unknownOrNullActionReturnsNull() {
        assertNull(BootActions.resolve(null));
        assertNull(BootActions.resolve(""));
        assertNull(BootActions.resolve("com.example.intent.action.NOT_A_BOOT_SIGNAL"));
        assertFalse(BootActions.knows(null));
    }

    @Test
    public void everyRegisteredActionResolves() {
        assertEquals(12, BootActions.allActions().size());
        for (String action : BootActions.allActions()) {
            assertTrue("未映射: " + action, BootActions.resolve(action) != null);
        }
    }

    @Test(expected = UnsupportedOperationException.class)
    public void allActionsIsReadOnly() {
        BootActions.allActions().add("com.example.extra");
    }

    @Test
    public void everyRegisteredActionHasPolicy() {
        for (String action : BootActions.allActions()) {
            BootActionPolicy policy = BootActions.policy(action);
            assertTrue("缺少策略: " + action, policy != null);
            assertEquals("来源必须与策略一致: " + action,
                    policy.source(), BootActions.resolve(action));
        }
    }

    @Test
    public void policyClassifiesBootNetworkUserDevice() {
        assertEquals(BootActionPolicy.Kind.BOOT,
                BootActions.policy(BootActions.ACTION_BOOT_COMPLETED).kind());
        assertEquals(BootActionPolicy.Kind.NETWORK,
                BootActions.policy(BootActions.ACTION_CONNECTIVITY_CHANGE).kind());
        assertEquals(BootActionPolicy.Kind.USER,
                BootActions.policy(BootActions.ACTION_USER_PRESENT).kind());
        assertEquals(BootActionPolicy.Kind.DEVICE,
                BootActions.policy(BootActions.ACTION_USB_DEVICE_ATTACHED).kind());
        assertEquals(BootActionPolicy.Kind.DEVICE,
                BootActions.policy(BootActions.ACTION_MEDIA_MOUNTED).kind());
        assertEquals(BootActionPolicy.Kind.BOOT,
                BootActions.policy(BootActions.ACTION_TCL_BOOT_COMPLETED).kind());
    }

    @Test
    public void unknownPolicyIsNull() {
        assertNull(BootActions.policy(null));
        assertNull(BootActions.policy("com.example.intent.action.NOT_A_BOOT_SIGNAL"));
    }
}
