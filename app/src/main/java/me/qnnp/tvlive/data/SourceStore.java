package me.qnnp.tvlive.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.qnnp.tvlive.data.SourceRecord.Kind;

/**
 * 源索引与每源缓存：{@code getFilesDir()/sources.json} + {@code getFilesDir()/sources/<id>.m3u}。
 *
 * <p>纯 Java（不 import Android），单测直接传临时目录。索引与缓存都用
 * {@link PlaylistRepository#writeAtomic} 原子写（同目录 .tmp → fsync → rename），
 * 不写外部存储。</p>
 *
 * <p>迁移：sources.json 不存在且旧 {@code playlist.m3u} 存在时，把它复制成
 * {@code sources/s1.m3u} 并生成 name「当前源」的 FILE 记录；之后 sources.json 存在，
 * 不会再重复导入。旧文件保留只读兼容。</p>
 */
public final class SourceStore {

    /** 最多源数。 */
    public static final int MAX_SOURCES = 8;
    /** 单个源文件上限 5MB。 */
    public static final long MAX_SOURCE_BYTES = 5L * 1024L * 1024L;
    /** 所有源解析出的频道总数上限。 */
    public static final int MAX_TOTAL_CHANNELS = 8000;
    /** 合并去重后的频道上限。 */
    public static final int MAX_MERGED_CHANNELS = 5000;

    public static final String INDEX_FILE = "sources.json";
    public static final String LEGACY_FILE = "playlist.m3u";
    public static final String SOURCE_DIR = "sources";
    public static final String INDEX_VERSION = "1";
    public static final String MIGRATED_NAME = "当前源";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private SourceStore() {
    }

    // ------------------------------------------------------------------ 路径

    public static File indexFile(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir == null");
        }
        return new File(filesDir, INDEX_FILE);
    }

    public static File legacyFile(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir == null");
        }
        return new File(filesDir, LEGACY_FILE);
    }

    public static File sourceDir(File filesDir) {
        if (filesDir == null) {
            throw new IllegalArgumentException("filesDir == null");
        }
        return new File(filesDir, SOURCE_DIR);
    }

    public static String defaultCachePath(String id) {
        return SOURCE_DIR + "/" + id + ".m3u";
    }

    /** 某源的缓存文件；cachePath 非法/越界时回退默认路径。 */
    public static File cacheFile(File filesDir, SourceRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record == null");
        }
        String path = record.cachePath();
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("..")) {
            path = defaultCachePath(record.id());
        }
        return new File(filesDir, path);
    }

    public static File cacheFile(File filesDir, String id) {
        return new File(filesDir, defaultCachePath(id));
    }

    // ------------------------------------------------------------------ 读写

    /**
     * 读索引；索引不存在时尝试迁移旧 playlist.m3u（没有旧文件则返回空列表）。
     * 索引损坏时抛 {@link IOException}（中文原因），调用方负责提示。
     */
    public static List<SourceRecord> load(File filesDir) throws IOException {
        File index = indexFile(filesDir);
        if (!index.isFile()) {
            File legacy = legacyFile(filesDir);
            if (legacy.isFile()) {
                return migrateLegacy(filesDir);
            }
            return new ArrayList<SourceRecord>();
        }
        return parseIndex(new String(PlaylistRepository.read(index), UTF_8));
    }

    /** 按 order 升序（同 order 保持文件顺序）。 */
    public static List<SourceRecord> sorted(List<SourceRecord> sources) {
        List<SourceRecord> copy = new ArrayList<SourceRecord>(
                sources == null ? Collections.<SourceRecord>emptyList() : sources);
        Collections.sort(copy, new Comparator<SourceRecord>() {
            @Override
            public int compare(SourceRecord left, SourceRecord right) {
                return left.order() < right.order() ? -1 : (left.order() > right.order() ? 1 : 0);
            }
        });
        return copy;
    }

    /** 原子写 sources.json；最多 8 个源，id 必须唯一。 */
    public static void save(File filesDir, List<SourceRecord> sources) throws IOException {
        List<SourceRecord> list = sources == null
                ? new ArrayList<SourceRecord>() : new ArrayList<SourceRecord>(sources);
        if (list.size() > MAX_SOURCES) {
            throw new IOException("最多 " + MAX_SOURCES + " 个源");
        }
        Set<String> ids = new HashSet<String>();
        for (SourceRecord record : list) {
            if (record == null) {
                throw new IOException("源配置为空");
            }
            if (!ids.add(record.id())) {
                throw new IOException("源 id 重复: " + record.id());
            }
        }
        PlaylistRepository.writeAtomic(indexFile(filesDir), toJson(list).getBytes(UTF_8));
    }

    /** 旧 playlist.m3u → 第一个源（FILE / order 0 / enabled）。没有旧文件返回空列表。 */
    public static List<SourceRecord> migrateLegacy(File filesDir) throws IOException {
        File legacy = legacyFile(filesDir);
        if (!legacy.isFile()) {
            return new ArrayList<SourceRecord>();
        }
        byte[] data = PlaylistRepository.read(legacy);
        if (data.length > MAX_SOURCE_BYTES) {
            throw new IOException("旧源文件超过 5MB，无法迁移");
        }
        SourceRecord record = new SourceRecord("s1", MIGRATED_NAME, Kind.FILE,
                legacy.getAbsolutePath(), true, 0, legacy.lastModified(), 0, null,
                defaultCachePath("s1"));
        writeCacheFile(filesDir, record, data);
        List<SourceRecord> list = new ArrayList<SourceRecord>(1);
        list.add(record);
        save(filesDir, list);
        return list;
    }

    /** 写某源缓存（≤5MB，原子写）。 */
    public static void writeCacheFile(File filesDir, SourceRecord record, byte[] data)
            throws IOException {
        if (record == null || data == null) {
            throw new IllegalArgumentException("record/data == null");
        }
        if (data.length > MAX_SOURCE_BYTES) {
            throw new IOException("源文件超过 5MB");
        }
        PlaylistRepository.writeAtomic(cacheFile(filesDir, record), data);
    }

    /** 删除某源缓存文件（不存在也算成功）。 */
    public static void deleteCacheFile(File filesDir, SourceRecord record) {
        File file = cacheFile(filesDir, record);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File temp = new File(file.getParentFile(), file.getName() + PlaylistRepository.TEMP_SUFFIX);
        if (temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        PlaylistRepository.invalidateParsedCache(file);
    }

    /** 删除源（索引 + 缓存）；索引里没有该 id 时只清缓存。 */
    public static List<SourceRecord> deleteSource(File filesDir, String id) throws IOException {
        List<SourceRecord> records = load(filesDir);
        List<SourceRecord> next = new ArrayList<SourceRecord>(records.size());
        SourceRecord removed = null;
        for (SourceRecord record : records) {
            if (record.id().equals(id)) {
                removed = record;
                continue;
            }
            next.add(record);
        }
        // 先写索引再删缓存：写失败时源仍在，最多留下一个无主缓存。
        save(filesDir, next);
        if (removed != null) {
            deleteCacheFile(filesDir, removed);
        }
        return next;
    }

    /** 清空索引与全部缓存 + 旧 playlist.m3u（删到 0 个源时用）。 */
    public static void clearAll(File filesDir) throws IOException {
        File index = indexFile(filesDir);
        if (index.exists()) {
            //noinspection ResultOfMethodCallIgnored
            index.delete();
        }
        File temp = new File(filesDir, INDEX_FILE + PlaylistRepository.TEMP_SUFFIX);
        if (temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        File dir = sourceDir(filesDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        File legacy = legacyFile(filesDir);
        if (legacy.exists()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
        PlaylistRepository.invalidateParsedCacheAll();
    }

    /** 最小可用且稳定的源 id：s1..s8 中第一个空位（不回收中间 id 的顺序问题）。 */
    public static String nextId(List<SourceRecord> sources) {
        Set<String> used = new HashSet<String>();
        if (sources != null) {
            for (SourceRecord record : sources) {
                used.add(record.id());
            }
        }
        for (int i = 1; i <= MAX_SOURCES; i++) {
            String id = "s" + i;
            if (!used.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /** 重新分配 order = 0..n-1，保持传入顺序。 */
    public static List<SourceRecord> renumber(List<SourceRecord> sources) {
        List<SourceRecord> next = new ArrayList<SourceRecord>();
        if (sources != null) {
            int order = 0;
            for (SourceRecord record : sources) {
                next.add(record.withOrder(order++));
            }
        }
        return next;
    }

    // ------------------------------------------------------------------ JSON 写

    private static String toJson(List<SourceRecord> sources) {
        StringBuilder out = new StringBuilder(256 + sources.size() * 160);
        out.append("{\"version\":").append(INDEX_VERSION).append(",\"sources\":[");
        for (int i = 0; i < sources.size(); i++) {
            SourceRecord record = sources.get(i);
            if (i > 0) {
                out.append(',');
            }
            out.append('{');
            appendString(out, "id", record.id()).append(',');
            appendString(out, "name", record.name()).append(',');
            appendString(out, "kind", record.kind().name()).append(',');
            appendString(out, "location", record.location()).append(',');
            out.append("\"enabled\":").append(record.enabled()).append(',');
            out.append("\"order\":").append(record.order()).append(',');
            out.append("\"lastUpdated\":").append(record.lastUpdated()).append(',');
            out.append("\"channelCount\":").append(record.channelCount()).append(',');
            appendString(out, "lastError", record.lastError()).append(',');
            appendString(out, "cachePath", record.cachePath());
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private static StringBuilder appendString(StringBuilder out, String key, String value) {
        out.append('"').append(key).append("\":");
        if (value == null) {
            out.append("null");
            return out;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
        return out;
    }

    // ------------------------------------------------------------------ JSON 读

    private static List<SourceRecord> parseIndex(String text) throws IOException {
        Object root = new Json(text).parseDocument();
        if (!(root instanceof JsonObject)) {
            throw new IOException("源配置格式错误");
        }
        JsonObject object = (JsonObject) root;
        String version = object.string("version");
        if (version != null && !INDEX_VERSION.equals(version)) {
            throw new IOException("不支持的源配置版本: " + version);
        }
        List<JsonObject> items = object.objectList("sources");
        if (items.size() > MAX_SOURCES) {
            throw new IOException("源配置超过 " + MAX_SOURCES + " 个源");
        }
        List<SourceRecord> sources = new ArrayList<SourceRecord>(items.size());
        for (JsonObject item : items) {
            String id = item.string("id");
            if (id == null || id.trim().isEmpty()) {
                throw new IOException("源配置缺少 id");
            }
            String kindName = item.string("kind");
            Kind kind;
            try {
                kind = Kind.valueOf(kindName == null ? Kind.FILE.name() : kindName);
            } catch (IllegalArgumentException e) {
                throw new IOException("源 " + id + " 的类型无效: " + kindName);
            }
            sources.add(new SourceRecord(
                    id,
                    item.string("name"),
                    kind,
                    item.string("location"),
                    item.bool("enabled", true),
                    (int) item.number("order", 0L),
                    item.number("lastUpdated", 0L),
                    (int) item.number("channelCount", 0L),
                    item.string("lastError"),
                    item.string("cachePath")));
        }
        return sorted(sources);
    }

    /** 极简 JSON 值模型（只覆盖本文件用到的类型；不引第三方）。 */
    private static final class JsonObject {

        private final java.util.Map<String, Object> values =
                new java.util.LinkedHashMap<String, Object>();

        String string(String key) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : null;
        }

        boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        long number(String key, long fallback) {
            Object value = values.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return fallback;
        }

        @SuppressWarnings("unchecked")
        List<JsonObject> objectList(String key) throws IOException {
            Object value = values.get(key);
            if (value == null) {
                return new ArrayList<JsonObject>();
            }
            if (!(value instanceof List)) {
                throw new IOException("源配置 sources 不是数组");
            }
            List<JsonObject> result = new ArrayList<JsonObject>();
            for (Object item : (List<Object>) value) {
                if (!(item instanceof JsonObject)) {
                    throw new IOException("源配置 sources 项格式错误");
                }
                result.add((JsonObject) item);
            }
            return result;
        }
    }

    private static final class Json {

        private final String text;
        private int pos;

        Json(String text) {
            this.text = text == null ? "" : text;
        }

        Object parseDocument() throws IOException {
            Object value = readValue();
            skipWhitespace();
            if (pos < text.length()) {
                throw error("多余内容");
            }
            return value;
        }

        private Object readValue() throws IOException {
            skipWhitespace();
            if (pos >= text.length()) {
                throw error("内容意外结束");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw error("无法识别的字符: " + c);
            }
        }

        private JsonObject readObject() throws IOException {
            JsonObject object = new JsonObject();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("对象键必须是字符串");
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("缺少冒号");
                }
                pos++;
                object.values.put(key, readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return object;
                }
                throw error("对象缺少逗号或右括号");
            }
        }

        private List<Object> readArray() throws IOException {
            List<Object> list = new ArrayList<Object>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw error("数组缺少逗号或右括号");
            }
        }

        private String readString() throws IOException {
            pos++; // "
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw error("字符串转义不完整");
                }
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error("\\u 转义不完整");
                        }
                        String hex = text.substring(pos, pos + 4);
                        pos += 4;
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("\\u 转义无效");
                        }
                        break;
                    default:
                        throw error("未知转义: \\" + escaped);
                }
            }
            throw error("字符串未闭合");
        }

        private Number readNumber() throws IOException {
            int start = pos;
            boolean decimal = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            try {
                return decimal ? (Number) Double.valueOf(token) : (Number) Long.valueOf(token);
            } catch (NumberFormatException e) {
                throw error("数字无效: " + token);
            }
        }

        private void expect(String literal) throws IOException {
            if (!text.startsWith(literal, pos)) {
                throw error("应为 " + literal);
            }
            pos += literal.length();
        }

        private char peek() throws IOException {
            if (pos >= text.length()) {
                throw error("内容意外结束");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private IOException error(String reason) {
            return new IOException("源配置损坏（位置 " + pos + "）：" + reason);
        }
    }
}
