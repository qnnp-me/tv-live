package me.qnnp.tvlive.boot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最近 10 条开机信号记录（来源 + action + 时间 + 决定），供后续诊断页读取。
 *
 * <p>纯 Java，序列化成一行一条的分隔串（TAB 分隔），不依赖 org.json，
 * JVM 单测可直接往返验证。{@link #entries()} 按「新 → 旧」返回。</p>
 */
public final class BootLog {

    public static final int MAX_ENTRIES = 10;

    private static final String SEP = "\t";

    private final ArrayDeque<Entry> entries = new ArrayDeque<Entry>();

    public static final class Entry {

        private final BootSource source;
        private final String action;
        private final long elapsed;
        private final long wallClock;
        private final BootCoordinator.Decision decision;

        public Entry(BootSource source, String action, long elapsed, long wallClock,
                     BootCoordinator.Decision decision) {
            this.source = source;
            this.action = action == null ? "" : action;
            this.elapsed = elapsed;
            this.wallClock = wallClock;
            this.decision = decision;
        }

        public BootSource source() {
            return source;
        }

        public String action() {
            return action;
        }

        /** elapsedRealtime 毫秒；重启后归零，只用于同一次开机的诊断。 */
        public long elapsed() {
            return elapsed;
        }

        /** 墙上时间毫秒，跨重启仍可读。 */
        public long wallClock() {
            return wallClock;
        }

        public BootCoordinator.Decision decision() {
            return decision;
        }
    }

    /** 追加一条；超过 {@link #MAX_ENTRIES} 丢最旧的。 */
    public void record(BootSource source, String action, long elapsed, long wallClock,
                       BootCoordinator.Decision decision) {
        entries.addFirst(new Entry(source, action, elapsed, wallClock, decision));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    /** 新 → 旧，不可变快照。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    /** 序列化为一行一条：source / decision / elapsed / wall / action。 */
    public String serialize() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            BootSource source = entry.source();
            BootCoordinator.Decision decision = entry.decision();
            if (source == null || decision == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(source.name()).append(SEP)
                    .append(decision.name()).append(SEP)
                    .append(entry.elapsed()).append(SEP)
                    .append(entry.wallClock()).append(SEP)
                    .append(sanitize(entry.action()));
        }
        return out.toString();
    }

    /** 容错解析：坏行/未知枚举直接跳过，空输入返回空日志。 */
    public static BootLog parse(String raw) {
        BootLog log = new BootLog();
        if (raw == null || raw.isEmpty()) {
            return log;
        }
        String[] lines = raw.split("\n");
        // 存的时候是新 → 旧；从最旧那行开始 record，才能保持同样的顺序。
        for (int i = lines.length - 1; i >= 0; i--) {
            Entry entry = parseLine(lines[i]);
            if (entry != null) {
                log.entries.addFirst(entry);
                while (log.entries.size() > MAX_ENTRIES) {
                    log.entries.removeLast();
                }
            }
        }
        return log;
    }

    private static Entry parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(SEP, -1);
        if (parts.length < 4) {
            return null;
        }
        try {
            BootSource source = BootSource.valueOf(parts[0]);
            BootCoordinator.Decision decision = BootCoordinator.Decision.valueOf(parts[1]);
            long elapsed = Long.parseLong(parts[2]);
            long wall = Long.parseLong(parts[3]);
            String action = parts.length > 4 ? parts[4] : "";
            return new Entry(source, action, elapsed, wall, decision);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** action 都是 ASCII 常量；仍把分隔符/换行替换掉，避免破坏格式。 */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
