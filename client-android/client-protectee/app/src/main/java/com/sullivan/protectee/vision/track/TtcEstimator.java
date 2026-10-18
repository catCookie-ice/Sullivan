package com.sullivan.protectee.vision.track;

import androidx.annotation.NonNull;

import com.sullivan.protectee.vision.detect.Detection;

/**
 * 从一条轨迹的观测序列里估计尺度增长率，以及由此推出的"还有多久撞上"。
 *
 * <p><b>为什么用高度而不是面积。</b>小孔成像下 {@code h = f·H/Z}，于是
 * {@code ln h = ln(fH) − ln Z}，只有一条边的噪声。面积 {@code A = w·h} 会把宽度的噪声
 * 也混进来，而宽度对姿态和遮挡敏感得多（人一侧身宽度就减半），会凭空造出尺度变化。
 *
 * <p><b>为什么回归的自变量必须是真实时间戳。</b>帧率在 5–15 FPS 之间浮动、帧间隔并不均匀，
 * 按帧序号回归等于假设"每帧间隔相等"，会引入与帧率抖动同量级的系统偏差。
 *
 * <p><b>为什么不上卡尔曼滤波。</b>这里的本质是带过程噪声的递归最小二乘，增益要按帧率调参，
 * 而帧率本身是浮动的；批量最小二乘只有 5~19 个样本，成本可以忽略，还能顺手给出**残差
 * RMS**——这是卡尔曼给不了的、非常便宜的可靠性门限。
 */
public final class TtcEstimator {

    /** 最少样本数。少于这个数，拟合出的斜率纯属噪声放大的产物。 */
    public static final int MIN_SAMPLES = 5;

    /** 拟合窗口的最小时间跨度，保证斜率的杠杆够长。 */
    public static final long MIN_SPAN_MS = 800L;

    /** 拟合窗口的最大时长。再长就会跨过"走路时人一转身、被东西挡住"这类尺度突变。 */
    public static final long MAX_WINDOW_MS = 1200L;

    /** 高度通道的残差 RMS 上限（单位是 ln h，0.08 ≈ 高度上 8% 的抖动）。 */
    public static final float MAX_RESIDUAL_RMS_LOG = 0.08f;

    /** 横向通道的残差 RMS 上限（单位是画面宽度比例）。 */
    public static final float MAX_RESIDUAL_RMS_LATERAL = 0.05f;

    /**
     * 斜率必须达到自身标准误的这个倍数才算"真的在动"。
     *
     * <p>没有这一条，一条几乎静止的轨迹会因为噪声拟合出一个小而非零的斜率，被当成"缓慢靠近"，
     * 给出一个几百秒的 TTC——数值上"没错"，但它会污染自运动估计的中位数。
     */
    public static final float MIN_SIGNIFICANCE = 1.5f;

    /** 高度小于此值（约 19 px）就不估 TTC：框太小时一个像素的抖动就能翻转斜率的符号。 */
    public static final float MIN_HEIGHT = 0.03f;

    /** 置信度低于此值的观测不参与拟合。 */
    public static final float MIN_SCORE = 0.50f;

    /** 第一遍拟合后，残差超过这个倍数的样本被剔除（只剔一轮）。 */
    public static final float OUTLIER_SIGMA = 2.0f;

    /** 拟合结果。{@link #ok} 为 false 时其余字段无意义。 */
    public static final class Fit {
        public final boolean ok;
        /** 斜率，单位 1/s（高度通道是 {@code d(ln h)/dt}，横向通道是 {@code d(centerX)/dt}）。 */
        public final float rate;
        /** 斜率的标准误。 */
        public final float stdErr;
        /** 残差 RMS。 */
        public final float residualRms;
        /**
         * 参与拟合样本的**时间质心**（绝对毫秒）。
         *
         * <p>最小二乘给出的是这个时刻的斜率，不是当前时刻的。这个字段是
         * {@link #ttcSeconds} 里那项质心校正的依据，别丢。
         */
        public final long centroidMs;
        /**
         * **质心时刻**的框高（窗口内高度的几何平均），仅高度通道有意义，横向通道为 NaN。
         *
         * <p>自运动补偿必须取这个高度，不能取当前帧的高度：增长率属于质心时刻，补偿量
         * {@code w·h/H} 里的 h 也必须取同一时刻，否则在近处会系统性**过补偿**——
         * 那正好会掩盖真正在靠近的目标。
         */
        public final float centroidHeight;
        /** 最终参与拟合的样本数（可能少于传入的窗口长度，野值已被剔除）。 */
        public final int samples;

        private Fit(boolean ok, float rate, float stdErr, float residualRms,
                    long centroidMs, float centroidHeight, int samples) {
            this.ok = ok;
            this.rate = rate;
            this.stdErr = stdErr;
            this.residualRms = residualRms;
            this.centroidMs = centroidMs;
            this.centroidHeight = centroidHeight;
            this.samples = samples;
        }

        public static final Fit NO_FIT =
                new Fit(false, Float.NaN, Float.NaN, Float.NaN, 0L, Float.NaN, 0);
    }

    private TtcEstimator() {
    }

    /**
     * 拟合尺度增长率 {@code d(ln h)/dt}。
     *
     * <p>会就地裁剪 {@code window}（丢掉超过 {@link #MAX_WINDOW_MS} 的老样本）。
     */
    @NonNull
    public static Fit fitHeight(@NonNull TrackWindow window, long nowMs) {
        window.prune(nowMs, MAX_WINDOW_MS);

        int size = window.size();
        long[] times = new long[size];
        float[] values = new float[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            float height = window.height(i);
            // 贴到画面底部的目标高度不再增长，尺度通道会算出"距离不变"——正好在该告警的
            // 时候失明。这类样本一律不进拟合（调用方另有"非常近"的判据兜底）。
            if (height < MIN_HEIGHT || window.score(i) < MIN_SCORE
                    || window.bottom(i) >= Detection.CLIPPED_BOTTOM) {
                continue;
            }
            float lnHeight = window.lnHeight(i);
            if (Float.isNaN(lnHeight) || Float.isInfinite(lnHeight)) {
                continue;
            }
            times[count] = window.timeMs(i);
            values[count] = lnHeight;
            count++;
        }
        return fit(times, values, count, MAX_RESIDUAL_RMS_LOG, true);
    }

    /**
     * 拟合横向移动速率 {@code d(centerX)/dt}。
     *
     * <p>必须单独走一条通道：横向穿越的行人**面积和高度都是恒定的**，任何基于尺度的
     * TTC 都看不见它（{@code d(ln h)/dt ≈ 0}），而它恰恰是最容易被撞上的一类目标。
     */
    @NonNull
    public static Fit fitLateral(@NonNull TrackWindow window, long nowMs) {
        window.prune(nowMs, MAX_WINDOW_MS);

        int size = window.size();
        long[] times = new long[size];
        float[] values = new float[size];
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (window.height(i) < MIN_HEIGHT || window.score(i) < MIN_SCORE) {
                continue;
            }
            // 横向通道不排除"贴底"的样本：横穿的人常常已经走到画面下半部，把它们排掉
            // 等于在最需要横向预警的时候失明。
            times[count] = window.timeMs(i);
            values[count] = window.centerX(i);
            count++;
        }
        return fit(times, values, count, MAX_RESIDUAL_RMS_LATERAL, false);
    }

    /**
     * 把拟合出的增长率换算成"当前时刻"的剩余碰撞时间。
     *
     * <p><b>质心校正。</b>匀速接近时 {@code TTC = Z/v} 而 {@code dZ/dt = −v}，所以
     * {@code dTTC/dt ≡ −1}：每过 1 秒，TTC 就少 1 秒。而最小二乘给出的斜率属于
     * **窗口质心时刻**。在 1.2 秒的窗口上裸用这个斜率，TTC 会**恒定高估约 0.6 秒**
     * （窗口长度的一半），等于每一次动态告警都晚半秒多。减去质心到现在的推移即可。
     *
     * @return 剩余秒数；正在靠近时为正。无法给出结论时返回 {@link Float#NaN}。
     */
    public static float ttcSeconds(float rate, long centroidMs, long nowMs) {
        if (!(rate > 0f) || Float.isInfinite(rate)) {
            // 没在靠近（rate ≤ 0）或数值不可用：不存在"碰撞时间"这个概念。
            return Float.NaN;
        }
        float ttc = 1f / rate - (nowMs - centroidMs) / 1000f;
        // 推出负值意味着"按这个速度早该撞上了却还在靠近"：只可能是目标已经贴到眼前。
        // 夹到 0，交给下游按"立刻"处理，别留下一个"负数秒后撞上"的怪结论。
        return ttc > 0f ? ttc : 0f;
    }

    // ------------------------------------------------------------------
    // 最小二乘
    // ------------------------------------------------------------------

    /** 一次最小二乘的中间结果。 */
    private static final class Line {
        boolean ok;
        float slope;
        float intercept;
        float stdErr;
        float rms;
        /** 观测值在质心处的均值。对数通道里反变换回原尺度就是质心高度。 */
        float meanY;
        long centroidMs;
        int samples;
    }

    @NonNull
    private static Fit fit(long[] times, float[] values, int count, float maxResidualRms,
                           boolean logarithmic) {
        if (count < MIN_SAMPLES || spanMs(times, count) < MIN_SPAN_MS) {
            return Fit.NO_FIT;
        }

        Line first = leastSquares(times, values, count);
        if (!first.ok) {
            return Fit.NO_FIT;
        }

        // 一遍 2σ 剔除。单帧的野值（关联错框、误检）在残差上非常显眼，剔掉一个就能把
        // 斜率拉回来；只剔一轮，剔多了会把"真的在急靠近"当成离群点。
        Line result = first;
        float[] keptValues = new float[count];
        long[] keptMs = new long[count];
        int kept = 0;
        float threshold = OUTLIER_SIGMA * Math.max(first.rms, 1e-6f);
        for (int i = 0; i < count; i++) {
            float fitted = first.slope * ((times[i] - times[0]) / 1000f) + first.intercept;
            if (Math.abs(values[i] - fitted) <= threshold) {
                keptMs[kept] = times[i];
                keptValues[kept] = values[i];
                kept++;
            }
        }
        if (kept >= MIN_SAMPLES && spanMs(keptMs, kept) >= MIN_SPAN_MS) {
            Line second = leastSquares(keptMs, keptValues, kept);
            if (second.ok) {
                result = second;
            }
        }

        // 残差 RMS 是免费的可靠性门限：关联错了、目标被遮挡、目标急转弯，都会让它飙升。
        if (result.rms > maxResidualRms) {
            return Fit.NO_FIT;
        }
        if (Math.abs(result.slope) < MIN_SIGNIFICANCE * result.stdErr) {
            return Fit.NO_FIT;
        }
        // 最小二乘直线过质心，所以质心处的拟合值就是 meanY；高度通道取了 ln，反变换回来
        // 得到的是窗口内高度的**几何平均**。
        float centroidHeight = logarithmic ? (float) Math.exp(result.meanY) : Float.NaN;
        return new Fit(true, result.slope, result.stdErr, result.rms,
                result.centroidMs, centroidHeight, result.samples);
    }

    /**
     * 对 {@code (时间, 观测值)} 做一元最小二乘。
     *
     * <p>自变量取**相对第一个样本的秒数**而不是绝对时刻：绝对毫秒数有 9 位，塞进 float
     * 只剩约 30 ms 的分辨率，正好把质心校正要用的那个量毁掉。
     */
    @NonNull
    private static Line leastSquares(long[] times, float[] values, int count) {
        Line line = new Line();
        long baseMs = times[0];

        double meanX = 0d;
        double meanY = 0d;
        for (int i = 0; i < count; i++) {
            meanX += (times[i] - baseMs) / 1000d;
            meanY += values[i];
        }
        meanX /= count;
        meanY /= count;

        double sxx = 0d;
        double sxy = 0d;
        for (int i = 0; i < count; i++) {
            double dx = (times[i] - baseMs) / 1000d - meanX;
            sxx += dx * dx;
            sxy += dx * (values[i] - meanY);
        }
        if (sxx <= 0d) {
            // 所有时间戳相同：换模型时重置过的时钟，或同一帧被处理了两次。
            return line;
        }
        line.slope = (float) (sxy / sxx);
        line.intercept = (float) (meanY - line.slope * meanX);
        line.meanY = (float) meanY;

        double sumSquares = 0d;
        for (int i = 0; i < count; i++) {
            // 过质心的直线：拟合值 = ȳ + k·(x − x̄)。写成截距形式容易少减一次 x̄，
            // 那会让残差整体偏大、把所有样本都判成野值。
            double residual = values[i] - (meanY
                    + line.slope * ((times[i] - baseMs) / 1000d - meanX));
            sumSquares += residual * residual;
        }
        line.rms = (float) Math.sqrt(sumSquares / count);
        // 自由度是 count − 2（斜率与截距各占一个）。
        line.stdErr = (float) Math.sqrt(sumSquares / (count - 2) / sxx);
        line.centroidMs = baseMs + Math.round(meanX * 1000d);
        line.samples = count;
        line.ok = true;
        return line;
    }

    private static long spanMs(long[] times, int count) {
        if (count < 2) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            min = Math.min(min, times[i]);
            max = Math.max(max, times[i]);
        }
        return max - min;
    }
}
