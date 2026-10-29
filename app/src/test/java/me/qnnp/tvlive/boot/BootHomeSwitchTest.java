package me.qnnp.tvlive.boot;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** 批次 25：切回 TCL 主页信源的 Intent 目标与失败容错。 */
public class BootHomeSwitchTest {

    @Test
    public void intentTargetsTclSourceManagerShortcutReceiver() {
        assertEquals("android.intent.action.ENTER_HOME", BootHomeSwitch.ACTION_ENTER_HOME);
        assertEquals("com.tcl.sourcemananger", BootHomeSwitch.COMPONENT_PACKAGE);
        assertEquals("com.tcl.insrc.ShortcutKeyReceiver", BootHomeSwitch.COMPONENT_CLASS);
        assertNotNull("Intent 构造不能依赖运行期状态", BootHomeSwitch.intent());
    }

    @Test
    public void sendReportsTrueWhenBroadcastDispatched() {
        final Intent[] sent = new Intent[1];
        boolean ok = BootHomeSwitch.send(null, new BootHomeSwitch.Sender() {
            @Override
            public void send(Context context, Intent intent) {
                sent[0] = intent;
            }
        });
        assertTrue("广播发出去就算成功", ok);
        assertNotNull("必须把切信源 Intent 交给系统", sent[0]);
    }

    @Test
    public void sendReturnsFalseWhenBroadcastThrows() {
        boolean ok = BootHomeSwitch.send(null, new BootHomeSwitch.Sender() {
            @Override
            public void send(Context context, Intent intent) {
                throw new SecurityException("厂商拦截后台广播");
            }
        });
        assertFalse("异常必须吞掉并返回 false", ok);
    }

    @Test
    public void sendWithoutContextFailsSilently() {
        assertFalse(BootHomeSwitch.send(null));
    }
}
