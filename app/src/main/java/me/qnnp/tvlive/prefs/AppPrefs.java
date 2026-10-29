package me.qnnp.tvlive.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

/**
 * App 本地偏好：收藏、上次频道、自动跳台、开机自启、诊断。
 *
 * <p>只存 id 与布尔值；源元数据在 {@code getFilesDir()/sources.json}，
 * 每源缓存在 {@code getFilesDir()/sources/<id>.m3u}（批次 2f）。</p>
 */
public final class AppPrefs {

    private static final String NAME = "tvlive";

    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_LAST_CHANNEL = "last_channel";
    private static final String KEY_AUTO_NEXT = "auto_next";
    private static final String KEY_BOOT_AUTOSTART = "boot_autostart";
    private static final String KEY_USER_EXITED = "user_exited";
    private static final String KEY_USER_EXITED_AT = "user_exited_at";
    private static final String KEY_BOOT_LOG = "boot_log";
    private static final String KEY_PENDING_SOURCE_URL = "pending_source_url";
    private static final String KEY_LAST_ERROR = "last_error";

    private final SharedPreferences prefs;

    public AppPrefs(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        Context app = context.getApplicationContext();
        this.prefs = (app != null ? app : context).getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 收藏频道 id 快照；修改后调用 {@link #setFavorite}。 */
    public Set<String> favorites() {
        Set<String> stored = prefs.getStringSet(KEY_FAVORITES, null);
        if (stored == null || stored.isEmpty()) {
            return new HashSet<String>();
        }
        return new HashSet<String>(stored);
    }

    public boolean isFavorite(String channelId) {
        if (channelId == null) {
            return false;
        }
        return favorites().contains(channelId);
    }

    /** 切换收藏并返回新状态（true = 已收藏）。 */
    public boolean toggleFavorite(String channelId) {
        if (channelId == null) {
            return false;
        }
        Set<String> next = favorites();
        boolean favorite;
        if (next.contains(channelId)) {
            next.remove(channelId);
            favorite = false;
        } else {
            next.add(channelId);
            favorite = true;
        }
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply();
        return favorite;
    }

    public void setFavorite(String channelId, boolean favorite) {
        if (channelId == null) {
            return;
        }
        Set<String> next = favorites();
        boolean changed = favorite ? next.add(channelId) : next.remove(channelId);
        if (changed) {
            prefs.edit().putStringSet(KEY_FAVORITES, next).apply();
        }
    }

    /** 上次成功起播的频道 id；没有返回 null。 */
    public String lastChannelId() {
        String id = prefs.getString(KEY_LAST_CHANNEL, null);
        return id == null || id.isEmpty() ? null : id;
    }

    public void setLastChannelId(String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            prefs.edit().remove(KEY_LAST_CHANNEL).apply();
            return;
        }
        prefs.edit().putString(KEY_LAST_CHANNEL, channelId).apply();
    }

    /** 全屏播放失败后自动跳下一台；默认开。 */
    public boolean autoNextEnabled() {
        return prefs.getBoolean(KEY_AUTO_NEXT, true);
    }

    public void setAutoNextEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply();
    }

    /** 开机自启；批次 18 起默认开（信号适配层会先判开关再拉起）。 */
    public boolean bootAutostart() {
        return prefs.getBoolean(KEY_BOOT_AUTOSTART, true);
    }

    public void setBootAutostart(boolean enabled) {
        prefs.edit().putBoolean(KEY_BOOT_AUTOSTART, enabled).apply();
    }

    /**
     * 用户主动退出（双击 Back / 菜单退出）标记：置 true 后本次开机不再被开机信号拉起，
     * 直到下次正常启动 Activity（onCreate 会清掉）。
     */
    public boolean userExited() {
        return prefs.getBoolean(KEY_USER_EXITED, false);
    }

    public void setUserExited(boolean exited) {
        if (exited) {
            prefs.edit()
                    .putBoolean(KEY_USER_EXITED, true)
                    .putLong(KEY_USER_EXITED_AT, System.currentTimeMillis())
                    .apply();
            return;
        }
        prefs.edit()
                .putBoolean(KEY_USER_EXITED, false)
                .remove(KEY_USER_EXITED_AT)
                .apply();
    }

    /** 用户退出时的墙上时间；用于判断退出是否发生在本次开机之前。 */
    public long userExitedAtWall() {
        return prefs.getLong(KEY_USER_EXITED_AT, 0L);
    }

    /**
     * 本次开机的大致墙上时间（now - elapsedRealtime）。TCL 上 BOOT_COMPLETED 可能收不到，
     * 用它判断持久化的「用户已退出」是否属于上一次开机。
     */
    public static long bootWallClock() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** 最近 10 条开机信号日志（分隔串，见 {@code BootLog}）；供后续诊断页读取。 */
    public String bootLog() {
        String raw = prefs.getString(KEY_BOOT_LOG, null);
        return raw == null ? "" : raw;
    }

    public void setBootLog(String raw) {
        if (raw == null || raw.isEmpty()) {
            prefs.edit().remove(KEY_BOOT_LOG).apply();
            return;
        }
        prefs.edit().putString(KEY_BOOT_LOG, raw).apply();
    }

    /** 首启「在本机输入地址」暂存；批次 3 接导入后使用。 */
    public String pendingSourceUrl() {
        String url = prefs.getString(KEY_PENDING_SOURCE_URL, null);
        return url == null || url.isEmpty() ? null : url;
    }

    public void setPendingSourceUrl(String url) {
        if (url == null || url.isEmpty()) {
            prefs.edit().remove(KEY_PENDING_SOURCE_URL).apply();
            return;
        }
        prefs.edit().putString(KEY_PENDING_SOURCE_URL, url).apply();
    }

    /** 最近一次播放失败原因（设置页诊断）；没有返回 null。 */
    public String lastError() {
        String error = prefs.getString(KEY_LAST_ERROR, null);
        return error == null || error.isEmpty() ? null : error;
    }

    public void setLastError(String error) {
        if (error == null || error.isEmpty()) {
            prefs.edit().remove(KEY_LAST_ERROR).apply();
            return;
        }
        prefs.edit().putString(KEY_LAST_ERROR, error).apply();
    }

    /** 设置页「播放」分组；每次返回一个新视图，读写都落在同一个 SharedPreferences。 */
    public PlaybackSettings playbackSettings() {
        return new PlaybackSettings(new PlaybackSettings.Store() {
            @Override
            public String getString(String key, String fallback) {
                return prefs.getString(key, fallback);
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return prefs.getBoolean(key, fallback);
            }

            @Override
            public void putString(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }

            @Override
            public void putBoolean(String key, boolean value) {
                prefs.edit().putBoolean(key, value).apply();
            }
        });
    }
}
