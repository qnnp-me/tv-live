package me.qnnp.tvlive.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 批次 10：TextureView 的 SurfaceTexture → Surface 绑定令牌（纯 Java）。
 *
 * <p>同一个 SurfaceTexture 只绑定一次；旧 SurfaceTexture 的迟到 destroy 不能解绑当前
 * 画面；切回 SurfaceView 时必须主动释放（detach 返回 RELEASE），否则 Surface 泄漏。</p>
 */
public class TextureBindingTest {

    @Test
    public void availableBindsOnlyOncePerSurfaceTexture() {
        TextureBinding binding = new TextureBinding();
        Object texture = new Object();

        assertEquals(TextureBinding.Result.BIND, binding.onAvailable(texture));
        assertTrue(binding.isBound());
        // TextureView 重新 attach 时可能再次回调同一个 SurfaceTexture。
        assertEquals(TextureBinding.Result.IGNORE, binding.onAvailable(texture));
        assertEquals(TextureBinding.Result.IGNORE, binding.onAvailable(null));
        assertTrue(binding.isBound());
    }

    @Test
    public void destroyedReleasesOnlyTheCurrentSurfaceTexture() {
        TextureBinding binding = new TextureBinding();
        Object first = new Object();
        Object second = new Object();

        binding.onAvailable(first);
        assertEquals("旧 SurfaceTexture 的迟到回调不能解绑当前画面",
                TextureBinding.Result.IGNORE, binding.onDestroyed(second));
        assertTrue(binding.isBound());

        assertEquals(TextureBinding.Result.RELEASE, binding.onDestroyed(first));
        assertFalse(binding.isBound());
        assertEquals("重复 destroy 忽略", TextureBinding.Result.IGNORE,
                binding.onDestroyed(first));
        assertEquals("detach 已解绑后忽略", TextureBinding.Result.IGNORE, binding.detach());
    }

    @Test
    public void detachReleasesCurrentBindingWhenSwitchingBackToSurfaceView() {
        TextureBinding binding = new TextureBinding();
        binding.onAvailable(new Object());

        assertEquals(TextureBinding.Result.RELEASE, binding.detach());
        assertFalse(binding.isBound());
        assertEquals("没有绑定时切模式不需要释放", TextureBinding.Result.IGNORE,
                binding.detach());
    }

    @Test
    public void availableAfterDetachBindsAgain() {
        TextureBinding binding = new TextureBinding();
        Object first = new Object();
        Object second = new Object();

        binding.onAvailable(first);
        binding.detach();
        assertEquals(TextureBinding.Result.BIND, binding.onAvailable(second));
        assertTrue(binding.isBound());
    }

    @Test
    public void disablingDualReleasesStandbySurfaceOnly() {
        // 批次 12：KEEP 下 detach B（removeView 触发 destroy）只释放 B，A 不受影响。
        TextureBinding a = new TextureBinding();
        TextureBinding b = new TextureBinding();
        Object surfaceA = new Object();
        Object surfaceB = new Object();
        a.onAvailable(surfaceA);
        b.onAvailable(surfaceB);

        assertEquals(TextureBinding.Result.RELEASE, b.detach());
        assertTrue("正在用的 A 必须保持绑定", a.isBound());
        assertEquals("detach 后 B 的迟到 destroy 不重复释放", TextureBinding.Result.IGNORE,
                b.onDestroyed(surfaceB));

        // 再切回 DUAL：B 重新 attach，available 能重新绑定。
        Object rebuiltB = new Object();
        assertEquals(TextureBinding.Result.BIND, b.onAvailable(rebuiltB));
        assertTrue(b.isBound());
        assertTrue(a.isBound());
    }
}
