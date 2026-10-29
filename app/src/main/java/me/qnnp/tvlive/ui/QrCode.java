package me.qnnp.tvlive.ui;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

import me.qnnp.tvlive.control.ControlAddress;

/**
 * 二维码生成（批次 3b）：ZXing core 3.5.3 的 {@link QRCodeWriter}，黑/白、无 logo、无 banner。
 *
 * <p>{@link #matrix(String, int)} 是纯 Java 的黑白矩阵（JVM 单测直接解码验证内容），
 * {@link #bitmap(String, int)} 只做像素转换，UI 侧调用。留白由 {@code MARGIN} 提示 +
 * 视图内白边共同保证，避免二维码贴边扫不出。</p>
 */
public final class QrCode {

    /** 首启页/SettingsView 二维码生成边长（像素）。 */
    public static final int DEFAULT_SIZE = 320;

    /** 二维码四周留白（模块数，规范建议 4；这里 2 模块 + 视图 10dp 白边足够）。 */
    private static final int MARGIN_MODULES = 2;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    private QrCode() {
    }

    /** 二维码内容 {@code http://ip:port/#t=token}；无网络返回 null。 */
    public static String content(String ip, int port, String token) {
        return ControlAddress.qr(ip, port, token);
    }

    /**
     * 生成黑白矩阵：{@code true = 黑}。内容为空/尺寸非法/编码失败返回 null。
     *
     * @param size 生成边长（像素），实际矩阵为该尺寸（含留白）
     */
    public static boolean[][] matrix(String text, int size) {
        if (text == null || text.isEmpty() || size <= 0) {
            return null;
        }
        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, Integer.valueOf(MARGIN_MODULES));
        BitMatrix bits;
        try {
            bits = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
        int width = bits.getWidth();
        int height = bits.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        boolean[][] out = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y][x] = bits.get(x, y);
            }
        }
        return out;
    }

    /**
     * 生成 Bitmap（纯黑/纯白，无 logo）；失败返回 null。
     *
     * <p>只在 UI 线程调用；320px 约 1ms，不需要后台线程。</p>
     */
    public static Bitmap bitmap(String text, int size) {
        boolean[][] matrix = matrix(text, size);
        if (matrix == null) {
            return null;
        }
        int height = matrix.length;
        int width = matrix[0].length;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            boolean[] row = matrix[y];
            int base = y * width;
            for (int x = 0; x < width; x++) {
                pixels[base + x] = row[x] ? BLACK : WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
