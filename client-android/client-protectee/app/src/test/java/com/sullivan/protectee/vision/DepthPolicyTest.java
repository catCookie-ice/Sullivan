package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.protectee.vision.depth.DepthMap;
import com.sullivan.protectee.vision.depth.DistancePolicy;
import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.detect.RgbaFrame;

import org.junit.Test;

import java.util.Arrays;

/**
 * 深度测距的口径测试。
 *
 * <p>这些用例**不碰 ONNX Runtime**：{@link DepthMap} 是从浮点数组构造的纯数据结构，
 * 所以能在 JVM 单测里直接造一张"已知答案"的深度图。真正需要验证模型精度的那部分
 * 只能上真机，见开发日记的"未验证"一节。
 */
public class DepthPolicyTest {

    /** 造一张每个像素都是同一距离的深度图，几何最简单、断言最明确。 */
    private static DepthMap uniform(float meters, int size) {
        float[] data = new float[size * size];
        Arrays.fill(data, meters);
        return new DepthMap(size, size, data);
    }

    /** 造一张上半近、下半远的深度图，用来验证"取框下沿往上一点"确实落在近处那段。 */
    private static DepthMap splitHorizon(float topMeters, float bottomMeters, int size) {
        float[] data = new float[size * size];
        for (int y = 0; y < size; y++) {
            float value = y < size / 2 ? topMeters : bottomMeters;
            for (int x = 0; x < size; x++) {
                data[y * size + x] = value;
            }
        }
        return new DepthMap(size, size, data);
    }

    /**
     * 一个「无灰边」的 letterbox：正方形画面 640×640 → content=640、pad=0。
     *
     * <p>用它是为了让这些用例只验证**深度采样本身**，而不是同时验证灰边换算。
     * 灰边换算另有专门的用例，见下面的 {@code footprintAccountsForPadding}。
     */
    private static final Letterbox SQUARE_640 = Letterbox.create(
            RgbaFrame.of(new byte[640 * 640 * 4], 640, 640, 640 * 4, 4, 0), 640);

    /**
     * 有灰边的 letterbox：16:9 横画面 1280×720 → 缩放到 640×360，上下各补 140 px。
     *
     * <p>这正是手机相机的常见几何，也是「不换算就会偏」的那个场景。
     */
    private static final Letterbox WIDE_16_9 = Letterbox.create(
            RgbaFrame.of(new byte[1280 * 720 * 4], 1280, 720, 1280 * 4, 4, 0), 640);

    private static Letterbox box640() {
        return SQUARE_640;
    }

    private static Detection detection(String label, float left, float top,
                                       float right, float bottom) {
        int classId = Arrays.asList(CocoLabels.COCO_80).indexOf(label);
        return new Detection(classId, label, 0.9f, left, top, right, bottom);
    }

    @Test
    public void uniformDepthGivesThatDistance() {
        DepthMap depth = uniform(3.0f, 640);
        Detection box = detection("person", 0.4f, 0.3f, 0.6f, 0.9f);
        assertEquals(3.0f, DistancePolicy.metersOf(box, depth, box640()), 0.01f);
    }

    /** 采样点必须落在框下沿附近，而不是框中心 —— 框中心会取到"胸口"，不是与地面的接触点。 */
    @Test
    public void samplesNearBottomEdgeNotCenter() {
        DepthMap depth = splitHorizon(10f, 2f, 640);
        // 框跨过中线：上半 10 米、下半 2 米。下沿在 0.9 处，抬 0.15 个框高后仍在 2 米那段。
        Detection box = detection("person", 0.4f, 0.2f, 0.6f, 0.9f);
        assertEquals(2.0f, DistancePolicy.metersOf(box, depth, box640()), 0.01f);
    }

    @Test
    public void noDepthFallsBackToGeometry() {
        // 1.0 米是一个"几何启发式看不算近、深度看算近"的距离：框很小且不贴底。
        Detection box = detection("person", 0.30f, 0.45f, 0.34f, 0.55f);
        assertFalse(com.sullivan.protectee.vision.assist.ObstacleAdvisor.isNear(box));
        assertTrue(DistancePolicy.isNear(box, uniform(1.0f, 640), box640()));

        // 没有深度图时必须逐字退回原判据，不能一律当近或一律当远。
        assertFalse(DistancePolicy.isNear(box, null, null));
        assertEquals(Float.NaN, DistancePolicy.metersOf(box, null, null), 0f);
    }

    @Test
    public void invalidDepthValuesAreRejected() {
        Detection box = detection("person", 0.4f, 0.3f, 0.6f, 0.9f);
        // 0 在天空/纯色墙面上很常见；直接当成"0 米"会制造假紧急。
        assertEquals(Float.NaN, DistancePolicy.metersOf(box, uniform(0f, 640), box640()), 0f);
        assertEquals(Float.NaN, DistancePolicy.metersOf(box, uniform(-1f, 640), box640()), 0f);
        // 超过上限的值同样不可信。
        assertEquals(Float.NaN, DistancePolicy.metersOf(box, uniform(200f, 640), box640()), 0f);
        assertFalse(DepthMap.isValid(Float.NaN));
    }

    @Test
    public void nearAndImminentThresholdsAreOrdered() {
        Detection box = detection("person", 0.4f, 0.3f, 0.6f, 0.9f);
        assertTrue(DistancePolicy.isImminent(box, uniform(1.0f, 640), box640()));
        assertFalse(DistancePolicy.isImminent(box, uniform(1.5f, 640), box640()));
        // 1.5 米：算近，但还不至于"立即停下"。
        assertTrue(DistancePolicy.isNear(box, uniform(1.5f, 640), box640()));
        assertFalse(DistancePolicy.isNear(box, uniform(5f, 640), box640()));
        assertTrue(DistancePolicy.DANGER_METERS < DistancePolicy.NEAR_METERS);
    }

    @Test
    public void metersAreFormattedWithoutFalsePrecision() {
        assertEquals("1.5 米", DistancePolicy.describeMeters(1.53f));
        assertEquals("3 米", DistancePolicy.describeMeters(3.2f));
        // 取不到有效距离时不该出现任何数字：宁可不说，也不能说错。
        assertNull(DistancePolicy.describeMeters(Float.NaN));
        assertNull(DistancePolicy.describeMeters(0f));
    }

    /** 中位数采样必须抗单个噪点：一个 0.1 米的孤立点不该把整簇说成"10 厘米"。 */
    @Test
    public void medianRejectsIsolatedOutlier() {
        int size = 640;
        float[] data = new float[size * size];
        Arrays.fill(data, 4f);
        // 在采样点正中央放一个极近的噪点。
        int cx = Math.round(0.5f * size);
        int cy = Math.round((0.9f - 0.1f * 0.5f * DepthMap.FOOT_LIFT_RATIO) * size);
        data[cy * size + cx] = 0.1f;
        DepthMap depth = new DepthMap(size, size, data);

        Detection box = detection("person", 0.4f, 0.5f, 0.6f, 0.9f);
        // 若用最小值，这里会读到 0.1；中位数仍在 4 米。
        assertEquals(4.0f, depth.distanceAtFootprint(box640(), box.left, box.top, box.right, box.bottom),
                0.01f);
    }

    @Test
    public void fromTensorRejectsDynamicShape() {
        java.nio.FloatBuffer buffer = java.nio.FloatBuffer.allocate(4);
        try {
            DepthMap.fromTensor(buffer, new long[]{1, 1, -1, -1});
            org.junit.Assert.fail("动态维度应当抛异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("动态"));
        }
    }

    /**
     * 采样点必须把 letterbox 的灰边算进去。
     *
     * <p><b>这个用例是针对一个真实的错误写的。</b>{@code Yolo26Detector.decode} 把检测框
     * 换算成了「**内容区**归一化坐标」（已经减过 pad、除过 contentWidth），而深度图是
     * 整张 640×640 的输入。第一版实现直接拿归一化坐标 × inputSize 去索引深度图，
     * 于是采样点朝左上角偏，偏移量 = pad。16:9 画面上上下各 140 px，
     * 采样点会跑到背景地面上去 —— 用户看到的是「深度模型一点都不准」，
     * 而模型其实是对的。
     *
     * <p>做法：造一张「上半远、下半近」的深度图，让 16:9 的灰边让采样点跨过中线。
     * 算对了取到近处那个值，算错了取到远处那个值 —— 两种结果差得很远，断言很硬。
     */
    @Test
    public void footprintAccountsForLetterboxPadding() {
        Letterbox wide = WIDE_16_9;
        // 前提断言：这个几何真的有灰边，否则这条用例什么也没验证。
        assertTrue("16:9 画面在 640 输入下应当有上下灰边", wide.padY > 100f);
        assertEquals(0f, wide.padX, 0.01f);

        int size = 640;
        Detection box = detection("person", 0.4f, 0.3f, 0.6f, 1.0f);

        // 算出「正确」与「忽略灰边」两种算法各自的采样行，把分界线放在它们中间，
        // 这样两种算法会落在不同的米数上，断言才有分辨力。
        float boxHeightPx = (box.bottom - box.top) * wide.contentHeight;
        int correctY = Math.round(box.bottom * wide.contentHeight + wide.padY
                - boxHeightPx * DepthMap.FOOT_LIFT_RATIO);
        int naiveY = Math.round(box.bottom * size - boxHeightPx * DepthMap.FOOT_LIFT_RATIO);
        assertTrue("两种算法的采样行必须差得足够远，否则这条用例分辨不出对错",
                naiveY - correctY > 60);

        float[] data = new float[size * size];
        int boundary = (correctY + naiveY) / 2;
        for (int y = 0; y < size; y++) {
            Arrays.fill(data, y * size, (y + 1) * size, y < boundary ? 9f : 1.5f);
        }
        DepthMap depth = new DepthMap(size, size, data);

        float meters = depth.distanceAtFootprint(wide, box.left, box.top, box.right, box.bottom);
        assertEquals("忽略灰边会把采样点读到画面更下方（那里是地面背景）", 9f, meters, 0.01f);
    }

    /** 没有灰边时，换算结果与直接用 inputSize 一致（保证新逻辑没有破坏原来的情形）。 */
    @Test
    public void squareFrameGivesSameResultWithOrWithoutPadding() {
        DepthMap depth = uniform(2.5f, 640);
        Detection box = detection("person", 0.4f, 0.3f, 0.6f, 0.9f);
        assertEquals(2.5f, depth.distanceAtFootprint(SQUARE_640,
                box.left, box.top, box.right, box.bottom), 0.01f);
    }
}
