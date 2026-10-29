package me.qnnp.tvlive.boot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 开机信号判定规则（纯 Java）：开关 / 退出 / 策略窗口 / 同类去重。 */
public class BootCoordinatorTest {

    private static BootActionPolicy policy(String action) {
        return BootActions.policy(action);
    }

    @Test
    public void normalSignalLaunches() {
        assertEquals(BootCoordinator.Decision.LAUNCH, BootCoordinator.decide(
                policy(BootActions.ACTION_BOOT_COMPLETED), 1_000L,
                true, false, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void disabledWinsBeforeEveryOtherRule() {
        assertEquals(BootCoordinator.Decision.SKIP_DISABLED, BootCoordinator.decide(
                policy(BootActions.ACTION_BOOT_COMPLETED),
                BootActionPolicy.BOOT_WINDOW_MS + 1L,
                false, true, 999L));
    }

    @Test
    public void userExitedSkipsUntilNextLaunch() {
        assertEquals(BootCoordinator.Decision.SKIP_USER_EXITED, BootCoordinator.decide(
                policy(BootActions.ACTION_USER_PRESENT), 1_000L,
                true, true, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void bootSignalAfterFiveMinutesIsTooLate() {
        assertEquals(BootCoordinator.Decision.SKIP_TOO_LATE, BootCoordinator.decide(
                policy(BootActions.ACTION_BOOT_COMPLETED),
                BootActionPolicy.BOOT_WINDOW_MS + 1L,
                true, false, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void exactlyFiveMinutesStillLaunches() {
        assertEquals(BootCoordinator.Decision.LAUNCH, BootCoordinator.decide(
                policy(BootActions.ACTION_BOOT_COMPLETED),
                BootActionPolicy.BOOT_WINDOW_MS,
                true, false, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void deviceSignalAfterTwoMinutesIsTooLate() {
        assertEquals(BootCoordinator.Decision.SKIP_TOO_LATE, BootCoordinator.decide(
                policy(BootActions.ACTION_USB_DEVICE_ATTACHED),
                BootActionPolicy.DEVICE_WINDOW_MS + 1L,
                true, false, BootCoordinator.NO_SIGNAL));
        assertEquals(BootCoordinator.Decision.LAUNCH, BootCoordinator.decide(
                policy(BootActions.ACTION_USB_DEVICE_ATTACHED),
                BootActionPolicy.DEVICE_WINDOW_MS,
                true, false, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void sameKindWithinThirtySecondsIsDeduped() {
        long now = 100_000L;
        assertEquals(BootCoordinator.Decision.SKIP_DEDUP, BootCoordinator.decide(
                policy(BootActions.ACTION_CONNECTIVITY_CHANGE), now,
                true, false, now - BootActionPolicy.DEDUP_WINDOW_MS + 1L));
    }

    @Test
    public void sameKindAtThirtySecondsLaunches() {
        long now = 100_000L;
        assertEquals(BootCoordinator.Decision.LAUNCH, BootCoordinator.decide(
                policy(BootActions.ACTION_CONNECTIVITY_CHANGE), now,
                true, false, now - BootActionPolicy.DEDUP_WINDOW_MS));
    }

    @Test
    public void staleClockDoesNotDedup() {
        long now = 100_000L;
        assertEquals(BootCoordinator.Decision.LAUNCH, BootCoordinator.decide(
                policy(BootActions.ACTION_CONNECTIVITY_CHANGE), now,
                true, false, now + 5_000L));
    }

    @Test
    public void unknownPolicyNeverLaunches() {
        assertEquals(BootCoordinator.Decision.SKIP_TOO_LATE, BootCoordinator.decide(
                null, 1_000L, true, false, BootCoordinator.NO_SIGNAL));
    }

    @Test
    public void deviceActionsShareOneDedupKind() {
        assertEquals(policy(BootActions.ACTION_USB_DEVICE_ATTACHED).kind(),
                policy(BootActions.ACTION_MEDIA_MOUNTED).kind());
        assertEquals(policy(BootActions.ACTION_USB_DEVICE_ATTACHED).kind(),
                policy(BootActions.ACTION_MEDIA_SCANNER_FINISHED).kind());
    }

    @Test
    public void bootAndNetworkKeepSeparateKinds() {
        assertFalse(policy(BootActions.ACTION_BOOT_COMPLETED).kind()
                == policy(BootActions.ACTION_CONNECTIVITY_CHANGE).kind());
    }

    @Test
    public void policyDelaysAreClassified() {
        assertEquals(BootActionPolicy.BOOT_DELAY_MS,
                policy(BootActions.ACTION_BOOT_COMPLETED).launchDelayMs());
        assertEquals(BootActionPolicy.NETWORK_DELAY_MS,
                policy(BootActions.ACTION_CONNECTIVITY_CHANGE).launchDelayMs());
        assertEquals(BootActionPolicy.USER_DELAY_MS,
                policy(BootActions.ACTION_USER_PRESENT).launchDelayMs());
        assertEquals(BootActionPolicy.DEVICE_DELAY_MS,
                policy(BootActions.ACTION_USB_DEVICE_ATTACHED).launchDelayMs());
        assertEquals(BootActionPolicy.DEVICE_DELAY_MS,
                policy(BootActions.ACTION_MEDIA_MOUNTED).launchDelayMs());
    }

    @Test
    public void deviceWindowIsShorterThanBootWindow() {
        assertTrue(BootActionPolicy.DEVICE_WINDOW_MS < BootActionPolicy.BOOT_WINDOW_MS);
    }
}
