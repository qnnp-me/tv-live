package me.qnnp.tvlive.ui;

/**
 * 页面惰性构建：第一次 {@link #get()} 才调用 builder，之后一直复用同一实例。
 *
 * <p>启动时不再构建设置页/首启页（SettingsView 有 50+ View 与二维码浮层），
 * 只有真正进入对应页面才付构建成本；退出再进不重建。</p>
 *
 * <p>只在主线程使用，不做同步。</p>
 */
public final class LazyPage<T> {

    public interface Builder<T> {

        T build();
    }

    private final Builder<T> builder;
    private T page;
    private int buildCount;

    public LazyPage(Builder<T> builder) {
        if (builder == null) {
            throw new IllegalArgumentException("builder == null");
        }
        this.builder = builder;
    }

    /** 首次访问时构建；之后返回同一实例。 */
    public T get() {
        if (page == null) {
            page = builder.build();
            buildCount++;
        }
        return page;
    }

    /** 已构建的实例；没有则 null（用于「存在才顺手隐藏/刷新」的场景，不触发构建）。 */
    public T peek() {
        return page;
    }

    public boolean isBuilt() {
        return page != null;
    }

    /** 实际构建次数；惰性逻辑的单测断言用。 */
    public int buildCount() {
        return buildCount;
    }
}
