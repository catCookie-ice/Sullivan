package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.detect.Detection;
import com.sullivan.android.vision.track.ObjectTracker;
import com.sullivan.android.vision.track.Track;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * 关联与生命周期的契约。用的是合成的小孔成像几何，所以每个数字都能手算复核。
 *
 * <p>投影模型：归一化高度 {@code h = K·H/Z}，底边 {@code = 0.5 + K·相机高/Z}（0.5 是地平线）。
 * {@code K = 0.6} 相当于一个较广的视场。行人身高取 1.70 m、宽 0.5 m。
 */
public class ObjectTrackerTest {

    /** 竖直方向的焦距系数（像素焦距 / 画面高度），决定视场大小。 */
    private static final float K = 0.6f;
    private static final float CAMERA_HEIGHT_M = 1.5f;
    private static final float HORIZON = 0.5f;
    private static final float PERSON_HEIGHT_M = 1.70f;
    private static final float PERSON_WIDTH_M = 0.5f;

    private static final int PERSON_CLASS = 0;

    private static final ObjectTracker.FrameEpoch EPOCH =
            new ObjectTracker.FrameEpoch(720, 1280, 0);

    private ObjectTracker tracker;

    @Before
    public void setUp() {
        tracker = new ObjectTracker();
    }

    /** 距离 {@code z} 米、横向偏离光轴 {@code lateralX} 米的行人。 */
    private static Detection person(float z, float lateralX) {
        float height = K * PERSON_HEIGHT_M / z;
        float bottom = HORIZON + K * CAMERA_HEIGHT_M / z;
        float width = K * PERSON_WIDTH_M / z;
        float centerX = HORIZON + K * lateralX / z;
        return new Detection(PERSON_CLASS, "person", 0.9f,
                centerX - width * 0.5f, bottom - height, centerX + width * 0.5f, bottom);
    }

    /** 框宽仅为 {@code width}、其余同上的行人——用来单独验证 IoU 门限的算术。 */
    private static Detection boxWithWidth(float width) {
        return new Detection(PERSON_CLASS, "person", 0.9f, 0f, 0.4f, width, 0.8f);
    }

    private static List<Detection> one(Detection detection) {
        return Collections.singletonList(detection);
    }

    /**
     * IoU 门限必须低于横穿行人在最低帧率下的 IoU。
     *
     * <p>这里把"0.282"这个数固化下来：它只取决于一帧内走了几个框宽，与距离无关。
     * 谁要是觉得 0.20 "太松"想改成 0.3，这个用例会先红。
     */
    @Test
    public void crossingPedestrianIouAtFiveFpsStaysAboveTheGate() {
        // 1.4 m/s、5 FPS（200 ms）→ 0.28 m；行人宽 0.5 m ⇒ 位移 0.56 个框宽。
        float displacement = 0.56f;
        Detection first = boxWithWidth(1f);
        Detection second = new Detection(PERSON_CLASS, "person", 0.9f,
                displacement, 0.4f, 1f + displacement, 0.8f);

        // 两个同样大的框横向错开 d 个框宽 ⇒ IoU = (1−d)/(1+d)。
        float iou = first.iou(second);
        assertEquals(0.282f, iou, 0.002f);
        assertTrue("门限不能高于横穿行人的实际 IoU，否则 5 FPS 时每帧都会丢 ID",
                ObjectTracker.IOU_GATE < iou);
        assertTrue("门限也不该松到把无关目标并起来", ObjectTracker.IOU_GATE >= 0.15f);
    }

    /** 横穿的行人在 5 FPS 下必须一直是同一条轨迹。 */
    @Test
    public void crossingPedestrianKeepsItsIdAtFiveFps() {
        float z = 4f;
        int id = -1;
        for (int frame = 0; frame < 8; frame++) {
            long nowMs = frame * 200L;
            // 1.4 m/s 的横向速度。
            Detection detection = person(z, 1.4f * frame * 0.2f);
            List<Track> tracks = tracker.update(one(detection), nowMs, EPOCH);
            if (frame == 0) {
                assertTrue("第一帧还不该确认，避免把单帧误检播出去", tracks.isEmpty());
                continue;
            }
            assertEquals("第 " + frame + " 帧应该还是同一条轨迹", 1, tracks.size());
            if (id < 0) {
                id = tracks.get(0).id;
            }
            assertEquals(id, tracks.get(0).id);
            assertEquals(frame + 1, tracks.get(0).hits);
            assertFalse("横穿时尺度不变，不该被判成遮挡", tracks.get(0).occluded);
            // 样本持续攒着；超过 1.2 秒窗口的老样本会被丢掉（第 7 帧起最老的那帧出窗）。
            assertEquals(Math.min(frame + 1, 7), tracks.get(0).window.size());
        }
    }

    /** 并排站着的两个行人各自成轨迹，不能被关联到一起。 */
    @Test
    public void twoPedestriansStandingApartKeepSeparateIds() {
        List<Detection> pair = java.util.Arrays.asList(person(4f, -0.5f), person(4f, 0.5f));
        tracker.update(pair, 0L, EPOCH);
        List<Track> tracks = tracker.update(pair, 200L, EPOCH);

        assertEquals(2, tracks.size());
        assertNotEquals(tracks.get(0).id, tracks.get(1).id);
        for (Track track : tracks) {
            assertEquals(2, track.hits);
        }
    }

    /** 轨迹按**时间**老化：500 ms 内丢一两帧不删，超过就删。 */
    @Test
    public void tracksExpireByTimeNotByFrameCount() {
        tracker.update(one(person(4f, 0f)), 0L, EPOCH);
        List<Track> tracks = tracker.update(one(person(4f, 0f)), 200L, EPOCH);
        assertEquals(1, tracks.size());

        // 丢了 400 ms：轨迹还在，但本帧没有匹配上。
        List<Track> stillAlive = tracker.update(Collections.<Detection>emptyList(), 600L, EPOCH);
        assertEquals("500 ms 以内的丢帧不该删轨迹", 1, stillAlive.size());
        assertFalse(stillAlive.get(0).matchedThisFrame);
        assertEquals(1, stillAlive.get(0).misses);

        // 累计丢了 600 ms：超过上限，删。
        List<Track> expired = tracker.update(Collections.<Detection>emptyList(), 800L, EPOCH);
        assertTrue(expired.isEmpty());
        assertEquals(0, tracker.trackCount());
    }

    /** 横竖屏切换后归一化坐标含义全变，旧轨迹必须作废。 */
    @Test
    public void epochChangeResetsEveryTrack() {
        tracker.update(one(person(4f, 0f)), 0L, EPOCH);
        List<Track> tracks = tracker.update(one(person(4f, 0f)), 200L, EPOCH);
        int oldId = tracks.get(0).id;

        ObjectTracker.FrameEpoch rotated =
                new ObjectTracker.FrameEpoch(1280, 720, 90);
        List<Track> afterRotation = tracker.update(one(person(4f, 0f)), 400L, rotated);

        assertTrue("转屏后旧轨迹不能再被确认", afterRotation.isEmpty());
        assertEquals("只应剩下刚建的那一条", 1, tracker.trackCount());

        // 新轨迹拿到的是新号：去重键里编了轨迹号，号重复会让新目标被当成"刚说过"。
        List<Track> confirmed = tracker.update(one(person(4f, 0f)), 600L, rotated);
        assertEquals(1, confirmed.size());
        assertTrue(confirmed.get(0).id > oldId);
    }

    /** 分辨率变化同样要重置。 */
    @Test
    public void resolutionChangeResetsEveryTrack() {
        tracker.update(one(person(4f, 0f)), 0L, EPOCH);
        tracker.update(one(person(4f, 0f)), 200L, EPOCH);
        assertEquals(1, tracker.trackCount());

        List<Track> after = tracker.update(one(person(4f, 0f)), 400L,
                new ObjectTracker.FrameEpoch(1080, 1920, 0));
        assertTrue(after.isEmpty());
    }

    /** 被挡住而骤缩的那一帧不能进拟合——否则静止的东西会被算成"在远离"。 */
    @Test
    public void occludedFrameIsKeptOutOfTheWindow() {
        Detection full = person(4f, 0f);
        tracker.update(one(full), 0L, EPOCH);
        List<Track> tracks = tracker.update(one(full), 200L, EPOCH);
        assertEquals(2, tracks.get(0).window.size());

        // 同一个人被前面的柱子挡住一半：高度只剩 60%。
        float height = full.height() * 0.6f;
        Detection occluded = new Detection(PERSON_CLASS, "person", 0.7f,
                full.left, full.bottom - height, full.right, full.bottom);
        List<Track> afterOcclusion = tracker.update(one(occluded), 400L, EPOCH);

        assertEquals(1, afterOcclusion.size());
        Track track = afterOcclusion.get(0);
        assertTrue("高度骤降应判为遮挡", track.occluded);
        assertEquals("遮挡帧不能被当成一次观测", 2, track.window.size());
        // 遮挡只是"这一帧的数据不可用"，关联本身仍然是成功的：轨迹继续活着，只是没长样本。
        assertEquals("轨迹本身要留着：目标很可能只是走过一根柱子", 3, track.hits);
    }

    /** 轻微缩小（不到 25%）是正常的靠近/远离，不能误判成遮挡。 */
    @Test
    public void gradualShrinkIsNotOcclusion() {
        Detection first = person(4f, 0f);
        tracker.update(one(first), 0L, EPOCH);

        Detection slightlySmaller = person(4.6f, 0f);
        List<Track> tracks = tracker.update(one(slightlySmaller), 200L, EPOCH);

        assertFalse(tracks.get(0).occluded);
        assertEquals(2, tracks.get(0).window.size());
    }

    /** 单帧误检不能立刻变成"目标"：连上两帧才算确认。 */
    @Test
    public void tentativeTrackIsNotReportedUntilConfirmed() {
        assertEquals(0, tracker.update(one(person(4f, 0f)), 0L, EPOCH).size());
        assertEquals(1, tracker.update(one(person(4f, 0f)), 200L, EPOCH).size());
        assertEquals(1, tracker.trackCount());
    }

    // ------------------------------------------------------------------
    // 测速
    // ------------------------------------------------------------------

    /** 固定不动的目标：距离 {@code z}、高 {@code heightMeters} 米。 */
    private static Detection still(String label, float z, float lateralX, float heightMeters) {
        float height = K * heightMeters / z;
        float bottom = HORIZON + K * CAMERA_HEIGHT_M / z;
        float width = K * heightMeters * 0.5f / z;
        float centerX = HORIZON + K * lateralX / z;
        return new Detection(0, label, 0.9f,
                centerX - width * 0.5f, bottom - height, centerX + width * 0.5f, bottom);
    }

    /** 匀速接近的行人，应当在 5 FPS 下被算出正确的碰撞时间。 */
    @Test
    public void approachingTargetYieldsTtc() {
        float z0 = 8f;
        float v = 2.8f;
        long now = 1400L;
        Track track = null;
        for (int frame = 0; frame <= 7; frame++) {
            long nowMs = frame * 200L;
            List<Track> tracks = tracker.update(one(person(z0 - v * nowMs / 1000f, 0f)),
                    nowMs, EPOCH);
            if (!tracks.isEmpty()) {
                track = tracks.get(0);
            }
        }

        assertTrue("八帧之后应当已经拟合出速度", track != null && track.relativeRate > 0f);
        // 真值：t=1.4s 时 Z = 8 − 3.92 = 4.08 m，TTC = 4.08 / 2.8 = 1.457 s。
        assertEquals(1.457f, track.ttcSeconds, 0.15f);
    }

    /**
     * 用户自己往前走时，画面里的**静止**物体绝不能被算成"正在靠近"。
     *
     * <p>这是整套动态播报最容易翻车的一条，也是真机验收里要专门走一遍的场景。
     * 三个距离、类别、物理高度都不同的静止目标——只有 {@code w = r·H/h} 才能把它们对齐。
     */
    @Test
    public void selfMotionDoesNotMakeStaticObjectsApproach() {
        float[] distances = {5f, 12f, 20f};
        String[] labels = {"person", "car", "truck"};
        float[] heights = {1.70f, 1.50f, 3.30f};
        // 横向要拉开：远处的目标在画面上本来就小，摆得太近会被合并阶段当成"被挡住的目标"。
        float[] lateral = {-0.8f, 0f, 0.8f};

        long now = 1400L;
        List<Track> tracks = null;
        for (int frame = 0; frame <= 7; frame++) {
            long nowMs = frame * 200L;
            List<Detection> frameDetections = new java.util.ArrayList<>();
            for (int i = 0; i < distances.length; i++) {
                // 用户以 1.4 m/s 前进 ⇒ 每个静止目标的距离都在均匀缩短。
                frameDetections.add(still(labels[i], distances[i] - 1.4f * nowMs / 1000f,
                        lateral[i], heights[i]));
            }
            tracks = tracker.update(frameDetections, nowMs, EPOCH);
        }

        assertEquals(3, tracks.size());
        assertEquals("自运动速度代理应当等于 v_ego / 视场系数",
                1.4f / K, tracker.egoVelocityProxy(), 0.15f);

        for (Track track : tracks) {
            assertEquals("补偿后静止目标不该有剩余速度：" + track.label,
                    0f, track.relativeRate, 0.05f);
            assertTrue("静止目标不能给出「要撞上了」的结论：" + track.label,
                    Float.isNaN(track.ttcSeconds) || track.ttcSeconds > 5f);

            // 不做补偿的话，最近的那个会落在「注意」档里——这条用例存在的意义就在这里。
            if (track.label.equals("person")) {
                float naiveTtc = 1f / track.rawRate - (now - track.rateCentroidMs) / 1000f;
                assertTrue("不补偿时这个静止行人本来会被误报", naiveTtc < 3.0f);
            }
        }
    }

    /**
     * 本帧没看到的轨迹，不能再往外报运动量。
     *
     * <p>{@code ttcSeconds} 含一项"距拟合质心过去了多久"的校正：质心是上一帧的，
     * 墙钟却还在走，所以**消失目标的剩余碰撞时间会自己往下掉**。500 ms 的宽限期足够
     * 把 2.2 秒压过 1.8 秒的危险门限——人会听到一句「行人接近」，而那个人早已走出画面。
     */
    @Test
    public void aTrackThatWasNotSeenThisFrameReportsNoMotion() {
        // t = 1.4 s 时 Z = 10.8 − 4.2 = 6.6 m，TTC = 2.2 s：正在"注意"档里。
        // 让它按墙钟继续往下掉 400 ms 就会跌进 1.8 秒的危险门限，这正是要钉住的那条边界。
        float z0 = 10.8f;
        float v = 3.0f;
        Track track = null;
        for (int frame = 0; frame <= 7; frame++) {
            long nowMs = frame * 200L;
            List<Track> tracks = tracker.update(one(person(z0 - v * nowMs / 1000f, 0f)),
                    nowMs, EPOCH);
            if (!tracks.isEmpty()) {
                track = tracks.get(0);
            }
        }
        assertTrue("前置条件：应当已经拟合出 2.2 秒左右的碰撞时间",
                track != null && track.ttcSeconds > 1.9f && track.ttcSeconds < 3.0f);

        List<Track> missed = tracker.update(Collections.<Detection>emptyList(), 8 * 200L, EPOCH);

        assertEquals("500 ms 之内轨迹还该留着：目标可能只是被挡住", 1, missed.size());
        assertFalse(missed.get(0).matchedThisFrame);
        assertTrue("没看到就不该有碰撞时间", Float.isNaN(missed.get(0).ttcSeconds));
        assertTrue(Float.isNaN(missed.get(0).relativeRate));
        assertTrue(Float.isNaN(missed.get(0).rawRate));
        assertTrue(Float.isNaN(missed.get(0).lateralRate));
    }

    /** 空输入不能崩，也不能留下幽灵轨迹。 */
    @Test
    public void emptyInputIsHarmless() {
        assertTrue(tracker.update(Collections.<Detection>emptyList(), 0L, EPOCH).isEmpty());
        assertTrue(tracker.update(Collections.<Detection>emptyList(), 1000L, EPOCH).isEmpty());
        assertEquals(0, tracker.trackCount());
    }
}
