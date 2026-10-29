package me.qnnp.tvlive.ui;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

import me.qnnp.tvlive.control.ControlAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 二维码内容/尺寸（3b）：ZXing 生成后直接用 ZXing 解码验证（设备验收再用 zbar 扫屏幕）。
 *
 * <p>只测纯 Java 的 {@link QrCode#matrix} / {@link QrCode#content}，不碰 Bitmap。</p>
 */
public class QrCodeTest {

    @Test
    public void contentIsPhonePageWithToken() {
        assertEquals("http://192.168.1.23:18088/?v=5#t=abc",
                QrCode.content("192.168.1.23", 18088, "abc"));
        assertNull(QrCode.content(null, 18088, "abc"));
    }

    @Test
    public void matrixHasRequestedSizeAndWhiteQuietZone() {
        boolean[][] matrix = QrCode.matrix("http://192.168.1.23:18088/#t=abc", 320);
        assertNotNull(matrix);
        assertEquals(320, matrix.length);
        assertEquals(320, matrix[0].length);
        // 留白：四角必须为白（黑角会导致部分扫码器识别失败）。
        assertFalse(matrix[0][0]);
        assertFalse(matrix[0][319]);
        assertFalse(matrix[319][0]);
        assertFalse(matrix[319][319]);
    }

    @Test
    public void generatedQrDecodesToExactUrl() throws Exception {
        String content = ControlAddress.qr("10.0.0.5", 18089,
                "0123456789abcdefghijklmnopqrstuv");
        boolean[][] matrix = QrCode.matrix(content, 320);
        assertNotNull(matrix);
        assertEquals(content, decode(matrix));
    }

    @Test
    public void invalidInputReturnsNull() {
        assertNull(QrCode.matrix(null, 320));
        assertNull(QrCode.matrix("", 320));
        assertNull(QrCode.matrix("x", 0));
    }

    /** 黑白矩阵 → RGB 像素 → ZXing 解码（与扫码器同一套定位/纠错）。 */
    private static String decode(boolean[][] matrix) throws Exception {
        int height = matrix.length;
        int width = matrix[0].length;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = matrix[y][x] ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(width, height, pixels)));
        String text = new QRCodeReader().decode(bitmap).getText();
        assertFalse(text.isEmpty());
        return text;
    }
}
