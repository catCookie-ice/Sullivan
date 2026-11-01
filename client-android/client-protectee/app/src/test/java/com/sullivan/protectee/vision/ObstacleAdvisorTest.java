package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.protectee.vision.assist.ObstacleAdvisor;
import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.detect.Detection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ObstacleAdvisorTest {

    private final ObstacleAdvisor advisor = new ObstacleAdvisor();

    private static Detection detection(String label, float left, float top,
                                       float right, float bottom) {
        int classId = Arrays.asList(CocoLabels.COCO_80).indexOf(label);
        return new Detection(classId, label, 0.9f, left, top, right, bottom);
    }

    @Test
    public void clearSceneWhenNothingDetected() {
        ObstacleAdvisor.Advice advice = advisor.advise(Collections.emptyList());
        assertEquals("前方暂未检测到明显障碍物", advice.message);
        assertEquals(ObstacleAdvisor.Level.CLEAR, advice.level);
        assertEquals(1f, advice.freeRatio, 0.0001f);
    }

    /** 画面顶部 38% 以内的远处目标不参与提示。 */
    @Test
    public void farAwayObjectsAreIgnored() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("person", 0.2f, 0.10f, 0.4f, 0.30f)));
        assertTrue(advice.obstacles.isEmpty());
        assertEquals(ObstacleAdvisor.Level.CLEAR, advice.level);
    }

    @Test
    public void nearCenteredPedestrianIsDanger() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("person", 0.40f, 0.45f, 0.60f, 0.95f)));
        assertEquals(1, advice.obstacles.size());
        assertEquals("中央行人较近，注意", advice.message);
        assertEquals(ObstacleAdvisor.Level.DANGER, advice.level);
    }

    @Test
    public void leftSideVehicleIsCaution() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("car", 0.05f, 0.45f, 0.30f, 0.62f)));
        assertEquals(1, advice.obstacles.size());
        assertEquals("左侧汽车，请注意", advice.message);
        assertEquals(ObstacleAdvisor.Level.CAUTION, advice.level);
    }

    @Test
    public void rightSideVehicleIsReportedAsRight() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("car", 0.72f, 0.45f, 0.95f, 0.62f)));
        assertEquals("右侧汽车，请注意", advice.message);
    }

    @Test
    public void nearestObstacleComesFirstAndOnlyTwoAreReported() {
        List<Detection> detections = new ArrayList<>();
        // 故意按"由远到近"的顺序传入，验证内部会重新排序。
        detections.add(detection("car", 0.05f, 0.42f, 0.25f, 0.55f));
        detections.add(detection("person", 0.40f, 0.55f, 0.60f, 0.99f));
        detections.add(detection("bicycle", 0.75f, 0.45f, 0.95f, 0.70f));

        ObstacleAdvisor.Advice advice = advisor.advise(detections);
        assertEquals(3, advice.obstacles.size());
        assertEquals("person", advice.obstacles.get(0).label);
        // 只播报最近的两个。
        assertEquals("中央行人较近，注意；右侧自行车，请注意", advice.message);
    }

    /**
     * 频率档位里的"一句话报几簇"真的能压到一簇。
     *
     * <p>代价是**明摆着的**：右侧的自行车被省掉了，用户只听到最近的中央行人。这是用户自己
     * 选的档位（默认档仍然报两簇），用例把它钉住是为了以后有人想"顺手改成 1"时能看见代价。
     */
    @Test
    public void maxReportedCapsHowManyClustersOneSentenceNames() {
        List<Detection> detections = new ArrayList<>();
        detections.add(detection("person", 0.40f, 0.55f, 0.60f, 0.99f));
        detections.add(detection("bicycle", 0.75f, 0.45f, 0.95f, 0.70f));

        ObstacleAdvisor.Advice quiet = advisor.advise(detections, 1);
        assertEquals("中央行人较近，注意", quiet.message);
        assertEquals("去重键里也只剩一簇", "static:中央:person:1:true:d", quiet.dedupKey);
        assertEquals("障碍物清单不受影响，只是不报", 2, quiet.obstacles.size());

        assertEquals("中央行人较近，注意；右侧自行车，请注意", advisor.advise(detections, 2).message);
    }

    /** 0 或负数也不能变成"一句话都不说"：画面里确实有东西，报最近的总比沉默强。 */
    @Test
    public void maxReportedNeverSilencesTheNearestCluster() {
        ObstacleAdvisor.Advice advice = advisor.advise(
                Collections.singletonList(detection("person", 0.40f, 0.55f, 0.60f, 0.99f)), 0);
        assertEquals("中央行人较近，注意", advice.message);
    }

    @Test
    public void nonObstacleClassesAreDropped() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("banana", 0.4f, 0.5f, 0.6f, 0.9f)));
        assertTrue(advice.obstacles.isEmpty());
    }

    /** 贴着画面左右边缘的目标不在行进方向上，不参与提示。 */
    @Test
    public void objectsAtFrameEdgesAreIgnored() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("car", 0.97f, 0.45f, 1.0f, 0.62f)));
        assertTrue(advice.obstacles.isEmpty());
    }

    /** 又小又偏上的目标视为噪声。 */
    @Test
    public void tinyHighObjectsAreIgnored() {
        ObstacleAdvisor.Advice advice =
                advisor.advise(Collections.singletonList(detection("car", 0.5f, 0.40f, 0.53f, 0.45f)));
        assertTrue(advice.obstacles.isEmpty());
    }

    @Test
    public void positionOfSplitsFrameIntothreeBands() {
        assertEquals("左侧", ObstacleAdvisor.positionOf(detection("car", 0.00f, 0.5f, 0.30f, 0.7f)));
        assertEquals("中央", ObstacleAdvisor.positionOf(detection("car", 0.40f, 0.5f, 0.60f, 0.7f)));
        assertEquals("右侧", ObstacleAdvisor.positionOf(detection("car", 0.70f, 0.5f, 0.98f, 0.7f)));
    }

    @Test
    public void isNearUsesBottomEdgeOrArea() {
        assertTrue(ObstacleAdvisor.isNear(detection("car", 0.4f, 0.6f, 0.6f, 0.85f)));
        assertTrue(ObstacleAdvisor.isNear(detection("car", 0.1f, 0.4f, 0.9f, 0.7f)));
        assertFalse(ObstacleAdvisor.isNear(detection("car", 0.4f, 0.40f, 0.5f, 0.55f)));
    }

    /** 中央走廊被大框挡住时，通路占比应该明显下降。 */
    @Test
    public void freeRatioDropsWhenCorridorIsBlocked() {
        ObstacleAdvisor.Advice open =
                advisor.advise(Collections.singletonList(detection("car", 0.05f, 0.42f, 0.15f, 0.50f)));
        ObstacleAdvisor.Advice blocked =
                advisor.advise(Collections.singletonList(detection("car", 0.10f, 0.50f, 0.90f, 1.00f)));

        assertEquals(1f, open.freeRatio, 0.0001f);
        assertEquals(0f, blocked.freeRatio, 0.0001f);
        assertEquals(ObstacleAdvisor.Level.DANGER, blocked.level);
    }

    // ------------------------------------------------------------------
    // 运动预测：只报"可能撞上"的，并按碰上的先后排序
    // ------------------------------------------------------------------

    /** 造一条带运动量的障碍：框沿底边向上 `height` 高。 */
    private static ObstacleAdvisor.Obstacle tracked(String label, float centerX, float bottom,
                                                    float height, float meters, float ttc,
                                                    float relativeRate, float lateralRate) {
        float halfWidth = Math.min(height * 0.25f, 0.08f);
        Detection box = detection(label, centerX - halfWidth, bottom - height,
                centerX + halfWidth, bottom);
        return new ObstacleAdvisor.Obstacle(box, meters, ttc, relativeRate, lateralRate, 7);
    }

    /**
     * 九米外的东西不会在六秒内被撞上，所以不说它。
     *
     * <p>这是改造前后最大的行为差别：以前只要框落在画面下部就会念一句"请注意"，站在
     * 人行道上每秒钟都有人在九米外经过。
     */
    @Test
    public void obstaclesBeyondTheHorizonAreNotAnnounced() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(
                Collections.singletonList(tracked("person", 0.5f, 0.90f, 0.25f,
                        9f, Float.NaN, 0f, Float.NaN)), 2, 1.2f);

        assertEquals("前方有目标，暂时不会撞上", advice.message);
        assertEquals(ObstacleAdvisor.Level.CLEAR, advice.level);
        assertEquals("清单里仍然留着它，只是不报", 1, advice.obstacles.size());
        assertEquals(7.5f, advice.timeToImpact, 0.05f);
    }

    /** 三米外正前方：报，而且带秒数。 */
    @Test
    public void obstacleInsideTheHorizonIsAnnouncedWithSeconds() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(
                Collections.singletonList(tracked("person", 0.5f, 0.90f, 0.25f,
                        3f, Float.NaN, 0f, Float.NaN)), 2, 1.2f);

        assertEquals("中央行人，2秒", advice.message);
        assertEquals(ObstacleAdvisor.Level.CAUTION, advice.level);
        assertEquals(2.5f, advice.timeToImpact, 0.05f);
    }

    /** 一秒半以内：压成"停"字句，因为句子必须比它描述的时间短。 */
    @Test
    public void imminentObstacleGetsTheStopSentence() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(
                Collections.singletonList(tracked("person", 0.5f, 0.90f, 0.25f,
                        1.5f, Float.NaN, 0f, Float.NaN)), 2, 1.2f);

        assertEquals("停，中央行人", advice.message);
        assertEquals(ObstacleAdvisor.Level.DANGER, advice.level);
    }

    /**
     * 一秒半以内的一群人：句子去掉数量。
     *
     * <p>"停，中央两名行人"要念 1.3 秒，而危险只剩 1.2 秒——数量在这个时间尺度上
     * 不改变该做什么。去重键里仍然带着人数，所以"一个人变两个人"这件事照样会被说出来。
     */
    @Test
    public void imminentCrowdUsesTheShortestSentence() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(Arrays.asList(
                        tracked("person", 0.44f, 0.90f, 0.25f, 1.4f, Float.NaN, 0f, Float.NaN),
                        tracked("person", 0.56f, 0.90f, 0.25f, 1.5f, Float.NaN, 0f, Float.NaN)),
                2, 1.2f);

        assertEquals("停，中央行人", advice.message);
        assertEquals(ObstacleAdvisor.Level.DANGER, advice.level);
        assertTrue("去重键里仍要能看出人数：" + advice.dedupKey,
                advice.dedupKey.contains(":2:"));
    }

    /**
     * 两条都要报时，**更早撞上的先说**，而不是更近的先说。     *
     * <p>右侧那辆车在六米外、五秒后才会碰上；正前方的人在一点五米、一秒后就到。距离排序会
     * 把车放在前面，那正是这次要修的东西。
     */
    @Test
    public void threatsAreOrderedByTimeToImpactNotByDistance() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(Arrays.asList(
                        tracked("car", 0.75f, 0.70f, 0.20f, 6f, Float.NaN, 0f, -0.05f),
                        tracked("person", 0.5f, 0.90f, 0.25f, 1.5f, Float.NaN, 0f, Float.NaN)),
                2, 1.2f);

        assertEquals("停，中央行人；右侧汽车，5秒", advice.message);
        assertEquals(1.25f, advice.timeToImpact, 0.05f);
    }

    /** 会从旁边过去的目标不报：它扫不到用户的行走线。 */
    @Test
    public void crossingTargetThatWillPassByIsNotAnnounced() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(
                Collections.singletonList(tracked("person", 0.85f, 0.90f, 0.25f,
                        2f, Float.NaN, 0f, 0.5f)), 2, 1.2f);

        assertEquals("前方有目标，暂时不会撞上", advice.message);
    }

    /** 没有深度图时退回尺度通道：TTC 也能给出秒数。 */
    @Test
    public void scaleChannelStillProvidesSecondsWithoutDepth() {
        ObstacleAdvisor.Advice advice = advisor.adviseTracked(
                Collections.singletonList(tracked("person", 0.5f, 0.90f, 0.25f,
                        Float.NaN, 2.0f, 0.5f, Float.NaN)), 2, 1.2f);

        assertEquals("中央行人，2秒", advice.message);
        assertEquals(2.0f, advice.timeToImpact, 0.001f);
    }

    /**
     * 低置信度框只有在贴近地面时才留下。
     *
     * <p>检测阈值从 0.35 降到 0.28 是为了小障碍，但噪声也是这么进来的。这道闸把"虚"
     * 和"近"绑在一起：远处（框靠上）的低分框仍然丢掉。
     */
    @Test
    public void lowScoreDetectionsMustBeNearTheGround() {
        Detection lowButHigh = new Detection(0, "person", 0.30f, 0.46f, 0.45f, 0.54f, 0.55f);
        Detection lowAndLow = new Detection(0, "person", 0.30f, 0.46f, 0.70f, 0.54f, 0.95f);

        assertEquals("靠上的低分框仍然丢掉", 0,
                advisor.advise(Collections.singletonList(lowButHigh)).obstacles.size());
        assertEquals("贴近地面的低分框保留", 1,
                advisor.advise(Collections.singletonList(lowAndLow)).obstacles.size());
    }

    /**
     * 又小又靠下的目标不再被当成噪声。
     *
     * <p>改造前的判据是"面积 &lt; 0.0015 且底边 &lt; 0.60"。一个 15 厘米高的路锥在
     * 1.5 米处只有约 0.04 的高度，面积远小于阈值，于是被当噪声扔掉——而它恰恰最容易
     * 绊倒人。新判据改看高度，并且把"靠下"的门限收到 0.55。
     */
    @Test
    public void smallObstacleNearTheGroundIsNoLongerNoise() {
        Detection smallLow = new Detection(0, "person", 0.9f, 0.48f, 0.53f, 0.52f, 0.575f);
        assertEquals(1, advisor.advise(Collections.singletonList(smallLow)).obstacles.size());
    }
}
