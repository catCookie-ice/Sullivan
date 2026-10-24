package com.sullivan.protectee.vision.detect;

import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * YOLO26 {@code end2end} 输出布局的解码。
 *
 * <p><b>这个测试类是为一类真实故障写的。</b>YOLO26 的官方导出默认
 * {@code end2end=True}，输出 {@code logits [1,300,80]} + {@code pred_boxes [1,300,4]}，
 * 而解码器原先只认 {@code [1,84,8400]}。用户换上正确下载的 yolo26n.onnx 之后
 * 检测完全失效 —— 权重是好的，是解码器读错了张量。
 *
 * <p>三个必须同时成立的语义（都实测过，见下方注释）：
 * <b>分数要 sigmoid</b>、<b>框是归一化到 letterbox 输入</b>、<b>不再跑 NMS</b>。
 * 少任何一条都会退化成"检测不到"或"框位置偏"。
 */
public class EndToEndDecodeTest {

    /** 正方形画面 → 无灰边，便于隔离测试解码本身。 */
    private static final Letterbox SQUARE = Letterbox.create(
            RgbaFrame.of(new byte[640 * 640 * 4], 640, 640, 640 * 4, 4, 0), 640);

    /** 16:9 横画面 1280×720 → 640×360 内容 + 上下各 140 px 灰边。 */
    private static final Letterbox WIDE = Letterbox.create(
            RgbaFrame.of(new byte[1280 * 720 * 4], 1280, 720, 1280 * 4, 4, 0), 640);

    private static final int CLASSES = 80;
    private static final float THRESHOLD = 0.25f;

    /**
     * 造一份 {@code count} 个槽位的输出。
     *
     * @param rows 每行 {cx, cy, w, h, classId, logit}
     */
    private static List<Detection> decode(Letterbox letterbox, int count, float[][] rows) {
        FloatBuffer boxes = FloatBuffer.allocate(count * 4);
        FloatBuffer scores = FloatBuffer.allocate(count * CLASSES);
        // 先铺满很低的 logits（-20 → sigmoid ≈ 0），保证只有显式写入的行会命中。
        for (int i = 0; i < count * CLASSES; i++) {
            scores.put(i, -20f);
        }
        for (int i = 0; i < rows.length; i++) {
            float[] row = rows[i];
            boxes.put(i * 4, row[0]);
            boxes.put(i * 4 + 1, row[1]);
            boxes.put(i * 4 + 2, row[2]);
            boxes.put(i * 4 + 3, row[3]);
            scores.put(i * CLASSES + (int) row[4], row[5]);
        }
        return Yolo26Detector.decodeEndToEnd(boxes, scores, count, CLASSES,
                letterbox, 640, THRESHOLD);
    }

    /**
     * 一个明确的 bus：logit 2.517 → sigmoid 0.925。
     *
     * <p>这个数值是从官方 yolo26n.onnx 真跑 bus.jpg 得到的，不是编的。
     */
    @Test
    public void sigmoidIsAppliedToRawLogits() {
        List<Detection> out = decode(SQUARE, 300, new float[][]{
                {0.5f, 0.5f, 0.2f, 0.4f, 5f, 2.517f},
        });
        assertEquals(1, out.size());
        Detection detection = out.get(0);
        assertEquals("bus", detection.label);
        assertEquals(0.925f, detection.score, 0.005f);
    }

    /**
     * <b>这一条是防"什么都检测不到"的核心。</b>
     *
     * <p>logit 2.5 在原始尺度下大于阈值 0.25，所以"忘记 sigmoid"的实现在这里会
     * **通过**阈值判断；但真实概率是 0.92。反过来，一个 logit 为 -1.0 的行
     * （sigmoid 后 0.269，刚好过阈值）在"忘记 sigmoid"的实现里会被**当成负数直接丢掉**。
     * 两种实现因此可以区分开。
     */
    @Test
    public void lowLogitThatStillPassesSigmoidIsKept() {
        List<Detection> out = decode(SQUARE, 300, new float[][]{
                {0.5f, 0.5f, 0.2f, 0.4f, 0f, -1.0f},
        });
        assertEquals("sigmoid(-1.0)=0.269，仍高于 0.25，应当保留", 1, out.size());
        assertEquals(0.269f, out.get(0).score, 0.005f);
    }

    /** 真正的低分（sigmoid 后远低于阈值）要被丢掉。 */
    @Test
    public void genuinelyLowScoreIsDropped() {
        List<Detection> out = decode(SQUARE, 300, new float[][]{
                {0.5f, 0.5f, 0.2f, 0.4f, 0f, -3.0f},
        });
        assertTrue("sigmoid(-3.0)=0.047，应当丢弃", out.isEmpty());
    }

    /**
     * 框坐标是 {@code cx,cy,w,h} 归一化到 **letterbox 输入**（含灰边），
     * 不是内容区。这一点弄反了框会整体偏移一个 pad。
     */
    @Test
    public void boxCoordinatesAreRelativeToLetterboxInput() {
        // 内容区是 y ∈ [140, 500)。让框正好覆盖内容区中间那一半：
        // 输入坐标 cy = 0.5（=320px），h = 360/640（=360px）→ 内容区 y 30..390 像素。
        float cy = 320f / 640f;
        float h = 360f / 640f;
        List<Detection> out = decode(WIDE, 300, new float[][]{
                {0.5f, cy, 0.5f, h, 0f, 3.0f},
        });
        assertEquals(1, out.size());
        Detection detection = out.get(0);

        // 输入像素 y: 320 ± 180 → [140, 500]；减 padY(140) → 内容像素 [0, 360]；
        // 除 contentHeight(360) → 归一化 [0, 1]。正好铺满内容区。
        assertEquals(0f, detection.top, 0.01f);
        assertEquals(1f, detection.bottom, 0.01f);
        // 横向：cx=0.5, w=0.5 → 输入 x [160,480]；padX=0 → 内容 [160,480]/640 → [0.25,0.75]。
        assertEquals(0.25f, detection.left, 0.01f);
        assertEquals(0.75f, detection.right, 0.01f);
    }

    /** 落在灰边里的框没有意义，应当丢掉（它的坐标全是补的 114 灰）。 */
    @Test
    public void boxEntirelyInsidePaddingIsDropped() {
        // cy 很小 → 框落在上方灰边内。padY=140 → 归一化 0.21875。
        List<Detection> out = decode(WIDE, 300, new float[][]{
                {0.5f, 0.05f, 0.1f, 0.05f, 0f, 3.0f},
        });
        assertTrue("整框在灰边里，应被丢弃", out.isEmpty());
    }

    /** 多个槽位各自取自己的最高类别，互不干扰。 */
    @Test
    public void eachSlotPicksItsOwnBestClass() {
        List<Detection> out = decode(SQUARE, 300, new float[][]{
                {0.3f, 0.5f, 0.1f, 0.2f, 0f, 2.378f},   // person
                {0.7f, 0.5f, 0.1f, 0.2f, 2f, 2.0f},     // car
        });
        assertEquals(2, out.size());
        // 输出按槽位顺序，槽位里按分数排好；这里两个都过阈值。
        assertEquals("person", out.get(0).label);
        assertEquals("car", out.get(1).label);
    }

    /**
     * {@code end2end} 已经做过 NMS，所以同一物体即使出现在两个槽位也会原样保留两份。
     *
     * <p>这不是缺陷：end2end 的 300 个槽位本来就是「已经筛好的结果」，重复概率极低。
     * 写这条用例是为了**记录这个决定** —— 谁会想"那就在这里再跑一次 NMS 吧"，
     * 而那会让本来正常的输出被二次抑制，小目标反而消失。
     */
    @Test
    public void doesNotRunNmsAgain() {
        List<Detection> out = decode(SQUARE, 300, new float[][]{
                {0.5f, 0.5f, 0.2f, 0.4f, 0f, 2.0f},
                {0.5f, 0.5f, 0.2f, 0.4f, 0f, 2.0f},
        });
        assertEquals("end2end 的输出不再二次抑制", 2, out.size());
    }

    /** 空槽位（logits 全是很低的值）不能产生框。 */
    @Test
    public void emptySlotsProduceNothing() {
        List<Detection> out = decode(SQUARE, 300, new float[0][]);
        assertTrue(out.isEmpty());
    }

    /** 超出类别表的类别号不能把 label 数组打越界（防御性）。 */
    @Test
    public void extraClassesBeyondCocoAreIgnored() {
        // numClasses 传 100（比 COCO 的 80 多），只用到前 80 个。
        FloatBuffer boxes = FloatBuffer.allocate(4);
        boxes.put(0, 0.5f); boxes.put(1, 0.5f); boxes.put(2, 0.2f); boxes.put(3, 0.4f);
        FloatBuffer scores = FloatBuffer.allocate(100);
        for (int i = 0; i < 100; i++) {
            scores.put(i, -20f);
        }
        // 第 85 类（超出 COCO）给高分：应当被忽略，而不是抛 ArrayIndexOutOfBounds。
        scores.put(85, 10f);
        List<Detection> out = Yolo26Detector.decodeEndToEnd(boxes, scores, 1, 100,
                SQUARE, 640, THRESHOLD);
        assertTrue("超出 COCO 80 类的类别不产生检测", out.isEmpty());
    }

    @Test
    public void sigmoidMatchesKnownValues() {
        assertEquals(0.5f, Yolo26Detector.sigmoid(0f), 1e-6);
        assertEquals(0.731f, Yolo26Detector.sigmoid(1f), 0.001f);
        assertEquals(0.925f, Yolo26Detector.sigmoid(2.517f), 0.002f);
        // 极端值不能溢出成 NaN/Inf。
        assertFalse(Float.isNaN(Yolo26Detector.sigmoid(200f)));
        assertFalse(Float.isNaN(Yolo26Detector.sigmoid(-200f)));
    }
}
