package me.qnnp.tvlive.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 能力档案的纯逻辑部分。 */
public class VideoProfileTest {

    @Test
    public void hdProfileAccepts1080pAndBelow() {
        VideoProfile profile = VideoProfile.hd();
        assertEquals(1920, profile.maxWidth());
        assertEquals(1080, profile.maxHeight());
        assertTrue(profile.withinLimit(1920, 1080));
        assertTrue(profile.withinLimit(1280, 720));
        assertFalse(profile.withinLimit(3840, 2160));
        assertFalse(profile.legacy());
    }

    @Test
    public void unknownSizeIsAllowed() {
        assertTrue(VideoProfile.hd().withinLimit(0, 0));
        assertTrue(VideoProfile.hd().withinLimit(-1, -1));
    }
}
