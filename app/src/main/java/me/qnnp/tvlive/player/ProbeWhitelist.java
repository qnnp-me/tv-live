package me.qnnp.tvlive.player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探测白名单（纯 Java）：按 URL + 设备 profile 记住已确认支持的 Format。
 *
 * <p>用途：起播预探测第一次要下播放列表/分片，切台会慢 0.5–2 秒；已确认过的
 * URL 在 TTL 内直接跳过探测。不是按频道名，源 URL 变了会重新探测。</p>
 *
 * <p>限制：live 流中途可能换清晰度；TTL 只降低风险。播放失败/看门狗超时时要
 * {@link #remove(String)} 让下次重新探测。</p>
 */
public final class ProbeWhitelist {

    /** 成功结果保留 7 天；每次成功播放会续期，常用的 URL 不会过期。 */
    public static final long TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    /** 最多保留多少条，超出按 LRU 淘汰。 */
    public static final int MAX_ENTRIES = 300;

    public static final class Entry {
        public final String url;
        public final String profile;
        public final int width;
        public final int height;
        public final String mime;
        public final String method;
        public final long expiresAt;

        public Entry(String url, String profile, int width, int height,
                     String mime, String method, long expiresAt) {
            this.url = url;
            this.profile = profile;
            this.width = width;
            this.height = height;
            this.mime = mime;
            this.method = method;
            this.expiresAt = expiresAt;
        }
    }

    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<String, Entry>(16, 0.75f, true);

    public synchronized Entry get(String url, String profile, long nowMs) {
        String key = key(url, profile);
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= nowMs) {
            entries.remove(key);
            return null;
        }
        return entry;
    }

    public synchronized void put(Entry entry) {
        if (entry == null || entry.url == null || entry.profile == null) {
            return;
        }
        entries.put(key(entry.url, entry.profile), entry);
        prune(entry.expiresAt - TTL_MS);
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    /** 成功播放后续期；只续已存在的条目，不凭一次播放凭空创建。 */
    public synchronized Entry touch(String url, String profile, long nowMs) {
        String key = key(url, profile);
        Entry entry = entries.get(key);
        if (entry == null || entry.expiresAt <= nowMs) {
            return null;
        }
        Entry renewed = new Entry(entry.url, entry.profile, entry.width, entry.height,
                entry.mime, entry.method, nowMs + TTL_MS);
        entries.put(key, renewed);
        return renewed;
    }

    public synchronized void remove(String url) {
        if (url == null) {
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (url.equals(it.next().getValue().url)) {
                it.remove();
            }
        }
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<Entry>(entries.values());
    }

    public synchronized void restore(List<Entry> restored, long nowMs) {
        entries.clear();
        if (restored == null) {
            return;
        }
        for (Entry entry : restored) {
            if (entry != null && entry.expiresAt > nowMs) {
                entries.put(key(entry.url, entry.profile), entry);
            }
        }
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    private void prune(long nowMs) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt <= nowMs) {
                it.remove();
            }
        }
    }

    private static String key(String url, String profile) {
        return (url == null ? "" : url) + "\u0000" + (profile == null ? "" : profile);
    }
}
