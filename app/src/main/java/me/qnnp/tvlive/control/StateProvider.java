package me.qnnp.tvlive.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 手机页状态快照来源：只读 TV 内存里的元数据，<b>不读磁盘、不解析 m3u</b>。
 *
 * <p>TV 主线程在列表加载完成/换台时构建不可变 {@link Snapshot}，HTTP 线程只读它，
 * 因此 {@code GET /api/state} 的耗时与源文件大小无关（实测解析全部缓存要 21 秒）。
 * 快照未就绪（启动首次加载完成前）返回 {@link Snapshot#notReady()}，页面显示「TV 正在加载」。</p>
 */
public interface StateProvider {

    /** 当前快照；未就绪返回 {@link Snapshot#notReady()} 或 null。 */
    Snapshot snapshot();

    /** 一次状态快照（不可变）。 */
    final class Snapshot {

        private static final Snapshot NOT_READY = new Snapshot(false, 0, null, null, null,
                Collections.<SourceEntry>emptyList());

        private final boolean ready;
        private final int channels;
        private final String currentId;
        private final String currentName;
        private final String fatalError;
        private final List<SourceEntry> sources;

        public Snapshot(boolean ready, int channels, String currentId, String currentName,
                String fatalError, List<SourceEntry> sources) {
            this.ready = ready;
            this.channels = Math.max(0, channels);
            this.currentId = currentId;
            this.currentName = currentName;
            this.fatalError = fatalError;
            List<SourceEntry> copy = sources == null
                    ? Collections.<SourceEntry>emptyList()
                    : new ArrayList<SourceEntry>(sources);
            this.sources = Collections.unmodifiableList(copy);
        }

        public static Snapshot notReady() {
            return NOT_READY;
        }

        public boolean ready() {
            return ready;
        }

        /** 合并去重后的频道总数。 */
        public int channels() {
            return channels;
        }

        public String currentId() {
            return currentId;
        }

        public String currentName() {
            return currentName;
        }

        /** 源索引损坏等整体错误；正常为 null。 */
        public String fatalError() {
            return fatalError;
        }

        public List<SourceEntry> sources() {
            return sources;
        }

        /** 只替换当前台（换台时用，源列表/频道数不变）。 */
        public Snapshot withCurrent(String currentId, String currentName) {
            return new Snapshot(ready, channels, currentId, currentName, fatalError, sources);
        }
    }

    /** 单个源的状态（元数据 + 上次解析出的台数/错误，不重新解析）。 */
    final class SourceEntry {

        private final String id;
        private final String name;
        private final String kind;
        private final boolean enabled;
        private final int order;
        private final int channels;
        private final String lastError;
        /** URL 源的原始地址（手机页「改地址」预填用）；FILE 源为 null。 */
        private final String location;

        public SourceEntry(String id, String name, String kind, boolean enabled, int order,
                int channels, String lastError) {
            this(id, name, kind, enabled, order, channels, lastError, null);
        }

        public SourceEntry(String id, String name, String kind, boolean enabled, int order,
                int channels, String lastError, String location) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.enabled = enabled;
            this.order = Math.max(0, order);
            this.channels = Math.max(0, channels);
            this.lastError = lastError;
            this.location = location;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String kind() {
            return kind;
        }

        public boolean enabled() {
            return enabled;
        }

        public int order() {
            return order;
        }

        public int channels() {
            return channels;
        }

        public String lastError() {
            return lastError;
        }

        public String location() {
            return location;
        }
    }
}
