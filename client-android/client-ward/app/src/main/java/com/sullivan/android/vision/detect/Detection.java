package com.sullivan.android.vision.detect;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 一个检测框。
 *
 * <p>坐标全部归一化到 [0, 1]，且相对于**正立后**的画面，因此与设备方向、相机分辨率
 * 都无关，可以直接交给界面层按比例绘制。
 */
public final class Detection {

    public final int classId;
    public final String label;
    public final float score;
    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public Detection(int classId, @NonNull String label, float score,
                     float left, float top, float right, float bottom) {
        this.classId = classId;
        this.label = label;
        this.score = score;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) * 0.5f;
    }

    /** 框面积占整幅画面的比例，对应 script.py 中基于分割掩膜计算的 area_ratio。 */
    public float areaRatio() {
        return width() * height();
    }

    /** 两个框的交集面积（归一化单位）。 */
    private float intersectionArea(@NonNull Detection other) {
        float w = Math.min(right, other.right) - Math.max(left, other.left);
        float h = Math.min(bottom, other.bottom) - Math.max(top, other.top);
        return w <= 0f || h <= 0f ? 0f : w * h;
    }

    /**
     * 交并比。
     *
     * <p>NMS 用 0.45（同类抑制），跨帧追踪关联用 0.20 —— 后者必须更松，
     * 因为低帧率下相邻帧的位移可能很大，详见 {@code ObjectTracker.IOU_GATE}。
     */
    public float iou(@NonNull Detection other) {
        float intersection = intersectionArea(other);
        float union = areaRatio() + other.areaRatio() - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    /**
     * 包含度：交集 / 两者中较小的面积。
     *
     * <p>一个大框套住小框时 IoU 会很小（分母被大框撑大），只靠 IoU 判不出"这两个框说的是
     * 同一个东西"。1 表示小框被完全包住。
     */
    public float intersectionOverMin(@NonNull Detection other) {
        float smaller = Math.min(areaRatio(), other.areaRatio());
        return smaller <= 0f ? 0f : intersectionArea(other) / smaller;
    }

    /** 两个框中心的横向距离（归一化单位）。 */
    public float centerDistanceX(@NonNull Detection other) {
        return Math.abs(centerX() - other.centerX());
    }

    public float centerY() {
        return (top + bottom) * 0.5f;
    }

    /** 两个框中心的平面距离（归一化单位）。 */
    public float centerDistance(@NonNull Detection other) {
        float dx = centerX() - other.centerX();
        float dy = centerY() - other.centerY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 两个框各自高的比值（≥ 1），用于判断单帧尺度变化是否合理。 */
    public float heightRatio(@NonNull Detection other) {
        float a = height();
        float b = other.height();
        if (a <= 0f || b <= 0f) {
            return Float.POSITIVE_INFINITY;
        }
        return a > b ? a / b : b / a;
    }

    /** 两个框的并集框（外接矩形），取较高的置信度。 */
    @NonNull
    public Detection union(@NonNull Detection other) {
        return new Detection(
                score >= other.score ? classId : other.classId,
                score >= other.score ? label : other.label,
                Math.max(score, other.score),
                Math.min(left, other.left),
                Math.min(top, other.top),
                Math.max(right, other.right),
                Math.max(bottom, other.bottom));
    }

    /**
     * 框底边是否已贴到画面底部。
     *
     * <p>被裁切的目标高度不再增长，基于尺度的测速会算出"距离不变"，恰好在该告警的时候
     * 失明，因此这类样本要排除在测速之外、单独按"非常近"处理。
     */
    public boolean isClippedBottom() {
        return bottom >= CLIPPED_BOTTOM;
    }

    /** 判定"贴底"的阈值。 */
    public static final float CLIPPED_BOTTOM = 0.97f;

    @NonNull
    @Override
    public String toString() {
        return String.format(Locale.US, "%s(%.2f) [%.3f, %.3f, %.3f, %.3f]",
                label, score, left, top, right, bottom);
    }
}
