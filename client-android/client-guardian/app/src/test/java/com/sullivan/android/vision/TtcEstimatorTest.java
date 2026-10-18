package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.track.TrackWindow;
import com.sullivan.android.vision.track.TtcEstimator;

import org.junit.Test;

/**
 * 测速通道的契约。每条轨迹都是**精确构造**出来的小孔成像序列，所以期望值能解析地写出来。
 *
 * <p>模型：{@code h(t) = K·H/(Z₀ − v·t)}，于是 {@code d(ln h)/dt = v/Z(t)}，
 * 而 {@code 1/rate = Z/v} 正是那一刻的 TTC。
 */
public class TtcEstimatorTest {

    /** 视场系数（= 像素焦距 / 画面高度）。合成数据里它只影响数字大小，不影响结构。 */
    private static final float K = 0.6f;
    private static final float PERSON_HEIGHT_M = 1.70f;

    /** 不等间隔的时间戳——真实设备上帧间隔从来不是均匀的。 */
    private static final long[] UNEVEN_MS = {0L, 180L, 340L, 520L, 700L, 880L, 1050L, 1200L};

    /** 均匀 5 FPS 的时间戳。 */
    private static final long[] FIVE_FPS_MS = {0L, 200L, 400L, 600L, 800L, 1000L, 1200L};

    private static TrackWindow window(long[] timesMs, float[] heights, float[] centerXs) {
        TrackWindow window = new TrackWindow();
        for (int i = 0; i < timesMs.length; i++) {
            // 底边固定在画面中部，保证不会被判为"贴底"。
            window.add(timesMs[i], heights[i], 0.7f, centerXs[i], 0.9f);
        }
        return window;
    }

    private static float heightAt(float z) {
        return K * PERSON_HEIGHT_M / z;
    }

    private static float[] advancingHeights(long[] timesMs, float z0, float v) {
        float[] heights = new float[timesMs.length];
        for (int i = 0; i < timesMs.length; i++) {
            heights[i] = heightAt(z0 - v * timesMs[i] / 1000f);
        }
        return heights;
    }

    private static float[] constant(int count, float value) {
        float[] values = new float[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    /**
     * 质心校正：不校正的话 TTC 会**恒定高估约半个窗口**。
     *
     * <p>这条用例把那个反例固化下来：谁要是觉得"减去质心偏移"是多余的而删掉它，
     * 这里会立刻红，而不是等到真机上发现每次告警都晚半秒。
     */
    @Test
    public void centroidCorrectionRemovesTheHalfWindowBias() {
        float z0 = 8f;
        float v = 2.8f;
        long now = 1200L;
        float trueTtc = (z0 - v * now / 1000f) / v;   // = 1.657s

        TrackWindow window = window(UNEVEN_MS, advancingHeights(UNEVEN_MS, z0, v),
                constant(UNEVEN_MS.length, 0.5f));
        TtcEstimator.Fit fit = TtcEstimator.fitHeight(window, now);

        assertTrue(fit.ok);
        float naive = 1f / fit.rate;
        float corrected = TtcEstimator.ttcSeconds(fit.rate, fit.centroidMs, now);

        assertEquals("校正后应当贴合真值", trueTtc, corrected, 0.1f);
        assertTrue("未校正时的高估应当接近窗口长度的一半，这个反例必须留着",
                naive - trueTtc > 0.45f);
        assertEquals("质心应当落在窗口中部", now / 2d, fit.centroidMs, now / 4d);
    }

    /** 帧率无关性：同一段物理运动，5 FPS 和不等间隔采样应当给出同样的结论。 */
    @Test
    public void rateIsIndependentOfFrameRate() {
        float z0 = 8f;
        float v = 2.8f;

        TtcEstimator.Fit uneven = TtcEstimator.fitHeight(
                window(UNEVEN_MS, advancingHeights(UNEVEN_MS, z0, v), constant(UNEVEN_MS.length, 0.5f)),
                1200L);
        TtcEstimator.Fit fiveFps = TtcEstimator.fitHeight(
                window(FIVE_FPS_MS, advancingHeights(FIVE_FPS_MS, z0, v), constant(FIVE_FPS_MS.length, 0.5f)),
                1200L);

        assertTrue(uneven.ok);
        assertTrue(fiveFps.ok);
        assertEquals(uneven.rate, fiveFps.rate, 0.02f);

        float expectedTtc = (z0 - v * 1.2f) / v;
        assertEquals(expectedTtc, TtcEstimator.ttcSeconds(fiveFps.rate, fiveFps.centroidMs, 1200L),
                0.1f);
    }

    /** 单帧野值（关联错框、误检）不能把斜率带跑。 */
    @Test
    public void singleOutlierIsRejected() {
        float z0 = 8f;
        float v = 2.8f;
        float[] clean = advancingHeights(UNEVEN_MS, z0, v);
        TtcEstimator.Fit reference = TtcEstimator.fitHeight(
                window(UNEVEN_MS, clean, constant(UNEVEN_MS.length, 0.5f)), 1200L);
        assertTrue(reference.ok);

        float[] dirty = clean.clone();
        dirty[3] *= 1.5f;   // 中间某一帧的框突然大了 50%
        TtcEstimator.Fit robust = TtcEstimator.fitHeight(
                window(UNEVEN_MS, dirty, constant(UNEVEN_MS.length, 0.5f)), 1200L);

        assertTrue("一个野值不该把整条轨迹判死", robust.ok);
        assertEquals("斜率应当基本回到干净序列的值", reference.rate, robust.rate, 0.03f);
        assertTrue("野值应当被剔掉", robust.samples < UNEVEN_MS.length);
        assertTrue(robust.residualRms < TtcEstimator.MAX_RESIDUAL_RMS_LOG);
    }

    /** 全是噪声的序列（目标其实没动）不能给出"缓慢靠近"的结论。 */
    @Test
    public void noisyStaticSequenceIsRejectedBySignificance() {
        float base = heightAt(6f);
        float[] heights = new float[UNEVEN_MS.length];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = base + (i % 2 == 0 ? 0.01f : -0.01f);
        }
        TtcEstimator.Fit fit = TtcEstimator.fitHeight(
                window(UNEVEN_MS, heights, constant(UNEVEN_MS.length, 0.5f)), 1200L);

        assertFalse("斜率不显著就不能当成'在靠近'", fit.ok);
    }

    /** 横穿的行人：高度恒定，尺度通道**看不见**他——所以才有单独的横向通道。 */
    @Test
    public void lateralCrossingIsInvisibleToTheHeightChannel() {
        float height = heightAt(4f);
        float[] centerXs = new float[UNEVEN_MS.length];
        for (int i = 0; i < centerXs.length; i++) {
            // 每秒钟横移 0.14 个画面宽——按**时间**而不是按帧号，帧间隔不均时才不会算错。
            centerXs[i] = 0.3f + 0.14f * UNEVEN_MS[i] / 1000f;
        }

        TtcEstimator.Fit scaleFit = TtcEstimator.fitHeight(
                window(UNEVEN_MS, constant(UNEVEN_MS.length, height), centerXs), 1200L);
        TtcEstimator.Fit lateralFit = TtcEstimator.fitLateral(
                window(UNEVEN_MS, constant(UNEVEN_MS.length, height), centerXs), 1200L);

        assertTrue(scaleFit.ok);
        assertEquals("尺度通道给出的增长率必须是 0", 0f, scaleFit.rate, 1e-4f);
        assertTrue("0 增长率不构成'碰撞时间'",
                Float.isNaN(TtcEstimator.ttcSeconds(scaleFit.rate, scaleFit.centroidMs, 1200L)));

        assertTrue(lateralFit.ok);
        assertEquals("横向通道要能看见它：0.14/s", 0.14f, lateralFit.rate, 0.005f);
    }

    /** 框贴到画面底部时高度不再增长，这类样本必须排除，否则"该报警时失明"。 */
    @Test
    public void clippedSamplesAreExcluded() {
        TrackWindow window = new TrackWindow();
        float[] heights = advancingHeights(UNEVEN_MS, 8f, 2.8f);
        for (int i = 0; i < UNEVEN_MS.length; i++) {
            // 底边贴到画面最下沿：视障用户正前方 1 米内的人就是这个样子。
            window.add(UNEVEN_MS[i], heights[i], 0.99f, 0.5f, 0.9f);
        }
        assertFalse(TtcEstimator.fitHeight(window, 1200L).ok);
    }

    /** 样本太少或跨度太短都不可靠。 */
    @Test
    public void tooFewSamplesOrTooShortSpanIsRejected() {
        long[] fourSamples = {0L, 300L, 600L, 900L};
        assertFalse(TtcEstimator.fitHeight(
                window(fourSamples, advancingHeights(fourSamples, 8f, 2.8f),
                        constant(fourSamples.length, 0.5f)), 900L).ok);

        // 样本够多但挤在 400 ms 内：斜率的杠杆太短。
        long[] crowded = {0L, 80L, 160L, 240L, 320L, 400L};
        assertFalse(TtcEstimator.fitHeight(
                window(crowded, advancingHeights(crowded, 8f, 2.8f), constant(crowded.length, 0.5f)),
                400L).ok);
    }

    /** 框太小时（小于约 19 px）一个像素的抖动就能翻转斜率符号。 */
    @Test
    public void tinyBoxesAreIgnored() {
        float[] heights = new float[UNEVEN_MS.length];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = TtcEstimator.MIN_HEIGHT * 0.8f;
        }
        assertFalse(TtcEstimator.fitHeight(
                window(UNEVEN_MS, heights, constant(UNEVEN_MS.length, 0.5f)), 1200L).ok);
    }

    /** 低置信度的观测不参与拟合：误检的框没有资格影响测速。 */
    @Test
    public void lowScoreSamplesAreIgnored() {
        TrackWindow window = new TrackWindow();
        float[] heights = advancingHeights(UNEVEN_MS, 8f, 2.8f);
        for (int i = 0; i < UNEVEN_MS.length; i++) {
            window.add(UNEVEN_MS[i], heights[i], 0.7f, 0.5f, 0.3f);
        }
        assertFalse(TtcEstimator.fitHeight(window, 1200L).ok);
    }

    /** 越走越远时没有"碰撞时间"这个概念，不能返回一个负数。 */
    @Test
    public void recedingTargetHasNoTtc() {
        float[] heights = advancingHeights(UNEVEN_MS, 4f, -2.8f);   // 负速度 = 远离
        TtcEstimator.Fit fit = TtcEstimator.fitHeight(
                window(UNEVEN_MS, heights, constant(UNEVEN_MS.length, 0.5f)), 1200L);

        assertTrue(fit.ok);
        assertTrue(fit.rate < 0f);
        assertTrue(Float.isNaN(TtcEstimator.ttcSeconds(fit.rate, fit.centroidMs, 1200L)));
    }

    /** 质心在过去、目标仍在靠近，推出的负 TTC 要夹到 0，不能变成"负数秒后撞上"。 */
    @Test
    public void alreadyOverdueTtcIsClampedToZero() {
        float ttc = TtcEstimator.ttcSeconds(2f, 0L, 5000L);   // 1/2 − 5 = −4.5
        assertEquals(0f, ttc, 0f);
    }
}
