package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.protectee.vision.assist.CollisionModel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 运动预测的数学契约。
 *
 * <p>每条期望值都能解析地写出来，所以这里不构造像素、不模拟轨迹，直接验算公式本身：
 * {@code t = Z / (v_ego + λ·Z)}。追踪器测这些量的正确性由 {@code TtcEstimatorTest} 与
 * {@code ObjectTrackerTest} 负责，这一层只负责"拿到量之后怎么外推"。
 */
public class CollisionModelTest {

    private static CollisionModel.EgoSample sample(float meters, float rawRate) {
        return new CollisionModel.EgoSample(meters, rawRate);
    }

    private static List<CollisionModel.EgoSample> samples(float... metersAndRates) {
        List<CollisionModel.EgoSample> list = new ArrayList<>();
        for (int i = 0; i + 1 < metersAndRates.length; i += 2) {
            list.add(sample(metersAndRates[i], metersAndRates[i + 1]));
        }
        return list;
    }

    /** 目标不动：相遇时间就是"走到它跟前要多久"。 */
    @Test
    public void staticObstacleTakesDistanceOverEgoSpeed() {
        assertEquals(2.0f, CollisionModel.timeToMeet(3f, 0f, 1.5f), 1e-4f);
        assertEquals(2.0f, CollisionModel.timeToMeet(3f, Float.NaN, 1.5f), 1e-4f);
    }

    /** 用户站着不动：退化成尺度通道的 1/λ，正是追踪器算出的那个 TTC。 */
    @Test
    public void standingStillReducesToTheScaleRateTtc() {
        assertEquals(2.0f, CollisionModel.timeToMeet(4f, 0.5f, 0f), 1e-4f);
        assertEquals(1f / 0.5f, CollisionModel.timeToMeet(4f, 0.5f, 0f), 1e-6f);
    }

    /** 两边都在动时闭合速度相加，而不是取较大者。 */
    @Test
    public void closingSpeedAddsBothMotions() {
        // v_ego = 1.2，λ·Z = 0.5×4 = 2.0 → 4 / 3.2 = 1.25
        assertEquals(1.25f, CollisionModel.timeToMeet(4f, 0.5f, 1.2f), 1e-4f);
    }

    /** 步速未知时用默认值，且默认值的方向必须是"报得更早"。 */
    @Test
    public void unknownEgoSpeedFallsBackToTheDefaultPace() {
        assertEquals(3f / CollisionModel.DEFAULT_EGO_SPEED_MPS,
                CollisionModel.timeToMeet(3f, 0f, Float.NaN), 1e-4f);
        assertEquals(3f / CollisionModel.DEFAULT_EGO_SPEED_MPS,
                CollisionModel.timeToMeet(3f, 0f, -1f), 1e-4f);
    }

    /** 没有距离就没有时间结论——返回 NaN，调用方据此退回几何判据。 */
    @Test
    public void noDistanceMeansNoTime() {
        assertTrue(Float.isNaN(CollisionModel.timeToMeet(Float.NaN, 0.3f, 1.2f)));
        assertTrue(Float.isNaN(CollisionModel.timeToMeet(0f, 0.3f, 1.2f)));
        assertTrue(Float.isNaN(CollisionModel.timeToMeet(-2f, 0.3f, 1.2f)));
    }

    /** 谁都不动：不会相遇，返回无穷大而不是一个巨大的秒数。 */
    @Test
    public void nothingMovingMeansNever() {
        assertEquals(Float.POSITIVE_INFINITY, CollisionModel.timeToMeet(3f, 0f, 0f), 0f);
        assertEquals(Float.POSITIVE_INFINITY, CollisionModel.timeToMeet(3f, -0.4f, 0f), 0f);
    }

    /**
     * 步速用中位数估计：迎面走来的人会把均值拽高，中位数不会。
     *
     * <p>{@code λ·Z} 对静止目标恒等于 {@code v_ego}，与距离、类别都无关——这正是它可以
     * 取中位数的原因（同一个论证见 {@code EgoMotionEstimator}）。
     */
    @Test
    public void egoSpeedIsTheMedianOfStaticTrackReadings() {
        // 两个静止目标（1.3 m/s），一个迎面走来的人（把读数抬到 3.6）。
        float speed = CollisionModel.estimateEgoSpeed(samples(3f, 0.4333f, 8f, 0.1625f, 2f, 1.8f));
        assertEquals(1.3f, speed, 0.02f);
    }

    /** 读数不够就不猜：交给调用方用默认步速。 */
    @Test
    public void egoSpeedNeedsAtLeastTwoSamples() {
        assertTrue(Float.isNaN(CollisionModel.estimateEgoSpeed(samples(3f, 0.5f))));
        assertTrue(Float.isNaN(CollisionModel.estimateEgoSpeed(new ArrayList<>())));
        // 无效读数不算样本。
        assertTrue(Float.isNaN(CollisionModel.estimateEgoSpeed(
                samples(3f, Float.NaN, 5f, -0.2f))));
    }

    /** 补一个上限：超过它只可能是关联错了。 */
    @Test
    public void egoSpeedIsCapped() {
        assertEquals(CollisionModel.MAX_EGO_SPEED_MPS,
                CollisionModel.estimateEgoSpeed(samples(3f, 5f, 4f, 5f)), 1e-4f);
    }

    /** 正前方的静止目标挡着路。 */
    @Test
    public void staticObstacleInFrontSweepsTheLine() {
        assertTrue(CollisionModel.sweepsWalkingLine(0.5f, 0.05f, Float.NaN, 2f, 1.5f));
        // 偏一点也算：一米半处的人体半宽约 0.16（归一化），还盖得住中线。
        assertTrue(CollisionModel.sweepsWalkingLine(0.38f, 0.05f, Float.NaN, 1f, 1.5f));
    }

    /** 远处的左侧目标：离中线够远，走过去也碰不到。 */
    @Test
    public void staticObstacleFarToTheSideDoesNotSweep() {
        assertFalse(CollisionModel.sweepsWalkingLine(0.10f, 0.04f, Float.NaN, 3f, 4f));
        assertFalse(CollisionModel.sweepsWalkingLine(0.90f, 0.04f, Float.NaN, 3f, 4f));
    }

    /** 横穿的目标：即使现在在边上，只要这段时间里会扫过中线就算数。 */
    @Test
    public void crossingTargetSweepsTheLine() {
        assertTrue(CollisionModel.sweepsWalkingLine(0.10f, 0.04f, 0.30f, 2f, 3f));
    }

    /** 正在走开的目标：外推后离中线更远，不算。 */
    @Test
    public void recedingTargetDoesNotSweep() {
        assertFalse(CollisionModel.sweepsWalkingLine(0.90f, 0.04f, 0.30f, 2f, 3f));
        assertFalse(CollisionModel.sweepsWalkingLine(0.10f, 0.04f, -0.30f, 2f, 3f));
    }

    /** 视野边缘永远不该被当成"挡路"，否则整幅画面都算正前方。 */
    @Test
    public void pathMarginIsBounded() {
        assertEquals(CollisionModel.MAX_PATH_MARGIN, CollisionModel.pathMargin(Float.NaN), 0f);
        assertEquals(CollisionModel.MAX_PATH_MARGIN, CollisionModel.pathMargin(0.2f), 0f);
        assertEquals(CollisionModel.MIN_PATH_MARGIN, CollisionModel.pathMargin(60f), 0f);
        assertTrue(CollisionModel.pathMargin(1.5f) > CollisionModel.pathMargin(5f));
    }

    /** 播报里的秒数向下取整：宁可说早，不能说晚。 */
    @Test
    public void secondsAreRoundedDown() {
        assertEquals(1, CollisionModel.wholeSeconds(1.9f));
        assertEquals(2, CollisionModel.wholeSeconds(2.5f));
        assertEquals(1, CollisionModel.wholeSeconds(0.3f));
        assertEquals(1, CollisionModel.wholeSeconds(Float.NaN));
        assertEquals(9, CollisionModel.wholeSeconds(40f));
    }

    /** 6 秒是"暂时不会撞到"的分界；NaN 与无穷大都不在范围内。 */
    @Test
    public void horizonSeparatesNowFromLater() {
        assertTrue(CollisionModel.withinHorizon(1.2f));
        assertTrue(CollisionModel.withinHorizon(CollisionModel.HORIZON_S));
        assertFalse(CollisionModel.withinHorizon(CollisionModel.HORIZON_S + 0.01f));
        assertFalse(CollisionModel.withinHorizon(Float.NaN));
        assertFalse(CollisionModel.withinHorizon(Float.POSITIVE_INFINITY));
    }
}
