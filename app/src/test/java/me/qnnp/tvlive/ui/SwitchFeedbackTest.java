package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 台名卡显示条件：开关、延迟、首帧、导航态、设置/菜单页。 */
public class SwitchFeedbackTest {

    @Test
    public void cardWaitsForDelayAndHidesAfterFirstFrame() {
        SwitchFeedback feedback = new SwitchFeedback();
        feedback.setCardEnabled(true);

        feedback.begin();
        assertFalse("120ms 内不出卡", feedback.shouldShowCard(false, false));

        feedback.delayElapsed();
        assertTrue(feedback.shouldShowCard(false, false));

        feedback.firstFrame();
        assertFalse("首帧后必须收起", feedback.shouldShowCard(false, false));
    }

    @Test
    public void navigationShownStillAllowsTransitionCard() {
        SwitchFeedback feedback = new SwitchFeedback();
        feedback.setCardEnabled(true);
        feedback.begin();
        feedback.delayElapsed();

        assertTrue("导航态不阻止切台反馈", feedback.shouldShowCard(true, false));
    }

    @Test
    public void menuOrSettingsPageSuppressesBothCardAndBadge() {
        SwitchFeedback card = new SwitchFeedback();
        card.setCardEnabled(true);
        card.begin();
        card.delayElapsed();
        assertFalse(card.shouldShowCard(false, true));

        SwitchFeedback badge = new SwitchFeedback();
        badge.setCardEnabled(false);
        badge.begin();
        badge.delayElapsed();
        assertTrue(badge.shouldShowLoadingBadge(false));
        assertFalse("设置/菜单页期间不显示加载角标", badge.shouldShowLoadingBadge(true));
    }

    @Test
    public void cardOffKeepsOriginalLoadingBadgeOnly() {
        SwitchFeedback feedback = new SwitchFeedback();
        feedback.setCardEnabled(false);
        feedback.begin();
        feedback.delayElapsed();

        assertFalse(feedback.shouldShowCard(false, false));
        assertTrue(feedback.shouldShowLoadingBadge(false));
    }

    @Test
    public void endClearsPendingFeedback() {
        SwitchFeedback feedback = new SwitchFeedback();
        feedback.setCardEnabled(true);
        feedback.begin();
        feedback.delayElapsed();

        feedback.end();

        assertFalse(feedback.isPending());
        assertFalse(feedback.shouldShowCard(false, false));
        assertFalse(feedback.shouldShowLoadingBadge(false));
    }
}
