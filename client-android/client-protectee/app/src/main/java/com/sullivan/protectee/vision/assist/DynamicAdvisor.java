package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.scene.CorridorCoverage;
import com.sullivan.protectee.vision.scene.SceneSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 动态告警：谁在靠近、谁在横穿。
 *
 * <p>静态提示回答的是"前面有什么"，这里回答的是"接下来会怎样"——只有跨帧追踪才能回答。
 *
 * <p><b>三条通道，全部带滞回</b>（进/出门限分开）。没有滞回的话，目标在门限附近抖动会
 * 让告警反复出现又消失，比不报更烦人。
 *
 * <ol>
 *   <li><b>靠近（尺度通道）</b>：用 {@code TtcEstimator} 算出的剩余碰撞时间分档。</li>
 *   <li><b>横穿（横向通道）</b>：纯横向移动的目标**面积和高度都恒定**，任何基于尺度的
 *       TTC 都看不见它（{@code d(ln h)/dt ≈ 0}），必须对 {@code centerX} 单独拟合。</li>
 *   <li><b>由远及近（兜底）</b>：算不出 TTC 时（样本不足、被裁切、自运动估计被拒），
 *       用"1.5 秒前它在远处、现在到了近处"这个**观测事实**兜底。它不需要最小二乘、
 *       也不需要自运动补偿，比任何单帧导数都稳。</li>
 * </ol>
 *
 * <p><b>没有自运动补偿时不给 DANGER</b>：单目下"他走向你"和"你走向他"在几何上无法区分，
 * 唯一的判据是自运动估计。拿不到它，就无法证明这个目标比用户自己走路来得更快，于是
 * 只降到 CAUTION——紧急等级交给静态通道（"很近且在正前方"）去给。
 *
 * <p><b>只允许推理线程访问</b>（要保存每条轨迹的滞回状态）。
 */
public final class DynamicAdvisor {

    /** 进入"要撞上了"的门限（秒）。 */
    public static final float TTC_DANGER_ENTER_S = 1.8f;
    /** 退出"要撞上了"的门限。比进门限大，才不会被抖动反复触发。 */
    public static final float TTC_DANGER_EXIT_S = 3.0f;
    /** 进入"注意"的门限。 */
    public static final float TTC_CAUTION_ENTER_S = 3.0f;
    /** 退出"注意"的门限。 */
    public static final float TTC_CAUTION_EXIT_S = 4.2f;

    /** 横向速率进入/退出"横穿"的门限，单位是画面宽度/秒。 */
    public static final float CROSS_ENTER = 0.10f;
    public static final float CROSS_EXIT = 0.06f;

    /** 横穿判据要预测多久之后的位置：1 秒。 */
    public static final float CROSS_HORIZON_S = 1.0f;

    /** 兜底通道要求的最小存活时长与匹配帧数。太年轻就谈不上"由远及近"。 */
    public static final long FALLBACK_MIN_AGE_MS = 1500L;
    public static final int FALLBACK_MIN_HITS = 3;

    /** 动态告警的种类。 */
    public enum Motion {
        /** 正在靠近。 */
        APPROACHING,
        /** 正在横穿。 */
        CROSSING
    }

    /** 一条动态告警。 */
    public static final class Alert {
        @NonNull
        public final Motion motion;
        @NonNull
        public final ObstacleAdvisor.Level level;
        /** 触发告警的轨迹编号，界面据此高亮对应的框。 */
        public final int trackId;
        @NonNull
        public final String label;
        @NonNull
        public final String message;
        /** 语义去重键。含轨迹号与档位，所以合理升级不会被当成"刚说过"而被吞掉。 */
        @NonNull
        public final String dedupKey;
        /** 剩余碰撞时间（秒）；兜底通道触发时为 NaN。 */
        public final float ttc;

        Alert(@NonNull Motion motion, @NonNull ObstacleAdvisor.Level level, int trackId,
              @NonNull String label, @NonNull String message, @NonNull String dedupKey,
              float ttc) {
            this.motion = motion;
            this.level = level;
            this.trackId = trackId;
            this.label = label;
            this.message = message;
            this.dedupKey = dedupKey;
            this.ttc = ttc;
        }

        @NonNull
        @Override
        public String toString() {
            return level + " " + motion + " " + message + " (" + dedupKey + ")";
        }
    }

    /** 每条轨迹的滞回状态。 */
    private static final class TrackState {
        /** 靠近档位：0 = 无，1 = CAUTION，2 = DANGER。 */
        int approachLevel;
        boolean crossing;
        /** 上一帧是否已经在"近处"。兜底通道判的是它的**跳变**。 */
        boolean wasNear;
        long firstSeenMs;
    }

    private final Map<Integer, TrackState> states = new HashMap<>();
    private final Set<Integer> pendingRemoval = new HashSet<>();

    /**
     * 评估本帧的动态告警。
     *
     * @return 最紧急的那一条；没有动态结论时返回 {@code null}。
     */
    @Nullable
    public Alert evaluate(@NonNull SceneSnapshot snapshot, long nowMs) {
        forgetVanishedTracks(snapshot);

        // 自运动估计拿不到时（轨迹不足、场景不一致），尺度通道的结论不够硬，不给 DANGER。
        boolean compensated = !Float.isNaN(snapshot.egoVelocityProxy);

        Alert best = null;
        int bestPriority = 0;
        for (SceneSnapshot.TrackSnapshot track : snapshot.tracks) {
            TrackState state = stateFor(track, nowMs);
            int approachLevel = updateApproachLevel(state, track.ttc);
            boolean crossing = updateCrossing(state, track);
            boolean fallback = approachLevel == 0 && Float.isNaN(track.ttc)
                    && updateFarToNear(state, track, nowMs);

            Alert alert = null;
            int priority;
            if (approachLevel > 0) {
                priority = approachLevel == 2 && compensated ? 3 : 2;
                alert = approachAlert(track, approachLevel, compensated);
            } else if (crossing) {
                priority = 1;
                alert = crossingAlert(track);
            } else if (fallback) {
                priority = 1;
                alert = fallbackAlert(track);
            } else {
                continue;
            }

            if (priority > bestPriority || (priority == bestPriority && isMoreUrgent(alert, best))) {
                bestPriority = priority;
                best = alert;
            }
        }
        return best;
    }

    /** 丢弃已经消失的轨迹的滞回状态；否则轨迹号一直攒着，场景一变就漏。 */
    private void forgetVanishedTracks(@NonNull SceneSnapshot snapshot) {
        if (states.isEmpty()) {
            return;
        }
        pendingRemoval.clear();
        pendingRemoval.addAll(states.keySet());
        for (SceneSnapshot.TrackSnapshot track : snapshot.tracks) {
            pendingRemoval.remove(track.id);
        }
        for (Integer id : pendingRemoval) {
            states.remove(id);
        }
    }

    @NonNull
    private TrackState stateFor(@NonNull SceneSnapshot.TrackSnapshot track, long nowMs) {
        TrackState state = states.get(track.id);
        if (state == null) {
            state = new TrackState();
            state.firstSeenMs = nowMs;
            // 第一帧就按当前观测定"近不远"：否则一个**一出现就在近处**的目标会在
            // 1.5 秒后凭空触发一次"由远及近"。
            state.wasNear = isNear(track);
            states.put(track.id, state);
        }
        return state;
    }

    /**
     * 更新"靠近"档位，带滞回。升档用进门限，降档要越过更松的出门限。
     */
    private static int updateApproachLevel(@NonNull TrackState state, float ttc) {
        int current = state.approachLevel;
        int next;
        if (Float.isNaN(ttc)) {
            next = 0;
        } else if (current >= 2) {
            next = ttc <= TTC_DANGER_EXIT_S ? 2 : (ttc <= TTC_CAUTION_EXIT_S ? 1 : 0);
        } else if (current == 1) {
            next = ttc <= TTC_DANGER_ENTER_S ? 2 : (ttc <= TTC_CAUTION_EXIT_S ? 1 : 0);
        } else {
            next = ttc <= TTC_DANGER_ENTER_S ? 2 : (ttc <= TTC_CAUTION_ENTER_S ? 1 : 0);
        }
        state.approachLevel = next;
        return next;
    }

    /**
     * 横向通道：目标在一秒后会不会进入前方走廊。
     *
     * <p>用**预测位置**而不是当前位置：一个正在横穿过来的人，此刻还在画面边上，
     * 等到他进入走廊再报就只剩零点几秒了。
     */
    private static boolean updateCrossing(@NonNull TrackState state,
                                          @NonNull SceneSnapshot.TrackSnapshot track) {
        float rate = track.centerXRate;
        boolean crossing;
        if (Float.isNaN(rate)) {
            crossing = false;
        } else {
            float threshold = state.crossing ? CROSS_EXIT : CROSS_ENTER;
            float futureCenterX = track.centerX() + rate * CROSS_HORIZON_S;
            crossing = Math.abs(rate) >= threshold
                    && CorridorCoverage.isInCorridor(futureCenterX, track.bottom);
        }
        state.crossing = crossing;
        return crossing;
    }

    /**
     * 兜底通道：观测到"由远及近"的跳变。只在尺度通道给不出结论时才走。
     */
    private static boolean updateFarToNear(@NonNull TrackState state,
                                           @NonNull SceneSnapshot.TrackSnapshot track,
                                           long nowMs) {
        boolean near = isNear(track);
        boolean jumped = !state.wasNear && near
                && nowMs - state.firstSeenMs >= FALLBACK_MIN_AGE_MS
                && track.hits >= FALLBACK_MIN_HITS
                && "中央".equals(ObstacleAdvisor.positionOf(track.centerX()));
        state.wasNear = near;
        return jumped;
    }

    private static boolean isNear(@NonNull SceneSnapshot.TrackSnapshot track) {
        return ObstacleAdvisor.isNear(track.left, track.top, track.right, track.bottom);
    }

    @NonNull
    private static Alert approachAlert(@NonNull SceneSnapshot.TrackSnapshot track, int level,
                                       boolean compensated) {
        String label = CocoLabels.translate(track.label);
        // TTS 中文约 5 字/秒，还要加上合成启动的几百毫秒。若 TTC 只剩 1.5 秒，一句 12 字的
        // 提示念完时人已经撞上了——所以这里的话必须短到不讲道理。
        if (level == 2 && compensated) {
            return new Alert(Motion.APPROACHING, ObstacleAdvisor.Level.DANGER, track.id,
                    track.label, label + "接近", "dyn:" + track.id + ":near", track.ttc);
        }
        // 没有自运动补偿时，level 2 也退成 CAUTION：可能是用户自己走过去的。
        return new Alert(Motion.APPROACHING, ObstacleAdvisor.Level.CAUTION, track.id,
                track.label, label + "正在靠近", "dyn:" + track.id + ":approach", track.ttc);
    }

    @NonNull
    private static Alert crossingAlert(@NonNull SceneSnapshot.TrackSnapshot track) {
        String label = CocoLabels.translate(track.label);
        return new Alert(Motion.CROSSING, ObstacleAdvisor.Level.CAUTION, track.id,
                track.label, label + "横穿", "dyn:" + track.id + ":cross", Float.NaN);
    }

    @NonNull
    private static Alert fallbackAlert(@NonNull SceneSnapshot.TrackSnapshot track) {
        String label = CocoLabels.translate(track.label);
        return new Alert(Motion.APPROACHING, ObstacleAdvisor.Level.CAUTION, track.id,
                track.label, label + "正在靠近", "dyn:" + track.id + ":approach", Float.NaN);
    }

    /** 同档位时挑更急的那条：TTC 更小优先，其次挑框更大的。 */
    private static boolean isMoreUrgent(@Nullable Alert candidate, @Nullable Alert current) {
        if (current == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        float a = Float.isNaN(candidate.ttc) ? Float.MAX_VALUE : candidate.ttc;
        float b = Float.isNaN(current.ttc) ? Float.MAX_VALUE : current.ttc;
        return a < b;
    }

    /**
     * 当前记着几条轨迹的滞回状态。用于测试与调试。
     *
     * <p>公开是为了让 {@code DynamicAdvisorTest} 能直接验证"消失的轨迹要被忘掉"——状态泄漏在
     * 真机上的表现是"很久以前那个人还会让告警跳一下"，不测就发现不了。
     */
    public int trackedStateCount() {
        return states.size();
    }

    /** 清空所有滞回状态。换模型、场景重置时调用。 */
    public void reset() {
        states.clear();
    }

    /** 调试用：打印当前所有状态。 */
    @NonNull
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("DynamicAdvisor{");
        Iterator<Map.Entry<Integer, TrackState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrackState> entry = iterator.next();
            out.append('#').append(entry.getKey())
                    .append("=L").append(entry.getValue().approachLevel);
            if (iterator.hasNext()) {
                out.append(", ");
            }
        }
        return out.append('}').toString();
    }
}
