package com.sullivan.protectee.vision.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.vision.assist.DynamicAdvisor;
import com.sullivan.protectee.vision.assist.ObstacleAdvisor;
import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.scene.CorridorCoverage;
import com.sullivan.protectee.vision.scene.SceneSnapshot;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 叠在相机预览上的检测结果图层：障碍框、中央走廊、通路占比。
 *
 * <p>检测框坐标是相对正立画面的归一化值，本视图按 {@code PreviewView} 的
 * FIT_CENTER 规则换算出画面在屏幕上的实际显示区域，再按比例落点，因此预览与
 * 叠加层始终对齐。
 *
 * <p>画的是**轨迹**而不是单帧检测框，标签里带上轨迹编号与运动方向。这两样东西在真机上
 * 肉眼一看就知道追踪器和测速是否可信——ID 频繁重建说明关联门限不对，标记来回跳说明
 * 拟合不稳。这些都不是看日志能看出来的。
 *
 * <p>真正的提示文字放在下方的 TextView 里而不是画在这里，是为了能用上系统字体
 * 缩放和无障碍朗读。
 */
public final class OverlayView extends View {

    private static final int BOX_COLOR = 0xFFE6281E;
    private static final int NEAR_BOX_COLOR = 0xFFFF3B30;
    private static final int CORRIDOR_COLOR = 0xFF00B4FF;
    private static final int FREE_TEXT_COLOR = 0xFF50DC32;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corridorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private List<SceneSnapshot.TrackSnapshot> tracks = Collections.emptyList();
    private float freeRatio = 1f;
    /** 正立画面的尺寸，用于确定预览的显示区域。 */
    private int frameWidth;
    private int frameHeight;
    private final float labelTextSize;
    private final float statusTextSize;

    /** 画面在视图中的实际显示区域，由 {@link #computeContentBounds()} 每帧算出。 */
    private float contentLeft;
    private float contentTop;
    private float contentWidth;
    private float contentHeight;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        labelTextSize = 12f * density;
        statusTextSize = 14f * density;

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2.5f * density);
        boxPaint.setColor(BOX_COLOR);

        corridorPaint.setStyle(Paint.Style.STROKE);
        corridorPaint.setStrokeWidth(1.5f * density);
        corridorPaint.setColor(CORRIDOR_COLOR);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(labelTextSize);
        labelPaint.setFakeBoldText(true);

        labelBackgroundPaint.setColor(0xCC000000);
        labelBackgroundPaint.setStyle(Paint.Style.FILL);

        statusPaint.setColor(FREE_TEXT_COLOR);
        statusPaint.setTextSize(statusTextSize);
        statusPaint.setFakeBoldText(true);

        statusBackgroundPaint.setColor(0x99000000);
        statusBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    /** 更新正立画面的尺寸；画面方向或分辨率变化时调用。 */
    public void setFrameSize(int width, int height) {
        if (width == frameWidth && height == frameHeight) {
            return;
        }
        this.frameWidth = width;
        this.frameHeight = height;
        invalidate();
    }

    /** 更新本帧的追踪结果。 */
    public void setResult(@NonNull List<SceneSnapshot.TrackSnapshot> tracks, float freeRatio) {
        this.tracks = tracks;
        this.freeRatio = freeRatio;
        invalidate();
    }

    /** 清空叠加层，例如相机停止或模型不可用时。 */
    public void clear() {
        this.tracks = Collections.emptyList();
        this.freeRatio = 1f;
        this.frameWidth = 0;
        this.frameHeight = 0;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (frameWidth <= 0 || frameHeight <= 0 || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        computeContentBounds();
        drawCorridor(canvas);
        drawObstacles(canvas);
        drawStatus(canvas);
    }

    /** 画面在视图中的实际显示区域，与 PreviewView 的 FIT_CENTER 保持一致。 */
    private void computeContentBounds() {
        float frameAspect = frameWidth / (float) frameHeight;
        float viewAspect = getWidth() / (float) getHeight();
        float contentWidth;
        float contentHeight;
        if (frameAspect > viewAspect) {
            contentWidth = getWidth();
            contentHeight = contentWidth / frameAspect;
        } else {
            contentHeight = getHeight();
            contentWidth = contentHeight * frameAspect;
        }
        contentLeft = (getWidth() - contentWidth) / 2f;
        contentTop = (getHeight() - contentHeight) / 2f;
        this.contentWidth = contentWidth;
        this.contentHeight = contentHeight;
    }

    private float mapX(float normalized) {
        return contentLeft + normalized * contentWidth;
    }

    private float mapY(float normalized) {
        return contentTop + normalized * contentHeight;
    }

    private void drawCorridor(@NonNull Canvas canvas) {
        rect.set(mapX(CorridorCoverage.CORRIDOR_LEFT), mapY(CorridorCoverage.CORRIDOR_TOP),
                mapX(CorridorCoverage.CORRIDOR_RIGHT), mapY(1f));
        canvas.drawRect(rect, corridorPaint);
    }

    private void drawObstacles(@NonNull Canvas canvas) {
        for (SceneSnapshot.TrackSnapshot track : tracks) {
            boolean near = ObstacleAdvisor.isNear(
                    track.left, track.top, track.right, track.bottom);
            boxPaint.setColor(near ? NEAR_BOX_COLOR : BOX_COLOR);
            rect.set(mapX(track.left), mapY(track.top),
                    mapX(track.right), mapY(track.bottom));
            canvas.drawRect(rect, boxPaint);
            drawLabel(canvas, track, near, rect);
        }
    }

    private void drawLabel(@NonNull Canvas canvas, @NonNull SceneSnapshot.TrackSnapshot track,
                           boolean near, @NonNull RectF box) {
        String text = String.format(Locale.US, "%s %d%% %s%s #%d%s",
                CocoLabels.translate(track.label),
                Math.round(track.score * 100f),
                ObstacleAdvisor.positionOf(track.centerX()),
                near ? " 较近" : "",
                track.id,
                motionMark(track));

        float padding = labelTextSize * 0.35f;
        float textWidth = labelPaint.measureText(text);
        float textHeight = labelPaint.getTextSize();
        // 框上方放不下时就挪到框内顶部，避免文字跑到画面外。
        float labelTop = box.top - textHeight - padding * 2f;
        if (labelTop < contentTop) {
            labelTop = box.top;
        }
        rect.set(box.left, labelTop, box.left + textWidth + padding * 2f, labelTop + textHeight + padding * 2f);
        canvas.drawRect(rect, labelBackgroundPaint);
        canvas.drawText(text, rect.left + padding,
                rect.bottom - padding - labelPaint.descent(), labelPaint);
    }

    /**
     * 运动标记：给真机上肉眼验证测速用的。
     *
     * <p>横穿优先于靠近：一个横向走过的人尺度几乎不变，两个通道给出的结论含义完全不同，
     * 同时成立时"横穿"才是更该看的那个。
     */
    @NonNull
    private static String motionMark(@NonNull SceneSnapshot.TrackSnapshot track) {
        if (!Float.isNaN(track.centerXRate)
                && Math.abs(track.centerXRate) >= DynamicAdvisor.CROSS_ENTER) {
            return track.centerXRate > 0f ? " ▶横穿" : " ◀横穿";
        }
        if (track.ttc > 0f && track.ttc <= DynamicAdvisor.TTC_CAUTION_ENTER_S) {
            return " ▼接近";
        }
        // NaN 的比较恒为 false，所以算不出来的渠道自然会被跳过。
        if (track.rate > 0f) {
            return " ▼靠近";
        }
        return track.rate < 0f ? " ▲远离" : "";
    }

    private void drawStatus(@NonNull Canvas canvas) {
        String text = String.format(Locale.US, "前方通路 %d%%", Math.round(freeRatio * 100f));
        float padding = statusTextSize * 0.4f;
        float textWidth = statusPaint.measureText(text);

        rect.set(mapX(0f) + padding, mapY(0f) + padding,
                mapX(0f) + textWidth + padding * 3f, mapY(0f) + statusTextSize + padding * 3f);
        canvas.drawRect(rect, statusBackgroundPaint);
        canvas.drawText(text, rect.left + padding,
                rect.bottom - padding - statusPaint.descent(), statusPaint);
    }
}
