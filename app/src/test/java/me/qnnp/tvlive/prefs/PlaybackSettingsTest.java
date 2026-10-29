package me.qnnp.tvlive.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import me.qnnp.tvlive.player.SwitchMode;

/** 设置页播放/导航/显示分组的默认值、循环顺序与持久化（内存 Store）。 */
public class PlaybackSettingsTest {

    @Test
    public void defaultsMatchSpec() {
        PlaybackSettings settings = new PlaybackSettings(new MemoryStore());

        assertEquals(SwitchMode.KEEP, settings.switchMode());
        assertEquals(RenderMode.AUTO, settings.renderMode());
        assertEquals(Aspect.FIT, settings.aspect());
        assertTrue(settings.firstFrameBoost());
        assertTrue(settings.transitionCard());
        assertTrue("显示网速默认开", settings.showSpeed());
        assertFalse(settings.switchModeHintSeen());
        assertEquals(NavWidth.STANDARD, settings.navWidth());
        assertEquals("自动收起默认 8 秒", 8, settings.autoHideSeconds());
        assertTrue("时钟角标默认开", settings.clockBadge());
        assertFalse("高级分组默认收起", settings.advancedOpen());
    }

    @Test
    public void switchModeCyclesKeepDirectDualAndWraps() {
        PlaybackSettings settings = new PlaybackSettings(new MemoryStore());

        assertEquals(SwitchMode.DIRECT, settings.cycleSwitchMode());
        assertEquals(SwitchMode.DUAL, settings.cycleSwitchMode());
        assertEquals(SwitchMode.KEEP, settings.cycleSwitchMode());
        assertEquals(SwitchMode.KEEP, settings.switchMode());
    }

    @Test
    public void valuesPersistAcrossInstances() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings first = new PlaybackSettings(store);
        first.setSwitchMode(SwitchMode.DIRECT);
        first.setFirstFrameBoost(false);
        first.setTransitionCard(false);
        first.setShowSpeed(false);

        PlaybackSettings reopened = new PlaybackSettings(store);
        assertEquals(SwitchMode.DIRECT, reopened.switchMode());
        assertFalse(reopened.firstFrameBoost());
        assertFalse(reopened.transitionCard());
        assertFalse(reopened.showSpeed());
    }

    @Test
    public void showSpeedCyclesOnOffAndPersists() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertTrue(settings.showSpeed());
        assertFalse("开 → 关", settings.cycleShowSpeed());
        assertFalse(settings.showSpeed());
        assertFalse("重新打开设置页仍是关", new PlaybackSettings(store).showSpeed());

        assertTrue("关 → 开", settings.cycleShowSpeed());
        assertTrue(settings.showSpeed());
        assertTrue(new PlaybackSettings(store).showSpeed());
    }

    @Test
    public void switchModeHintAppearsOnlyOnce() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);
        assertFalse(settings.switchModeHintSeen());

        settings.markSwitchModeHintSeen();
        assertTrue(settings.switchModeHintSeen());
        assertTrue("重开设置页看到的仍是已提示", new PlaybackSettings(store).switchModeHintSeen());
    }

    @Test
    public void unknownStoredModeFallsBackToKeep() {
        MemoryStore store = new MemoryStore();
        store.putString(PlaybackSettings.KEY_SWITCH_MODE, "future-mode");

        assertEquals(SwitchMode.KEEP, new PlaybackSettings(store).switchMode());
    }

    @Test
    public void renderModeCyclesAutoSurfaceTextureAndWraps() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertEquals(RenderMode.AUTO, settings.renderMode());
        assertEquals(RenderMode.SURFACE, settings.cycleRenderMode());
        assertEquals(RenderMode.TEXTURE, settings.cycleRenderMode());
        assertEquals(RenderMode.AUTO, settings.cycleRenderMode());
        assertEquals(RenderMode.AUTO, settings.renderMode());
        assertEquals("循环后仍是自动，再从自动开始循环", RenderMode.SURFACE,
                new PlaybackSettings(store).cycleRenderMode());
    }

    @Test
    public void renderModePersistsAcrossInstances() {
        MemoryStore store = new MemoryStore();
        new PlaybackSettings(store).setRenderMode(RenderMode.SURFACE);

        assertEquals("重新打开设置页仍是 SurfaceView", RenderMode.SURFACE,
                new PlaybackSettings(store).renderMode());
        assertEquals("切台方式不受渲染方式影响", SwitchMode.KEEP,
                new PlaybackSettings(store).switchMode());
    }

    @Test
    public void unknownStoredRenderModeFallsBackToAuto() {
        MemoryStore store = new MemoryStore();
        store.putString(PlaybackSettings.KEY_RENDER_MODE, "opengl");

        assertEquals(RenderMode.AUTO, new PlaybackSettings(store).renderMode());
    }

    @Test
    public void aspectCyclesFitStretchCropAndPersists() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertEquals(Aspect.FIT, settings.aspect());
        assertEquals(Aspect.STRETCH, settings.cycleAspect());
        assertEquals(Aspect.CROP, settings.cycleAspect());
        assertEquals(Aspect.FIT, settings.cycleAspect());
        assertEquals("重新打开设置页仍是适应", Aspect.FIT,
                new PlaybackSettings(store).aspect());

        settings.setAspect(Aspect.CROP);
        assertEquals("裁剪持久化", Aspect.CROP, new PlaybackSettings(store).aspect());
    }

    @Test
    public void unknownStoredAspectFallsBackToFit() {
        MemoryStore store = new MemoryStore();
        store.putString(PlaybackSettings.KEY_ASPECT, "zoom");

        assertEquals(Aspect.FIT, new PlaybackSettings(store).aspect());
    }

    @Test
    public void navWidthCyclesNarrowStandardWideAndPersists() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertEquals(NavWidth.STANDARD, settings.navWidth());
        assertEquals(NavWidth.WIDE, settings.cycleNavWidth());
        assertEquals(NavWidth.NARROW, settings.cycleNavWidth());
        assertEquals(NavWidth.STANDARD, settings.cycleNavWidth());

        settings.setNavWidth(NavWidth.NARROW);
        assertEquals(NavWidth.NARROW, new PlaybackSettings(store).navWidth());
        assertEquals(0.38f, new PlaybackSettings(store).navWidth().ratio(), 0.0001f);
    }

    @Test
    public void unknownStoredNavWidthFallsBackToStandard() {
        MemoryStore store = new MemoryStore();
        store.putString(PlaybackSettings.KEY_NAV_WIDTH, "huge");

        assertEquals(NavWidth.STANDARD, new PlaybackSettings(store).navWidth());
    }

    @Test
    public void autoHideCyclesFiveEightFifteenOff() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertEquals(8, settings.autoHideSeconds());
        assertEquals(15, settings.cycleAutoHide().seconds());
        assertEquals(0, settings.cycleAutoHide().seconds());
        assertEquals(5, settings.cycleAutoHide().seconds());
        assertEquals(8, settings.cycleAutoHide().seconds());

        settings.setAutoHide(AutoHide.OFF);
        assertEquals("不收起持久化", 0, new PlaybackSettings(store).autoHideSeconds());
        assertFalse(new PlaybackSettings(store).autoHide().isEnabled());
    }

    @Test
    public void unknownStoredAutoHideFallsBackToEight() {
        MemoryStore store = new MemoryStore();
        store.putString(PlaybackSettings.KEY_AUTO_HIDE, "30");

        assertEquals(8, new PlaybackSettings(store).autoHideSeconds());
        store.putString(PlaybackSettings.KEY_AUTO_HIDE, "abc");
        assertEquals(8, new PlaybackSettings(store).autoHideSeconds());
    }

    @Test
    public void clockBadgeTogglesAndPersists() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertTrue(settings.clockBadge());
        assertFalse(settings.cycleClockBadge());
        assertFalse("关持久化", new PlaybackSettings(store).clockBadge());
        assertTrue(settings.cycleClockBadge());
        assertTrue(new PlaybackSettings(store).clockBadge());
    }

    @Test
    public void advancedOpenTogglesAndPersists() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);

        assertFalse(settings.advancedOpen());
        assertTrue(settings.toggleAdvancedOpen());
        assertTrue("展开状态持久化", new PlaybackSettings(store).advancedOpen());
        assertFalse(settings.toggleAdvancedOpen());
        assertFalse(new PlaybackSettings(store).advancedOpen());
    }

    /** 高级分组的键与播放/导航键互不覆盖。 */
    @Test
    public void advancedStateDoesNotTouchPlaybackValues() {
        MemoryStore store = new MemoryStore();
        PlaybackSettings settings = new PlaybackSettings(store);
        settings.setAdvancedOpen(true);
        settings.setAspect(Aspect.STRETCH);

        PlaybackSettings reopened = new PlaybackSettings(store);
        assertTrue(reopened.advancedOpen());
        assertEquals(Aspect.STRETCH, reopened.aspect());
        assertEquals(SwitchMode.KEEP, reopened.switchMode());
        assertEquals(NavWidth.STANDARD, reopened.navWidth());
    }

    /** 内存 Store：与 SharedPreferences 同语义（有默认值）。 */
    static final class MemoryStore implements PlaybackSettings.Store {

        private final Map<String, String> strings = new HashMap<String, String>();
        private final Map<String, Boolean> booleans = new HashMap<String, Boolean>();

        @Override
        public String getString(String key, String fallback) {
            return strings.containsKey(key) ? strings.get(key) : fallback;
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            return booleans.containsKey(key) ? booleans.get(key) : fallback;
        }

        @Override
        public void putString(String key, String value) {
            strings.put(key, value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            booleans.put(key, value);
        }
    }
}
