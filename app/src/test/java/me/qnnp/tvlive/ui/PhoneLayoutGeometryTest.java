package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 批次 5a 修复：竖屏几何。视频区高度必须按**本 View 的宽度**算 16:9，
 * 1080 宽 → 608 高；tab/列表从 608 开始，列表满宽。
 */
public class PhoneLayoutGeometryTest {

    @Test
    public void videoHeightIs169OfLayoutWidth() {
        // 1080×9/16 = 607.5 → 取整 608（模拟器竖屏验收值）。
        assertEquals(608, PhoneLayout.videoHeightFor(1080));
        assertEquals(405, PhoneLayout.videoHeightFor(720));
        assertEquals(342, PhoneLayout.videoHeightFor(608));
    }

    @Test
    public void smallWidthsStayProportional() {
        // 320dp/360dp 手机（420dpi 下 ≈ 840/945px）也要保持 16:9。
        assertEquals(473, PhoneLayout.videoHeightFor(840));
        assertEquals(532, PhoneLayout.videoHeightFor(945));
    }

    @Test
    public void unknownWidthDoesNotProduceNegativeHeight() {
        assertEquals(0, PhoneLayout.videoHeightFor(0));
        assertEquals(0, PhoneLayout.videoHeightFor(-1080));
    }
}
