package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.vision.depth.DepthMap;
import com.sullivan.protectee.vision.depth.DistancePolicy;
import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.scene.SceneSnapshot;
import com.sullivan.protectee.vision.track.ObjectTracker;
import com.sullivan.protectee.vision.track.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * 感知与播报决策之间的那一段管线：检测框进，一句话出。
 *
 * <pre>
 *   检测框 → ObjectTracker（合并 / 关联 / 测速）
 *          → SceneSnapshot（不可变，供日志与回放）
 *          → ObstacleAdvisor（静态提示）+ DynamicAdvisor（动态告警）
 *          → Decision（说哪句、什么等级、去重键是什么）
 * </pre>
 *
 * <p>之所以把这些收拢到一个类里，是为了让"两个重叠的框只播报一次""有人正在走过来要
 * 提醒"这类**端到端行为可以在单测里断言**，而不必每次改动都上真机走一遍。
 *
 * <p><b>只允许推理线程访问</b>：内部的追踪器、提示器、动态告警都保存着跨帧状态，
 * 且都靠"单线程串行"来代替加锁。{@link Decision} 里的东西则是不可变的，可以随便
 * 交给界面线程。
 */
public final class SceneAnalyzer {

    /** 一次分析的结果：说哪句话，以及支撑它的原始数据。 */
    public static final class Decision {
        /** 本帧的完整场景快照（也是给界面画框用的）。 */
        @NonNull
        public final SceneSnapshot snapshot;
        /** 静态提示：前面有什么。 */
        @NonNull
        public final ObstacleAdvisor.Advice advice;
        /** 动态告警：谁在靠近、谁在横穿。没有就是 {@code null}。 */
        @Nullable
        public final DynamicAdvisor.Alert dynamic;

        /** 最终要播报的那句话。 */
        @NonNull
        public final String message;
        @NonNull
        public final ObstacleAdvisor.Level level;
        @NonNull
        public final String dedupKey;
        /** 见 {@link AnnouncementPolicy#decide} 的同名参数：动态告警自带滞回，不必再等稳定窗口。 */
        public final boolean hysteretic;

        Decision(@NonNull SceneSnapshot snapshot, @NonNull ObstacleAdvisor.Advice advice,
                 @Nullable DynamicAdvisor.Alert dynamic) {
            this.snapshot = snapshot;
            this.advice = advice;
            this.dynamic = dynamic;
            if (dynamic != null) {
                // 有动态结论时只说那一句，**不附带静态清单**：紧急时刻需要一句能听清的话，
                // 而不是一串"中央行人，请注意；右侧自行车，请注意"。
                this.message = dynamic.message;
                this.level = dynamic.level;
                this.dedupKey = dynamic.dedupKey;
                this.hysteretic = true;
            } else {
                this.message = advice.message;
                this.level = advice.level;
                this.dedupKey = advice.dedupKey;
                this.hysteretic = false;
            }
        }
    }

    private final ObjectTracker tracker = new ObjectTracker();
    private final ObstacleAdvisor advisor = new ObstacleAdvisor();
    private final DynamicAdvisor dynamicAdvisor = new DynamicAdvisor();

    /** 复用的框列表，避免每帧重建。只装引用，目标框本身归轨迹所有。 */
    private final List<Detection> boxBuffer = new ArrayList<>();

    /**
     * 用户的播报频率档位。
     *
     * <p><b>这是本类唯一一处允许跨线程写的字段</b>：界面线程 {@link #setRate}、推理线程
     * {@link #analyze} 里读。volatile 是必须的（保证安全发布），而 {@link AnnounceRate}
     * 本身不可变，所以读到的实例一定自洽。
     */
    @NonNull
    private volatile AnnounceRate rate = AnnounceRate.STANDARD;

    /** 换档位。见 {@link #rate} 的线程说明。 */
    public void setRate(@NonNull AnnounceRate rate) {
        this.rate = rate;
    }

    /**
     * 丢弃所有跨帧状态。换模型、重置场景时调用。
     *
     * <p>换模型之后类别表、坐标语义都可能变，旧轨迹继续用只会算出乱七八糟的速度。
     */
    public void reset() {
        tracker.reset();
        dynamicAdvisor.reset();
    }

    /**
     * 处理一帧。
     *
     * @param detections   本帧的原始检测框
     * @param capturedAtMs 相机**曝光时刻**（毫秒）。不要传推理完成的时刻。
     * @param epoch        画面几何特征；变了会自动作废所有轨迹
     */
    @NonNull
    public Decision analyze(@NonNull List<Detection> detections, long capturedAtMs,
                            @NonNull ObjectTracker.FrameEpoch epoch) {
        return analyze(detections, capturedAtMs, epoch, null, null);
    }

    /**
     * 带深度图的版本。
     *
     * @param depth     与 {@code detections} **同一帧**的深度图；{@code null} 表示没有深度模型，
     *                  此时距离判断与文案全部退回改造前的几何启发式
     * @param letterbox **深度模型自己**的 letterbox 几何，不是检测器的。
     *                  检测框是相对「内容区」归一化的，而深度图是整张 letterbox 输入
     *                  （官方权重是 768×768，检测是 640×640 —— 两者不同），
     *                  换算成深度像素时必须用生成**深度图那一份**参数。
     *                  传错了不会崩，只会采样到旁边几十像素处、读出错误的距离。
     */
    @NonNull
    public Decision analyze(@NonNull List<Detection> detections, long capturedAtMs,
                            @NonNull ObjectTracker.FrameEpoch epoch,
                            @Nullable DepthMap depth, @Nullable Letterbox letterbox) {
        List<Track> confirmed = tracker.update(detections, capturedAtMs, epoch);

        List<SceneSnapshot.TrackSnapshot> tracks = new ArrayList<>(confirmed.size());
        boxBuffer.clear();
        for (Track track : confirmed) {
            // 非障碍类别不进决策层：街景里模型偶尔把什么认成"书""电视"，那不该变成一句
            // "电视正在靠近"。它们仍然参与自运动估计（那是追踪器内部的事）。
            if (!CocoLabels.isObstacle(track.label)) {
                continue;
            }
            Detection box = track.box;
            float meters = DistancePolicy.metersOf(box, depth, letterbox);
            tracks.add(new SceneSnapshot.TrackSnapshot(track.id, track.label, box.score,
                    box.left, box.top, box.right, box.bottom,
                    track.relativeRate, track.rawRate, track.ttcSeconds,
                    box.height(), track.lateralRate, track.samples, track.hits, track.clipped,
                    meters));
            boxBuffer.add(box);
        }

        // 静态提示走"已确认的轨迹"而不是本帧原始检测：只露一帧就消失的多半是误检。
        ObstacleAdvisor.Advice advice =
                advisor.advise(boxBuffer, rate.maxReported(), depth, letterbox);

        SceneSnapshot snapshot = new SceneSnapshot(capturedAtMs, epoch.width, epoch.height,
                epoch.rotationDegrees, tracker.egoVelocityProxy(), advice.freeRatio, tracks);
        DynamicAdvisor.Alert dynamic = dynamicAdvisor.evaluate(snapshot, capturedAtMs);

        return new Decision(snapshot, advice, dynamic);
    }
}
