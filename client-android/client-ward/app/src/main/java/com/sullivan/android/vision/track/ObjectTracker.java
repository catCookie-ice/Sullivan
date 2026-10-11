package com.sullivan.android.vision.track;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.vision.detect.CocoLabels;
import com.sullivan.android.vision.detect.Detection;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 跨帧关联：把每一帧各自独立的检测框串成有身份的轨迹。
 *
 * <p>这是"动态播报"的前提——只有知道一个目标上一帧在哪，才谈得上"它在靠近"。
 *
 * <p><b>只允许推理线程访问。</b>整条管线跑在 {@code MainActivity} 的单个推理线程上，
 * 所以这里不加锁；一旦有人从别的线程调 {@link #update}，轨迹状态会静默错乱。
 */
public final class ObjectTracker {

    /**
     * 关联用的 IoU 门槛，**必须比 NMS 的 0.45 松得多**。
     *
     * <p>算一下横向穿越的行人（1.4 m/s，约 2 m 外）相邻帧的 IoU：IoU 是尺度不变的量，
     * 与人离得多远无关，只取决于一帧内走了多远。5 FPS（帧间隔 200 ms）时位移 0.28 m，
     * 对应的 IoU 只有 <b>0.282</b>；10 FPS 是 0.562，15 FPS 是 0.685。
     * 若按直觉把门槛设成 0.3，**5 FPS 时横穿行人每一帧都会丢 ID**，追踪器永远建不起来，
     * 测速也就无从谈起。正面接近反而很稳：5 FPS、3 m 外、2.8 m/s 时 IoU = 0.662。
     *
     * <p>代价是 IoU 0.2 附近可能把两个紧挨着的目标关联错。所以另加了中心距兜底与跨类别
     * 从严（{@link #CROSS_CLASS_IOU_GATE}），并且合并阶段已经把"同一个人的两个框"
     * 收成了一个，剩下的都是真正分开的目标。
     *
     * <p>注意这个门限与中心距兜底是**两条并行的通道**：行人这类"窄而高"的框，一帧内
     * 横向位移与框高的比值是尺度无关的常量（1.4 m/s、5 FPS 时约 0.16），远小于
     * {@link #CENTER_GATE_BOX_FRACTION}，所以横穿行人通常由中心距兜底接住。IoU 门限
     * 仍是必要的第二道保险——兜底要求"离得近"，而 IoU 能接住那些中心距偏大、但框本身
     * 高度重叠的情况。
     */
    public static final float IOU_GATE = 0.20f;

    /**
     * 跨类别关联的门槛，比同类别严得多。
     *
     * <p>不能直接禁止跨类别关联：YOLO 对同一个骑车的人在 person 与 bicycle 之间来回翻转
     * 很常见，禁止了会导致 ID 每帧重建。但也不能放太松——相邻的行人和自行车本来就不是
     * 一个目标。
     */
    public static final float CROSS_CLASS_IOU_GATE = 0.35f;

    /**
     * IoU 不够时的兜底：中心距 ≤ 这个比例 × 两个框中较大的边长，就还认作同一个目标。
     *
     * <p>低帧率下目标可能一帧跳出 IoU 覆盖的范围（尤其是小框），此时中心距是更稳的量。
     * 用框尺寸归一化，这样远处的小目标不会被"0.05 的绝对距离"误判成不同目标。
     */
    public static final float CENTER_GATE_BOX_FRACTION = 0.75f;

    /**
     * 单帧高度跌幅超过这个比例，判为遮挡（被前面的车、柱子挡住了一部分）。
     *
     * <p>遮挡会让框突然变小，而"框变小"正是"在远离"的特征——不剔除就会把一辆静止的
     * 公交车误报成"有东西正在远离"，或者让正在靠近的行人的测速停滞甚至反向。
     */
    public static final float OCCLUSION_HEIGHT_DROP = 0.25f;

    /** 累计匹配上这么多帧之后，轨迹才算"确认"，才有资格被播报。 */
    public static final int MIN_HITS = 2;

    /** 同时存在的轨迹上限。行人密集的路口也不至于超过，超出说明关联已经乱了。 */
    public static final int MAX_TRACKS = 40;

    /**
     * 连续多少毫秒没匹配上就删掉轨迹。
     *
     * <p>用时间而不是帧数：帧率在 5–15 FPS 之间浮动，按帧数算的话"容忍 2 帧"在低帧率下
     * 只有 0.4 秒，在高帧率下却有 2 秒——同一条轨迹的寿命会随设备负载变化。
     * 500 ms 在 5 FPS 下容忍 2 帧，15 FPS 下容忍 7 帧。
     */
    public static final long MAX_MISS_MS = 500L;

    /** 观测窗口的时长。约 1.2 秒：够拟合出稳定的斜率，又不至于跨过"走路时人一转身"的尺度突变。 */
    public static final long WINDOW_MS = 1200L;

    /**
     * 画面几何特征。任何一个变了，归一化坐标的含义就变了，旧轨迹必须全部作废。
     *
     * <p>横竖屏切换最典型：`centerX` 与 `height` 的含义直接互换，拿旧样本去拟合会算出
     * 巨大的、方向都不对的增长率，表现为"一转屏就报有人快速靠近"。
     */
    public static final class FrameEpoch {
        /** 正立之后的画面宽度（像素）。 */
        public final int width;
        /** 正立之后的画面高度（像素）。 */
        public final int height;
        public final int rotationDegrees;

        public FrameEpoch(int width, int height, int rotationDegrees) {
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FrameEpoch)) {
                return false;
            }
            FrameEpoch that = (FrameEpoch) other;
            return width == that.width
                    && height == that.height
                    && rotationDegrees == that.rotationDegrees;
        }

        @Override
        public int hashCode() {
            return ((width * 31 + height) * 31 + rotationDegrees);
        }

        @NonNull
        @Override
        public String toString() {
            return width + "x" + height + "@" + rotationDegrees;
        }
    }

    /** 不允许关联的哨兵值。比所有真实得分都小。 */
    private static final float NO_MATCH = Float.NEGATIVE_INFINITY;

    private final List<Track> tracks = new ArrayList<>();
    /** 每帧复用的自运动样本表，避免在 10 FPS 的循环里反复建表。 */
    private final List<EgoMotionEstimator.Sample> egoSamples = new ArrayList<>();
    @Nullable
    private FrameEpoch epoch;
    private int nextId = 1;
    private float egoProxy = Float.NaN;

    /**
     * 丢弃所有轨迹。换模型、画面几何变化、场景重置时调用。
     *
     * <p>{@code nextId} **故意不重置**：播报去重键里编了轨迹号，重置后如果从 1 重新发号，
     * 新目标会沿用旧目标的去重键，那句提示就会被当成"刚说过"而被静默掉。
     */
    public void reset() {
        tracks.clear();
    }

    /**
     * 用本帧的检测结果推进所有轨迹。
     *
     * @param detections   本帧原始检测框（内部会先做同目标合并）
     * @param capturedAtMs 相机**曝光时刻**。不要用推理完成的时刻：推理要 60–120 ms，
     *                     与帧间隔同量级，用它当时间轴等于给速度估计注入同量级的抖动。
     * @return 本帧已确认的轨迹（新出现的要连续匹配 {@link #MIN_HITS} 帧才算数）
     */
    @NonNull
    public List<Track> update(@NonNull List<Detection> detections, long capturedAtMs,
                              @NonNull FrameEpoch epoch) {
        if (this.epoch == null) {
            this.epoch = epoch;
        } else if (!this.epoch.equals(epoch)) {
            reset();
            this.epoch = epoch;
        }

        List<ObjectMerger.Merged> targets = ObjectMerger.merge(detections);
        for (Track track : tracks) {
            track.matchedThisFrame = false;
        }

        boolean[] targetTaken = new boolean[targets.size()];
        associate(targets, targetTaken, capturedAtMs);
        spawn(targets, targetTaken, capturedAtMs);
        expire(capturedAtMs);
        estimateMotion(capturedAtMs);

        List<Track> confirmed = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            if (track.isConfirmed()) {
                confirmed.add(track);
            }
        }
        return confirmed;
    }

    /** 当前活着的轨迹数（含尚未确认的）。 */
    public int trackCount() {
        return tracks.size();
    }

    /** 最近一次传入的画面特征；还没跑过任何一帧时为 null。 */
    @Nullable
    public FrameEpoch epoch() {
        return epoch;
    }

    // ------------------------------------------------------------------
    // 关联
    // ------------------------------------------------------------------

    private void associate(@NonNull List<ObjectMerger.Merged> targets, boolean[] targetTaken,
                           long nowMs) {
        int trackCount = tracks.size();
        int targetCount = targets.size();
        if (trackCount == 0 || targetCount == 0) {
            return;
        }

        // 先一次性算出所有候选对的得分，再贪心挑选。得分表虽然朴素，但帧内只有几十个框，
        // 建表远比"每挑一对就重算一遍 IoU"划算。
        float[] scores = new float[trackCount * targetCount];
        for (int t = 0; t < trackCount; t++) {
            for (int d = 0; d < targetCount; d++) {
                scores[t * targetCount + d] = matchScore(tracks.get(t), targets.get(d).box);
            }
        }

        boolean[] trackTaken = new boolean[trackCount];
        while (true) {
            float best = NO_MATCH;
            int bestTrack = -1;
            int bestTarget = -1;
            for (int t = 0; t < trackCount; t++) {
                if (trackTaken[t]) {
                    continue;
                }
                for (int d = 0; d < targetCount; d++) {
                    if (targetTaken[d]) {
                        continue;
                    }
                    // 严格大于：并列时取下标小的，关联结果才不依赖遍历顺序以外的东西。
                    if (scores[t * targetCount + d] > best) {
                        best = scores[t * targetCount + d];
                        bestTrack = t;
                        bestTarget = d;
                    }
                }
            }
            if (bestTrack < 0) {
                return;
            }
            trackTaken[bestTrack] = true;
            targetTaken[bestTarget] = true;
            observe(tracks.get(bestTrack), targets.get(bestTarget), nowMs);
        }
    }

    /**
     * 一对"轨迹—检测框"的匹配得分。
     *
     * @return IoU（≥ 门槛时）；IoU 不够但中心距够近时返回一个**负数**得分，保证它排在所有
     *         IoU 匹配之后；都不满足时返回 {@link #NO_MATCH}。
     */
    private static float matchScore(@NonNull Track track, @NonNull Detection box) {
        Detection previous = track.box;
        float iou = previous.iou(box);
        float gate = track.label.equals(box.label) ? IOU_GATE : CROSS_CLASS_IOU_GATE;
        if (iou >= gate) {
            return iou;
        }
        float largest = Math.max(Math.max(previous.width(), previous.height()),
                Math.max(box.width(), box.height()));
        if (largest <= 0f) {
            return NO_MATCH;
        }
        float distance = previous.centerDistance(box);
        if (distance <= CENTER_GATE_BOX_FRACTION * largest) {
            return -distance / largest;
        }
        return NO_MATCH;
    }

    /** 把本帧的观测并进轨迹。 */
    private static void observe(@NonNull Track track, @NonNull ObjectMerger.Merged target,
                                long nowMs) {
        Detection box = target.box;
        float height = box.height();

        // 遮挡判定要和"上一个**好**样本"比：窗口里本来就不存遮挡帧，所以它天然是上个好样本。
        boolean occluded = false;
        if (height > 0f && !track.window.isEmpty()) {
            float previousHeight = track.window.height(track.window.size() - 1);
            occluded = previousHeight > 0f
                    && height < previousHeight * (1f - OCCLUSION_HEIGHT_DROP);
        }

        track.box = box;
        track.label = target.label;
        track.hits++;
        track.misses = 0;
        track.lastSeenMs = nowMs;
        track.matchedThisFrame = true;
        track.clipped = box.isClippedBottom();
        track.occluded = occluded;

        // 遮挡帧不进拟合。轨迹本身留着——目标很可能只是走过了一根柱子后面。
        if (!occluded && height > 0f) {
            track.window.add(nowMs, height, box.bottom, box.centerX(), box.score);
        }
        track.window.prune(nowMs, WINDOW_MS);
    }

    /** 没匹配上的检测框各自开一条新轨迹。 */
    private void spawn(@NonNull List<ObjectMerger.Merged> targets, boolean[] targetTaken,
                       long nowMs) {
        for (int i = 0; i < targets.size(); i++) {
            if (targetTaken[i]) {
                continue;
            }
            ObjectMerger.Merged target = targets.get(i);
            if (tracks.size() >= MAX_TRACKS) {
                // 画面里已经塞满目标了，这一帧的框里必然有大量误检。宁可丢掉新的，
                // 也不要把已经跟了半天的轨迹挤掉。
                return;
            }
            Track track = new Track(nextId++, target.box, target.label, nowMs);
            track.matchedThisFrame = true;
            track.clipped = target.box.isClippedBottom();
            if (target.box.height() > 0f) {
                track.window.add(nowMs, target.box.height(), target.box.bottom,
                        target.box.centerX(), target.box.score);
            }
            tracks.add(track);
        }
    }

    // ------------------------------------------------------------------
    // 测速
    // ------------------------------------------------------------------

    /**
     * 刷新每条轨迹的运动量：尺度增长率、自运动补偿、TTC、横向速率。
     *
     * <p>分两遍是必须的：自运动要从**整幅画面**的中位数里估，所以得先把所有轨迹的原始
     * 增长率都算出来，才能回头逐条补偿。
     */
    private void estimateMotion(long nowMs) {
        egoSamples.clear();
        for (Track track : tracks) {
            if (!track.matchedThisFrame) {
                stale(track);
                continue;
            }
            TtcEstimator.Fit fit = TtcEstimator.fitHeight(track.window, nowMs);
            track.rawRate = fit.ok ? fit.rate : Float.NaN;
            track.rateCentroidMs = fit.ok ? fit.centroidMs : 0L;
            track.rateCentroidHeight = fit.ok ? fit.centroidHeight : Float.NaN;
            track.samples = fit.samples;

            // 参与自运动估计的必须是"干净"的读数：没被裁切、没被遮挡、并且已经确认
            // （本帧匹配上是上面那道 continue 保证的）。拿刚建的轨迹或半截框去估，
            // 中位数会被拖走。
            if (!fit.ok || track.clipped || track.occluded || !track.isConfirmed()) {
                continue;
            }
            egoSamples.add(new EgoMotionEstimator.Sample(fit.rate, fit.centroidHeight,
                    CocoLabels.referenceHeightMeters(track.label)));
        }
        egoProxy = EgoMotionEstimator.estimateVelocityProxy(egoSamples);

        for (Track track : tracks) {
            if (!track.matchedThisFrame) {
                // stale() 在第一遍里已经清干净了，这里不用再算一遍横向拟合。
                continue;
            }
            TtcEstimator.Fit lateral = TtcEstimator.fitLateral(track.window, nowMs);
            track.lateralRate = lateral.ok ? lateral.rate : Float.NaN;

            if (Float.isNaN(track.rawRate)) {
                track.relativeRate = Float.NaN;
                track.ttcSeconds = Float.NaN;
                continue;
            }
            track.relativeRate = EgoMotionEstimator.compensatedRate(track.rawRate,
                    track.rateCentroidHeight, CocoLabels.referenceHeightMeters(track.label),
                    egoProxy);
            track.ttcSeconds = TtcEstimator.ttcSeconds(track.relativeRate,
                    track.rateCentroidMs, nowMs);
        }
    }

    /**
     * 把一条本帧没被看到的轨迹的运动量清成"没有读数"。
     *
     * <p>不清的后果很具体：{@code ttcSeconds} 里有一项是"距拟合质心过去了多久"，
     * 质心不动而墙钟在走，于是**一条已经消失的轨迹的剩余时间会自己往下掉**。
     * 500 ms 的宽限期足够把 2.2 秒压到 1.8 秒的告警门限以下——用户会在人已经走出
     * 画面、或者刚被柱子挡住之后，反而听到一句「行人接近」。
     * 没有新观测就没有新读数，这里宁可让动态通道自己失效。
     *
     * <p>轨迹本身仍留在快照里（{@code MAX_MISS_MS} 之内不删）：框继续画着，滞回状态
     * 继续记着，目标重新出现时接着用。{@code samples} 等描述**上一次观测**的字段也
     * 原样保留——所有消费方都会先看 {@code rate} 是不是 NaN。
     */
    private static void stale(@NonNull Track track) {
        track.rawRate = Float.NaN;
        track.relativeRate = Float.NaN;
        track.ttcSeconds = Float.NaN;
        track.lateralRate = Float.NaN;
    }

    /**
     * 本帧估计出的自运动速度代理 {@code v_ego·H_img/f}；{@link Float#NaN} 表示样本不足
     * 或场景不一致，没有做补偿。
     */
    public float egoVelocityProxy() {
        return egoProxy;
    }

    /** 累计未匹配时长，超时的轨迹删掉。 */
    private void expire(long nowMs) {
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next();
            if (track.matchedThisFrame) {
                continue;
            }
            track.misses++;
            if (nowMs - track.lastSeenMs > MAX_MISS_MS) {
                iterator.remove();
            }
        }
    }
}
