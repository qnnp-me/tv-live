package me.qnnp.tvlive.boot;

import android.content.Context;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 批次 26：启动前厂商动作表的设备判定与执行语义。 */
public class BootHooksTest {

    /** 只用来验证 runAll 的执行顺序/短路，不碰系统 API。 */
    private static final class FakeHook implements BootLaunchHook {

        private final String name;
        private final boolean result;
        private final boolean throwing;
        private int runs;

        FakeHook(String name, boolean result, boolean throwing) {
            this.name = name;
            this.result = result;
            this.throwing = throwing;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean run(Context context) {
            runs++;
            if (throwing) {
                throw new IllegalStateException("厂商接口崩了");
            }
            return result;
        }
    }

    /** 真实 hook 必须能被复用（每次开机只跑一次，但不能有状态残留）。 */
    private static final class RecordingHook implements BootLaunchHook {

        private int runs;
        private final boolean result;

        RecordingHook(boolean result) {
            this.result = result;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean run(Context context) {
            runs++;
            return result;
        }
    }

    @Test
    public void tclManufacturerGetsTclHomeHook() {
        List<BootLaunchHook> hooks = BootHooks.table("TCL", false);
        assertEquals(1, hooks.size());
        assertEquals(TclHomeHook.NAME, hooks.get(0).name());
        assertTrue(hooks.get(0) instanceof TclHomeHook);
    }

    /**
     * 批次 27：TCL L32F3800A 第一次开机时 {@code com.tcl.sourcemananger} 还没起来，
     * 组件探测必然 false；厂商名命中就必须给钩子，否则 HDMI 信源下「有画无声」。
     */
    @Test
    public void tclManufacturerGetsHookEvenWithoutComponent() {
        List<BootLaunchHook> hooks = BootHooks.table("TCL", (Context) null);
        assertEquals("TCL 不依赖组件探测", 1, hooks.size());
        assertTrue(hooks.get(0) instanceof TclHomeHook);
    }

    @Test
    public void nonTclWithoutComponentGetsNoHooks() {
        assertTrue("非 TCL + 无组件 = 空表，通用设备行为不变",
                BootHooks.table("Generic", (Context) null).isEmpty());
        assertTrue("厂商未知 + 无组件也不能误判成 TCL",
                BootHooks.table((String) null, (Context) null).isEmpty());
    }

    @Test
    public void nonTclWithTclComponentGetsHook() {
        assertTrue("白牌/贴牌机装了 TCL 信源服务也要切源",
                BootHooks.table("Generic", true).get(0) instanceof TclHomeHook);
    }

    @Test
    public void manufacturerMatchIsCaseInsensitive() {
        assertEquals(1, BootHooks.table("tcl-television", false).size());
        assertEquals(1, BootHooks.table("Tcl Electronics", false).size());
    }

    @Test
    public void genericDeviceGetsNoHooks() {
        assertTrue(BootHooks.table("Generic", false).isEmpty());
        assertTrue("厂商未知也不能误判成 TCL", BootHooks.table((String) null, false).isEmpty());
    }

    @Test
    public void tclComponentWinsOverManufacturer() {
        assertEquals("白牌机装了 TCL 组件也要切源", 1, BootHooks.table("Generic", true).size());
    }

    @Test
    public void runAllShortCircuitsOnFirstTrue() {
        FakeHook first = new FakeHook("a", false, false);
        FakeHook second = new FakeHook("b", true, false);
        FakeHook third = new FakeHook("c", true, false);
        List<BootLaunchHook> hooks = new ArrayList<BootLaunchHook>(Arrays.asList(first, second, third));

        assertTrue(BootHooks.runAll(hooks, null));
        assertEquals(1, first.runs);
        assertEquals(1, second.runs);
        assertEquals("命中后不再执行后面的钩子", 0, third.runs);
    }

    @Test
    public void runAllKeepsGoingAfterHookThrows() {
        FakeHook broken = new FakeHook("broken", true, true);
        RecordingHook ok = new RecordingHook(true);

        assertTrue("异常钩子不能阻断后面的钩子", BootHooks.runAll(
                Arrays.<BootLaunchHook>asList(broken, ok), null));
        assertEquals(1, broken.runs);
        assertEquals(1, ok.runs);
    }

    @Test
    public void runAllReportsNoDelayWhenNothingActed() {
        assertFalse("没有钩子 = 不延迟", BootHooks.runAll(null, null));
        assertFalse(BootHooks.runAll(new ArrayList<BootLaunchHook>(), null));

        RecordingHook quiet = new RecordingHook(false);
        assertFalse("钩子说不用等就不延迟", BootHooks.runAll(
                Arrays.<BootLaunchHook>asList(quiet), null));
        assertEquals(1, quiet.runs);
    }

    @Test
    public void runAllSkipsNullEntries() {
        RecordingHook ok = new RecordingHook(true);
        List<BootLaunchHook> hooks = new ArrayList<BootLaunchHook>();
        hooks.add(null);
        hooks.add(ok);
        assertTrue(BootHooks.runAll(hooks, null));
    }
}
