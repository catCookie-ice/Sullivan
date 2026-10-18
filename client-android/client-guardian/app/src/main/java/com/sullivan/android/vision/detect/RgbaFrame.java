package com.sullivan.android.vision.detect;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * 一帧 RGBA_8888 相机数据的独立副本。
 *
 * <p>CameraX 的 {@link ImageProxy} 必须在分析回调返回前关闭，而推理耗时较长，
 * 因此这里先把像素复制出来，再在后台线程上处理，避免阻塞相机管线。
 *
 * <p>注意数据本身并没有被旋转，{@link #rotationDegrees} 表示"需要顺时针旋转多少度
 * 才能让画面正立"。旋转在预处理采样时一并完成，不需要额外的内存搬运。
 */
public final class RgbaFrame {

    /** 像素数据，行间可能有 padding，需配合 {@link #rowStride} 使用。 */
    public final byte[] data;
    /** 未旋转时的缓冲区宽度。 */
    public final int width;
    /** 未旋转时的缓冲区高度。 */
    public final int height;
    public final int rowStride;
    public final int pixelStride;
    /** 0 / 90 / 180 / 270。 */
    public final int rotationDegrees;
    /**
     * 传感器曝光时刻，单位纳秒；未知时为 0。
     *
     * <p>测速要用**曝光时刻**而不是推理完成时刻：推理本身要 60–120 ms，而帧间隔可能只有
     * 67 ms，用后者会在时间轴上注入与帧间隔同量级的抖动。
     */
    public final long timestampNanos;

    private RgbaFrame(byte[] data, int width, int height, int rowStride, int pixelStride,
                      int rotationDegrees, long timestampNanos) {
        this.data = data;
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.pixelStride = pixelStride;
        this.rotationDegrees = rotationDegrees;
        this.timestampNanos = timestampNanos;
    }

    /** 把 {@link ImageProxy} 的像素复制出来；调用方负责随后关闭它。 */
    @NonNull
    public static RgbaFrame from(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        buffer.rewind();
        return of(bytes,
                image.getWidth(),
                image.getHeight(),
                plane.getRowStride(),
                plane.getPixelStride(),
                image.getImageInfo().getRotationDegrees(),
                image.getImageInfo().getTimestamp());
    }

    /** 用已有的像素数据构造，便于测试以及后续接入静态图片输入。 */
    @NonNull
    public static RgbaFrame of(@NonNull byte[] data, int width, int height,
                               int rowStride, int pixelStride, int rotationDegrees) {
        return of(data, width, height, rowStride, pixelStride, rotationDegrees, 0L);
    }

    /** 同上，另带曝光时刻（纳秒）。 */
    @NonNull
    public static RgbaFrame of(@NonNull byte[] data, int width, int height, int rowStride,
                               int pixelStride, int rotationDegrees, long timestampNanos) {
        return new RgbaFrame(data, width, height, rowStride, pixelStride,
                rotationDegrees, timestampNanos);
    }

    /** 画面正立后的宽度。 */
    public int rotatedWidth() {
        return isQuarterTurn() ? height : width;
    }

    /** 画面正立后的高度。 */
    public int rotatedHeight() {
        return isQuarterTurn() ? width : height;
    }

    private boolean isQuarterTurn() {
        return rotationDegrees == 90 || rotationDegrees == 270;
    }
}
