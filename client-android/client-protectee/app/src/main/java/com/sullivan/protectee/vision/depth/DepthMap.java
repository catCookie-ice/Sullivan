package com.sullivan.protectee.vision.depth;

import androidx.annotation.NonNull;

import com.sullivan.protectee.vision.detect.Letterbox;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * 一张单目深度图，以及从图上取"某个像素有多远"的读法。
 *
 * <p>模型是 {@code yolo26n-depth}（单目深度估计，独立于检测模型），输出
 * {@code [1, 1, H, W]} 的逐像素**米数**浮点图，已对齐到模型输入（letterbox 后的
 * 640×640）。本类只负责几何换算与采样，不碰推理。
 *
 * <p><b>为什么取"下沿往上抬一点"而不是框中心。</b>框中心落在障碍物本身上（行人时是
 * 胸口），而深度图给的是**表面**距离；行人的胸口与脚在单目深度里差的往往不是真实
 * 距离，而是"脚踩在地面上更远"的透视结果。真正决定"还有多远撞上"的是障碍物与地面
 * 的接触点，也就是框的下沿。但下沿恰好压在轮廓线上，一个像素的偏差就会滑到背景
 * （地面延伸到远处），所以实际取值位置是**下沿往上 {@link #FOOT_LIFT_RATIO} 个框高**，
 * 并在一个小邻域里取中位数。
 *
 * <p><b>为什么取中位数而不是最小值。</b>最小值会被单个噪点或细长突出物（比如伸出来的
 * 树枝）拉近，报出一个"1 米内有障碍"的假紧急；中位数对这类孤立点稳健。反过来，
 * 均值会被远处背景拉远，把近处障碍说成"还远"，那是危险方向，所以也不用均值。
 */
public final class DepthMap {

    /**
     * 采样点相对框下沿上抬的比例（按框高算）。
     *
     * <p>取 0.15：行人时落在小腿/脚踝附近，仍属于"与地面的接触段"；同时离轮廓线足够远，
     * 不会因为一个像素的抖动滑到地面背景上。这个值是启发式的，需要用真机标定。
     */
    public static final float FOOT_LIFT_RATIO = 0.15f;

    /** 采样窗口的半边长（深度图像素）。3 表示 7×7 窗口，37 个样本取中位数。 */
    public static final int SAMPLE_RADIUS = 3;

    /** 低于此值（米）视为无效：深度模型在天空、强光、纯色墙面上会输出接近 0 的值。 */
    public static final float MIN_VALID_METERS = 0.05f;
    /** 高于此值（米）视为无效：单目深度在远距离上基本不可信，报一个 80 米没有意义。 */
    public static final float MAX_VALID_METERS = 80f;

    /** 宽度（深度模型输入坐标，官方权重是 768）。 */
    public final int width;
    /** 高度（深度模型输入坐标，官方权重是 768）。 */
    public final int height;

    @NonNull
    private final float[] meters;

    /**
     * @param width  深度图宽（模型输入边长）
     * @param height 深度图高（模型输入边长）
     * @param meters 逐像素米数，行优先
     *
     * <p>**刻意不保存"模型输入边长"这个字段。** 早先版本存过，用于"把归一化坐标乘成
     * 像素"；但那种换算是错的——检测框的归一化坐标是相对**内容区**的，必须先加回
     * 灰边偏移，而灰边信息不在本类里。唯一正确的入口是
     * {@link #distanceAtFootprint(Letterbox, float, float, float, float)}，
     * 它显式要求调用方传入**深度模型自己的** {@link Letterbox}。
     * 留着一个语义含糊的 {@code inputSize} 只会诱使人绕过那条路径。
     */
    public DepthMap(@NonNull int width, @NonNull int height, @NonNull float[] meters) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("深度图尺寸非法: " + width + "x" + height);
        }
        if (meters.length < width * height) {
            throw new IllegalArgumentException(
                    "深度图数据不足: 需要 " + (width * height) + "，实际 " + meters.length);
        }
        this.width = width;
        this.height = height;
        this.meters = meters;
    }

    /**
     * 从 ONNX 张量缓冲区构造。
     *
     * <p>输出形状是 {@code [1, 1, H, W]}；也接受 {@code [1, H, W]}（某些导出会省掉通道维）。
     *
     * @param shape 模型输出的形状
     */
    @NonNull
    public static DepthMap fromTensor(@NonNull FloatBuffer buffer, @NonNull long[] shape) {
        int h;
        int w;
        if (shape.length == 4) {
            h = (int) shape[2];
            w = (int) shape[3];
        } else if (shape.length == 3) {
            h = (int) shape[1];
            w = (int) shape[2];
        } else {
            throw new IllegalStateException("不支持的深度图输出维度: " + Arrays.toString(shape));
        }
        if (h <= 0 || w <= 0) {
            throw new IllegalStateException("深度图维度是动态的，未在导出时固定: "
                    + Arrays.toString(shape));
        }
        float[] data = new float[h * w];
        FloatBuffer view = buffer.duplicate();
        view.position(0);
        view.get(data, 0, Math.min(data.length, view.remaining()));
        return new DepthMap(w, h, data);
    }

    /** 原始米数（无有效性判断），供单测与调试用。 */
    public float rawAt(int x, int y) {
        return meters[y * width + x];
    }

    /**
     * 在归一化检测框的下沿附近取一个稳健的距离估计。
     *
     * @param letterbox 生成本帧检测框时用的几何参数。**必须传同一帧的那一个**：
     *                  {@code Detection} 的坐标是相对「正立画面内容区」归一化的
     *                  （{@code Yolo26Detector} 的解码已经减过 pad、除过 content），
     *                  而深度图是 640×640 的**整张 letterbox 输入**。少了这一步换算，
     *                  取到的像素会朝左上角偏移，偏移量在灰边越宽（画面越不方）时越大 ——
     *                  而那正好会让人以为"深度模型不准"，其实是坐标没对上。
     * @param left      归一化左边界（0..1，相对内容区）
     * @param top       归一化上边界
     * @param right     归一化右边界
     * @param bottom    归一化下边界
     * @return 米数；取不到有效值（落在灰边、全为无效值）时返回 {@link Float#NaN}
     */
    public float distanceAtFootprint(@NonNull Letterbox letterbox,
                                     float left, float top, float right, float bottom) {
        // 内容区归一化坐标 → 深度图像素：先乘内容尺寸，再加回灰边偏移。
        float boxHeightPx = Math.max(0f, bottom - top) * letterbox.contentHeight;
        float centerX = (left + right) * 0.5f * letterbox.contentWidth + letterbox.padX;
        // 采样点在下沿往上 FOOT_LIFT_RATIO 个框高处（理由见类注释）。
        float footY = bottom * letterbox.contentHeight + letterbox.padY
                - boxHeightPx * FOOT_LIFT_RATIO;

        int px = clamp(Math.round(centerX), 0, width - 1);
        int py = clamp(Math.round(footY), 0, height - 1);
        return medianInWindow(px, py);
    }

    /**
     * 在 (cx, cy) 周围 {@link #SAMPLE_RADIUS} 的窗口里取有效值的中位数。
     */
    float medianInWindow(int cx, int cy) {
        int radius = SAMPLE_RADIUS;
        float[] samples = new float[(2 * radius + 1) * (2 * radius + 1)];
        int count = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            int y = cy + dy;
            if (y < 0 || y >= height) {
                continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
                int x = cx + dx;
                if (x < 0 || x >= width) {
                    continue;
                }
                float value = meters[y * width + x];
                if (isValid(value)) {
                    samples[count++] = value;
                }
            }
        }
        if (count == 0) {
            return Float.NaN;
        }
        // 窗口最大 49 个样本，直接排序比选择算法更简单，成本可忽略。
        Arrays.sort(samples, 0, count);
        return samples[count / 2];
    }

    /** 米数是否可信。 */
    public static boolean isValid(float meters) {
        return !Float.isNaN(meters) && meters >= MIN_VALID_METERS && meters <= MAX_VALID_METERS;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** 空图，用于"深度模型没加载"的路径，避免调用方到处判空。 */
    @NonNull
    public static DepthMap empty() {
        return new DepthMap(1, 1, new float[]{Float.NaN});
    }
}
