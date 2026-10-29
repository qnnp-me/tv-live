package me.qnnp.tvlive.player;

import android.util.Log;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 切台前对目标 host 做一次后台 DNS 预解析：不阻塞主线程、不发 HTTP 请求、失败静默。
 *
 * <p>只在「首帧加速」开启时由 Activity 调用；解析放单线程守护线程，200ms 内没完成
 * 就取消（不打断播放）。Android 的 DNS 结果有系统缓存，预解析后再建 Exo 数据源
 * 通常能省掉首次连接里的解析等待。</p>
 */
public final class DnsPrefetch {

    private static final String TAG = "TvLive";
    private static final long TIMEOUT_MS = 200L;

    private static final ExecutorService RESOLVER = Executors.newSingleThreadExecutor(
            daemonFactory("dns-prefetch"));
    private static final ScheduledExecutorService TIMEOUT = Executors.newSingleThreadScheduledExecutor(
            daemonFactory("dns-prefetch-timeout"));

    private DnsPrefetch() {
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /** 从 URL 里取 host；URL 非法/没有 host 返回 null。 */
    public static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return host == null || host.isEmpty() ? null : host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** 后台解析 URL 的 host；URL 无 host 或失败时静默返回。 */
    public static void prefetchUrl(String url) {
        String host = hostOf(url);
        if (host != null) {
            prefetch(host);
        }
    }

    /** 后台解析 host，最多等 {@value #TIMEOUT_MS}ms；任何异常/超时都吞掉。 */
    public static void prefetch(final String host) {
        if (host == null || host.isEmpty()) {
            return;
        }
        try {
            final Future<?> future = RESOLVER.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    java.net.InetAddress.getByName(host);
                    return null;
                }
            });
            TIMEOUT.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            Log.d(TAG, "DNS 预解析调度失败: " + host);
        }
    }
}
