package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;

import com.sullivan.protectee.vision.depth.DepthMap;

import java.util.Arrays;
import java.util.List;

/**
 * 障碍物运动预测：把"现在在哪"外推成"什么时候会撞上、会不会撞上"。
 *
 * <p><b>为什么需要这一层。</b>只按距离播报，等于假设"远近就等于危险"：三米外横着走过去的人
 * 和正前方三米处不会动的电线杆会被念成同一句话。要"只报可能撞上的"，就必须有一段时间上的
 * 预测，而预测只需要两个从深度图与追踪器里已经拿得到的量：距离 {@code Z} 与尺度增长率
 * {@code λ = d(ln h)/dt}。
 *
 * <p><b>模型。</b>小孔成像下框高 {@code h = f·H/Z}，于是
 * <pre>
 *   λ = d(ln h)/dt = −d(ln Z)/dt
 * </pre>
 * 目标自身的接近速度（已扣掉自运动）是 {@code v_obj = λ·Z}；用户自己的步速是
 * {@code v_ego}。两者相向，闭合速度 {@code v = v_ego + λ·Z}，于是
 * <pre>
 *   t_meet = Z / (v_ego + λ·Z)
 * </pre>
 * 两个极限都对得上：目标不动（λ=0）时 {@code t = Z/v_ego}，即"我走到它跟前要多久"；
 * 用户站着（v_ego=0）时 {@code t = 1/λ}，正是追踪器里那个剩余碰撞时间。**同一个式子
 * 把静态障碍与动态目标统一了**，不再需要两条互不相干的判据。
 *
 * <p><b>会不会撞上。</b>只看时间不够：一个在画面左侧 0.2 处横着走的行人，1.5 秒后也
 * "到跟前了"，但他会从旁边过去。判据是**横向扫掠**——在 {@code [0, t_meet]} 这段时间里，
 * 目标的横向区间有没有扫过 {@code x = 0.5}（用户的行进线）。横向速度为零时它退化成
 * "现在挡不挡在正前方"，所以静态目标也适用。
 *
 * <p><b>为什么用"扫过"而不是"会合时刻的位置"。</b>会合时刻距离趋近于 0，任何按距离
 * 收窄的走廊都会退化成一个点，判据失去意义；而"这段时间里有没有挡过路"在几何上就是
 * 碰撞的充分条件，且对距离估计的误差不敏感。
 *
 * <p>纯函数、无状态、不碰 Android API：这层的每个数值都能在 JVM 单测里解析地验算。
 */
public final class CollisionModel {

    /**
     * 预计相遇时间超过这么多秒，就认为"暂时不会撞到"。
     *
     * <p>6 秒 ≈ 成年人快走 7 米。比这更远的目标，用户有的是时间改变路线；把它们念出来
     * 只会稀释真正紧急的那一句。这个值也是**播报的取舍点**：调大＝报得更多更早，
     * 调小＝更安静但留给用户的反应时间更短。
     */
    public static final float HORIZON_S = 6.0f;

    /**
     * 进入"要撞上了"的时间门限。
     *
     * <p>1.8 秒 ≈ 盲杖步速下 2 米。与 {@link DynamicAdvisor#TTC_DANGER_ENTER_S} 取同一个
     * 值不是巧合：两条通道对"多急算急"必须一致，否则同一个目标会在两句话之间来回跳。
     */
    public static final float DANGER_S = 1.8f;

    /**
     * 估不出用户步速时用的默认值（米/秒）。
     *
     * <p>1.2 比盲杖使用者的常见步速（约 1.0–1.1）略高：步速取大 → {@code t_meet} 取小 →
     * 报得更早。预测误差的方向应当是"早一点"而不是"晚一点"。
     */
    public static final float DEFAULT_EGO_SPEED_MPS = 1.2f;

    /** 步速估计的上限。超过这个值只可能是关联错了，不是走路。 */
    public static final float MAX_EGO_SPEED_MPS = 2.6f;

    /** 估步速至少要几条轨迹。一条轨迹的 {@code λ·Z} 混进一个迎面走来的人就偏了。 */
    public static final int MIN_EGO_SAMPLES = 2;

    /** 低于此值的闭合速度视为"不会相遇"，返回无穷大而不是一个巨大的秒数。 */
    private static final float MIN_CLOSING_SPEED = 0.05f;

    /**
     * 相机水平半视场角的正切（约 32°，对应 64° 水平视场）。
     *
     * <p>只用来把"人的半身宽"换算成归一化横坐标上的安全边距。这个值随机型不同会有
     * ±10% 的出入，而边距本身还夹在 0.02–0.25 之间，所以不必精确 —— 它不是测距，
     * 只是"挡不挡路"的一点点余量。
     */
    public static final float TAN_HALF_HFOV = 0.62f;

    /** 用户连同盲杖摆动在内的半身宽（米）。 */
    public static final float BODY_HALF_WIDTH_M = 0.30f;

    /** 安全边距的夹取范围：太小时擦着肩膀过去的目标会被漏掉，太大时整幅画面都算"正前方"。 */
    public static final float MIN_PATH_MARGIN = 0.02f;
    public static final float MAX_PATH_MARGIN = 0.25f;

    /** 一次步速估计的输入：一条轨迹的距离与**补偿前**的尺度增长率。 */
    public static final class EgoSample {
        /** 由深度图得到的距离（米）。 */
        public final float meters;
        /** 补偿前的尺度增长率 {@code d(ln h)/dt}（1/s）。 */
        public final float rawRate;

        public EgoSample(float meters, float rawRate) {
            this.meters = meters;
            this.rawRate = rawRate;
        }
    }

    private CollisionModel() {
    }

    /**
     * 预计多久之后与目标相遇。
     *
     * @param meters       到目标的距离（米）。无效时返回 {@link Float#NaN}
     * @param relativeRate 自运动补偿后的尺度增长率（1/s）。{@link Float#NaN} 当作 0，
     *                     即"目标自己不动，只有我在走"
     * @param egoSpeed     用户步速（米/秒）。{@link Float#NaN} 或负数时用
     *                     {@link #DEFAULT_EGO_SPEED_MPS}
     * @return 相遇时间（秒）；闭合速度接近 0（谁都不动）时返回
     *         {@link Float#POSITIVE_INFINITY}，表示"不会相遇"
     */
    public static float timeToMeet(float meters, float relativeRate, float egoSpeed) {
        if (!DepthMap.isValid(meters)) {
            return Float.NaN;
        }
        float ego = egoSpeed;
        if (Float.isNaN(ego) || ego < 0f) {
            ego = DEFAULT_EGO_SPEED_MPS;
        }
        float closing = ego;
        if (relativeRate > 0f && !Float.isInfinite(relativeRate)) {
            closing += relativeRate * meters;
        }
        if (closing < MIN_CLOSING_SPEED) {
            // 目标没在靠近（或正被拉远），而我也没在走：没有"相遇"这件事。
            return Float.POSITIVE_INFINITY;
        }
        return meters / closing;
    }

    /**
     * 从一帧里的多条轨迹估用户自己的步速（米/秒）。
     *
     * <p>对**静止**目标有 {@code λ = v_ego/Z}，于是 {@code λ·Z = v_ego}——与焦距、与目标
     * 类别、与它离得多远都无关。这正是可以取中位数的量（同一结论见
     * {@code EgoMotionEstimator}，那里用的是没有深度时的代理量）。取中位数而不是均值：
     * 迎面走来的人会把均值往上拽，中位数不会。
     *
     * @return 中位数；样本不足或全部无效时返回 {@link Float#NaN}（调用方应退回默认步速）
     */
    public static float estimateEgoSpeed(@NonNull List<EgoSample> samples) {
        if (samples.size() < MIN_EGO_SAMPLES) {
            return Float.NaN;
        }
        float[] speeds = new float[samples.size()];
        int count = 0;
        for (int i = 0; i < samples.size(); i++) {
            EgoSample sample = samples.get(i);
            if (!DepthMap.isValid(sample.meters) || Float.isNaN(sample.rawRate)
                    || Float.isInfinite(sample.rawRate)) {
                continue;
            }
            float speed = sample.rawRate * sample.meters;
            // 负值意味着"目标在远离"，只可能来自拟合噪声；0 附近的值没有信息量。
            if (speed > MIN_CLOSING_SPEED) {
                speeds[count++] = speed;
            }
        }
        if (count < MIN_EGO_SAMPLES) {
            return Float.NaN;
        }
        Arrays.sort(speeds, 0, count);
        int middle = count / 2;
        float median = (count & 1) == 1
                ? speeds[middle]
                : (speeds[middle - 1] + speeds[middle]) * 0.5f;
        return Math.min(median, MAX_EGO_SPEED_MPS);
    }

    /**
     * 目标会不会挡在用户的行进线上。
     *
     * @param centerX     目标框中心（归一化横坐标）
     * @param halfWidth   目标框半宽（归一化）。它把"目标本身有多宽"算进去
     * @param lateralRate 横向速率 {@code d(centerX)/dt}；{@link Float#NaN} 视为不动
     * @param horizonS    向前看多久（秒），通常是 {@code min(t_meet, HORIZON_S)}
     * @param meters      当前距离（米），只用来算安全边距
     * @return 在这段时间里，目标的横向区间是否扫过画面中线
     */
    public static boolean sweepsWalkingLine(float centerX, float halfWidth, float lateralRate,
                                            float horizonS, float meters) {
        float margin = pathMargin(meters);
        float endX = centerX;
        if (!Float.isNaN(lateralRate) && !Float.isInfinite(lateralRate) && horizonS > 0f) {
            // 无穷大的 horizon 没有意义（那时人早就走过去了），按 HORIZON_S 外推即可。
            float span = Float.isInfinite(horizonS) ? HORIZON_S : Math.min(horizonS, HORIZON_S);
            endX = centerX + lateralRate * span;
        }
        float left = Math.min(centerX, endX) - Math.max(halfWidth, 0f) - margin;
        float right = Math.max(centerX, endX) + Math.max(halfWidth, 0f) + margin;
        return left <= 0.5f && right >= 0.5f;
    }

    /**
     * 用户身体在画面横向上的半宽（归一化）。距离越近越宽，这正是透视该有的样子：
     * 一米外的人挡住的横向范围远大于五米外的人。
     *
     * <p>距离未知时返回 {@link #MAX_PATH_MARGIN}：没有尺度的场合宁可把边距放到最宽，
     * 因为"漏报一个正前方的目标"比"多报一个旁边的目标"严重。
     */
    public static float pathMargin(float meters) {
        if (!DepthMap.isValid(meters)) {
            return MAX_PATH_MARGIN;
        }
        float halfWidth = BODY_HALF_WIDTH_M / (2f * meters * TAN_HALF_HFOV);
        if (halfWidth < MIN_PATH_MARGIN) {
            return MIN_PATH_MARGIN;
        }
        return Math.min(halfWidth, MAX_PATH_MARGIN);
    }

    /** 这个时间是否落在"需要播报"的范围内（NaN 与无穷大都返回 false）。 */
    public static boolean withinHorizon(float seconds) {
        return !Float.isNaN(seconds) && seconds <= HORIZON_S;
    }

    /** 秒数换成一个能塞进一句话的整数（1–9）。 */
    public static int wholeSeconds(float seconds) {
        if (Float.isNaN(seconds) || seconds <= 0f) {
            return 1;
        }
        // **向下取整**，不四舍五入：2.5 秒说成"2秒"是稍早预告，说成"3秒"是骗人。
        // 报晚了的代价是用户以为还有时间，报早了的代价只是多走一步。
        return Math.max(1, Math.min(9, (int) Math.floor(seconds)));
    }
}
