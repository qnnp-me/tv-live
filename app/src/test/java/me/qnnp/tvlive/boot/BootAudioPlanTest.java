package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 开机音频自检计划（纯 Java）。 */
public class BootAudioPlanTest {

    @Test
    public void armsOnlyForBootLaunchOnTclLegacy() {
        assertTrue(BootAudioPlan.shouldArm(true, true));
        assertFalse(BootAudioPlan.shouldArm(false, true));
        assertFalse(BootAudioPlan.shouldArm(true, false));
    }

    @Test
    public void fallsBackAfterBootDeadline() {
        assertFalse(BootAudioPlan.shouldFallback(
                BootAudioPlan.FALLBACK_BOOT_MS - 1L, true));
        assertTrue(BootAudioPlan.shouldFallback(
                BootAudioPlan.FALLBACK_BOOT_MS, true));
        assertFalse(BootAudioPlan.shouldFallback(
                BootAudioPlan.FALLBACK_BOOT_MS, false));
    }
}
