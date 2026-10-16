package com.sullivan.android.qr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把一段文本画成二维码位图。
 *
 * <p>{@code com.google.zxing:core} 只到 {@link BitMatrix} 为止，不含 Bitmap 桥接
 * （那是 {@code zxing-android-embedded} 才有的东西）。为了省这二十行而多引一个库、
 * 顺带被它那个难以做无障碍定制的 CaptureActivity 绑住，不划算，所以自己写。
 */
public final class QrBitmapGenerator {

    /**
     * 纯黑与纯白，**硬编码不走主题色**。
     *
     * <p>主题里的 {@code sullivan_indigo} 之类深色在低亮度屏幕、相机白平衡偏移下
     * 对比度会掉到检不出来的程度；而二维码对对比度的要求是「越高越好」，
     * 跟界面配色诉求完全相反。这是少数几处该无视主题的地方之一。
     *
     * <p>两个颜色都必须带 {@code 0xFF} 前缀，否则模块是**全透明**的
     * （{@code 0x00FFFFFF} 也是「白」），扫出来一片空白。
     */
    private static final int COLOR_BLACK = 0xFF000000;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /**
     * 静区（四周留白）宽度，单位是模块数。
     *
     * <p><b>不能设 0</b>：QR 规范要求至少 4 个模块的静区，少了它，
     * 定位图案与背景之间没有边界，相机端会大面积检不出。
     * 取 2 是「规范下限」与「尽量把模块做大」之间的折中 ——
     * 屏幕上这圈白边本来就是背景色，实际效果等于静区无限大。
     */
    private static final int MARGIN_MODULES = 2;

    private QrBitmapGenerator() {
    }

    /**
     * 生成二维码位图。
     *
     * @param text   要编码的文本（本工程是 {@link QrPayload#encode()}）
     * @param sizePx 位图边长（正方形）
     * @throws WriterException 文本太长、放不进这个尺寸时抛出
     */
    @NonNull
    public static Bitmap encode(@NonNull String text, int sizePx) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // M 档（约 15% 容错）：L 档在屏幕反光/镜头脏污下掉得很快，
        // H 档会让同样边长下的模块变小、反而更难扫。M 是常见的正确取值。
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, MARGIN_MODULES);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new QRCodeWriter()
                .encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        return toBitmap(matrix);
    }

    /** {@link BitMatrix} → {@link Bitmap}，逐像素填色。 */
    @NonNull
    private static Bitmap toBitmap(@NonNull BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                pixels[rowStart + x] = matrix.get(x, y) ? COLOR_BLACK : COLOR_WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
