package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;

import com.sullivan.android.vision.detect.Letterbox;
import com.sullivan.android.vision.detect.RgbaFrame;

import org.junit.Test;

/**
 * 校验 letterbox 的几何计算。
 *
 * <p>这组参数直接决定检测框能不能落回正确的位置，算错的话框会整体偏移，
 * 而且很难靠肉眼发现，所以单独覆盖一下。
 */
public class LetterboxTest {

    private static RgbaFrame frame(int width, int height, int rotationDegrees) {
        return RgbaFrame.of(new byte[width * height * 4], width, height,
                width * 4, 4, rotationDegrees);
    }

    /** 横屏画面：1280×720 缩到 640 后高度只有 360，上下各补 140 像素灰边。 */
    @Test
    public void landscapeFrameIsPaddedVertically() {
        Letterbox box = Letterbox.create(frame(1280, 720, 0), 640);
        assertEquals(640, box.inputSize);
        assertEquals(1280, box.frameWidth);
        assertEquals(720, box.frameHeight);
        assertEquals(0.5f, box.scale, 0.0001f);
        assertEquals(640f, box.contentWidth, 0.0001f);
        assertEquals(360f, box.contentHeight, 0.0001f);
        assertEquals(0f, box.padX, 0.0001f);
        assertEquals(140f, box.padY, 0.0001f);
    }

    /** 竖屏画面旋转 90° 后就是横屏，几何参数应该和上面完全一致。 */
    @Test
    public void rotatedPortraitFrameMatchesLandscapeGeometry() {
        Letterbox box = Letterbox.create(frame(720, 1280, 90), 640);
        assertEquals(90, box.rotationDegrees);
        assertEquals(1280, box.frameWidth);
        assertEquals(720, box.frameHeight);
        assertEquals(640f, box.contentWidth, 0.0001f);
        assertEquals(360f, box.contentHeight, 0.0001f);
        assertEquals(0f, box.padX, 0.0001f);
        assertEquals(140f, box.padY, 0.0001f);
    }

    /** 正方形输入下画面被完整缩放，没有灰边。 */
    @Test
    public void squareFrameFillsInputWithoutPadding() {
        Letterbox box = Letterbox.create(frame(640, 640, 0), 640);
        assertEquals(640f, box.contentWidth, 0.0001f);
        assertEquals(640f, box.contentHeight, 0.0001f);
        assertEquals(0f, box.padX, 0.0001f);
        assertEquals(0f, box.padY, 0.0001f);
    }

    /** 竖屏且不旋转时是左右补灰边。 */
    @Test
    public void portraitFrameIsPaddedHorizontally() {
        Letterbox box = Letterbox.create(frame(480, 640, 0), 640);
        assertEquals(480, box.frameWidth);
        assertEquals(640, box.frameHeight);
        assertEquals(1f, box.scale, 0.0001f);
        assertEquals(480f, box.contentWidth, 0.0001f);
        assertEquals(640f, box.contentHeight, 0.0001f);
        assertEquals(80f, box.padX, 0.0001f);
        assertEquals(0f, box.padY, 0.0001f);
    }

    /** 输入尺寸取自模型，换成 480 时几何参数应同步变化。 */
    @Test
    public void inputSizeComesFromModel() {
        Letterbox box = Letterbox.create(frame(1280, 720, 0), 480);
        assertEquals(480, box.inputSize);
        assertEquals(0.375f, box.scale, 0.0001f);
        assertEquals(480f, box.contentWidth, 0.0001f);
        assertEquals(270f, box.contentHeight, 0.0001f);
        assertEquals(105f, box.padY, 0.0001f);
    }
}
