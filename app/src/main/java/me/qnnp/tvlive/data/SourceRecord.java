package me.qnnp.tvlive.data;

/**
 * 一个源的元数据（不可变）。持久化到 {@code getFilesDir()/sources.json}。
 *
 * <p>{@link Kind#URL} 的 location 是导入时的 m3u 地址，解析时作为相对地址的 baseUrl，
 * 手动「重载」才重新下载；{@link Kind#FILE} 的 location 是导入时的本机文件路径，
 * 启动/重载只读 {@link #cachePath()} 缓存。缓存路径相对 filesDir（如 {@code sources/s1.m3u}）。</p>
 */
public final class SourceRecord {

    public enum Kind {
        URL,
        FILE
    }

    private final String id;
    private final String name;
    private final Kind kind;
    private final String location;
    private final boolean enabled;
    private final int order;
    private final long lastUpdated;
    private final int channelCount;
    private final String lastError;
    private final String cachePath;

    public SourceRecord(String id, String name, Kind kind, String location, boolean enabled,
            int order, long lastUpdated, int channelCount, String lastError, String cachePath) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind == null");
        }
        this.id = id.trim();
        String cleanedName = name == null ? null : name.trim();
        this.name = cleanedName == null || cleanedName.isEmpty() ? this.id : cleanedName;
        this.kind = kind;
        this.location = location == null ? "" : location;
        this.enabled = enabled;
        this.order = Math.max(0, order);
        this.lastUpdated = Math.max(0L, lastUpdated);
        this.channelCount = Math.max(0, channelCount);
        this.lastError = emptyToNull(lastError);
        this.cachePath = emptyToNull(cachePath);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public String location() {
        return location;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 合并优先级：order 小的源线路在前。 */
    public int order() {
        return order;
    }

    /** 最近一次成功导入/重载的毫秒时间戳；0 表示未知。 */
    public long lastUpdated() {
        return lastUpdated;
    }

    /** 最近一次成功解析的频道数；失败时为上次成功值（0 表示无）。 */
    public int channelCount() {
        return channelCount;
    }

    /** 最近一次读取/解析/下载失败原因；正常时为 null。 */
    public String lastError() {
        return lastError;
    }

    /** 相对 filesDir 的缓存路径；null 时用默认 {@code sources/<id>.m3u}。 */
    public String cachePath() {
        return cachePath;
    }

    public boolean isUrl() {
        return kind == Kind.URL;
    }

    public SourceRecord withName(String name) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    public SourceRecord withEnabled(boolean enabled) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    public SourceRecord withOrder(int order) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    public SourceRecord withLastUpdated(long lastUpdated) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    public SourceRecord withChannelCount(int channelCount) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    public SourceRecord withLastError(String lastError) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    /** 改地址后整体替换类型/地址/台数/更新时间，并清掉旧错误（名称/顺序/启用状态不变）。 */
    public SourceRecord withLocation(Kind kind, String location, long lastUpdated,
            int channelCount) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, null, cachePath);
    }

    public SourceRecord withCachePath(String cachePath) {
        return new SourceRecord(id, name, kind, location, enabled, order, lastUpdated,
                channelCount, lastError, cachePath);
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return "SourceRecord{" + id + " " + name + " " + kind + " order=" + order
                + " enabled=" + enabled + " channels=" + channelCount
                + (lastError == null ? "" : " error=" + lastError) + "}";
    }
}
