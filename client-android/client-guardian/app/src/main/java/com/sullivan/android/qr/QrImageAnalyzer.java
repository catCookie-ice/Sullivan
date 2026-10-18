package com.sullivan.android.qr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX 的逐帧分析器：把 YUV 帧交给 ZXing 找二维码。
 *
 * <p>几条写在代码里的硬约束，每条都对应一个「能预览但永远扫不出」的坑：
 *
 * <ul>
 *   <li><b>保留默认的 YUV 输出格式</b>。{@code ProtecteeActivity} 里那句
 *       {@code setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)} 是**为喂 ONNX 而设的**，
 *       照抄过来会让 ZXing 拿到 RGBA 数据、亮度平面直接失效。这里只用 Y 平面。</li>
 *   <li><b>每帧都必须 {@code close()}</b>，包括节流分支、解析失败分支与最后一条分支。
 *       漏掉一次，分析管线在缓冲区满之后会彻底停止回调，表现为「画面在动、就是扫不出」。</li>
 *   <li><b>{@code NotFoundException} 是正常控制流</b>（一帧里没有二维码就是常态），
 *       不打日志 —— 否则 logcat 每 200 ms 刷一条，把真正的问题淹掉。</li>
 *   <li><b>不处理旋转</b>：QR 的定位图案对旋转无关，ZXing 四个方向都能检出；
 *       本设计也不在预览上画结果框，所以旋转角只记一次日志、不做变换。</li>
 * </ul>
 *
 * <p><b>回调在主线程</b>：ZXing 的解码在 CameraX 给的分析线程上跑，
 * 解出来之后 post 回主线程，调用方可以直接停相机、finish 界面。
 */
public final class QrImageAnalyzer implements ImageAnalysis.Analyzer {

    private static final String TAG = "QrImageAnalyzer";

    /**
     * 两次解码之间的最小间隔。
     *
     * <p>相机通常给 30 fps，而整帧二值化 + 定位在 720p 下要几十毫秒。
     * 每帧都解会把分析线程占满，预览掉帧；200 ms（5 次/秒）对手持对准来说已经足够灵敏。
     */
    private static final long DECODE_INTERVAL_MS = 200L;

    public interface Listener {
        /** 扫到一张二维码。已经切回主线程。 */
        void onQrCodeDecoded(@NonNull String text);
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * {@code MultiFormatReader} **不是线程安全的**，且内部有可变状态。
     * 这里靠「整个类只在 CameraX 的单线程分析器 executor 上被调用」来保证串行，
     * 所以不要把它挪到别的线程，也不要给多个分析器共用。
     */
    private final MultiFormatReader reader = new MultiFormatReader();

    /** 扫到一次就停：继续解只是白烧电，而且会重复回调。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private long lastDecodeAtMs;
    private boolean rotationLogged;
    private boolean pixelStrideWarned;

    public QrImageAnalyzer(@NonNull Listener listener) {
        this.listener = listener;
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        // TRY_HARDER：手持对准时画面是抖的、常带运动模糊，多花一点 CPU 换检出率值得。
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
    }

    /** 停用本分析器；之后到达的帧直接丢弃。 */
    public void stop() {
        stopped.set(true);
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        // 关闭动作放在 finally：四条提前 return 的分支里漏掉任何一条，
        // 整条分析管线都会在缓冲区耗尽后静默停摆。
        try {
            if (stopped.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastDecodeAtMs < DECODE_INTERVAL_MS) {
                return;
            }
            lastDecodeAtMs = now;

            if (!rotationLogged && image.getImageInfo().getRotationDegrees() != 0) {
                rotationLogged = true;
                Log.d(TAG, "预览旋转 " + image.getImageInfo().getRotationDegrees()
                        + "°，QR 解码对旋转无关，不做变换");
            }

            byte[] luminance = extractLuminance(image);
            if (luminance == null) {
                return;
            }
            String text = decode(luminance, image.getWidth(), image.getHeight());
            if (text == null) {
                return;
            }
            stopped.set(true);
            mainHandler.post(() -> listener.onQrCodeDecoded(text));
        } finally {
            image.close();
        }
    }

    /**
     * 取出紧凑的 Y（亮度）平面。
     *
     * <p>这里选择**逐行拷成紧凑数组**，而不是把 {@code rowStride} 直接当
     * {@link PlanarYUVLuminanceSource} 的 {@code dataWidth} 传进去。
     * 后者要求「缓冲区下标 0 就是图像左上角」且没有 {@code arrayOffset}，
     * 换成直接缓冲、或被 CameraX 换成带偏移的缓冲就会错位成斜纹。
     * 拷一份是**无条件正确**的，代价是与分辨率同量级的一次 memcpy ——
     * 720p 约 0.9 MB、每秒 5 次，可以忽略。
     *
     * @return 长度 {@code width*height} 的紧凑数组；本帧不可用时返回 {@code null}
     */
    @Nullable
    private byte[] extractLuminance(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        // Y 平面在 Android 上恒为每像素 1 字节；真出现别的步长说明拿到了非预期的格式，
        // 与其按步长猜着重排，不如整帧丢掉（提示一次），免得扫出错误的码。
        if (pixelStride != 1) {
            if (!pixelStrideWarned) {
                pixelStrideWarned = true;
                Log.w(TAG, "Y 平面 pixelStride=" + pixelStride + "，本帧丢弃");
            }
            return null;
        }

        ByteBuffer buffer = yPlane.getBuffer();
        int needed = rowStride * height;
        if (buffer.capacity() < needed && rowStride != width) {
            Log.w(TAG, "Y 平面缓冲不足：capacity=" + buffer.capacity() + " needed=" + needed);
            return null;
        }

        byte[] compact = new byte[width * height];
        if (buffer.hasArray() && buffer.arrayOffset() == 0 && rowStride == width) {
            // 已经是紧凑布局，直接整块拷走。
            System.arraycopy(buffer.array(), 0, compact, 0, compact.length);
            return compact;
        }
        if (buffer.hasArray()) {
            byte[] array = buffer.array();
            int offset = buffer.arrayOffset();
            for (int y = 0; y < height; y++) {
                System.arraycopy(array, offset + y * rowStride, compact, y * width, width);
            }
            return compact;
        }
        // 直接缓冲：absolute get 不受 position 影响，但保守起见按行用 duplicate 定位。
        ByteBuffer slice = buffer.duplicate();
        for (int y = 0; y < height; y++) {
            slice.position(y * rowStride);
            slice.get(compact, y * width, width);
        }
        return compact;
    }

    @Nullable
    private String decode(@NonNull byte[] luminance, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    luminance, width, height, 0, 0, width, height, false);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            String text = result.getText();
            return text == null || text.isEmpty() ? null : text;
        } catch (NotFoundException expected) {
            // 这一帧里没有二维码，是常态；不打日志。
            return null;
        } catch (RuntimeException error) {
            // 畸形帧偶尔会让 ZXing 抛运行时异常（如 ArrayIndexOutOfBounds），
            // 不能让它冒到 CameraX 把分析器整个干掉。
            Log.w(TAG, "解码异常，已跳过本帧", error);
            return null;
        } finally {
            reader.reset();
        }
    }
}
