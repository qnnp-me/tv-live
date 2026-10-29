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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 批次 17/18：设置页一级「播放」分组的行序与文案不回归。
 *
 * <p>行序取自 {@link PlayRowSpec}（设置页按它建行）；文案直接从 strings.xml 读取，
 * 避免只断言资源 id 而漏掉改字。首帧加速移到高级分组后，由
 * {@link AdvancedRowSpecTest} 覆盖。</p>
 */
public class PlayRowSpecTest {

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
    public void playRowsKeepSpecOrder() {
        PlayRowSpec.Row[] rows = PlayRowSpec.rows();
        assertEquals("一级播放分组固定 5 行（批次 18）", 5, rows.length);
        assertEquals(PlayRowSpec.ACTION_SWITCH_MODE, rows[0].action);
        assertEquals("视频渲染紧跟切台方式", PlayRowSpec.ACTION_RENDER, rows[1].action);
        assertEquals("画面比例在视频渲染之后", PlayRowSpec.ACTION_ASPECT, rows[2].action);
        assertEquals(PlayRowSpec.ACTION_CARD, rows[3].action);
        assertEquals(PlayRowSpec.ACTION_SPEED, rows[4].action);
        for (PlayRowSpec.Row row : rows) {
            assertNotEquals("首帧加速已移入高级分组", AdvancedRowSpec.ACTION_BOOST, row.action);
        }
    }

    @Test
    public void playRowActionsAreUnique() {
        PlayRowSpec.Row[] rows = PlayRowSpec.rows();
        for (int i = 0; i < rows.length; i++) {
            for (int j = i + 1; j < rows.length; j++) {
                assertNotEquals("动作 id 不能重复", rows[i].action, rows[j].action);
            }
            assertNotEquals("标签/说明不能共用同一个资源", rows[i].labelRes, rows[i].detailRes);
        }
    }

    @Test
    public void playRowLabelsMatchCopy() {
        assertEquals("切台方式", strings.get("settings_play_switch_mode"));
        assertEquals("视频渲染", strings.get("settings_play_render"));
        assertEquals("画面比例", strings.get("settings_play_aspect"));
        assertEquals("切台提示", strings.get("settings_play_card"));
        assertEquals("显示网速", strings.get("settings_play_speed"));
        assertEquals("首帧加速仍在高级分组文案里", "首帧加速",
                strings.get("settings_play_boost"));
    }

    @Test
    public void renderLabelsAreAutoSurfaceTexture() {
        assertEquals("自动", strings.get("settings_play_render_auto"));
        assertEquals("SurfaceView", strings.get("settings_play_render_surface"));
        assertEquals("TextureView", strings.get("settings_play_render_texture"));

        assertEquals("renderLabelRes(0) 应是自动",
                me.qnnp.tvlive.R.string.settings_play_render_auto, PlayRowSpec.renderLabelRes(0));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_surface,
                PlayRowSpec.renderLabelRes(1));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_texture,
                PlayRowSpec.renderLabelRes(2));
        assertEquals("越界按自动处理",
                me.qnnp.tvlive.R.string.settings_play_render_auto, PlayRowSpec.renderLabelRes(7));
    }

    @Test
    public void renderDetailsExistAndDiffer() {
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_auto_detail,
                PlayRowSpec.renderDetailRes(0));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_surface_detail,
                PlayRowSpec.renderDetailRes(1));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_texture_detail,
                PlayRowSpec.renderDetailRes(2));

        String auto = strings.get("settings_play_render_auto_detail");
        String surface = strings.get("settings_play_render_surface_detail");
        String texture = strings.get("settings_play_render_texture_detail");
        assertTrue("自动说明非空", auto != null && !auto.isEmpty());
        assertTrue("SurfaceView 说明非空", surface != null && !surface.isEmpty());
        assertTrue("TextureView 说明非空", texture != null && !texture.isEmpty());
        assertNotEquals(auto, surface);
        assertNotEquals(auto, texture);
        assertNotEquals(surface, texture);
    }

    /** SURFACE + DUAL 的降级提示（Activity 直接引用该文案）。 */
    @Test
    public void degradedMessageMatchesSpec() {
        assertEquals("SurfaceView 不支持双画面，已使用保留画面",
                strings.get("settings_play_render_dual_degraded"));
    }

    /** 视频渲染行的初始说明来自「自动」，与默认值一致。 */
    @Test
    public void renderRowInitialDetailMatchesAuto() {
        PlayRowSpec.Row[] rows = PlayRowSpec.rows();
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render_auto_detail,
                rows[1].detailRes);
        assertEquals(me.qnnp.tvlive.R.string.settings_play_render, rows[1].labelRes);
    }

    @Test
    public void aspectLabelsAreFitStretchCrop() {
        assertEquals("适应", strings.get("settings_play_aspect_fit"));
        assertEquals("拉伸", strings.get("settings_play_aspect_stretch"));
        assertEquals("裁剪", strings.get("settings_play_aspect_crop"));

        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_fit,
                PlayRowSpec.aspectLabelRes(0));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_stretch,
                PlayRowSpec.aspectLabelRes(1));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_crop,
                PlayRowSpec.aspectLabelRes(2));
        assertEquals("越界按适应处理", me.qnnp.tvlive.R.string.settings_play_aspect_fit,
                PlayRowSpec.aspectLabelRes(9));
    }

    @Test
    public void aspectDetailsExistAndDiffer() {
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_fit_detail,
                PlayRowSpec.aspectDetailRes(0));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_stretch_detail,
                PlayRowSpec.aspectDetailRes(1));
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_crop_detail,
                PlayRowSpec.aspectDetailRes(2));

        String fit = strings.get("settings_play_aspect_fit_detail");
        String stretch = strings.get("settings_play_aspect_stretch_detail");
        String crop = strings.get("settings_play_aspect_crop_detail");
        assertTrue("适应说明非空", fit != null && !fit.isEmpty());
        assertTrue("拉伸说明非空", stretch != null && !stretch.isEmpty());
        assertTrue("裁剪说明非空", crop != null && !crop.isEmpty());
        assertNotEquals(fit, stretch);
        assertNotEquals(fit, crop);
        assertNotEquals(stretch, crop);
    }

    /** 画面比例行的初始说明来自「适应」，与默认值一致。 */
    @Test
    public void aspectRowInitialDetailMatchesFit() {
        PlayRowSpec.Row[] rows = PlayRowSpec.rows();
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect_fit_detail,
                rows[2].detailRes);
        assertEquals(me.qnnp.tvlive.R.string.settings_play_aspect, rows[2].labelRes);
    }
}
