package me.qnnp.tvlive.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 探测白名单的持久化（SharedPreferences，进程内单例）。
 *
 * <p>只存“已确认支持”的 Format；播放失败时由 {@link StreamProbe#invalidate}
 * 删除，让下次重新探测。</p>
 */
final class ProbeStore {

    private static final String TAG = "TvLive";
    private static final String PREFS = "tvlive_probe";
    private static final String KEY = "whitelist";

    private static ProbeStore instance;

    private final SharedPreferences prefs;
    private final ProbeWhitelist whitelist = new ProbeWhitelist();

    static synchronized ProbeStore get(Context context) {
        if (instance == null) {
            instance = new ProbeStore(context.getApplicationContext());
        }
        return instance;
    }

    private ProbeStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    synchronized ProbeResult get(String url, String profile) {
        ProbeWhitelist.Entry entry = whitelist.get(url, profile, System.currentTimeMillis());
        if (entry == null) {
            return null;
        }
        return ProbeResult.supported(entry.width, entry.height, entry.mime, "whitelist");
    }

    synchronized void put(String url, String profile, ProbeResult result) {
        if (url == null || profile == null || result == null
                || result.status() != ProbeResult.Status.SUPPORTED) {
            return;
        }
        whitelist.put(new ProbeWhitelist.Entry(url, profile, result.width(), result.height(),
                result.mime(), result.method(),
                System.currentTimeMillis() + ProbeWhitelist.TTL_MS));
        save();
    }

    synchronized void touch(String url, String profile) {
        if (whitelist.touch(url, profile, System.currentTimeMillis()) != null) {
            save();
        }
    }

    synchronized void remove(String url) {
        whitelist.remove(url);
        save();
    }

    private void load() {
        String raw = prefs.getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        List<ProbeWhitelist.Entry> restored = new ArrayList<ProbeWhitelist.Entry>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String url = object.optString("url", null);
                String profile = object.optString("profile", null);
                if (url == null || profile == null) {
                    continue;
                }
                restored.add(new ProbeWhitelist.Entry(
                        url,
                        profile,
                        object.optInt("w", 0),
                        object.optInt("h", 0),
                        object.optString("mime", null),
                        object.optString("method", "whitelist"),
                        object.optLong("expires", 0L)));
            }
            whitelist.restore(restored, System.currentTimeMillis());
        } catch (Throwable t) {
            Log.w(TAG, "探测白名单读取失败: " + t);
        }
    }

    private void save() {
        try {
            JSONArray array = new JSONArray();
            for (ProbeWhitelist.Entry entry : whitelist.entries()) {
                JSONObject object = new JSONObject();
                object.put("url", entry.url);
                object.put("profile", entry.profile);
                object.put("w", entry.width);
                object.put("h", entry.height);
                object.put("mime", entry.mime == null ? "" : entry.mime);
                object.put("method", entry.method == null ? "" : entry.method);
                object.put("expires", entry.expiresAt);
                array.put(object);
            }
            prefs.edit().putString(KEY, array.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "探测白名单保存失败: " + t);
        }
    }
}
