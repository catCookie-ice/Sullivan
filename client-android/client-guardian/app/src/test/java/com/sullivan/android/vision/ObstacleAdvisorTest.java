package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.assist.ObstacleAdvisor;
import com.sullivan.android.vision.detect.CocoLabels;
import com.sullivan.android.vision.detect.Detection;

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
        assertEquals("去重键里也只剩一簇", "static:中央:person:1:true", quiet.dedupKey);
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
}
