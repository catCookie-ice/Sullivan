package com.sullivan.protectee.vision.depth;

import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.detect.RgbaFrame;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 检测与深度**输入尺寸不同**这件事的回归测试。
 *
 * <p><b>这个测试类是为一个真实的设计错误写的。</b>第一版假设两个模型都是 640、
 * 可以"共用同一份预处理张量"，于是把检测器的 {@code prepared.chw} 直接喂给深度模型。
 * 实测官方权重后发现：{@code yolo26n.onnx} 是 640，而 {@code yolo26n-depth.onnx}
 * 是 **768** —— ONNX Runtime 直接报
 * {@code Got invalid dimensions for input: index 2 Got: 640 Expected: 768}。
 *
 * <p>改法是"各做各的 letterbox"。但这带来一个更隐蔽的风险：检测框是在 **640** 的
 * 几何下归一化到内容区的，而深度图是 **768** 的几何。把框换算成深度像素时必须用
 * 深度那一份参数，用错会让采样点偏移几十像素 —— 而偏移后的采样点往往是背景地面，
 * 于是"距离"看起来偏大且毫无规律，表现得像"深度模型不准"。
 *
 * <p>下面两条用例分别钉住"差异确实存在"与"用错会算错"。
 */
public class DepthGeometryTest {

    /** 1280×720 横屏帧：detect 会得到 640×360 内容 + 上下各 140；depth 会得到 768×432 + 各 168。 */
    private static RgbaFrame wideFrame() {
        int w = 1280;
        int h = 720;
        return RgbaFrame.of(new byte[w * h * 4], w, h, w * 4, 4, 0);
    }

    /**
     * 两个模型对同一帧算出的 letterbox **必须不同**。
     *
     * <p>如果哪天有人把 {@code DEFAULT_INPUT_SIZE} 改回 640 去"统一"两者，
     * 这条会立刻红 —— 而那正是会让深度模型加载后一跑就崩的改动。
     */
    @Test
    public void detectAndDepthUseDifferentLetterboxGeometry() {
        RgbaFrame frame = wideFrame();

        Letterbox forDetect = Letterbox.create(frame, 640);
        Letterbox forDepth = Letterbox.create(frame, 768);

        assertEquals("检测用 640", 640f, forDetect.contentWidth + forDetect.padX * 2, 0.01f);
        assertEquals("深度用 768", 768f, forDepth.contentWidth + forDepth.padX * 2, 0.01f);

        // 16:9 → 640 的内容高 360、灰边各 140；768 的内容高 432、灰边各 168。
        assertEquals(360f, forDetect.contentHeight, 0.5f);
        assertEquals(140f, forDetect.padY, 0.5f);
        assertEquals(432f, forDepth.contentHeight, 0.5f);
        assertEquals(168f, forDepth.padY, 0.5f);

        assertNotEquals("两者的 padY 必须不同（这正是不能混用的原因）",
                forDetect.padY, forDepth.padY, 1f);
    }

    /**
     * 深度模型的默认输入边长必须是 768。
     *
     * <p>这不是"随便选一个默认值"：{@link DepthEstimator#DEFAULT_INPUT_SIZE} 只在
     * 模型声明动态维度时才生效，但一旦生效错了，要么推理崩、要么结果全错。
     * 官方权重的真实输入就是 768，所以常量也必须是 768。
     */
    @Test
    public void depthDefaultInputSizeMatchesOfficialWeights() {
        assertEquals("官方 yolo26n-depth.onnx 的输入是 768×768（实测）",
                768, DepthEstimator.DEFAULT_INPUT_SIZE);
    }

    /**
     * 用错几何参数会让采样落到**不同的像素**上，从而读出不同的距离。
     *
     * <p>这条用例把"偏移"这件事变成可断言的事实：同一张深度图、同一个检测框，
     * 配 640 的参数和配 768 的参数会读到完全不同的行。用一个沿 y 轴线性递增的
     * 深度场，就能把"读到了哪一行"直接换算成米数差异。
     */
    @Test
    public void usingDetectLetterboxForDepthReadsWrongPixel() {
        RgbaFrame frame = wideFrame();
        Letterbox detectGeometry = Letterbox.create(frame, 640);
        Letterbox depthGeometry = Letterbox.create(frame, 768);

        // 沿 y 线性递增的深度场（上远下近反过来也行，只要单调就能区分行号）：
        // 米数 = y 像素 / 16，即 0..47.9 米。
        int size = 768;
        float[] meters = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                meters[y * size + x] = y / 16f;
            }
        }
        DepthMap depth = new DepthMap(size, size, meters);

        // 一个贴底的框（下沿 = 1.0），换算后采样点在框下沿往上 15%。
        Detection box = new Detection(0, "person", 0.9f, 0.4f, 0.5f, 0.6f, 1.0f);

        float withDepthGeometry = depth.distanceAtFootprint(depthGeometry,
                box.left, box.top, box.right, box.bottom);
        float withDetectGeometry = depth.distanceAtFootprint(detectGeometry,
                box.left, box.top, box.right, box.bottom);

        assertTrue("两种几何都应得到有限读数", Float.isFinite(withDepthGeometry));
        assertTrue("两种几何都应得到有限读数", Float.isFinite(withDetectGeometry));

        // 深度场随行号单调递增，所以行号不同 → 读数必然不同。
        // 若两者相等，说明 letterbox 根本没参与换算（就是把归一化坐标直接乘了 inputSize）。
        assertNotEquals("用检测的几何去读深度图会落到别的行上，读数不该相同",
                withDepthGeometry, withDetectGeometry, 0.5f);

        // 768 图的 padY 更大，同一个归一化 y 会被抬到更靠下的绝对行，
        // 于是读到更大的米数。方向也钉一下。
        assertTrue("768 的 padY(168) > 640 的 padY(140)，同样归一化纵坐标会落到更靠下的行",
                withDepthGeometry > withDetectGeometry);
    }
}
