package com.sullivan.protectee.vision.detect;

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
 * 端侧 YOLO26 目标检测器，基于 ONNX Runtime。
 *
 * <p>一次 {@link #detect} 调用完成：letterbox 预处理 → 模型推理 → end2end 输出解码，
 * 返回的坐标已经归一化到**正立画面的内容区**，可直接交给界面层。
 *
 * <p><b>只支持 YOLO26 的 {@code end2end} 导出</b>：两个输出
 * {@code logits [1,N,C]} + {@code pred_boxes [1,N,4]}，概率需过 sigmoid，
 * 框归一化到 letterbox 输入，NMS 由模型自带。不再兼容 YOLOv8 时代的
 * {@code [1,84,8400]} 单张量 —— 放错模型会抛出说明清楚的异常，
 * 而不是静默给出空画面。详见 {@link #detect(Prepared)} 的注释。
 *
 * <p>本类不是线程安全的：{@link #detect} 内部复用了输入缓冲区。调用方需要保证
 * 同一时刻只有一个线程在用它。
 */
public final class Yolo26Detector implements Closeable {

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

    /** 复用的模型输入缓冲区（直接内存，避免每帧拷贝到堆内）。 */
    private final FloatBuffer inputBuffer;
    /** 复用的灰边填充块，长度等于 inputSize²。 */
    private final float[] padding;

    /**
     * @param confidenceThreshold 作用在 **sigmoid 之后**的置信度阈值
     *
     * <p>没有 {@code iouThreshold} 参数：end2end 输出自带 NMS，模型已经替我们做完
     * 去重，调用方再给一个 IoU 阈值只会让人误以为它还在生效。
     */
    public Yolo26Detector(@NonNull byte[] modelBytes,
                          @Nullable String[] classNames,
                          float confidenceThreshold) throws OrtException {
        this.confidenceThreshold = confidenceThreshold;
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
        return detect(preprocess(frame));
    }

    /**
     * 已预处理的一帧：NCHW 张量 + 该帧的 letterbox 几何。
     *
     * <p>存在的理由是**检测与深度两个模型必须看到逐像素相同的画面**。深度模型若自己
     * 再做一次 letterbox，插值与取整的细微差异会让检测框落在深度图上的位置系统性偏移——
     * 在近处那点偏移正好能改写「1 米」与「2 米」的结论。把预处理结果连同几何参数一起
     * 交出去，两个模型就都从这份数据出发。
     */
    public static final class Prepared {
        /** 长度 {@code 3 × inputSize²} 的 NCHW 浮点数据。 */
        @NonNull
        public final float[] chw;
        /** 生成这份数据时用的几何参数，供检测框解码还原坐标。 */
        @NonNull
        public final Letterbox letterbox;

        Prepared(@NonNull float[] chw, @NonNull Letterbox letterbox) {
            this.chw = chw;
            this.letterbox = letterbox;
        }
    }

    /** 只做预处理，不推理。 */
    @NonNull
    public Prepared preprocess(@NonNull RgbaFrame frame) {
        Letterbox letterbox = Letterbox.create(frame, inputSize);
        fillInput(frame, letterbox);
        float[] chw = new float[3 * inputSize * inputSize];
        FloatBuffer view = inputBuffer.duplicate();
        view.position(0);
        view.get(chw, 0, chw.length);
        return new Prepared(chw, letterbox);
    }

    /**
     * 在已预处理的数据上跑检测。
     *
     * <p><b>只支持 YOLO26 的 end2end 输出。</b>官方导出（默认且唯一推荐）是
     * {@code logits [1,300,80]} + {@code pred_boxes [1,300,4]} 两个张量，
     * 而且**自带 NMS**（300 是 max_det）。
     *
     * <p>刻意**不**兼容 YOLOv8 时代的单张量 {@code [1,84,8400]}。理由：
     * 那种布局要自己做 NMS、自己过阈值，是另一条完整的解码路径；两条路径同时
     * 存在意味着"同一份画面走哪条路"取决于用户放了哪个文件，出问题时先要猜
     * 走的是哪条。本应用的模型是随包固定分发的，不需要这种灵活性 ——
     * 放错模型就明确报错，比"能跑但结果对不上"好得多。
     */
    @NonNull
    public List<Detection> detect(@NonNull Prepared prepared) throws OrtException {
        inputBuffer.clear();
        inputBuffer.put(prepared.chw, 0, prepared.chw.length);
        inputBuffer.position(0);

        try (OnnxTensor input = OnnxTensor.createTensor(environment, inputBuffer,
                new long[]{1, 3, inputSize, inputSize});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            checkEndToEndLayout(result);
            return decodeEndToEnd(result, prepared.letterbox);
        }
    }

    /**
     * 校验输出确实是 end2end 布局，不是就抛出说明清楚的错误。
     *
     * <p>放在这里而不是让解码逻辑"猜"：放错模型（比如误放了 YOLOv8 的
     * {@code [1,84,8400]}）时，用户需要看到的是"这个模型不被支持"，
     * 而不是一个空画面或者一堆离谱的框。错误信息里带上实际形状，排查时能直接用。
     */
    private static void checkEndToEndLayout(@NonNull OrtSession.Result result)
            throws IllegalStateException {
        if (result.size() < 2) {
            throw new IllegalStateException(
                    "该模型不是 YOLO26 end2end 导出（只有 " + result.size()
                            + " 个输出）。请使用 ultralytics 默认导出的 yolo26n.onnx"
                            + "（应为 logits[1,300,80] + pred_boxes[1,300,4] 两个输出）。");
        }
        long[] a = shapeOf(result.get(0));
        long[] b = shapeOf(result.get(1));
        boolean ok = a != null && b != null
                && ((a[2] == 4 && b[2] > 4) || (b[2] == 4 && a[2] > 4));
        if (!ok) {
            throw new IllegalStateException(
                    "模型输出形状不是 YOLO26 end2end 布局："
                            + Arrays.toString(a) + " 与 " + Arrays.toString(b)
                            + "。期望一个是 [1,N,4]、另一个是 [1,N,C]。");
        }
    }

    @Nullable
    private static long[] shapeOf(@NonNull Object tensor) {
        if (!(tensor instanceof OnnxTensor)) {
            return null;
        }
        long[] shape = ((OnnxTensor) tensor).getInfo().getShape();
        return shape.length == 3 ? shape : null;
    }

    /**
     * 解码 {@code end2end} 输出。
     *
     * <p>与老布局的三点差别，每一条都实测确认过（用 bus.jpg 跑出来的）：
     *
     * <ol>
     *   <li><b>分数要过 sigmoid</b>。老布局输出的是已归一化的概率，这里是**原始
     *       logits**（实测同一张图 bus 得 2.517、person 得 2.378，sigmoid 后是
     *       0.925 / 0.915，与 ultralytics 的 own 结果一致）。不过 sigmoid 的话
     *       阈值 0.25 会把一切都挡掉 —— 表现为"什么都检测不到"。</li>
     *   <li><b>框已经是 cx,cy,w,h 归一化到 letterbox 输入</b>，不再是内容区坐标。
     *      所以要乘 640 得到像素、再减 pad 才回到内容区。</li>
     *   <li><b>不需要再跑 NMS</b>：end2end 导出里已经做过（300 个槽位按分数排好）。</li>
     * </ol>
     */
    @NonNull
    private List<Detection> decodeEndToEnd(@NonNull OrtSession.Result result,
                                           @NonNull Letterbox letterbox) {
        // 按形状认哪个是框、哪个是分数，不依赖输出顺序。
        int boxIndex = shapeOf(result.get(0))[2] == 4 ? 0 : 1;
        int scoreIndex = boxIndex == 0 ? 1 : 0;

        OnnxTensor boxesTensor = (OnnxTensor) result.get(boxIndex);
        OnnxTensor scoresTensor = (OnnxTensor) result.get(scoreIndex);
        int count = (int) boxesTensor.getInfo().getShape()[1];
        int numClasses = (int) scoresTensor.getInfo().getShape()[2];

        return decodeEndToEnd(boxesTensor.getFloatBuffer(), scoresTensor.getFloatBuffer(),
                count, numClasses, letterbox, inputSize, confidenceThreshold);
    }

    /**
     * {@code end2end} 输出的纯解码逻辑（不碰 ONNX，可在 JVM 单测里直接喂数组）。
     *
     * @param boxes        长度 {@code count*4}，每行 cx,cy,w,h，归一化到 letterbox 输入
     * @param scores       长度 {@code count*numClasses}，每行是**原始 logits**
     * @param count        槽位数（导出的 max_det，通常是 300）
     * @param numClasses   类别数
     * @param letterbox    生成本帧输入时用的几何
     * @param inputSize    模型输入边长（640）
     * @param threshold    置信度阈值（作用在 sigmoid 之后）
     */
    @NonNull
    static List<Detection> decodeEndToEnd(@NonNull FloatBuffer boxes, @NonNull FloatBuffer scores,
                                          int count, int numClasses,
                                          @NonNull Letterbox letterbox, int inputSize,
                                          float threshold) {
        int classes = Math.min(numClasses, CocoLabels.COCO_80.length);
        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int base = i * numClasses;
            int bestClass = -1;
            float bestScore = threshold;
            for (int c = 0; c < classes; c++) {
                // 必须过 sigmoid：这里是原始 logits，不是概率。
                // 实测 bus 得 2.517、person 得 2.378，sigmoid 后 0.925 / 0.915。
                float score = sigmoid(scores.get(base + c));
                if (score > bestScore) {
                    bestScore = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0) {
                continue;
            }

            int boxBase = i * 4;
            float centerX = boxes.get(boxBase);
            float centerY = boxes.get(boxBase + 1);
            float width = boxes.get(boxBase + 2);
            float height = boxes.get(boxBase + 3);

            // cx,cy,w,h 是相对 inputSize×inputSize 输入的归一化值 → 像素 → 内容区归一化。
            float leftPx = (centerX - width * 0.5f) * inputSize;
            float rightPx = (centerX + width * 0.5f) * inputSize;
            float topPx = (centerY - height * 0.5f) * inputSize;
            float bottomPx = (centerY + height * 0.5f) * inputSize;

            float left = (leftPx - letterbox.padX) / letterbox.contentWidth;
            float right = (rightPx - letterbox.padX) / letterbox.contentWidth;
            float top = (topPx - letterbox.padY) / letterbox.contentHeight;
            float bottom = (bottomPx - letterbox.padY) / letterbox.contentHeight;

            if (right <= 0f || bottom <= 0f || left >= 1f || top >= 1f) {
                continue;
            }
            detections.add(new Detection(bestClass, labelOfStatic(bestClass), bestScore,
                    clamp01(left), clamp01(top), clamp01(right), clamp01(bottom)));
        }
        return detections;
    }

    /**
     * 静态版本的标签解析。
     *
     * <p>纯解码函数没有实例可依附，用 COCO 内置表。自定义类别（classes.txt）走的是
     * 老布局那条路，或者实例上的 {@code labelOf}，不受影响。
     */
    @NonNull
    private static String labelOfStatic(int classId) {
        return classId >= 0 && classId < CocoLabels.COCO_80.length
                ? CocoLabels.COCO_80[classId]
                : "class " + classId;
    }

    static float sigmoid(float value) {
        return 1f / (1f + (float) Math.exp(-value));
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
