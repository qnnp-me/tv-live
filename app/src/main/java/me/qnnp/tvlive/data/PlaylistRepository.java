package me.qnnp.tvlive.data;

import android.content.Context;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.qnnp.tvlive.data.PlaylistModel.Playlist;

/**
 * 源存储与加载：{@code getFilesDir()/sources.json} + 每源 {@code sources/<id>.m3u}。
 *
 * <p>旧单文件 {@code playlist.m3u} 的方法保留给批次 1 的兼容/迁移测试；
 * 应用启动走 {@link #loadAll(File)}：按源独立解析、坏源隔离、合并成一张频道表。
 * 同目录 {@code .tmp} 写完 fsync 后 rename，避免半截文件；不写外部存储，
 * 不硬编码 /sdcard 等路径。单测走 {@link File}，不依赖 Android Context。</p>
 */
public final class PlaylistRepository {

    public static final String FILE_NAME = "playlist.m3u";
    public static final String TEMP_SUFFIX = ".tmp";

    private PlaylistRepository() {
    }

    public static File playlistFile(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        return playlistFile(context.getFilesDir());
    }

    public static File playlistFile(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir == null");
        }
        return new File(filesDir, FILE_NAME);
    }

    /**
     * 读整文件。按文件长度一次性分配缓冲（长度不准时才 1.5 倍增长），
     * 避免 {@code ByteArrayOutputStream} 反复扩容 + {@code toByteArray()} 再复制一份
     * （5MB 源上少一次 5MB 以上的峰值）。
     */
    public static byte[] read(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file == null");
        }
        InputStream input = new FileInputStream(file);
        try {
            long declared = file.length();
            int initial = (int) Math.max(1024L, Math.min(declared, 16L * 1024L * 1024L));
            byte[] buffer = new byte[initial];
            int size = 0;
            while (true) {
                if (size == buffer.length) {
                    int grown = buffer.length + (buffer.length >> 1) + 1;
                    byte[] bigger = new byte[grown];
                    System.arraycopy(buffer, 0, bigger, 0, size);
                    buffer = bigger;
                }
                int read = input.read(buffer, size, buffer.length - size);
                if (read == -1) {
                    break;
                }
                size += read;
            }
            return size == buffer.length ? buffer : Arrays.copyOf(buffer, size);
        } finally {
            input.close();
        }
    }

    /** 原子写：同目录 playlist.m3u.tmp → fsync → rename。 */
    public static void writeAtomic(File file, byte[] data) throws IOException {
        writeAtomic(file, data, true);
    }

    /**
     * 原子写，可关闭 fsync。
     *
     * <p>源文件/索引必须 sync（断电后不能半截文件）；PIN 这类可重新生成的小文件
     * 用 {@code sync=false}，避免启动/首次访问时在主线程触发磁盘同步。</p>
     */
    public static void writeAtomic(File file, byte[] data, boolean sync) throws IOException {
        writeAtomic(file, data, sync, DEFAULT_SYNCER);
    }

    /** 文件同步接口；单测注入计数实现，验证 sync 开关确实生效。 */
    interface Syncer {

        void sync(FileDescriptor descriptor) throws IOException;
    }

    private static final Syncer DEFAULT_SYNCER = new Syncer() {
        @Override
        public void sync(FileDescriptor descriptor) throws IOException {
            descriptor.sync();
        }
    };

    static void writeAtomic(File file, byte[] data, boolean sync, Syncer syncer)
            throws IOException {
        if (file == null || data == null) {
            throw new IllegalArgumentException("file/data == null");
        }
        if (syncer == null) {
            throw new IllegalArgumentException("syncer == null");
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        File temp = new File(parent, file.getName() + TEMP_SUFFIX);

        FileOutputStream output = new FileOutputStream(temp);
        boolean written = false;
        try {
            output.write(data);
            output.flush();
            if (sync) {
                syncer.sync(output.getFD());
            }
            written = true;
        } finally {
            try {
                output.close();
            } catch (IOException ignored) {
                // 保留原始异常
            }
            if (!written) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }

        if (temp.renameTo(file)) {
            invalidateParsedCache(file);
            return;
        }
        // rename 失败（个别文件系统不允许覆盖）时退化为删除后重命名
        if (file.exists() && file.delete() && temp.renameTo(file)) {
            invalidateParsedCache(file);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
        throw new IOException("原子写入失败: " + file.getAbsolutePath());
    }

    public static Playlist load(Context context) throws IOException, PlaylistParseException {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        return load(context.getFilesDir());
    }

    public static Playlist load(File filesDir) throws IOException, PlaylistParseException {
        File file = playlistFile(filesDir);
        if (!file.isFile()) {
            throw new FileNotFoundException("源文件不存在: " + file.getAbsolutePath());
        }
        byte[] data = read(file);
        Playlist parsed = PlaylistParser.parse(data, null);
        return ChannelSet.organize(parsed.channels(), parsed.skipped());
    }

    public static void save(Context context, byte[] data) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        writeAtomic(playlistFile(context), data);
    }

    public static void save(File filesDir, byte[] data) throws IOException {
        writeAtomic(playlistFile(filesDir), data);
    }

    public static boolean delete(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        return delete(context.getFilesDir());
    }

    public static boolean delete(File filesDir) {
        File file = playlistFile(filesDir);
        boolean deleted = !file.exists() || file.delete();
        File temp = new File(file.getParentFile(), file.getName() + TEMP_SUFFIX);
        if (temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        invalidateParsedCache(file);
        return deleted;
    }

    // ------------------------------------------------------------------ 解析缓存

    /** 缓存条目上限：最多 8 个源，留余量给旧路径/单测，超出按访问顺序淘汰最旧项。 */
    private static final int PARSED_CACHE_MAX = 16;

    /**
     * 进程内解析结果缓存：key = 源文件绝对路径，value 记住 lastModified/length/baseUrl，
     * 任一不同就重新 read()+parse。内存态、不落盘；进程重启后首轮仍是全量解析。
     *
     * <p>线程安全：解析在后台线程，读写都在同一个锁内；缓存的 {@link Playlist}
     * 只读（{@link ChannelMerger} 合并时复制 Channel，不改解析快照）。</p>
     */
    private static final Map<String, ParsedEntry> PARSED_CACHE =
            new LinkedHashMap<String, ParsedEntry>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ParsedEntry> eldest) {
                    return size() > PARSED_CACHE_MAX;
                }
            };

    private static int cacheHits;
    private static int cacheParses;

    /**
     * 单源解析（带缓存）：文件路径 + {@code lastModified()} + {@code length()}（外加 baseUrl）
     * 都没变时直接复用上次的快照，不读文件、不解析。
     */
    static Playlist parseCached(File file, String baseUrl) throws IOException, PlaylistParseException {
        String key = file.getAbsolutePath();
        long lastModified = file.lastModified();
        long length = file.length();
        synchronized (PARSED_CACHE) {
            ParsedEntry entry = PARSED_CACHE.get(key);
            if (entry != null && entry.matches(lastModified, length, baseUrl)) {
                cacheHits++;
                return entry.playlist;
            }
            cacheParses++;
        }

        byte[] data = read(file);
        Playlist parsed = PlaylistParser.parse(data, baseUrl);
        synchronized (PARSED_CACHE) {
            // 读取期间文件被改写（并发重载）时不入缓存，避免把旧内容挂在新的 mtime 上。
            if (file.lastModified() == lastModified && file.length() == length) {
                PARSED_CACHE.put(key, new ParsedEntry(lastModified, length, baseUrl, parsed));
            }
        }
        return parsed;
    }

    /** 单文件失效：源缓存被重写/删除时调用（{@link #writeAtomic} 已自动挂钩）。 */
    static void invalidateParsedCache(File file) {
        if (file == null) {
            return;
        }
        synchronized (PARSED_CACHE) {
            PARSED_CACHE.remove(file.getAbsolutePath());
        }
    }

    /** 全量失效：清空全部源（旧 playlist.m3u 迁移等）时调用。 */
    static void invalidateParsedCacheAll() {
        synchronized (PARSED_CACHE) {
            PARSED_CACHE.clear();
        }
    }

    /** 单测用：清空缓存并把命中/解析计数归零。 */
    static void resetParsedCacheForTests() {
        synchronized (PARSED_CACHE) {
            PARSED_CACHE.clear();
            cacheHits = 0;
            cacheParses = 0;
        }
    }

    /** 缓存统计快照（日志/单测断言用）。 */
    public static CacheStats cacheStats() {
        synchronized (PARSED_CACHE) {
            return new CacheStats(cacheHits, cacheParses, PARSED_CACHE.size());
        }
    }

    private static final class ParsedEntry {

        private final long lastModified;
        private final long length;
        private final String baseUrl;
        private final Playlist playlist;

        ParsedEntry(long lastModified, long length, String baseUrl, Playlist playlist) {
            this.lastModified = lastModified;
            this.length = length;
            this.baseUrl = baseUrl;
            this.playlist = playlist;
        }

        boolean matches(long lastModified, long length, String baseUrl) {
            return this.lastModified == lastModified
                    && this.length == length
                    && (this.baseUrl == null ? baseUrl == null : this.baseUrl.equals(baseUrl));
        }
    }

    /** 解析缓存统计（不可变）。 */
    public static final class CacheStats {

        private final int hits;
        private final int parses;
        private final int entries;

        CacheStats(int hits, int parses, int entries) {
            this.hits = hits;
            this.parses = parses;
            this.entries = entries;
        }

        /** 命中次数（没有 read()+parse）。 */
        public int hits() {
            return hits;
        }

        /** 实际解析次数（含读取/解析失败，命中不计数）。 */
        public int parses() {
            return parses;
        }

        public int entries() {
            return entries;
        }

        @Override
        public String toString() {
            return "CacheStats{hits=" + hits + " parses=" + parses + " entries=" + entries + "}";
        }
    }

    // ------------------------------------------------------------------ 多源

    /**
     * 读 sources.json + 各源本地缓存并合并；不联网。索引不存在时先迁移旧 playlist.m3u。
     *
     * <p>单源失败只写进 {@link SourceState#error()}，其余源继续；全部失败时
     * {@link MultiPlaylist#playlist()} 为 null，调用方按「有源但不可用」提示。</p>
     *
     * <p>源文件未变化（路径 + lastModified + length 相同）时复用上次解析快照，
     * 回前台的重新加载不会重复 read()+parse；见 {@link #parseCached(File, String)}。</p>
     */
    public static MultiPlaylist loadAll(File filesDir) {
        List<SourceRecord> records;
        try {
            records = SourceStore.load(filesDir);
        } catch (IOException e) {
            return new MultiPlaylist(Collections.<SourceRecord>emptyList(),
                    Collections.<SourceState>emptyList(), null,
                    Collections.<String>emptyList(), e.getMessage());
        } catch (RuntimeException e) {
            return new MultiPlaylist(Collections.<SourceRecord>emptyList(),
                    Collections.<SourceState>emptyList(), null,
                    Collections.<String>emptyList(),
                    e.getMessage() == null ? "源配置读取失败" : e.getMessage());
        }

        List<ChannelMerger.SourcePlaylist> inputs =
                new ArrayList<ChannelMerger.SourcePlaylist>();
        List<SourceState> states = new ArrayList<SourceState>(records.size());
        int totalParsed = 0;
        int skippedTotal = 0;

        for (SourceRecord record : records) {
            if (!record.enabled()) {
                states.add(new SourceState(record, record.channelCount(), null, 0));
                continue;
            }
            int count = record.channelCount();
            String error = null;
            int skipped = 0;
            try {
                File cache = SourceStore.cacheFile(filesDir, record);
                if (!cache.isFile()) {
                    throw new IOException("缓存文件缺失");
                }
                if (cache.length() > SourceStore.MAX_SOURCE_BYTES) {
                    throw new IOException("源文件超过 5MB");
                }
                // 未变化的源直接复用上次解析快照：回前台不再 read()+parse。
                Playlist parsed = parseCached(cache,
                        record.isUrl() ? record.location() : null);
                count = parsed.channelCount();
                skipped = parsed.skipped();
                totalParsed += count;
                skippedTotal += skipped;
                inputs.add(new ChannelMerger.SourcePlaylist(record, parsed.channels()));
            } catch (IOException e) {
                error = messageOf(e);
            } catch (PlaylistParseException e) {
                error = messageOf(e);
            } catch (RuntimeException e) {
                error = messageOf(e);
            }
            states.add(new SourceState(record, count, error, skipped));
        }

        ChannelMerger.Result merged = inputs.isEmpty()
                ? null : ChannelMerger.merge(inputs);
        List<String> warnings = merged == null
                ? new ArrayList<String>() : new ArrayList<String>(merged.warnings());

        // 把频道数/最近错误写回索引；失败不影响本次显示。
        List<SourceRecord> updated = new ArrayList<SourceRecord>(records.size());
        List<SourceState> updatedStates = new ArrayList<SourceState>(records.size());
        boolean changed = false;
        for (int i = 0; i < records.size(); i++) {
            SourceRecord record = records.get(i);
            SourceState state = states.get(i);
            SourceRecord next = record;
            if (state.error() != null) {
                next = record.withLastError(state.error());
            } else if (record.enabled()) {
                next = record.withChannelCount(state.channelCount()).withLastError(null);
            }
            if (!sameRecord(record, next)) {
                changed = true;
            }
            updated.add(next);
            updatedStates.add(new SourceState(next, state.channelCount(), state.error(),
                    state.skipped()));
        }
        if (changed) {
            try {
                SourceStore.save(filesDir, updated);
            } catch (IOException ignored) {
                // 状态写回失败只影响下次启动的显示，不影响本次播放。
            }
        }

        return new MultiPlaylist(updated, updatedStates,
                merged == null ? null : merged.playlist(), warnings, null,
                totalParsed, skippedTotal);
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "读取失败" : message;
    }

    private static boolean sameRecord(SourceRecord left, SourceRecord right) {
        return left.channelCount() == right.channelCount()
                && (left.lastError() == null
                ? right.lastError() == null : left.lastError().equals(right.lastError()));
    }

    /** 单源加载状态（设置页/启动提示用）。 */
    public static final class SourceState {

        private final SourceRecord record;
        private final int channelCount;
        private final String error;
        private final int skipped;

        public SourceState(SourceRecord record, int channelCount, String error, int skipped) {
            this.record = record;
            this.channelCount = Math.max(0, channelCount);
            this.error = error;
            this.skipped = Math.max(0, skipped);
        }

        public SourceRecord record() {
            return record;
        }

        /** 解析出的频道数；失败/停用时为上次成功值。 */
        public int channelCount() {
            return channelCount;
        }

        /** null = 正常；否则为读取/解析失败原因。 */
        public String error() {
            return error;
        }

        public boolean ok() {
            return error == null;
        }

        public int skipped() {
            return skipped;
        }

        @Override
        public String toString() {
            return "SourceState{" + record.id() + " channels=" + channelCount
                    + (error == null ? "" : " error=" + error) + "}";
        }
    }

    /** 多源加载快照。 */
    public static final class MultiPlaylist {

        private final List<SourceRecord> records;
        private final List<SourceState> sources;
        private final Playlist playlist;
        private final List<String> warnings;
        private final String fatalError;
        private final int totalParsed;
        private final int skipped;

        public MultiPlaylist(List<SourceRecord> records, List<SourceState> sources,
                Playlist playlist, List<String> warnings, String fatalError) {
            this(records, sources, playlist, warnings, fatalError, 0, 0);
        }

        public MultiPlaylist(List<SourceRecord> records, List<SourceState> sources,
                Playlist playlist, List<String> warnings, String fatalError,
                int totalParsed, int skipped) {
            this.records = Collections.unmodifiableList(new ArrayList<SourceRecord>(records));
            this.sources = Collections.unmodifiableList(new ArrayList<SourceState>(sources));
            this.playlist = playlist;
            this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
            this.fatalError = fatalError;
            this.totalParsed = totalParsed;
            this.skipped = skipped;
        }

        public List<SourceRecord> records() {
            return records;
        }

        public List<SourceState> sources() {
            return sources;
        }

        /** 合并后的频道表；没有可用源时为 null。 */
        public Playlist playlist() {
            return playlist;
        }

        /** 上限截断等提示。 */
        public List<String> warnings() {
            return warnings;
        }

        /** 索引损坏等整体错误；单源失败不在这里。 */
        public String fatalError() {
            return fatalError;
        }

        public int totalParsed() {
            return totalParsed;
        }

        public int skipped() {
            return skipped;
        }

        /** 是否存在源记录（含停用/失败）。判「无源」用这个，不用 playlist。 */
        public boolean hasSources() {
            return !records.isEmpty();
        }

        public int failedCount() {
            int count = 0;
            for (SourceState state : sources) {
                if (state.error() != null) {
                    count++;
                }
            }
            return count;
        }

        public int enabledCount() {
            int count = 0;
            for (SourceRecord record : records) {
                if (record.enabled()) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * 源状态。label 目前固定为文件名（源 URL host 由批次 3 的导入流程提供），
     * channelCount 解析失败时为 0。
     */
    public static SourceInfo readInfo(File filesDir) {
        File file = playlistFile(filesDir);
        if (!file.isFile()) {
            return new SourceInfo(null, 0L, 0L, 0, false);
        }
        int channelCount = 0;
        try {
            channelCount = load(filesDir).channelCount();
        } catch (IOException ignored) {
            channelCount = 0;
        } catch (PlaylistParseException ignored) {
            channelCount = 0;
        } catch (RuntimeException ignored) {
            channelCount = 0;
        }
        long size = file.length();
        return new SourceInfo(file.getName(), size, file.lastModified(), channelCount, size > 0L);
    }

    /** 源元数据快照（不可变）。 */
    public static final class SourceInfo {

        private final String label;
        private final long size;
        private final long lastModified;
        private final int channelCount;
        private final boolean hasSource;

        public SourceInfo(String label, long size, long lastModified, int channelCount, boolean hasSource) {
            this.label = label;
            this.size = size;
            this.lastModified = lastModified;
            this.channelCount = channelCount;
            this.hasSource = hasSource;
        }

        public String label() {
            return label;
        }

        public long size() {
            return size;
        }

        public long lastModified() {
            return lastModified;
        }

        public int channelCount() {
            return channelCount;
        }

        public boolean hasSource() {
            return hasSource;
        }

        @Override
        public String toString() {
            return "SourceInfo{" + label + " size=" + size + " channels=" + channelCount
                    + " hasSource=" + hasSource + "}";
        }
    }
}
