package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.assist.AnnounceRate;
import com.sullivan.android.vision.assist.DynamicAdvisor;
import com.sullivan.android.vision.assist.ObstacleAdvisor;
import com.sullivan.android.vision.assist.SceneAnalyzer;
import com.sullivan.android.vision.detect.CocoLabels;
import com.sullivan.android.vision.detect.Detection;
import com.sullivan.android.vision.track.ObjectTracker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 整条管线的端到端行为：喂合成的检测框序列，断言最终**说哪句话**。
 *
 * <p>这是用户那两条抱怨的回归线——"两个相近物体会重复播报"与"希望有动态播报"——
 * 全部在这里以"一句话"的形式被钉住。用合成序列而不是真机视频，是为了让每个用例都能
 * 精确复现：帧率、框的演化、时间戳都写在用例里。
 */
public class SceneAnalyzerTest {

    /** 10 FPS。帧间隔故意取整，但时间戳仍然显式传给管线，不依赖帧序号。 */
    private static final long STEP_MS = 100L;

    /** 画面几何。整个用例里不变；变了的用例单独写。 */
    private static final ObjectTracker.FrameEpoch EPOCH =
            new ObjectTracker.FrameEpoch(720, 1280, 90);

    /** 行人靠近时的尺度增长率（1/s）。约 0.55 对应 1.2 秒左右的碰撞时间。 */
    private static final float APPROACH_RATE = 0.55f;

    private final SceneAnalyzer analyzer = new SceneAnalyzer();

    // ------------------------------------------------------------------
    // 合成检测框
    // ------------------------------------------------------------------

    private static Detection detection(String label, float centerX, float centerY, float height) {
        float halfWidth = height * 0.25f;
        float halfHeight = height * 0.5f;
        int classId = Arrays.asList(CocoLabels.COCO_80).indexOf(label);
        return new Detection(classId, label, 0.9f,
                centerX - halfWidth, centerY - halfHeight,
                centerX + halfWidth, centerY + halfHeight);
    }

    /** 人：窄而高的框。 */
    private static Detection person(float centerX, float centerY, float height) {
        return detection("person", centerX, centerY, height);
    }

    /** 一辆停着的车：静态目标，用来给自运动估计提供"零运动"的基准。 */
    private static Detection parkedCar(float centerX, float centerY, float height) {
        return detection("car", centerX, centerY, height);
    }

    private static Detection parkedBicycle(float centerX, float centerY, float height) {
        return detection("bicycle", centerX, centerY, height);
    }

    /**
     * 一段合成的街景：三个静止目标凑够自运动估计所需的轨迹数，外加一个可选的行人。
     *
     * @param includeApproacher 是否把那个匀速走近的行人放进来；false 表示"他走出了画面"
     */
    private static List<Detection> scene(long nowMs, boolean includeApproacher) {
        List<Detection> detections = new ArrayList<>();
        detections.add(person(0.10f, 0.60f, 0.20f));
        detections.add(parkedCar(0.90f, 0.60f, 0.20f));
        detections.add(parkedBicycle(0.28f, 0.78f, 0.16f));
        if (includeApproacher) {
            // 高度按 e^{rate·t} 增长，正是"匀速走近"在小孔成像下的样子。
            detections.add(person(0.50f, 0.50f,
                    0.20f * (float) Math.exp(APPROACH_RATE * nowMs / 1000f)));
        }
        return detections;
    }

    /** 同一组输入从 0 时刻开始连续喂若干帧，返回最后一帧的决策。 */
    private SceneAnalyzer.Decision feed(List<Detection> detections, int frames) {
        return feed(detections, 0L, frames);
    }

    /**
     * 同上，但可以接在别的片段后面。
     *
     * <p>时间戳必须**连续**：{@code TrackWindow} 会丢掉时间倒流的样本，从 0 重新开始计时
     * 会把后半段整个废掉。
     */
    private SceneAnalyzer.Decision feed(List<Detection> detections, long startMs, int frames) {
        SceneAnalyzer.Decision decision = null;
        for (int i = 0; i < frames; i++) {
            decision = analyzer.analyze(detections, startMs + i * STEP_MS, EPOCH);
        }
        return decision;
    }

    // ------------------------------------------------------------------
    // 静态提示：合并与计数
    // ------------------------------------------------------------------

    /** 第一帧还什么都没确认，不该急着说"前方有人"。 */
    @Test
    public void nothingIsReportedBeforeTheTrackIsConfirmed() {
        SceneAnalyzer.Decision decision = feed(
                Collections.singletonList(person(0.5f, 0.70f, 0.35f)), 1);

        assertEquals("前方暂未检测到明显障碍物", decision.message);
        assertEquals(0, decision.snapshot.tracks.size());
        assertNull(decision.dynamic);
    }

    /**
     * 同一个人被检出两个框：合并成一个目标，**只报一次**。
     *
     * <p>这是用户原话里"两个相近物体会重复播报"最直接的一种：NMS 是类别内的，跨类别
     * 或者刚好没被抑制住的重叠框会各自活下来，然后被念成两遍。
     */
    @Test
    public void twoOverlappingBoxesOfOnePersonAreReportedOnce() {
        List<Detection> detections = new ArrayList<>();
        detections.add(person(0.50f, 0.70f, 0.35f));
        detections.add(person(0.52f, 0.70f, 0.35f));

        SceneAnalyzer.Decision decision = feed(detections, 3);

        assertEquals("两个框应该合成一条轨迹", 1, decision.snapshot.tracks.size());
        assertEquals("中央行人较近，注意", decision.message);
        assertFalse("不该出现两个分句：" + decision.message, decision.message.contains("；"));
    }

    /**
     * 并排站着的两个人：一**句**话里带上数量，而不是把同一句话念两遍。
     *
     * <p>两个人不是同一个目标，合并阶段不会把它们并成一个框——所以计数必须发生在播报
     * 这一层。否则用户听到的是「中央行人较近，注意；中央行人较近，注意」。
     */
    @Test
    public void twoPedestriansSideBySideAreAnnouncedAsTwoInOneSentence() {
        List<Detection> detections = new ArrayList<>();
        detections.add(person(0.42f, 0.70f, 0.35f));
        detections.add(person(0.58f, 0.70f, 0.35f));

        SceneAnalyzer.Decision decision = feed(detections, 3);

        assertEquals(2, decision.snapshot.tracks.size());
        assertEquals("中央两名行人，注意", decision.message);
        assertEquals(ObstacleAdvisor.Level.DANGER, decision.level);
    }

    /**
     * 第二个人走进画面时，去重键要跟着变，而且新来的人也得先被确认两帧才算数。
     *
     * <p>去重键变了，这句话才不会被节流当成"刚说过"而静默吞掉：一个人走成两个人，
     * 用户需要听到新的那句。
     */
    @Test
    public void theDedupKeyChangesWhenASecondPedestrianJoins() {
        SceneAnalyzer.Decision one = feed(
                Collections.singletonList(person(0.42f, 0.70f, 0.35f)), 3);
        assertEquals("static:中央:person:1:true", one.dedupKey);

        List<Detection> both = new ArrayList<>();
        both.add(person(0.42f, 0.70f, 0.35f));
        both.add(person(0.58f, 0.70f, 0.35f));

        // 只出现一帧的新目标还不算数——那是"宁可不报，也不报错"的那道门。
        SceneAnalyzer.Decision firstFrame = feed(both, 3 * STEP_MS, 1);
        assertEquals("static:中央:person:1:true", firstFrame.dedupKey);

        SceneAnalyzer.Decision settled = feed(both, 4 * STEP_MS, 1);
        assertEquals("static:中央:person:2:true", settled.dedupKey);
        assertEquals("中央两名行人，注意", settled.message);
    }

    // ------------------------------------------------------------------
    // 播报频率档位
    // ------------------------------------------------------------------

    /**
     * 「少」档下同一帧里的两簇只报最近的那一簇。
     *
     * <p>这是用户在界面上按一下按钮就能改变的行为，所以端到端钉住：档位必须真的一路走到
     * {@link com.sullivan.android.vision.assist.ObstacleAdvisor} 里，而不是停在
     * {@code AnnouncementPolicy} 的节流参数上。
     */
    @Test
    public void quietRateReportsOnlyTheNearestCluster() {
        List<Detection> detections = new ArrayList<>();
        detections.add(person(0.50f, 0.70f, 0.35f));
        detections.add(parkedCar(0.85f, 0.60f, 0.20f));

        SceneAnalyzer.Decision standard = feed(detections, 3);
        assertEquals("中央行人较近，注意；右侧汽车，请注意", standard.message);

        analyzer.setRate(AnnounceRate.QUIET);
        SceneAnalyzer.Decision quiet = feed(detections, 4 * STEP_MS, 1);
        assertEquals("中央行人较近，注意", quiet.message);
        assertEquals("static:中央:person:1:true", quiet.dedupKey);
        assertEquals("只压缩了措辞，障碍物清单仍然完整", 2, quiet.advice.obstacles.size());
    }

    // ------------------------------------------------------------------
    // 动态告警
    // ------------------------------------------------------------------

    /**
     * 持续放大的行人轨迹触发"接近"告警——用户要的那个功能。
     *
     * <p>画面里另外三个静止目标是有意加上的：自运动补偿要求至少三条轨迹才能估出
     * 场景运动，少于这个数就只能给 CAUTION（见 {@link DynamicAdvisor}）。静止目标的
     * 尺度增长率为 0，于是估出的自运动速度也是 0，补偿不会削掉靠近速度。
     */
    @Test
    public void approachingPedestrianRaisesADangerAlert() {
        SceneAnalyzer.Decision decision = null;
        for (int frame = 0; frame < 24; frame++) {
            long nowMs = frame * STEP_MS;
            decision = analyzer.analyze(scene(nowMs, true), nowMs, EPOCH);
        }

        assertNotNull(decision);
        assertNotNull("持续放大的轨迹必须触发动态告警", decision.dynamic);
        assertEquals(DynamicAdvisor.Motion.APPROACHING, decision.dynamic.motion);
        assertEquals(ObstacleAdvisor.Level.DANGER, decision.dynamic.level);
        assertEquals("行人接近", decision.message);
        assertTrue("动态告警自带滞回，不必再等稳定窗口", decision.hysteretic);
        assertTrue("动态告警的去重键：" + decision.dedupKey, decision.dedupKey.startsWith("dyn:"));
        assertTrue(decision.dynamic.ttc < DynamicAdvisor.TTC_DANGER_ENTER_S);
    }

    /**
     * 走近的行人又走出了画面：动态告警必须跟着停，不能残留。
     *
     * <p>剩余碰撞时间的表达式里有一项"距拟合质心过去了多久"。轨迹在 500 ms 的宽限期内
     * 还活着（被柱子挡一下就该继续用），如果那期间不清掉它的读数，质心不动而墙钟在走，
     * 剩余时间就会自己往下掉——人已经走出画面，语音却还在报「行人接近」。
     */
    @Test
    public void anApproachingPedestrianWhoLeavesStopsTheAlert() {
        SceneAnalyzer.Decision during = null;
        for (int frame = 0; frame < 16; frame++) {
            long nowMs = frame * STEP_MS;
            during = analyzer.analyze(scene(nowMs, true), nowMs, EPOCH);
        }
        assertNotNull("前置条件：走近的过程中应当报过警", during.dynamic);
        assertEquals(DynamicAdvisor.Motion.APPROACHING, during.dynamic.motion);

        SceneAnalyzer.Decision after = null;
        for (int frame = 16; frame < 20; frame++) {
            long nowMs = frame * STEP_MS;
            after = analyzer.analyze(scene(nowMs, false), nowMs, EPOCH);
        }
        assertNull("人已经走出画面，不该再报「正在靠近」", after.dynamic);
    }

    /**
     * 站着不动的场景不产生任何动态告警。
     *
     * <p>这是自运动补偿的验收点，也是最容易出问题的一条：走路时相机前进，所有目标的框
     * 都会变大，不补偿就会一路喊"有东西在靠近"。
     */
    @Test
    public void staticSceneNeverRaisesADynamicAlert() {
        List<Detection> detections = new ArrayList<>();
        detections.add(person(0.15f, 0.60f, 0.20f));
        detections.add(parkedCar(0.85f, 0.60f, 0.20f));
        detections.add(parkedBicycle(0.50f, 0.45f, 0.14f));
        detections.add(person(0.35f, 0.40f, 0.12f));

        SceneAnalyzer.Decision decision = feed(detections, 25);

        assertNull("静止目标不该有「正在靠近」的结论", decision.dynamic);
        assertTrue(decision.snapshot.egoVelocityProxy == 0f
                || Float.isNaN(decision.snapshot.egoVelocityProxy));
        assertFalse(decision.hysteretic);
    }

    /**
     * 同一帧里既有静止目标又有横穿目标时，**只有横向通道**能看见横穿。
     *
     * <p>横穿行人的高度恒定，尺度通道算出的增长率是 0，TTC 为 NaN——它必须靠
     * {@code centerX} 的那条拟合接住。
     */
    @Test
    public void crossingPedestrianIsCaughtByTheLateralChannel() {
        SceneAnalyzer.Decision decision = null;
        for (int frame = 0; frame < 20; frame++) {
            long nowMs = frame * STEP_MS;
            List<Detection> detections = new ArrayList<>();
            detections.add(person(0.10f, 0.60f, 0.20f));
            detections.add(parkedCar(0.88f, 0.60f, 0.20f));
            // 从左往右走：高度不变，中心横坐标匀速增加 0.14/秒。
            detections.add(person(0.22f + 0.14f * nowMs / 1000f, 0.70f, 0.22f));
            decision = analyzer.analyze(detections, nowMs, EPOCH);
        }

        assertNotNull(decision);
        assertNotNull("横穿不能只靠尺度通道", decision.dynamic);
        assertEquals(DynamicAdvisor.Motion.CROSSING, decision.dynamic.motion);
        assertEquals("行人横穿", decision.message);
    }

    // ------------------------------------------------------------------
    // 状态重置
    // ------------------------------------------------------------------

    /** 画面几何一变，旧轨迹必须整体作废：横竖屏切换后归一化坐标的含义完全不同。 */
    @Test
    public void geometryChangeDiscardsAllTracks() {
        List<Detection> detections = Collections.singletonList(person(0.5f, 0.70f, 0.35f));
        SceneAnalyzer.Decision before = feed(detections, 3);
        assertEquals(1, before.snapshot.tracks.size());

        ObjectTracker.FrameEpoch rotated = new ObjectTracker.FrameEpoch(1280, 720, 0);
        SceneAnalyzer.Decision after = analyzer.analyze(detections, 3 * STEP_MS, rotated);

        assertEquals("换了几何特征之后要从头建轨", 0, after.snapshot.tracks.size());
    }

    /** 换模型时重置：旧轨迹的坐标语义已经不可信。 */
    @Test
    public void resetClearsEveryCrossFrameState() {
        feed(Collections.singletonList(person(0.5f, 0.70f, 0.35f)), 3);
        analyzer.reset();

        SceneAnalyzer.Decision decision = analyzer.analyze(
                Collections.singletonList(person(0.5f, 0.70f, 0.35f)), 400L, EPOCH);

        assertEquals(0, decision.snapshot.tracks.size());
        assertEquals("前方暂未检测到明显障碍物", decision.message);
    }
}
