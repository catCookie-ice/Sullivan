package com.sullivan.protectee.vision.detect;

/**
 * 等比缩放 + 灰边填充的几何参数。
 *
 * <p>YOLO 要求输入为正方形，而相机画面通常不是，所以先把画面按较短边等比缩放到
 * {@code inputSize × inputSize} 之内，再在两侧补上灰边（取值 114，与 ultralytics
 * 训练/推理脚本一致）。
 *
 * <p>这里的宽高都是**画面正立后**的尺寸。检测框从模型坐标还原到归一化坐标时，
 * 用的也是同一套参数，见 {@link Yolo26Detector#detect(Prepared)}。
 */
public final class Letterbox {

    /** 模型输入的边长。 */
    public final int inputSize;
    /** 正立后的画面宽度。 */
    public final int frameWidth;
    /** 正立后的画面高度。 */
    public final int frameHeight;
    /** 缩放系数，正立画面坐标 × scale = 内容区坐标。 */
    public final float scale;
    /** 左侧灰边宽度（模型输入坐标系）。 */
    public final float padX;
    /** 顶部灰边高度（模型输入坐标系）。 */
    public final float padY;
    /** 缩放后内容的宽度。 */
    public final float contentWidth;
    /** 缩放后内容的高度。 */
    public final float contentHeight;
    /** 需要施加的顺时针旋转角度。 */
    public final int rotationDegrees;

    private Letterbox(int inputSize, int frameWidth, int frameHeight, float scale,
                      float padX, float padY, float contentWidth, float contentHeight,
                      int rotationDegrees) {
        this.inputSize = inputSize;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.scale = scale;
        this.padX = padX;
        this.padY = padY;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
        this.rotationDegrees = rotationDegrees;
    }

    public static Letterbox create(RgbaFrame frame, int inputSize) {
        int frameWidth = frame.rotatedWidth();
        int frameHeight = frame.rotatedHeight();
        float scale = Math.min(inputSize / (float) frameWidth, inputSize / (float) frameHeight);
        float contentWidth = frameWidth * scale;
        float contentHeight = frameHeight * scale;
        float padX = (inputSize - contentWidth) / 2f;
        float padY = (inputSize - contentHeight) / 2f;
        return new Letterbox(inputSize, frameWidth, frameHeight, scale,
                padX, padY, contentWidth, contentHeight, frame.rotationDegrees);
    }
}
