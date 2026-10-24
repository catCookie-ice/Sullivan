package com.sullivan.protectee.vision.depth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.Letterbox;

/**
 * 把"有多远"换算成"算不算近"的口径。
 *
 * <p><b>为什么单独一个类。</b>改造前 {@code ObstacleAdvisor.isNear} 是纯几何启发式
 * （框下沿超过画面 78%，或面积超过 12%），它的问题是**没有尺度**：同样一个人，
 * 在 1 米和 3 米处都可能同时满足或不满足那两条，取决于手机举得多高、镜头多广。
 * 有了深度图之后这个判断可以直接用米，但"多少米算近"是一个需要单独标定和单独讨论的
 * 数值，不应该散落在播报文案的拼接代码里。
 *
 * <p><b>没有深度图时完全退回原判据。</b>不是"用 0 米代替"或"当作很远"：前者会
 * 制造假紧急，后者会在最需要提示的时候沉默。深度模型是可选资产（20 MB），
 * 只装检测模型的设备必须与改造前行为逐字一致。
 */
public final class DistancePolicy {

    /**
     * 低于此距离算"较近，注意"。
     *
     * <p>2.0 米≈成年人两步半。取这个值是因为盲杖的探测范围约 1–1.2 米，
     * 而提示必须**早于**盲杖接触才可能改变行走决策；同时 2 米处的人流密度
     * 在城市人行道上仍属"需要提醒"的量级。这个值需要用真机标定，不是定论。
     */
    public static final float NEAR_METERS = 2.0f;

    /**
     * 低于此距离、且位于画面中央时升级为 DANGER（立即打断播报）。
     *
     * <p>1.2 米已经进入盲杖范围，此时提示的意义从"注意"变成"停下"。
     */
    public static final float DANGER_METERS = 1.2f;

    private DistancePolicy() {
    }

    /**
     * 该障碍物是否算"近"。
     *
     * @param depth 本帧的深度图；为 null 或取不到有效值时退回几何启发式
     * @param box   本帧的 letterbox 几何（把归一化框坐标换回深度图像素要用到）
     */
    public static boolean isNear(@NonNull Detection detection, @Nullable DepthMap depth,
                                 @Nullable Letterbox box) {
        float meters = metersOf(detection, depth, box);
        if (Float.isNaN(meters)) {
            return com.sullivan.protectee.vision.assist.ObstacleAdvisor.isNear(detection);
        }
        return meters <= NEAR_METERS;
    }

    /** 是否进入"立即停下"的距离。无有效深度时永远 false（由几何判据决定 DANGER）。 */
    public static boolean isImminent(@NonNull Detection detection, @Nullable DepthMap depth,
                                     @Nullable Letterbox box) {
        float meters = metersOf(detection, depth, box);
        return !Float.isNaN(meters) && meters <= DANGER_METERS;
    }

    /**
     * 该障碍物的估算距离（米）。
     *
     * @return 无法估算时返回 {@link Float#NaN} —— 调用方据此决定"要不要报米数"
     */
    public static float metersOf(@NonNull Detection detection, @Nullable DepthMap depth,
                                 @Nullable Letterbox box) {
        if (depth == null || box == null) {
            return Float.NaN;
        }
        float meters = depth.distanceAtFootprint(box, detection.left, detection.top,
                detection.right, detection.bottom);
        return DepthMap.isValid(meters) ? meters : Float.NaN;
    }

    /**
     * 播报用的距离串，例如 {@code "1.5 米"}；取不到有效距离时返回 {@code null}。
     *
     * <p>只保留一位小数：深度模型的绝对误差在米级，报"1.47 米"是在暗示
     * 一个并不存在的精度。两米以内报一位，两米以上整数 —— 见 {@link #formatMeters}。
     */
    @Nullable
    public static String describeMeters(float meters) {
        if (Float.isNaN(meters) || !DepthMap.isValid(meters)) {
            return null;
        }
        if (meters < 2f) {
            return String.format(java.util.Locale.US, "%.1f 米", meters);
        }
        return Math.round(meters) + " 米";
    }
}
