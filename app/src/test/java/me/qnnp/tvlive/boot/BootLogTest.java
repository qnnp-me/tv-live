package me.qnnp.tvlive.boot;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 最近 10 条日志的容量、顺序与分隔串往返。 */
public class BootLogTest {

    @Test
    public void keepsOnlyLatestTenEntries() {
        BootLog log = new BootLog();
        for (int i = 1; i <= 12; i++) {
            log.record(BootSource.BOOT_COMPLETED, BootActions.ACTION_BOOT_COMPLETED,
                    i, 1_000L + i, BootCoordinator.Decision.LAUNCH);
        }
        assertEquals(BootLog.MAX_ENTRIES, log.size());
        List<BootLog.Entry> entries = log.entries();
        assertEquals(12L, entries.get(0).elapsed());
        assertEquals(3L, entries.get(entries.size() - 1).elapsed());
    }

    @Test
    public void entriesAreNewestFirst() {
        BootLog log = new BootLog();
        log.record(BootSource.QUICKBOOT_POWERON, BootActions.ACTION_QUICKBOOT_POWERON,
                100L, 1_100L, BootCoordinator.Decision.LAUNCH);
        log.record(BootSource.CONNECTIVITY_CHANGE, BootActions.ACTION_CONNECTIVITY_CHANGE,
                200L, 1_200L, BootCoordinator.Decision.SKIP_TOO_LATE);
        assertEquals(BootSource.CONNECTIVITY_CHANGE, log.entries().get(0).source());
        assertEquals(BootSource.QUICKBOOT_POWERON, log.entries().get(1).source());
    }

    @Test
    public void serializeAndParseRoundTrip() {
        BootLog log = new BootLog();
        log.record(BootSource.BOOT_COMPLETED, BootActions.ACTION_BOOT_COMPLETED,
                5_000L, 1_700_000_000_000L, BootCoordinator.Decision.LAUNCH);
        log.record(BootSource.VENDOR, BootActions.ACTION_TCL_TV_BOOT_COMPLETED,
                6_000L, 1_700_000_001_000L, BootCoordinator.Decision.SKIP_DEDUP);
        log.record(BootSource.CONNECTIVITY_CHANGE, BootActions.ACTION_CONNECTIVITY_CHANGE,
                7_000L, 1_700_000_002_000L, BootCoordinator.Decision.SKIP_DISABLED);

        BootLog parsed = BootLog.parse(log.serialize());
        assertEquals(3, parsed.size());
        List<BootLog.Entry> a = log.entries();
        List<BootLog.Entry> b = parsed.entries();
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).source(), b.get(i).source());
            assertEquals(a.get(i).action(), b.get(i).action());
            assertEquals(a.get(i).elapsed(), b.get(i).elapsed());
            assertEquals(a.get(i).wallClock(), b.get(i).wallClock());
            assertEquals(a.get(i).decision(), b.get(i).decision());
        }
    }

    @Test
    public void parseNullAndEmptyReturnsEmptyLog() {
        assertTrue(BootLog.parse(null).entries().isEmpty());
        assertTrue(BootLog.parse("").entries().isEmpty());
    }

    @Test
    public void parseSkipsBrokenLinesAndKeepsValidOnes() {
        BootLog log = new BootLog();
        log.record(BootSource.USER_PRESENT, BootActions.ACTION_USER_PRESENT,
                900L, 1_900L, BootCoordinator.Decision.SKIP_USER_EXITED);
        String raw = "not-a-line\n"
                + "UNKNOWN_SOURCE\tLAUNCH\t1\t2\tx\n"
                + "BOOT_COMPLETED\tNOT_A_DECISION\t1\t2\tx\n"
                + log.serialize();
        BootLog parsed = BootLog.parse(raw);
        assertEquals(1, parsed.size());
        assertEquals(BootSource.USER_PRESENT, parsed.entries().get(0).source());
        assertEquals(BootCoordinator.Decision.SKIP_USER_EXITED,
                parsed.entries().get(0).decision());
    }

    @Test
    public void parseCapsAtTenEntries() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                raw.append('\n');
            }
            raw.append("BOOT_COMPLETED\tLAUNCH\t").append(i).append("\t0\tx");
        }
        BootLog parsed = BootLog.parse(raw.toString());
        assertEquals(BootLog.MAX_ENTRIES, parsed.size());
        assertEquals(0L, parsed.entries().get(0).elapsed());
        assertEquals(9L, parsed.entries().get(9).elapsed());
    }
}
