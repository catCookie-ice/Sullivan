package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.track.EgoMotionEstimator;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 自运动补偿的契约。
 *
 * <p>合成模型：{@code r = v_ego/Z}、{@code h = K·H/Z}，于是
 * {@code w = r·H/h = v_ego/K} ——**与距离、与类别都无关**，这才是能取中位数的量。
 *
 * <p>这里固化了复核时发现的错误做法，防止以后有人"简化"回去。
 */
public class EgoMotionEstimatorTest {

    /** 视场系数（像素焦距 / 画面高度）。 */
    private static final float K = 0.6f;
    /** 用户自己的步行速度。 */
    private static final float V_EGO = 1.4f;

    /** 一个静止目标的读数：距离 z、高度 h 米。 */
    private static EgoMotionEstimator.Sample still(float z, float heightMeters) {
        return new EgoMotionEstimator.Sample(V_EGO / z, K * heightMeters / z, heightMeters);
    }

    /**
     * `w = r·H/h` 对所有静止目标恒等——这是整个补偿方案的立足点。
     */
    @Test
    public void velocityProxyIsEqualForEveryStaticObject() {
        List<EgoMotionEstimator.Sample> samples = Arrays.asList(
                still(3f, 1.70f),
                still(15f, 1.50f),
                still(25f, 3.30f));

        for (EgoMotionEstimator.Sample sample : samples) {
            float proxy = sample.rate * sample.referenceHeightMeters / sample.height;
            assertEquals("静止目标的 w 必须与距离、类别无关", V_EGO / K, proxy, 1e-4f);
        }
        assertEquals(V_EGO / K, EgoMotionEstimator.estimateVelocityProxy(samples), 1e-3f);
    }

    /**
     * 反例：若改成对**增长率**取中位数，一个站着不动的行人会被算成"2.68 秒后撞上"。
     *
     * <p>三个静止目标的 r 分别是 0.467 / 0.093 / 0.056，中位数 0.093 是"中位数距离处的
     * 自运动率"，没有物理意义；减掉之后近处那个人还剩 0.373。
     */
    @Test
    public void medianOfRatesWouldFabricateAFalseAlert() {
        List<EgoMotionEstimator.Sample> samples = Arrays.asList(
                still(3f, 1.70f),
                still(15f, 1.50f),
                still(25f, 3.30f));

        float[] rates = new float[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            rates[i] = samples.get(i).rate;
        }
        Arrays.sort(rates);
        float medianRate = rates[rates.length / 2];
        assertEquals("中位数落在中间那个距离上", V_EGO / 15f, medianRate, 1e-4f);

        float nearPersonRate = still(3f, 1.70f).rate - medianRate;
        float falseTtc = 1f / nearPersonRate;
        assertEquals("这就是那个假告警", 2.68f, falseTtc, 0.05f);

        // 正确做法：减掉的是同一个 w·h/H，三者补偿后都归零。
        float proxy = EgoMotionEstimator.estimateVelocityProxy(samples);
        for (EgoMotionEstimator.Sample sample : samples) {
            float relative = EgoMotionEstimator.compensatedRate(sample.rate, sample.height,
                    sample.referenceHeightMeters, proxy);
            assertEquals("静止目标补偿后必须没有剩余速度", 0f, relative, 1e-3f);
        }
    }

    /** 轨迹太少时不补偿：两条轨迹里混进一个迎面走来的人，中位数就被带跑了。 */
    @Test
    public void tooFewTracksMeansNoCompensation() {
        assertTrue(Float.isNaN(EgoMotionEstimator.estimateVelocityProxy(
                Arrays.asList(still(3f, 1.7f), still(15f, 1.5f)))));
        assertTrue(Float.isNaN(EgoMotionEstimator.estimateVelocityProxy(
                new ArrayList<EgoMotionEstimator.Sample>())));
    }

    /**
     * 场景一半在动时放弃补偿。
     *
     * <p>构造：用户以 1.4 m/s 前进，画面里两个静止背景（w = v_ego/K）+ 两个**迎面同样
     * 以 1.4 m/s 走来**的人（相对速度为零，w = 0）。中位数落在两者之间，MAD 判断出场景
     * 不一致，于是不补偿——这正是"迎面人群"这个最容易翻车的场景。
     */
    @Test
    public void inconsistentSceneIsRejected() {
        List<EgoMotionEstimator.Sample> samples = Arrays.asList(
                still(3f, 1.70f),
                still(15f, 1.50f),
                walkingTowardMe(4f, 1.70f),
                walkingTowardMe(5f, 1.50f));

        assertTrue("场景不一致时必须放弃补偿，而不是给一个和稀泥的值",
                Float.isNaN(EgoMotionEstimator.estimateVelocityProxy(samples)));
    }

    /** 与用户等速迎面走来的人：相对速度为零，增长率也是零。 */
    private static EgoMotionEstimator.Sample walkingTowardMe(float z, float heightMeters) {
        return new EgoMotionEstimator.Sample(0f, K * heightMeters / z, heightMeters);
    }

    /** 补偿量要封顶：迎面人群会把中位数拉走，宁可少补偿也不能过补偿。 */
    @Test
    public void compensationIsCapped() {
        // 四个"目标"的 w 都在 9 附近，远超走路能产生的量。
        List<EgoMotionEstimator.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(new EgoMotionEstimator.Sample(9f * K, 0.2f, 1f));
        }
        float proxy = EgoMotionEstimator.estimateVelocityProxy(samples);
        assertTrue("超过物理上限就该放弃补偿，而不是给一个很猛的补偿量",
                Float.isNaN(proxy) || Math.abs(proxy) <= EgoMotionEstimator.EGO_CAP);
    }

    /** 没有自运动估计时原样放行，绝不能把增长率变成 NaN。 */
    @Test
    public void missingProxyLeavesRateUntouched() {
        assertEquals(0.37f, EgoMotionEstimator.compensatedRate(0.37f, 0.3f, 1.7f, Float.NaN), 0f);
        assertEquals(0.37f, EgoMotionEstimator.compensatedRate(0.37f, 0f, 1.7f, 1.2f), 0f);
    }

    /** 走路的自运动在近处目标上贡献很大：3 米外的静止行人——这就是不能省掉补偿的原因。 */
    @Test
    public void egoRateMatchesTheDistanceLaw() {
        float proxy = V_EGO / K;
        float z = 3f;
        float height = K * 1.70f / z;
        assertEquals(V_EGO / z, EgoMotionEstimator.egoRate(height, 1.70f, proxy), 1e-4f);
    }
}
