package me.qnnp.tvlive.control;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.qnnp.tvlive.control.M3uImport.ImportException;
import me.qnnp.tvlive.control.StateProvider.Snapshot;
import me.qnnp.tvlive.control.StateProvider.SourceEntry;
import me.qnnp.tvlive.data.PlaylistRepository;
import me.qnnp.tvlive.data.SourceDownloader;
import me.qnnp.tvlive.data.SourceRecord;
import me.qnnp.tvlive.data.SourceRecord.Kind;
import me.qnnp.tvlive.data.SourceStore;

/**
 * 手机页业务接口：源增删改查 + 状态快照（3a）；频道搜索、手机切台（3b）。
 *
 * <p>不依赖 Android/HTTP 传输层：文件目录、「当前台」「重新加载」由 {@link Host} 提供，
 * URL 下载由 {@link Fetcher} 提供（单测注入假实现）。所有写操作串行执行，
 * 成功后调用 {@link Host#requestReload()} 让 TV 重新合并并刷新列表。搜索/切台只读
 * {@link Host#channels()} 的内存快照，切台由 Host 投递到 TV 主线程，HTTP 线程不等待播放。</p>
 */
public final class SourceApi {

    public static final int MAX_SOURCES = SourceStore.MAX_SOURCES;
    /** 单次请求体上限 5MB。 */
    public static final long MAX_REQUEST_BYTES = SourceStore.MAX_SOURCE_BYTES;
    /** 源地址最大长度。 */
    public static final int MAX_URL_LENGTH = 2048;
    /** 源名称最大长度。 */
    public static final int MAX_NAME_LENGTH = 40;
    /** 手机搜索最多返回多少条（批次 3b）。 */
    public static final int MAX_SEARCH_RESULTS = 50;
    /** 频道 id 最大长度。 */
    public static final int MAX_CHANNEL_ID_LENGTH = 128;

    private static final String MIME_JSON = "application/json; charset=utf-8";

    private final Host host;
    private final Fetcher fetcher;
    private final Object lock = new Object();

    /** TV 侧环境：文件目录、版本、重新加载、频道快照与切台。 */
    public interface Host {

        File filesDir();

        String appVersion();

        /** 源变更后请求 TV 重新加载合并列表（实现方负责切主线程）。 */
        void requestReload();

        /**
         * 当前合并后的频道（不可变快照，按列表顺序）。HTTP 线程直接读，实现方
         * 不得在此访问磁盘/等待主线程；没有列表时返回空列表。
         */
        List<ChannelRef> channels();

        /**
         * 请求切台<b>立即返回</b>：实现方只需判断 id 是否存在并把播放投递到主线程。
         *
         * @return true = 已投递；false = 当前无法切（无列表/暂停等）
         */
        boolean play(String id);
    }

    /** 手机页搜索/切台用的频道元数据（不带上游地址，避免暴露到页面）。 */
    public static final class ChannelRef {

        private final String id;
        private final String name;
        private final String group;
        private final String source;

        public ChannelRef(String id, String name, String group, String source) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.source = source;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String group() {
            return group;
        }

        public String source() {
            return source;
        }
    }

    /** URL 下载器（单测注入）。 */
    public interface Fetcher {

        byte[] fetch(String url) throws IOException;
    }

    public SourceApi(Host host) {
        this(host, new Fetcher() {
            @Override
            public byte[] fetch(String url) throws IOException {
                return SourceDownloader.download(url, (int) MAX_REQUEST_BYTES);
            }
        });
    }

    public SourceApi(Host host, Fetcher fetcher) {
        if (host == null) {
            throw new IllegalArgumentException("host == null");
        }
        this.host = host;
        this.fetcher = fetcher;
    }

    // ------------------------------------------------------------------ 读

    /** App 版本（/api/ping 用）。 */
    public String stateVersion() {
        return host.appVersion();
    }

    /**
     * 状态快照：版本、频道总数、当前台、各源状态。
     *
     * <p>只读 {@link Snapshot}，<b>不读磁盘、不解析 m3u</b>；快照未就绪返回
     * {@code {"ready":false,...}}，不阻塞、不重试。</p>
     */
    public Result state(Snapshot snapshot) {
        boolean ready = snapshot != null && snapshot.ready();
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        out.append("\"ready\":").append(ready).append(',');
        out.append("\"version\":").append(Json.quote(host.appVersion()));
        if (!ready) {
            out.append(",\"channels\":0,\"current\":null,\"sources\":[]");
            out.append(",\"note\":\"TV 正在加载\"}");
            return new Result(200, out.toString());
        }
        out.append(",\"channels\":").append(snapshot.channels()).append(',');
        out.append("\"current\":");
        if (snapshot.currentId() == null && snapshot.currentName() == null) {
            out.append("null");
        } else {
            out.append('{')
                    .append("\"id\":").append(Json.quote(snapshot.currentId())).append(',')
                    .append("\"name\":").append(Json.quote(snapshot.currentName()))
                    .append('}');
        }
        if (snapshot.fatalError() != null) {
            out.append(",\"fatalError\":").append(Json.quote(snapshot.fatalError()));
        }
        out.append(",\"sources\":[");
        List<SourceEntry> sources = snapshot.sources();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(sourceJson(sources.get(i)));
        }
        out.append("]}");
        return new Result(200, out.toString());
    }

    /** 没有快照来源时（测试/异常）按未就绪返回。 */
    public Result state() {
        return state(null);
    }

    // ------------------------------------------------------------------ 搜索 / 切台

    /**
     * 按频道名包含匹配（不区分大小写），最多 {@value #MAX_SEARCH_RESULTS} 条。
     *
     * <p>只读 {@link Host#channels()} 的内存快照，不解析源/不碰磁盘，HTTP 线程可直接调用。
     * 返回 {@code {ok, query, results:[{id,name,group,source}], total, truncated}}；
     * 空关键词返回空结果（不当作错误，页面输入清空时直接不请求）。</p>
     */
    public Result search(String query) {
        String keyword = query == null ? "" : query.trim();
        List<ChannelRef> channels = host.channels();
        StringBuilder out = new StringBuilder(512);
        out.append("{\"ok\":true,\"query\":").append(Json.quote(keyword))
                .append(",\"results\":[");
        int total = 0;
        if (!keyword.isEmpty() && channels != null) {
            String needle = keyword.toLowerCase(Locale.US);
            String compactNeedle = compact(keyword, false);
            String plusNeedle = compact(keyword, true);
            // 去掉空格/符号后的关键词为空（例如只输入 "-"）时只保留原名直接包含。
            boolean normalized = !compactNeedle.isEmpty();
            for (ChannelRef channel : channels) {
                if (channel == null || channel.name() == null
                        || !matches(channel.name(), needle, compactNeedle, plusNeedle,
                                normalized)) {
                    continue;
                }
                total++;
                if (total > MAX_SEARCH_RESULTS) {
                    continue;
                }
                if (total > 1) {
                    out.append(',');
                }
                out.append(channelJson(channel));
            }
        }
        int shown = Math.min(total, MAX_SEARCH_RESULTS);
        out.append("],\"total\":").append(total)
                .append(",\"truncated\":").append(total > shown)
                .append('}');
        return new Result(200, out.toString());
    }

    /**
     * 频道名匹配：先保留原名直接包含（旧行为），再按「去符号」规范化匹配。
     *
     * <p>规范化保留字母/数字/中文，删除空格与 {@code - + . _ ·} 等符号；同时提供
     * 把 {@code +} 读成 {@code plus} 的变体，所以 {@code cctvplus1} 能搜到
     * {@code CCTV+ 1}，{@code cctv1} 能搜到 {@code CCTV-1} / {@code CCTV1}。</p>
     */
    private static boolean matches(String name, String rawNeedle, String compactNeedle,
            String plusNeedle, boolean normalized) {
        if (name.toLowerCase(Locale.US).contains(rawNeedle)) {
            return true;
        }
        if (!normalized) {
            return false;
        }
        return compact(name, false).contains(compactNeedle)
                || compact(name, true).contains(plusNeedle);
    }

    /**
     * 只保留字母/数字/中文并统一小写；{@code plusAsWord} 时把 {@code +} 展开成
     * {@code plus}（用于 {@code CCTV+ 1} ↔ {@code cctvplus1}）。
     */
    static String compact(String text, boolean plusAsWord) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' && plusAsWord) {
                out.append("plus");
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /**
     * 手机点搜索结果切台：校验 id 存在于当前快照后交给 {@link Host#play(String)}。
     *
     * <p>不阻塞 HTTP 线程：host 只投递到主线程；这里的检查只读内存列表。</p>
     */
    public Result play(String id) {
        String channelId = id == null ? "" : id.trim();
        if (channelId.isEmpty()) {
            return Result.error(400, "缺少频道 id");
        }
        if (channelId.length() > MAX_CHANNEL_ID_LENGTH) {
            return Result.error(400, "频道 id 过长");
        }
        List<ChannelRef> channels = host.channels();
        boolean found = false;
        if (channels != null) {
            for (ChannelRef channel : channels) {
                if (channel != null && channelId.equals(channel.id())) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            return Result.error(404, "频道不存在，请刷新后重试");
        }
        if (!host.play(channelId)) {
            return Result.error(409, "电视当前无法切台（没有可用列表）");
        }
        return Result.ok("{\"id\":" + Json.quote(channelId) + "}");
    }

    // ------------------------------------------------------------------ 写

    /** 添加 URL 源：下载 → 校验 → 保存缓存。 */
    public Result add(String url, String name) {
        String address = url == null ? "" : url.trim();
        if (address.isEmpty()) {
            return Result.error(400, "源地址不能为空");
        }
        if (address.length() > MAX_URL_LENGTH) {
            return Result.error(400, "源地址过长");
        }
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            return Result.error(400, "源地址需以 http:// 或 https:// 开头");
        }
        byte[] data;
        try {
            data = fetcher.fetch(address);
        } catch (IOException e) {
            return Result.error(502, downloadError(e));
        } catch (RuntimeException e) {
            return Result.error(502, downloadError(e));
        }
        int channels;
        try {
            channels = M3uImport.validate(data, address);
        } catch (ImportException e) {
            return Result.error(400, e.getMessage());
        }
        String label = cleanName(name);
        if (label == null) {
            label = M3uImport.suggestName(address, "网络源");
        }
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            if (records.size() >= MAX_SOURCES) {
                return Result.error(409, "最多 " + MAX_SOURCES + " 个源，请先删除一个");
            }
            for (SourceRecord record : records) {
                if (record.isUrl() && address.equals(record.location())) {
                    return Result.error(409, "该地址已存在");
                }
            }
            String id = SourceStore.nextId(records);
            if (id == null) {
                return Result.error(409, "最多 " + MAX_SOURCES + " 个源，请先删除一个");
            }
            SourceRecord record = new SourceRecord(id, label, Kind.URL, address, true,
                    records.size(), System.currentTimeMillis(), channels, null,
                    SourceStore.defaultCachePath(id));
            try {
                SourceStore.writeCacheFile(host.filesDir(), record, data);
            } catch (IOException e) {
                return Result.error(500, "保存源文件失败：" + messageOf(e));
            }
            Result saved = persist(records, record);
            if (saved != null) {
                SourceStore.deleteCacheFile(host.filesDir(), record);
                return saved;
            }
            return Result.ok("{\"source\":" + sourceJson(entryOf(record, channels, null)) + "}");
        }
    }

    /** 上传 m3u 文件（手机页 multipart 或 curl 原始 body）。 */
    public Result upload(byte[] data, String filename) {
        if (data == null || data.length == 0) {
            return Result.error(400, "文件为空");
        }
        if (data.length > MAX_REQUEST_BYTES) {
            return Result.error(413, "文件超过 5MB");
        }
        String name = filename == null ? "" : filename.trim();
        if (!name.isEmpty()) {
            int slash = name.lastIndexOf('/');
            int backslash = name.lastIndexOf('\\');
            name = name.substring(Math.max(slash, backslash) + 1).trim();
        }
        if (!name.isEmpty() && !M3uImport.hasM3uExtension(name)) {
            return Result.error(400, "只接受 .m3u / .m3u8 文件");
        }
        int channels;
        try {
            channels = M3uImport.validate(data, null);
        } catch (ImportException e) {
            return Result.error(400, e.getMessage());
        }
        String label = M3uImport.suggestName(name, "上传源");
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            if (records.size() >= MAX_SOURCES) {
                return Result.error(409, "最多 " + MAX_SOURCES + " 个源，请先删除一个");
            }
            String id = SourceStore.nextId(records);
            if (id == null) {
                return Result.error(409, "最多 " + MAX_SOURCES + " 个源，请先删除一个");
            }
            File cache = SourceStore.cacheFile(host.filesDir(), id);
            SourceRecord record = new SourceRecord(id, label, Kind.FILE, cache.getAbsolutePath(),
                    true, records.size(), System.currentTimeMillis(), channels, null,
                    SourceStore.defaultCachePath(id));
            try {
                SourceStore.writeCacheFile(host.filesDir(), record, data);
            } catch (IOException e) {
                return Result.error(500, "保存文件失败：" + messageOf(e));
            }
            Result saved = persist(records, record);
            if (saved != null) {
                SourceStore.deleteCacheFile(host.filesDir(), record);
                return saved;
            }
            return Result.ok("{\"source\":" + sourceJson(entryOf(record, channels, null)) + "}");
        }
    }

    public Result toggle(String id, boolean enabled) {
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, id);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            List<SourceRecord> next = new ArrayList<SourceRecord>(records);
            next.set(index, records.get(index).withEnabled(enabled));
            Result saved = persistAll(next);
            return saved != null ? saved : Result.ok("{}");
        }
    }

    public Result delete(String id) {
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, id);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            try {
                if (records.size() <= 1) {
                    SourceStore.clearAll(host.filesDir());
                } else {
                    SourceStore.deleteSource(host.filesDir(), id);
                }
            } catch (IOException e) {
                return Result.error(500, "删除源失败：" + messageOf(e));
            } catch (RuntimeException e) {
                return Result.error(500, "删除源失败：" + messageOf(e));
            }
        }
        host.requestReload();
        return Result.ok("{}");
    }

    public Result rename(String id, String name) {
        String label = cleanName(name);
        if (label == null) {
            return Result.error(400, "名称不能为空");
        }
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, id);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            List<SourceRecord> next = new ArrayList<SourceRecord>(records);
            next.set(index, records.get(index).withName(label));
            Result saved = persistAll(next);
            return saved != null ? saved : Result.ok("{}");
        }
    }

    /** delta = -1 上移，+1 下移；到边界返回 400。 */
    public Result order(String id, int delta) {        if (delta != -1 && delta != 1) {
            return Result.error(400, "delta 只能是 -1 或 1");
        }
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, id);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            int target = index + delta;
            if (target < 0 || target >= records.size()) {
                return Result.error(400, "已经到边界");
            }
            List<SourceRecord> next = new ArrayList<SourceRecord>(records);
            next.set(index, records.get(target));
            next.set(target, records.get(index));
            Result saved = persistAll(next);
            return saved != null ? saved : Result.ok("{}");
        }
    }

    /** 重新加载全部：只重读本地缓存，不联网。 */
    public Result reload() {
        host.requestReload();
        return Result.ok("{}");
    }

    /**
     * 改源地址：下载并校验新 URL，成功后保留 id / 名称 / 顺序 / 启用状态，
     * kind 一律变为 URL（FILE 源因此转成 URL 源），缓存替换为新内容。
     *
     * <p>失败（地址非法、下载失败、不是 m3u、与其他 URL 源重复、保存失败）保留旧源
     * 与旧缓存，返回中文原因；成功触发 TV 重新合并。</p>
     */
    public Result changeLocation(String id, String url) {
        String sourceId = id == null ? "" : id.trim();
        if (sourceId.isEmpty()) {
            return Result.error(400, "缺少源 id");
        }
        String address = url == null ? "" : url.trim();
        if (address.isEmpty()) {
            return Result.error(400, "源地址不能为空");
        }
        if (address.length() > MAX_URL_LENGTH) {
            return Result.error(400, "源地址过长");
        }
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            return Result.error(400, "源地址需以 http:// 或 https:// 开头");
        }
        // 先查一次重复，避免明显冲突时白下载。
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, sourceId);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            Result duplicate = duplicateLocation(records, index, address);
            if (duplicate != null) {
                return duplicate;
            }
        }
        byte[] data;
        try {
            data = fetcher.fetch(address);
        } catch (IOException e) {
            return Result.error(502, downloadError(e));
        } catch (RuntimeException e) {
            return Result.error(502, downloadError(e));
        }
        int channels;
        try {
            channels = M3uImport.validate(data, address);
        } catch (ImportException e) {
            return Result.error(400, e.getMessage());
        }
        synchronized (lock) {
            List<SourceRecord> records;
            try {
                records = loadRecords();
            } catch (StorageError e) {
                return Result.error(500, e.getMessage());
            }
            int index = indexOf(records, sourceId);
            if (index < 0) {
                return Result.error(404, "源不存在");
            }
            Result duplicate = duplicateLocation(records, index, address);
            if (duplicate != null) {
                return duplicate;
            }
            SourceRecord old = records.get(index);
            SourceRecord updated = old.withLocation(Kind.URL, address,
                    System.currentTimeMillis(), channels);
            byte[] previousCache = readCache(old);
            try {
                SourceStore.writeCacheFile(host.filesDir(), updated, data);
            } catch (IOException e) {
                return Result.error(500, "保存源文件失败：" + messageOf(e));
            }
            List<SourceRecord> next = new ArrayList<SourceRecord>(records);
            next.set(index, updated);
            Result saved = persistAll(next);
            if (saved != null) {
                // 索引没写成功：把旧缓存放回去，保持「失败保留旧源」。
                restoreCache(old, previousCache);
                return saved;
            }
            return Result.ok("{\"source\":"
                    + sourceJson(entryOf(updated, channels, null)) + "}");
        }
    }

    private static Result duplicateLocation(List<SourceRecord> records, int index,
            String address) {
        for (int i = 0; i < records.size(); i++) {
            SourceRecord record = records.get(i);
            if (i != index && record.isUrl() && address.equals(record.location())) {
                return Result.error(409, "该地址已存在");
            }
        }
        return null;
    }

    /** 旧缓存内容（用于索引保存失败时回滚）；不存在/读失败返回 null。 */
    private byte[] readCache(SourceRecord record) {
        try {
            File file = SourceStore.cacheFile(host.filesDir(), record);
            return file.isFile() ? PlaylistRepository.read(file) : null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void restoreCache(SourceRecord record, byte[] data) {
        try {
            if (data == null) {
                SourceStore.deleteCacheFile(host.filesDir(), record);
            } else {
                SourceStore.writeCacheFile(host.filesDir(), record, data);
            }
        } catch (IOException ignored) {
            // 回滚失败只能等下轮重载重新下载；不覆盖原始错误。
        } catch (RuntimeException ignored) {
            // 同上。
        }
    }

    // ------------------------------------------------------------------ 内部

    private Result persist(List<SourceRecord> records, SourceRecord added) {
        List<SourceRecord> next = new ArrayList<SourceRecord>(records.size() + 1);
        next.addAll(records);
        next.add(added);
        return persistAll(next);
    }

    /** 保存并请求 TV 重载；成功返回 null，失败返回错误结果。 */
    private Result persistAll(List<SourceRecord> next) {
        try {
            SourceStore.save(host.filesDir(), SourceStore.renumber(next));
        } catch (IOException e) {
            return Result.error(500, "保存源配置失败：" + messageOf(e));
        } catch (RuntimeException e) {
            return Result.error(500, "保存源配置失败：" + messageOf(e));
        }
        host.requestReload();
        return null;
    }

    private List<SourceRecord> loadRecords() throws StorageError {
        try {
            return SourceStore.load(host.filesDir());
        } catch (IOException e) {
            throw new StorageError("读取源配置失败：" + messageOf(e));
        } catch (RuntimeException e) {
            throw new StorageError("读取源配置失败：" + messageOf(e));
        }
    }

    private static int indexOf(List<SourceRecord> records, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static SourceEntry entryOf(SourceRecord record, int channels, String lastError) {
        return new SourceEntry(record.id(), record.name(), record.kind().name(),
                record.enabled(), record.order(), channels, lastError,
                record.isUrl() ? record.location() : null);
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_NAME_LENGTH
                ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    private static String channelJson(ChannelRef channel) {
        return "{"
                + "\"id\":" + Json.quote(channel.id()) + ","
                + "\"name\":" + Json.quote(channel.name()) + ","
                + "\"group\":" + Json.quote(channel.group()) + ","
                + "\"source\":" + Json.quote(channel.source())
                + "}";
    }

    private static String sourceJson(SourceEntry source) {
        return "{"
                + "\"id\":" + Json.quote(source.id()) + ","
                + "\"name\":" + Json.quote(source.name()) + ","
                + "\"kind\":" + Json.quote(source.kind()) + ","
                + "\"enabled\":" + source.enabled() + ","
                + "\"order\":" + source.order() + ","
                + "\"channels\":" + source.channels() + ","
                + "\"lastError\":" + Json.quote(source.lastError()) + ","
                + "\"location\":" + Json.quote(source.location())
                + "}";
    }

    private static String downloadError(Throwable error) {
        String message = messageOf(error);
        String lower = message.toLowerCase(java.util.Locale.US);
        if (lower.contains("ssl") || lower.contains("tls") || lower.contains("handshake")) {
            return "下载失败：" + message + "（电视系统较旧，https 可能不受支持，可改用 http）";
        }
        return "下载失败：" + message;
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty() ? "未知错误" : message;
    }

    /** 源配置读写失败。 */
    private static final class StorageError extends Exception {

        private static final long serialVersionUID = 1L;

        StorageError(String message) {
            super(message);
        }
    }

    /** 接口结果：HTTP 状态码 + JSON 体。 */
    public static final class Result {

        private final int status;
        private final String json;

        public Result(int status, String json) {
            this.status = status;
            this.json = json == null ? "{}" : json;
        }

        public int status() {
            return status;
        }

        public String json() {
            return json;
        }

        public String mimeType() {
            return MIME_JSON;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 失败结果里的中文原因；成功返回 null。 */
        public String errorMessage() {
            if (ok()) {
                return null;
            }
            try {
                String message = Json.string(Json.parseObject(json), "error");
                return message == null || message.isEmpty() ? "未知错误" : message;
            } catch (Json.JsonException e) {
                return "未知错误";
            } catch (RuntimeException e) {
                return "未知错误";
            }
        }

        static Result ok(String json) {
            return new Result(200, "{\"ok\":true" + jsonBody(json) + "}");
        }

        static Result error(int status, String message) {
            return new Result(status,
                    "{\"ok\":false,\"error\":" + Json.quote(message) + "}");
        }

        private static String jsonBody(String json) {
            if (json == null || json.isEmpty() || "{}".equals(json)) {
                return "";
            }
            if (json.startsWith("{")) {
                return "," + json.substring(1);
            }
            return ",\"data\":" + json;
        }
    }
}
