package com.sullivan.protectee.vision.track;

import androidx.annotation.NonNull;

import com.sullivan.protectee.vision.detect.Detection;

/**
 * 一条跨帧的轨迹：同一个目标在连续多帧里的观测。
 *
 * <p>模型每帧独立推理，单帧结果里没有"这个东西上一帧在哪"的信息。轨迹把这层状态补上，
 * 于是才能回答"它在靠近还是在远离"这类只有时间维度上才有意义的问题。
 *
 * <p>字段全部可变，且**不是线程安全的**：只允许推理线程访问。轨迹对象在
 * {@link ObjectTracker} 内部复用，不要把它交给界面线程长期持有——要跨线程传递就拷成
 * 不可变快照。
 */
public final class Track {

    /** 全跟踪器内唯一、单调递增的编号。只用于去重键与调试显示。 */
    public final int id;

    /** 本帧（或最后一次匹配到的帧）的框。 */
    @NonNull
    public Detection box;

    /** 代表类别，由 {@link ObjectMerger} 挑选（人 &gt; 车 &gt; 其他）。 */
    @NonNull
    public String label;

    /** 累计匹配上的帧数。新目标从 1 开始。 */
    public int hits;

    /** 连续未匹配的帧数。匹配上就清零。 */
    public int misses;

    public long firstSeenMs;
    public long lastSeenMs;

    /** 本帧的框底边贴到了画面底部（离得太近或太靠下）。 */
    public boolean clipped;
    /** 本帧的高度比上一次骤降，判为遮挡——**不能被当成"在远离"**。 */
    public boolean occluded;
    /** 本帧是否匹配到了检测框。未匹配的轨迹沿用上一次的框，仅供绘制。 */
    public boolean matchedThisFrame;

    /** 观测序列，测速的原料。 */
    @NonNull
    public final TrackWindow window = new TrackWindow();

    // ---- 测速通道的输出，由 ObjectTracker 每帧刷新（见 TtcEstimator / EgoMotionEstimator）----

    /**
     * 自运动补偿后的尺度增长率 {@code d(ln h)/dt}，单位 1/s。
     * 正 = 在靠近。算不出来时为 {@link Float#NaN}。
     */
    public float relativeRate = Float.NaN;

    /** 补偿前的原始增长率，单位 1/s。留着是为了调试时能区分"本来就慢"和"被补偿掉了"。 */
    public float rawRate = Float.NaN;

    /**
     * 原始增长率所属的**时间质心**（绝对毫秒）。
     *
     * <p>最小二乘给出的是这个时刻的斜率。{@link TtcEstimator#ttcSeconds} 要用它把结论
     * 推到"当前时刻"，所以这个量必须跟着增长率一起传下去。
     */
    public long rateCentroidMs;

    /**
     * 原始增长率所属时刻的框高（窗口内高度的几何平均）。
     *
     * <p>自运动补偿要用它，而不是 {@link #box} 当前帧的高度——两者不是同一时刻的量。
     */
    public float rateCentroidHeight = Float.NaN;

    /**
     * 到碰撞的剩余时间（秒），已经把拟合窗口质心到当前时刻的推移算进去。
     * 正在靠近时为正，其余情况为 {@link Float#NaN}。
     */
    public float ttcSeconds = Float.NaN;

    /** 横幅方向的移动速率 {@code d(centerX)/dt}，单位 1/s（画面宽度比例/秒）。 */
    public float lateralRate = Float.NaN;

    /** 参与拟合的样本数；0 表示这一帧没能拟合。 */
    public int samples;

    public Track(int id, @NonNull Detection box, @NonNull String label, long nowMs) {
        this.id = id;
        this.box = box;
        this.label = label;
        this.firstSeenMs = nowMs;
        this.lastSeenMs = nowMs;
        // 建轨时这一帧就已经算一次观测；否则"连续两帧确认"会退化成要等三帧。
        this.hits = 1;
    }

    /** 是否已达到"值得播报"的确认门槛。 */
    public boolean isConfirmed() {
        return hits >= ObjectTracker.MIN_HITS;
    }

    /** 从首次出现到现在存活了多久。 */
    public long ageMs(long nowMs) {
        return nowMs - firstSeenMs;
    }

    /** 本帧的置信度。 */
    public float score() {
        return box.score;
    }

    @NonNull
    @Override
    public String toString() {
        return "#" + id + " " + label + " hits=" + hits + " misses=" + misses
                + " rate=" + relativeRate + " ttc=" + ttcSeconds;
    }
}
