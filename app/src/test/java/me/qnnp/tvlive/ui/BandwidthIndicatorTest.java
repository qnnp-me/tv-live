package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * 网速行开关与去重（批次 15）：关闭时不得回调 UI；开启时同一文案只回调一次。
 */
public class BandwidthIndicatorTest {

    @Test
    public void disabledIndicatorNeverReachesUi() {
        RecordingListener ui = new RecordingListener();
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.setListener(ui);
        indicator.setEnabled(false);

        assertFalse(indicator.isEnabled());
        indicator.onBandwidth(1L * 1024L * 1024L);
        indicator.onBandwidth(0L);

        assertEquals("关闭时不得回调 UI", 0, ui.texts.size());
    }

    @Test
    public void enabledIndicatorFormatsAndDeduplicates() {
        RecordingListener ui = new RecordingListener();
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.setListener(ui);

        assertTrue(indicator.isEnabled());
        indicator.onBandwidth(1228800L);
        assertEquals(1, ui.texts.size());
        assertEquals("1.2 MB/s", ui.last());

        // 同一文案不重复刷 UI
        indicator.onBandwidth(1228800L);
        assertEquals(1, ui.texts.size());

        indicator.onBandwidth(320L * 1024L);
        assertEquals(2, ui.texts.size());
        assertEquals(SpeedFormat.SLOW_PREFIX + "320 KB/s", ui.last());

        indicator.onBandwidth(100L * 1024L);
        assertEquals(3, ui.texts.size());
        assertEquals(SpeedFormat.VERY_SLOW_PREFIX + "100 KB/s", ui.last());
    }

    @Test
    public void zeroBandwidthShowsDash() {
        RecordingListener ui = new RecordingListener();
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.setListener(ui);

        indicator.onBandwidth(0L);
        assertEquals(SpeedFormat.NO_DATA, ui.last());
    }

    @Test
    public void resetAllowsSameTextAgainAfterChannelSwitch() {
        RecordingListener ui = new RecordingListener();
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.setListener(ui);

        indicator.onBandwidth(1024L);
        indicator.reset();
        indicator.onBandwidth(1024L);

        assertEquals("换台后同一文案应再刷一次（台名卡已重置为「—」）", 2, ui.texts.size());
    }

    @Test
    public void reenablingEmitsOnNextSample() {
        RecordingListener ui = new RecordingListener();
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.setListener(ui);

        indicator.setEnabled(false);
        indicator.onBandwidth(1024L);
        assertEquals(0, ui.texts.size());

        indicator.setEnabled(true);
        indicator.onBandwidth(1024L);
        assertEquals("重新打开后要能立刻刷新", 1, ui.texts.size());
    }

    @Test
    public void noListenerNoCrash() {
        BandwidthIndicator indicator = new BandwidthIndicator();
        indicator.onBandwidth(1234L);
    }

    /** 记录 UI 收到的网速文案。 */
    static final class RecordingListener implements BandwidthIndicator.Listener {

        final List<String> texts = new ArrayList<String>();

        @Override
        public void onSpeedText(String text) {
            texts.add(text);
        }

        String last() {
            return texts.isEmpty() ? null : texts.get(texts.size() - 1);
        }
    }
}
