package com.sullivan.protectee.vision.scene;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一帧场景的**不可变**快照。
 *
 * <p>这是感知层与决策层之间的那个"信息"：{@code ObjectTracker} 维护的是可变的、只能被
 * 推理线程碰的轨迹对象，而快照一旦构造出来就不可变、可以随便跨线程传（交给界面画框、
 * 交给日志线程写盘都不会出问题）。
 *
 * <p>{@link #toJson()} / {@link #fromJson(String)} 是它的**日志与回放**接口：真机上开
 * {@code LOG_SCENE_JSON} 就能把每帧的真实数据记下来，回到电脑上回放调阈值；单测里也能
 * 拿它做端到端的输入输出。热路径（每帧的决策）**不走字符串**，直接消费对象。
 *
 * <p><b>这里只放测量值，不放判断结果。</b>"左/中/右"、"较近"、"正在靠近"这类结论各自的
 * 阈值只应该在决策层存在一份；快照里再存一遍，两处阈值早晚会不一致。
 */
public final class SceneSnapshot {

    /** 格式版本。字段增删时递增，日志回放才能认出旧数据。 */
    public static final int VERSION = 1;

    /** 这一帧的**曝光时刻**（毫秒，单调时钟）。不是推理完成的时刻。 */
    public final long timestampMs;
    public final int frameWidth;
    public final int frameHeight;
    public final int rotationDegrees;

    /** 本帧估计出的自运动速度代理；{@link Float#NaN} 表示没有做补偿。 */
    public final float egoVelocityProxy;
    /** 前方中央走廊未被遮挡的面积比例，1 表示完全畅通。 */
    public final float freeRatio;

    @NonNull
    public final List<TrackSnapshot> tracks;

    public SceneSnapshot(long timestampMs, int frameWidth, int frameHeight, int rotationDegrees,
                         float egoVelocityProxy, float freeRatio,
                         @NonNull List<TrackSnapshot> tracks) {
        this.timestampMs = timestampMs;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.rotationDegrees = rotationDegrees;
        this.egoVelocityProxy = egoVelocityProxy;
        this.freeRatio = freeRatio;
        this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    /** 一条轨迹的快照：框 + 本帧测到的运动量，全部是原始读数。 */
    public static final class TrackSnapshot {
        public final int id;
        @NonNull
        public final String label;
        public final float score;
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        /** 自运动补偿后的尺度增长率 {@code d(ln h)/dt}，1/s。正 = 在靠近。NaN = 算不出。 */
        public final float rate;
        /** 补偿前的增长率，用来区分"本来就慢"和"被补偿掉了"。 */
        public final float rawRate;
        /** 剩余碰撞时间（秒），已做质心校正。NaN = 没有得出"正在靠近"。 */
        public final float ttc;
        /** 框高（归一化）。 */
        public final float height;
        /** 横向移动速率 {@code d(centerX)/dt}，1/s。NaN = 算不出。 */
        public final float centerXRate;
        /** 参与拟合的样本数。 */
        public final int samples;
        /** 累计匹配帧数。 */
        public final int hits;
        /** 框底边贴到了画面底部：此时尺度通道失效，只能靠"非常近"处理。 */
        public final boolean clipped;

        public TrackSnapshot(int id, @NonNull String label, float score, float left, float top,
                             float right, float bottom, float rate, float rawRate, float ttc,
                             float height, float centerXRate, int samples, int hits,
                             boolean clipped) {
            this.id = id;
            this.label = label;
            this.score = score;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.rate = rate;
            this.rawRate = rawRate;
            this.ttc = ttc;
            this.height = height;
            this.centerXRate = centerXRate;
            this.samples = samples;
            this.hits = hits;
            this.clipped = clipped;
        }

        public float centerX() {
            return (left + right) * 0.5f;
        }

        /** 是否正在靠近：有正的相对增长率。 */
        public boolean isApproaching() {
            return rate > 0f;
        }

        @NonNull
        @Override
        public String toString() {
            return "#" + id + " " + label + " rate=" + rate + " ttc=" + ttc;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TrackSnapshot)) {
                return false;
            }
            TrackSnapshot that = (TrackSnapshot) other;
            return id == that.id
                    && samples == that.samples
                    && hits == that.hits
                    && clipped == that.clipped
                    && label.equals(that.label)
                    && Float.compare(score, that.score) == 0
                    && Float.compare(left, that.left) == 0
                    && Float.compare(top, that.top) == 0
                    && Float.compare(right, that.right) == 0
                    && Float.compare(bottom, that.bottom) == 0
                    && Float.compare(rate, that.rate) == 0
                    && Float.compare(rawRate, that.rawRate) == 0
                    && Float.compare(ttc, that.ttc) == 0
                    && Float.compare(height, that.height) == 0
                    && Float.compare(centerXRate, that.centerXRate) == 0;
        }

        @Override
        public int hashCode() {
            int result = id;
            result = 31 * result + label.hashCode();
            result = 31 * result + Float.hashCode(score);
            result = 31 * result + Float.hashCode(left);
            result = 31 * result + Float.hashCode(top);
            result = 31 * result + Float.hashCode(right);
            result = 31 * result + Float.hashCode(bottom);
            result = 31 * result + Float.hashCode(rate);
            result = 31 * result + Float.hashCode(rawRate);
            result = 31 * result + Float.hashCode(ttc);
            result = 31 * result + Float.hashCode(height);
            result = 31 * result + Float.hashCode(centerXRate);
            result = 31 * result + samples;
            result = 31 * result + hits;
            result = 31 * result + (clipped ? 1 : 0);
            return result;
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /** 序列化成一行 JSON。NaN / Infinity 会写成 {@code null}。 */
    @NonNull
    public String toJson() {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        out.append("\"version\":").append(VERSION).append(',');
        out.append("\"timestampMs\":").append(timestampMs).append(',');
        out.append("\"frameWidth\":").append(frameWidth).append(',');
        out.append("\"frameHeight\":").append(frameHeight).append(',');
        out.append("\"rotationDegrees\":").append(rotationDegrees).append(',');
        out.append("\"egoVelocityProxy\":");
        Json.writeNumber(out, egoVelocityProxy);
        out.append(",\"freeRatio\":");
        Json.writeNumber(out, freeRatio);
        out.append(",\"tracks\":[");
        for (int i = 0; i < tracks.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendTrack(out, tracks.get(i));
        }
        out.append("]}");
        return out.toString();
    }

    private static void appendTrack(@NonNull StringBuilder out, @NonNull TrackSnapshot track) {
        out.append("{\"id\":").append(track.id);
        out.append(",\"label\":");
        Json.writeString(out, track.label);
        out.append(",\"score\":");
        Json.writeNumber(out, track.score);
        out.append(",\"left\":");
        Json.writeNumber(out, track.left);
        out.append(",\"top\":");
        Json.writeNumber(out, track.top);
        out.append(",\"right\":");
        Json.writeNumber(out, track.right);
        out.append(",\"bottom\":");
        Json.writeNumber(out, track.bottom);
        out.append(",\"rate\":");
        Json.writeNumber(out, track.rate);
        out.append(",\"rawRate\":");
        Json.writeNumber(out, track.rawRate);
        out.append(",\"ttc\":");
        Json.writeNumber(out, track.ttc);
        out.append(",\"height\":");
        Json.writeNumber(out, track.height);
        out.append(",\"centerXRate\":");
        Json.writeNumber(out, track.centerXRate);
        out.append(",\"samples\":").append(track.samples);
        out.append(",\"hits\":").append(track.hits);
        out.append(",\"clipped\":").append(track.clipped);
        out.append('}');
    }

    /** 从 {@link #toJson()} 的输出读回来。与 {@link #equals} 配合可做**精确**的往返校验。 */
    @NonNull
    public static SceneSnapshot fromJson(@NonNull String json) {
        Map<String, Object> root = Json.asObject(Json.parse(json));
        Json.getInt(root, "version");

        List<Object> rawTracks = Json.asList(Json.get(root, "tracks"));
        List<TrackSnapshot> tracks = new ArrayList<>(rawTracks.size());
        for (Object rawTrack : rawTracks) {
            Map<String, Object> track = Json.asObject(rawTrack);
            tracks.add(new TrackSnapshot(
                    Json.getInt(track, "id"),
                    Json.getString(track, "label"),
                    Json.getFloat(track, "score"),
                    Json.getFloat(track, "left"),
                    Json.getFloat(track, "top"),
                    Json.getFloat(track, "right"),
                    Json.getFloat(track, "bottom"),
                    Json.getFloat(track, "rate"),
                    Json.getFloat(track, "rawRate"),
                    Json.getFloat(track, "ttc"),
                    Json.getFloat(track, "height"),
                    Json.getFloat(track, "centerXRate"),
                    Json.getInt(track, "samples"),
                    Json.getInt(track, "hits"),
                    Json.getBoolean(track, "clipped")));
        }

        return new SceneSnapshot(
                Json.getLong(root, "timestampMs"),
                Json.getInt(root, "frameWidth"),
                Json.getInt(root, "frameHeight"),
                Json.getInt(root, "rotationDegrees"),
                Json.getFloat(root, "egoVelocityProxy"),
                Json.getFloat(root, "freeRatio"),
                tracks);
    }

    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SceneSnapshot)) {
            return false;
        }
        SceneSnapshot that = (SceneSnapshot) other;
        return timestampMs == that.timestampMs
                && frameWidth == that.frameWidth
                && frameHeight == that.frameHeight
                && rotationDegrees == that.rotationDegrees
                // 每个 float 都用 Float.compare：一次覆盖 -0.0 与 NaN 两个坑
                // （== 认为 NaN != NaN，也认为 -0.0f == 0.0f）。
                && Float.compare(egoVelocityProxy, that.egoVelocityProxy) == 0
                && Float.compare(freeRatio, that.freeRatio) == 0
                && tracks.equals(that.tracks);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(timestampMs);
        result = 31 * result + frameWidth;
        result = 31 * result + frameHeight;
        result = 31 * result + rotationDegrees;
        result = 31 * result + Float.hashCode(egoVelocityProxy);
        result = 31 * result + Float.hashCode(freeRatio);
        result = 31 * result + tracks.hashCode();
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "SceneSnapshot(t=" + timestampMs + ", tracks=" + tracks.size()
                + ", ego=" + egoVelocityProxy + ")";
    }
}
