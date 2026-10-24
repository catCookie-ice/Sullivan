package com.sullivan.protectee.vision.depth;

import androidx.annotation.NonNull;

import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.detect.RgbaFrame;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * 单目深度估计（{@code yolo26n-depth}），基于 ONNX Runtime。
 *
 * <p><b>为什么这是一个独立的类而不是给检测器加个开关。</b>YOLO26 的深度是**另一个模型**
 * （{@code task=depth}，权重名带 {@code -depth} 后缀），不是检测模型的附带输出。
 *
 * <p><b>⚠️ 输入尺寸和检测模型不一样，这是实测出来的。</b>官方 {@code yolo26n-depth.onnx}
 * 的输入是 {@code [1,3,768,768]}，输出是 {@code [1,1,768,768]} 的逐像素**米数**；
 * 而 {@code yolo26n.onnx} 检测是 640。第一版设计假设两者都是 640、可以"共用同一份
 * 预处理张量"，那个假设是**错的** —— 拿 640 的张量喂 768 的模型，ONNX Runtime 直接
 * 报 {@code Got invalid dimensions for input}。
 *
 * <p><b>所以两个模型各做各的 letterbox。</b>代价是多一次缩放（相对 84 ms 的推理可忽略），
 * 换来的是两个模型都拿到符合自己期望的输入。好处是**两者仍然看同一帧画面**：
 * 传入的是同一个 {@link RgbaFrame}，只是缩放目标不同。
 *
 * <p>由此产生一个必须小心的后果：检测框是在 640 的 letterbox 几何下归一化到内容区的，
 * 而深度图是 768 的 letterbox 几何。把框换算到深度图像素时**必须用深度自己的
 * {@link #letterboxOf}**，不能用检测器那一份 —— 用错会让采样点偏移几十像素。
 * 见 {@link #predict} 的返回值与 {@code DistancePolicy} 的调用方式。
 *
 * <p><b>本类不是线程安全的</b>：复用输入缓冲区，且 ONNX Runtime 的 session 本身
 * 也不是并发安全的。调用方需保证同一时刻只有一个线程在用它 —— 与
 * {@code Yolo26Detector} 的约定一致。
 */
public final class DepthEstimator implements Closeable {

    /** 输入为动态维度时的默认边长。768 来自官方 yolo26n-depth 的实际输入。 */
    public static final int DEFAULT_INPUT_SIZE = 768;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;

    private final int inputSize;
    private final FloatBuffer inputBuffer;

    public DepthEstimator(@NonNull byte[] modelBytes) throws OrtException {
        this(modelBytes, DEFAULT_INPUT_SIZE);
    }

    public DepthEstimator(@NonNull byte[] modelBytes, int fallbackInputSize) throws OrtException {
        this.environment = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // 深度模型比检测模型重（768 输入、约 85 ms vs 43 ms）。线程数沿用检测器的策略，
        // 避免把大核全占满导致相机回调被饿死（相机丢帧比推理慢一点更影响可用性）。
        options.setIntraOpNumThreads(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        this.session = environment.createSession(modelBytes, options);

        this.inputName = session.getInputNames().iterator().next();
        this.inputSize = resolveInputSize(session, inputName, fallbackInputSize);

        int elements = 3 * inputSize * inputSize;
        this.inputBuffer = ByteBuffer.allocateDirect(elements * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    /** 模型实际使用的输入边长（官方权重是 768）。 */
    public int getInputSize() {
        return inputSize;
    }

    /**
     * 对一帧画面做深度估计。
     *
     * @param frame 与检测**同一帧**的 RGBA 画面
     * @return 逐像素米数图；其 {@link DepthMap#inputSize} 是本模型的输入边长，
     *         换算检测框时必须配 {@link #letterboxOf} 的结果
     */
    @NonNull
    public DepthMap predict(@NonNull RgbaFrame frame) throws OrtException {
        Letterbox letterbox = letterboxOf(frame);
        fillInput(frame, letterbox);

        try (OnnxTensor input = OnnxTensor.createTensor(environment, inputBuffer,
                new long[]{1, 3, inputSize, inputSize});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            return DepthMap.fromTensor(output.getFloatBuffer(), output.getInfo().getShape());
        }
    }

    /**
     * 本模型对这一帧使用的 letterbox 几何。
     *
     * <p>调用方拿它和检测框一起换算深度像素。**不要跨模型复用**：这是 768 的几何，
     * 检测是 640 的，混用会让采样点偏。
     */
    @NonNull
    public Letterbox letterboxOf(@NonNull RgbaFrame frame) {
        return Letterbox.create(frame, inputSize);
    }

    /**
     * 把 RGBA 帧按 letterbox 参数写入 NCHW 浮点张量。
     *
     * <p>与 {@code Yolo26Detector.fillInput} 是同一套算法、同一组填充值（114 灰）。
     * 重复实现是刻意的：两者的目标尺寸不同、缓冲归属不同，硬抽一个公共函数会把
     * "谁负责哪份缓冲"这件事搅浑，而这个类最怕的就是缓冲被并发写。
     */
    private void fillInput(@NonNull RgbaFrame frame, @NonNull Letterbox letterbox) {
        final int size = inputSize;
        final int area = size * size;
        final int rotation = letterbox.rotationDegrees;
        final float padX = letterbox.padX;
        final float padY = letterbox.padY;
        final float contentWidth = letterbox.contentWidth;
        final float contentHeight = letterbox.contentHeight;

        final byte[] data = frame.data;
        final int rowStride = frame.rowStride;
        final int pixelStride = frame.pixelStride;
        final int srcWidth = frame.width;
        final int srcHeight = frame.height;

        // 逆着放：屏幕坐标 (x,y) → 原始缓冲区坐标。旋转 90/270 时宽高互换，
        // 预先算好旋转后的宽高，避免每次采样都判分支。
        final int rotWidth = (rotation == 90 || rotation == 270) ? srcHeight : srcWidth;
        final int rotHeight = (rotation == 90 || rotation == 270) ? srcWidth : srcHeight;

        float[] chw = new float[3 * area];
        // 背景填充 114（YOLO 的惯例灰），与检测保持一致。
        for (int i = 0; i < area; i++) {
            chw[i] = 114f / 255f;
            chw[area + i] = 114f / 255f;
            chw[2 * area + i] = 114f / 255f;
        }

        for (int y = 0; y < size; y++) {
            // 该输出行在内容区里的连续坐标。
            float cy = (y + 0.5f - padY);
            if (cy < 0f || cy >= contentHeight) {
                continue;
            }
            // 内容像素 → 旋转后画面像素。
            float ry = cy * (rotHeight / contentHeight);
            for (int x = 0; x < size; x++) {
                float cx = (x + 0.5f - padX);
                if (cx < 0f || cx >= contentWidth) {
                    continue;
                }
                float rx = cx * (rotWidth / contentWidth);

                int oy = (int) ry;
                int ox = (int) rx;
                float ty = ry - oy;
                float tx = rx - ox;

                int x0 = clamp(ox, 0, rotWidth - 1);
                int x1 = clamp(ox + 1, 0, rotWidth - 1);
                int y0 = clamp(oy, 0, rotHeight - 1);
                int y1 = clamp(oy + 1, 0, rotHeight - 1);

                int p00 = sample(data, rowStride, pixelStride, rotWidth, rotHeight,
                        rotation, srcWidth, srcHeight, x0, y0);
                int p10 = sample(data, rowStride, pixelStride, rotWidth, rotHeight,
                        rotation, srcWidth, srcHeight, x1, y0);
                int p01 = sample(data, rowStride, pixelStride, rotWidth, rotHeight,
                        rotation, srcWidth, srcHeight, x0, y1);
                int p11 = sample(data, rowStride, pixelStride, rotWidth, rotHeight,
                        rotation, srcWidth, srcHeight, x1, y1);

                float r = lerpByte(p00, 0, p10, p01, p11, tx, ty);
                float g = lerpByte(p00, 1, p10, p01, p11, tx, ty);
                float b = lerpByte(p00, 2, p10, p01, p11, tx, ty);

                int index = y * size + x;
                chw[index] = r / 255f;
                chw[area + index] = g / 255f;
                chw[2 * area + index] = b / 255f;
            }
        }

        inputBuffer.clear();
        inputBuffer.put(chw, 0, chw.length);
        inputBuffer.position(0);
    }

    /** 取旋转后坐标 (x,y) 对应的 0xRRGGBB（旋转在采样时完成）。 */
    private static int sample(byte[] data, int rowStride, int pixelStride,
                              int rotWidth, int rotHeight, int rotation,
                              int srcWidth, int srcHeight, int x, int y) {
        int sx;
        int sy;
        switch (rotation) {
            case 90 -> {
                sx = y;
                sy = srcHeight - 1 - x;
            }
            case 180 -> {
                sx = srcWidth - 1 - x;
                sy = srcHeight - 1 - y;
            }
            case 270 -> {
                sx = srcWidth - 1 - y;
                sy = x;
            }
            default -> {
                sx = x;
                sy = y;
            }
        }
        int offset = sy * rowStride + sx * pixelStride;
        int r = data[offset] & 0xFF;
        int g = data[offset + 1] & 0xFF;
        int b = data[offset + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    /** 双线性插值，取出一个通道。 */
    private static float lerpByte(int p00, int channel, int p10, int p01, int p11,
                                  float tx, float ty) {
        int shift = 16 - channel * 8;
        float v00 = (p00 >> shift) & 0xFF;
        float v10 = (p10 >> shift) & 0xFF;
        float v01 = (p01 >> shift) & 0xFF;
        float v11 = (p11 >> shift) & 0xFF;
        float top = v00 * (1f - tx) + v10 * tx;
        float bottom = v01 * (1f - tx) + v11 * tx;
        return top * (1f - ty) + bottom * ty;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // 关闭失败没有补救手段，忽略即可。
        }
    }

    /** 输入边长：优先取模型声明，动态维度时才用调用方给的默认值。 */
    private static int resolveInputSize(@NonNull OrtSession session, @NonNull String inputName,
                                        int fallback) {
        try {
            if (session.getInputInfo().get(inputName).getInfo() instanceof TensorInfo info) {
                long[] shape = info.getShape();
                if (shape.length == 4 && shape[2] > 0 && shape[3] > 0) {
                    if (shape[2] != shape[3]) {
                        throw new IllegalStateException(
                                "深度模型要求方形输入，实际 " + shape[2] + "x" + shape[3]);
                    }
                    return (int) shape[2];
                }
            }
        } catch (OrtException ignored) {
            // 拿不到就退回默认值；真到推理时形状不符会报错，比在这里猜更容易定位。
        }
        return fallback;
    }

    /** 日志里的模型概况。 */
    @NonNull
    public String describe() {
        return "DepthEstimator{input=" + inputSize + ", outputs="
                + Arrays.toString(session.getOutputNames().toArray()) + "}";
    }
}
