package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 批次 18：设置页可折叠「高级」分组的行序、标题文案与档位标签。
 *
 * <p>文案直接从 strings.xml 读取，避免只断言资源 id 而漏掉改字。</p>
 */
public class AdvancedRowSpecTest {

    private static Map<String, String> strings;

    @BeforeClass
    public static void loadStrings() throws IOException {
        File file = locate("src/main/res/values/strings.xml",
                "app/src/main/res/values/strings.xml");
        String xml = new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
        strings = new HashMap<String, String>();
        Matcher matcher = Pattern.compile(
                "<string name=\"([^\"]+)\">(.*?)</string>", Pattern.DOTALL).matcher(xml);
        while (matcher.find()) {
            strings.put(matcher.group(1), matcher.group(2));
        }
        assertTrue("strings.xml 应能解析出字符串", strings.size() > 100);
    }

    private static File locate(String... candidates) {
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        fail("找不到 strings.xml，尝试过：" + String.join(", ", candidates));
        return null;
    }

    @Test
    public void advancedRowsKeepSpecOrder() {
        AdvancedRowSpec.Row[] rows = AdvancedRowSpec.rows();
        assertEquals("高级分组固定 4 项（批次 18）", 4, rows.length);
        assertEquals("首帧加速", AdvancedRowSpec.ACTION_BOOST, rows[0].action);
        assertEquals(AdvancedRowSpec.ACTION_NAV_WIDTH, rows[1].action);
        assertEquals(AdvancedRowSpec.ACTION_AUTO_HIDE, rows[2].action);
        assertEquals(AdvancedRowSpec.ACTION_CLOCK, rows[3].action);
        assertEquals(rows.length, AdvancedRowSpec.itemCount());
    }

    @Test
    public void advancedActionsAreUniqueAndSeparateFromPlayRows() {
        AdvancedRowSpec.Row[] rows = AdvancedRowSpec.rows();
        for (int i = 0; i < rows.length; i++) {
            assertNotEquals("折叠标题不能复用子项动作", AdvancedRowSpec.ACTION_TOGGLE,
                    rows[i].action);
            for (int j = i + 1; j < rows.length; j++) {
                assertNotEquals("动作 id 不能重复", rows[i].action, rows[j].action);
            }
            assertNotEquals("标签/说明不能共用同一个资源", rows[i].labelRes, rows[i].detailRes);
            for (PlayRowSpec.Row play : PlayRowSpec.rows()) {
                assertNotEquals("一级播放项不能重复出现在高级分组", play.action, rows[i].action);
            }
        }
    }

    @Test
    public void advancedLabelsMatchCopy() {
        assertEquals("首帧加速", strings.get("settings_play_boost"));
        assertEquals("导航宽度", strings.get("settings_adv_nav_width"));
        assertEquals("自动收起", strings.get("settings_adv_auto_hide"));
        assertEquals("时钟角标", strings.get("settings_adv_clock"));

        AdvancedRowSpec.Row[] rows = AdvancedRowSpec.rows();
        assertEquals(me.qnnp.tvlive.R.string.settings_play_boost, rows[0].labelRes);
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_nav_width, rows[1].labelRes);
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_auto_hide, rows[2].labelRes);
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_clock, rows[3].labelRes);
    }

    @Test
    public void collapsedAndExpandedTitlesShowChevronAndCount() {
        String collapsed = String.format(Locale.ROOT,
                strings.get("settings_advanced_title_collapsed"), 4);
        String expanded = String.format(Locale.ROOT,
                strings.get("settings_advanced_title_expanded"), 4);

        assertEquals("▶ 高级设置 · 4 项", collapsed);
        assertEquals("▼ 高级设置 · 4 项", expanded);
        assertEquals(me.qnnp.tvlive.R.string.settings_advanced_title_collapsed,
                AdvancedRowSpec.titleRes(false));
        assertEquals(me.qnnp.tvlive.R.string.settings_advanced_title_expanded,
                AdvancedRowSpec.titleRes(true));
    }

    @Test
    public void titleCountFollowsItemCountNotHardCoded() {
        String withCount = String.format(Locale.ROOT,
                strings.get("settings_advanced_title_collapsed"), AdvancedRowSpec.itemCount());
        assertTrue("折叠标题应包含实际项数", withCount.contains(
                AdvancedRowSpec.itemCount() + " 项"));
    }

    @Test
    public void navWidthLabelsAreNarrowStandardWide() {
        assertEquals("窄 38%", strings.get("settings_adv_nav_width_narrow"));
        assertEquals("标准 44%", strings.get("settings_adv_nav_width_standard"));
        assertEquals("宽 50%", strings.get("settings_adv_nav_width_wide"));

        assertEquals(me.qnnp.tvlive.R.string.settings_adv_nav_width_narrow,
                AdvancedRowSpec.navWidthLabelRes(0));
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_nav_width_standard,
                AdvancedRowSpec.navWidthLabelRes(1));
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_nav_width_wide,
                AdvancedRowSpec.navWidthLabelRes(2));
        assertEquals("越界按标准处理", me.qnnp.tvlive.R.string.settings_adv_nav_width_standard,
                AdvancedRowSpec.navWidthLabelRes(7));
    }

    @Test
    public void autoHideLabelsCoverFiveEightFifteenOff() {
        assertEquals("5 秒", strings.get("settings_adv_auto_hide_5"));
        assertEquals("8 秒", strings.get("settings_adv_auto_hide_8"));
        assertEquals("15 秒", strings.get("settings_adv_auto_hide_15"));
        assertEquals("不收起", strings.get("settings_adv_auto_hide_off"));

        assertEquals(me.qnnp.tvlive.R.string.settings_adv_auto_hide_5,
                AdvancedRowSpec.autoHideLabelRes(5));
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_auto_hide_8,
                AdvancedRowSpec.autoHideLabelRes(8));
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_auto_hide_15,
                AdvancedRowSpec.autoHideLabelRes(15));
        assertEquals(me.qnnp.tvlive.R.string.settings_adv_auto_hide_off,
                AdvancedRowSpec.autoHideLabelRes(0));
    }

    @Test
    public void autoHideNormalizesAndCyclesInPageOrder() {
        assertEquals(8, AdvancedRowSpec.normalizeAutoHideSeconds(-1));
        assertEquals(8, AdvancedRowSpec.normalizeAutoHideSeconds(30));
        assertEquals(5, AdvancedRowSpec.normalizeAutoHideSeconds(5));
        assertEquals(0, AdvancedRowSpec.normalizeAutoHideSeconds(0));

        assertEquals("5 → 8", 8, AdvancedRowSpec.nextAutoHideSeconds(5));
        assertEquals("8 → 15", 15, AdvancedRowSpec.nextAutoHideSeconds(8));
        assertEquals("15 → 不收起", 0, AdvancedRowSpec.nextAutoHideSeconds(15));
        assertEquals("不收起 → 5", 5, AdvancedRowSpec.nextAutoHideSeconds(0));
    }
}
