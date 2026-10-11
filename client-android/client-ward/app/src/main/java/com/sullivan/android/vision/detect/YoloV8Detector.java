package com.sullivan.android.vision.detect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * 端侧 YOLOv8 / YOLO11 目标检测器，基于 ONNX Runtime。
 *
 * <p>一次 {@link #detect} 调用完成：letterbox 预处理 → 模型推理 → 输出解码 → NMS，
 * 返回的坐标已经归一化到正立画面，可直接交给界面层。
 *
 * <p>支持 ultralytics 默认导出的输出布局 {@code [1, 4+C, N]}，也兼容转置后的
 * {@code [1, N, 4+C]}；输入边长优先取模型自身声明的值，动态维度时才退回 640。
 *
 * <p>本类不是线程安全的：{@link #detect} 内部复用了输入缓冲区。调用方需要保证
 * 同一时刻只有一个线程在用它。
 */
public final class YoloV8Detector implements Closeable {

    /** 模型输入为动态维度时使用的默认边长。 */
    public static final int DEFAULT_INPUT_SIZE = 640;
    /** letterbox 灰边取值，与 ultralytics 一致。 */
    private static final float PAD_VALUE = 114f / 255f;
    private static final float INV_255 = 1f / 255f;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;

    private final String[] labels;
    private final int inputSize;
    private final float confidenceThreshold;
    private final float iouThreshold;

    /** 复用的模型输入缓冲区（直接内存，避免每帧拷贝到堆内）。 */
    private final FloatBuffer inputBuffer;
    /** 复用的灰边填充块，长度等于 inputSize²。 */
    private final float[] padding;

    public YoloV8Detector(@NonNull byte[] modelBytes,
                          @Nullable String[] classNames,
                          float confidenceThreshold,
                          float iouThreshold) throws OrtException {
        this.confidenceThreshold = confidenceThreshold;
        this.iouThreshold = iouThreshold;
        this.environment = OrtEnvironment.getEnvironment();

        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        this.session = environment.createSession(modelBytes, options);

        this.inputName = session.getInputNames().iterator().next();
        this.inputSize = resolveInputSize(session, inputName);

        int numClasses = resolveNumClasses(session);
        this.labels = (classNames != null && classNames.length == numClasses)
                ? classNames.clone()
                : CocoLabels.defaultLabels(numClasses);

        int elements = 3 * inputSize * inputSize;
        this.inputBuffer = ByteBuffer.allocateDirect(elements * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        this.padding = new float[inputSize * inputSize];
        Arrays.fill(this.padding, PAD_VALUE);
    }

    /** 模型实际使用的输入边长。 */
    public int getInputSize() {
        return inputSize;
    }

    /** 类别名，下标即类别 id。 */
    @NonNull
    public String[] getLabels() {
        return labels.clone();
    }

    /**
     * 检测一帧。
     *
     * @return 经过置信度过滤和 NMS 的检测框，坐标相对正立画面归一化
     */
    @NonNull
    public List<Detection> detect(@NonNull RgbaFrame frame) throws OrtException {
        Letterbox letterbox = Letterbox.create(frame, inputSize);
        fillInput(frame, letterbox);

        try (OnnxTensor input = OnnxTensor.createTensor(environment, inputBuffer,
                new long[]{1, 3, inputSize, inputSize});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            List<Detection> candidates = decode(output.getFloatBuffer(), shape, letterbox);
            return nonMaxSuppression(candidates);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // 关闭失败没有补救手段，忽略即可。
        }
    }

    // ------------------------------------------------------------------
    // 预处理
    // ------------------------------------------------------------------

    /**
     * 把 RGBA 帧按 letterbox 参数写入 NCHW 浮点张量，采样时顺带完成旋转和双线性插值。
     */
    private void fillInput(@NonNull RgbaFrame frame, @NonNull Letterbox letterbox) {
        final int size = inputSize;
        final int area = size * size;
        final int rotation = letterbox.rotationDegrees;
        final float padX = letterbox.padX;
        final float padY = letterbox.padY;
        final float contentWidth = letterbox.contentWidth;
        final float contentHeight = letterbox.contentHeight;

        inputBuffer.clear();
        inputBuffer.put(padding);
        inputBuffer.put(padding);
        inputBuffer.put(padding);

        for (int dy = 0; dy < size; dy++) {
            // 目标像素在正立画面中的归一化纵坐标；落在灰边上则保持填充值。
            float v = (dy + 0.5f - padY) / contentHeight;
            if (v < 0f || v > 1f) {
                continue;
            }
            int rowBase = dy * size;
            for (int dx = 0; dx < size; dx++) {
                float u = (dx + 0.5f - padX) / contentWidth;
                if (u < 0f || u > 1f) {
                    continue;
                }

                // 反查旋转：从"正立画面"坐标回到相机缓冲区坐标。
                float srcU;
                float srcV;
                switch (rotation) {
                    case 90:
                        srcU = v;
                        srcV = 1f - u;
                        break;
                    case 180:
                        srcU = 1f - u;
                        srcV = 1f - v;
                        break;
                    case 270:
                        srcU = 1f - v;
                        srcV = u;
                        break;
                    default:
                        srcU = u;
                        srcV = v;
                        break;
                }

                int rgb = sampleBilinear(frame, srcU, srcV);
                int index = rowBase + dx;
                inputBuffer.put(index, ((rgb >> 16) & 0xFF) * INV_255);
                inputBuffer.put(area + index, ((rgb >> 8) & 0xFF) * INV_255);
                inputBuffer.put(2 * area + index, (rgb & 0xFF) * INV_255);
            }
        }
        inputBuffer.position(0);
    }

    /** 从 RGBA 缓冲区双线性采样，返回打包的 0xRRGGBB。 */
    private static int sampleBilinear(@NonNull RgbaFrame frame, float u, float v) {
        float fx = u * frame.width - 0.5f;
        float fy = v * frame.height - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;

        int x1 = clamp(x0 + 1, 0, frame.width - 1);
        int y1 = clamp(y0 + 1, 0, frame.height - 1);
        x0 = clamp(x0, 0, frame.width - 1);
        y0 = clamp(y0, 0, frame.height - 1);

        byte[] data = frame.data;
        int rowStride = frame.rowStride;
        int pixelStride = frame.pixelStride;
        int i00 = y0 * rowStride + x0 * pixelStride;
        int i10 = y0 * rowStride + x1 * pixelStride;
        int i01 = y1 * rowStride + x0 * pixelStride;
        int i11 = y1 * rowStride + x1 * pixelStride;

        float r = lerp(data[i00], data[i10], data[i01], data[i11], tx, ty);
        float g = lerp(data[i00 + 1], data[i10 + 1], data[i01 + 1], data[i11 + 1], tx, ty);
        float b = lerp(data[i00 + 2], data[i10 + 2], data[i01 + 2], data[i11 + 2], tx, ty);
        return (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
    }

    private static float lerp(byte c00, byte c10, byte c01, byte c11, float tx, float ty) {
        float top = (c00 & 0xFF) * (1f - tx) + (c10 & 0xFF) * tx;
        float bottom = (c01 & 0xFF) * (1f - tx) + (c11 & 0xFF) * tx;
        return top * (1f - ty) + bottom * ty;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    // ------------------------------------------------------------------
    // 后处理
    // ------------------------------------------------------------------

    /**
     * 解码模型输出为检测框。
     *
     * <p>每个候选框的 4 个坐标是中心点 + 宽高，之后是各类别得分（YOLOv8 起输出的是
     * 已归一化的分数，不需要再做 sigmoid）。
     */
    @NonNull
    private List<Detection> decode(@NonNull FloatBuffer buffer, @NonNull long[] shape,
                                   @NonNull Letterbox letterbox) {
        if (shape.length != 3) {
            throw new IllegalStateException("不支持的模型输出维度: " + Arrays.toString(shape));
        }
        int dim1 = (int) shape[1];
        int dim2 = (int) shape[2];
        // [1, 4+C, N] 是 ultralytics 的默认布局；[1, N, 4+C] 是转置后的布局。
        boolean channelsFirst = dim1 < dim2;
        int attributes = channelsFirst ? dim1 : dim2;
        int anchors = channelsFirst ? dim2 : dim1;
        int numClasses = attributes - 4;
        if (numClasses <= 0) {
            throw new IllegalStateException("模型输出属性数异常: " + attributes);
        }

        List<Detection> candidates = new ArrayList<>();
        for (int anchor = 0; anchor < anchors; anchor++) {
            float centerX;
            float centerY;
            float width;
            float height;
            if (channelsFirst) {
                centerX = buffer.get(anchor);
                centerY = buffer.get(anchors + anchor);
                width = buffer.get(2 * anchors + anchor);
                height = buffer.get(3 * anchors + anchor);
            } else {
                int base = anchor * attributes;
                centerX = buffer.get(base);
                centerY = buffer.get(base + 1);
                width = buffer.get(base + 2);
                height = buffer.get(base + 3);
            }

            int bestClass = -1;
            float bestScore = confidenceThreshold;
            for (int c = 0; c < numClasses; c++) {
                float score = channelsFirst
                        ? buffer.get((4 + c) * anchors + anchor)
                        : buffer.get(anchor * attributes + 4 + c);
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0) {
                continue;
            }

            // 模型坐标 → 归一化的正立画面坐标。
            float left = (centerX - width * 0.5f - letterbox.padX) / letterbox.contentWidth;
            float top = (centerY - height * 0.5f - letterbox.padY) / letterbox.contentHeight;
            float right = (centerX + width * 0.5f - letterbox.padX) / letterbox.contentWidth;
            float bottom = (centerY + height * 0.5f - letterbox.padY) / letterbox.contentHeight;

            if (right <= 0f || bottom <= 0f || left >= 1f || top >= 1f) {
                continue;
            }
            candidates.add(new Detection(
                    bestClass,
                    labelOf(bestClass),
                    bestScore,
                    clamp01(left),
                    clamp01(top),
                    clamp01(right),
                    clamp01(bottom)));
        }
        return candidates;
    }

    /** 按类别做贪心 NMS。 */
    @NonNull
    private List<Detection> nonMaxSuppression(@NonNull List<Detection> candidates) {
        candidates.sort((a, b) -> Float.compare(b.score, a.score));
        boolean[] suppressed = new boolean[candidates.size()];
        List<Detection> kept = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) {
                continue;
            }
            Detection best = candidates.get(i);
            kept.add(best);
            for (int j = i + 1; j < candidates.size(); j++) {
                if (suppressed[j]) {
                    continue;
                }
                Detection other = candidates.get(j);
                if (other.classId == best.classId && intersectionOverUnion(best, other) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return kept;
    }

    private static float intersectionOverUnion(@NonNull Detection a, @NonNull Detection b) {
        return a.iou(b);
    }

    @NonNull
    private String labelOf(int classId) {
        return classId >= 0 && classId < labels.length ? labels[classId] : "class " + classId;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    // ------------------------------------------------------------------
    // 模型元信息
    // ------------------------------------------------------------------

    /** 读取模型声明的输入边长；动态维度时退回 {@link #DEFAULT_INPUT_SIZE}。 */
    private static int resolveInputSize(@NonNull OrtSession session, @NonNull String inputName)
            throws OrtException {
        NodeInfo info = session.getInputInfo().get(inputName);
        if (info != null && info.getInfo() instanceof TensorInfo) {
            long[] shape = ((TensorInfo) info.getInfo()).getShape();
            if (shape.length == 4 && shape[2] > 0 && shape[2] == shape[3]) {
                return (int) shape[2];
            }
        }
        return DEFAULT_INPUT_SIZE;
    }

    /** 从输出张量形状推断类别数，用于挑选类别名。 */
    private static int resolveNumClasses(@NonNull OrtSession session) throws OrtException {
        for (NodeInfo info : session.getOutputInfo().values()) {
            if (!(info.getInfo() instanceof TensorInfo)) {
                continue;
            }
            long[] shape = ((TensorInfo) info.getInfo()).getShape();
            if (shape.length != 3) {
                continue;
            }
            long dim1 = shape[1];
            long dim2 = shape[2];
            if (dim1 <= 0 || dim2 <= 0) {
                break;
            }
            int attributes = (int) Math.min(dim1, dim2);
            if (attributes > 4) {
                return attributes - 4;
            }
        }
        return CocoLabels.COCO_80.length;
    }
}
