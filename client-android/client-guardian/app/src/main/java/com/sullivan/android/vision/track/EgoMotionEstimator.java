package com.sullivan.android.vision.track;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

/**
 * 从整幅画面的运动里估计"自己走得多快"，用来把自运动从目标运动里减掉。
 *
 * <p>用户自己往前走时，相机平移也会让**所有**目标变大。不补偿的话，满屏静止的东西都会被
 * 算成"在靠近"——这是这套动态播报最容易翻车的地方，也是真机验收里最该盯的一条。
 *
 * <p><b>补偿量必须取在正确的量上。</b>直觉是"对各个目标的增长率取中位数"，但那是错的：
 * {@code r = d(ln h)/dt = v_ego/Z} **即使在静止物体上也随距离变化**，对它取中位数得到的是
 * "中位数距离处的自运动率"，没有任何物理意义。反例：{@code v_ego = 1.4 m/s}，三个静止
 * 目标（行人 Z=3、车 Z=15、卡车 Z=25）的 r 分别是 0.467 / 0.093 / 0.056，中位数 0.093；
 * 减掉之后近处那个**站着不动**的行人变成 r = 0.373，即"2.68 秒后撞上"的假告警。
 *
 * <p>正确的量是速度代理 {@code w = r·H/h}（H 是类别典型物理高度）：
 * <pre>
 *   静止目标：r = v_ego/Z，h = (f/H_img)·H/Z
 *   ⇒ w = r·H/h = v_ego·H_img/f
 * </pre>
 * w 对**所有**静止目标恒等，与距离、与类别都无关——这才是可以取中位数的量。
 *
 * <p>补偿：{@code rate_relative = rate − w·h/H}。
 */
public final class EgoMotionEstimator {

    /** 少于这么多条轨迹就不敢下结论：两条轨迹里混进一个迎面走来的人，中位数就被带跑了。 */
    public static final int MIN_TRACKS = 3;

    /**
     * 相对中位数绝对偏差的上限。场景的 w 不一致说明"静止背景"这个假设不成立
     * （路口全是走动的人），此时放弃补偿比错误补偿强。
     */
    public static final float MAX_RELATIVE_MAD = 0.5f;

    /** w 的物理上限。超过说明关联错了、或者画面在快速旋转，不是走路能产生的量。 */
    public static final float MAX_EGO = 6.0f;

    /**
     * 补偿量的封顶。**欠补偿优于过补偿**：迎面人群场景下中位数会被拉走，补偿过度会让
     * 真正在靠近的人被算成静止（该报而不报），补偿不足只是让静止物体稍微"像在靠近"，
     * 后者的后果轻得多。
     */
    public static final float EGO_CAP = 3.0f;

    /** 小于此值就当作"没有自运动"。 */
    private static final float NEAR_ZERO = 1e-3f;

    /** 参与估计的一条轨迹的读数。 */
    public static final class Sample {
        /** 原始尺度增长率 {@code d(ln h)/dt}，单位 1/s。 */
        public final float rate;
        /**
         * **质心时刻**的归一化框高（{@link TtcEstimator.Fit#centroidHeight}）。
         *
         * <p>必须与 {@link #rate} 取自同一时刻：拿当前帧的高度去配质心时刻的增长率，
         * 会在近处系统性过补偿，把真正在靠近的目标算成静止。
         */
        public final float height;
        /** 该类别的典型物理高度（米）。 */
        public final float referenceHeightMeters;

        public Sample(float rate, float height, float referenceHeightMeters) {
            this.rate = rate;
            this.height = height;
            this.referenceHeightMeters = referenceHeightMeters;
        }
    }

    private EgoMotionEstimator() {
    }

    /**
     * 估计自运动速度代理 {@code v_ego·H_img/f}。
     *
     * @return 中位数；轨迹不足、场景不一致或数值越界时返回 {@link Float#NaN}，表示"不做补偿"。
     */
    public static float estimateVelocityProxy(@NonNull List<Sample> samples) {
        int count = samples.size();
        if (count < MIN_TRACKS) {
            return Float.NaN;
        }

        float[] proxies = new float[count];
        for (int i = 0; i < count; i++) {
            Sample sample = samples.get(i);
            if (!(sample.height > 0f) || !(sample.referenceHeightMeters > 0f)
                    || Float.isNaN(sample.rate) || Float.isInfinite(sample.rate)) {
                return Float.NaN;
            }
            proxies[i] = sample.rate * sample.referenceHeightMeters / sample.height;
        }
        Arrays.sort(proxies);

        float median = medianOfSorted(proxies);
        float[] deviations = new float[count];
        for (int i = 0; i < count; i++) {
            deviations[i] = Math.abs(proxies[i] - median);
        }
        Arrays.sort(deviations);
        float mad = medianOfSorted(deviations);

        if (Math.abs(median) < NEAR_ZERO) {
            // 场景整体没在动：没有可补偿的量。MAD 判据在 median ≈ 0 时会退化成
            // "必须所有 w 都严格相等"，反而把正常情况拒掉，所以这里先短路。
            return mad == 0f ? 0f : Float.NaN;
        }
        if (mad > MAX_RELATIVE_MAD * Math.abs(median)) {
            // 场景不一致（路口全是走动的人），"静止背景"这个前提不成立。
            return Float.NaN;
        }
        if (Math.abs(median) > MAX_EGO) {
            // 走路产生不了这么大的量，只可能是关联错了或关联到了快速经过的车辆。
            return Float.NaN;
        }
        return clamp(median);
    }

    /**
     * 把自运动从一条轨迹的增长率里减掉。
     *
     * @param centroidHeight 必须是与 {@code rate} 同一时刻（窗口质心）的框高，
     *                       而不是当前帧的框高；见 {@link Sample#height}。
     * @param egoProxy       {@link #estimateVelocityProxy} 的结果；NaN 表示不补偿，原样返回。
     */
    public static float compensatedRate(float rate, float centroidHeight,
                                        float referenceHeightMeters, float egoProxy) {
        if (Float.isNaN(rate)) {
            return Float.NaN;
        }
        if (Float.isNaN(egoProxy) || !(centroidHeight > 0f) || !(referenceHeightMeters > 0f)) {
            return rate;
        }
        return rate - egoRate(centroidHeight, referenceHeightMeters, egoProxy);
    }

    /** 自运动在这一条轨迹上贡献的增长率：{@code w·h/H}，正好是 {@code v_ego/Z}。 */
    public static float egoRate(float height, float referenceHeightMeters, float egoProxy) {
        return egoProxy * height / referenceHeightMeters;
    }

    private static float clamp(float value) {
        if (value > EGO_CAP) {
            return EGO_CAP;
        }
        return value < -EGO_CAP ? -EGO_CAP : value;
    }

    private static float medianOfSorted(@NonNull float[] sorted) {
        int count = sorted.length;
        if (count == 0) {
            return Float.NaN;
        }
        int middle = count / 2;
        return (count & 1) == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) * 0.5f;
    }
}
