package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.vision.depth.DepthMap;
import com.sullivan.protectee.vision.depth.DistancePolicy;
import com.sullivan.protectee.vision.detect.CocoLabels;
import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.scene.CorridorCoverage;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一堆检测框变成一句人话提示。
 *
 * <p>相关阈值、"左侧/中央/右侧"的划分、远近的判断，都直接沿用 script.py 中
 * {@code PanopticRoadAssistant} 的实现，保证两个版本的口径一致。
 *
 * <p>唯一的替换是"道路区域占比"：原脚本靠全景分割得到路面掩膜，而 YOLO 检测
 * 没有掩膜，所以这里改成统计**画面下半部中央走廊**被障碍框覆盖的比例，取补集
 * 得到"前方通路"占比。这是启发式的估计，不是真实的可通行区域测量。
 *
 * <p><b>同方位同类别的目标合并成一句</b>：紧挨着的两个行人如果各报一次，用户听到的是
 * 「中央行人较近，注意；中央行人较近，注意」——同一句话重复两遍，信息量却是零。所以
 * 先按（方位，类别）聚类再播报：「中央两名行人，注意」。真正重叠的两个框（同一个人被
 * 检出两次）在更早的 {@code ObjectMerger} 里就已经合成一个框了，走不到这里。
 */
public final class ObstacleAdvisor {

    /** 提示等级，用于配色与振动强度。 */
    public enum Level {
        /** 前方通畅。 */
        CLEAR,
        /** 检测到障碍物。 */
        CAUTION,
        /** 障碍物很近且位于正前方，或前方通路被明显遮挡。 */
        DANGER
    }

    /** 一次分析的结果。 */
    public static final class Advice {
        /** 通过相关性筛选的障碍物，已按"由近到远"排序。 */
        public final List<Detection> obstacles;
        /** 给用户看的中文提示。 */
        public final String message;
        public final Level level;
        /** 前方通路的畅通比例，1 表示完全无遮挡。 */
        public final float freeRatio;
        /**
         * 语义去重键，由（方位、类别、数量、远近）拼成。
         *
         * <p>**不能拿文案当键**：包围盒在门限上抖动时，"较近，注意"与"，请注意"会来回跳，
         * 键跟着跳，去重就会失效——那正是"同一件事反复播报"的成因之一。
         */
        @NonNull
        public final String dedupKey;

        Advice(@NonNull List<Detection> obstacles, @NonNull String message, @NonNull Level level,
               float freeRatio) {
            this(obstacles, message, level, freeRatio, "static:" + message);
        }

        Advice(@NonNull List<Detection> obstacles, @NonNull String message, @NonNull Level level,
               float freeRatio, @NonNull String dedupKey) {
            this.obstacles = obstacles;
            this.message = message;
            this.level = level;
            this.freeRatio = freeRatio;
            this.dedupKey = dedupKey;
        }
    }

    /** 同方位、同类别的一簇目标。 */
    private static final class Group {
        final String position;
        final String label;
        int count;
        /** 组内**任意**一个目标算得上"近"，整组就算近。 */
        boolean near;
        /**
         * 组内最近那个目标的米数；没有深度图或取不到有效值时是 {@link Float#NaN}。
         *
         * <p>取组内**最小值**而不是平均：一簇里有一个已经贴到跟前，这句话就必须按
         * 最近的那个说，否则"中央三名行人"会把一个 0.8 米的人稀释成"约 3 米"。
         */
        float meters = Float.NaN;

        Group(String position, String label) {
            this.position = position;
            this.label = label;
        }

        /** 去重键里的一段：方位、类别、数量、远近。 */
        @NonNull
        String dedupKey() {
            return position + ':' + label + ':' + count + ':' + near;
        }
    }

    /** 画面顶部这个比例以内的目标只当背景，避免播报远处的无关物体。 */
    private static final float TOP_IGNORE_RATIO = 0.38f;
    /** 面积占比小于此值、且位置偏上的目标视为噪声。 */
    private static final float TINY_AREA_RATIO = 0.0015f;
    private static final float TINY_BOTTOM_RATIO = 0.60f;
    /** 画面左右边缘外一定范围内的目标不参与播报。 */
    private static final float SIDE_MARGIN = 0.05f;
    /** 横向位置划分：中心点小于左边界算左侧，大于右边界算右侧。 */
    private static final float LEFT_BAND = 0.35f;
    private static final float RIGHT_BAND = 0.65f;
    /** 框底边低于该比例，或面积占比超过该值，判定为"较近"。 */
    private static final float NEAR_BOTTOM_RATIO = 0.78f;
    private static final float NEAR_AREA_RATIO = 0.12f;
    /** 前方通路被遮挡超过该比例时判定为受阻。 */
    private static final float BLOCKED_RATIO = 0.35f;

    /** 走廊覆盖统计，复用栅格以免每帧分配。统计范围见 {@link CorridorCoverage}。 */
    private final CorridorCoverage corridorCoverage = new CorridorCoverage();

    /**
     * 分析一帧的检测结果。传入的列表不会被修改。等价于
     * {@code advise(detections, AnnounceRate.STANDARD.maxReported())}。
     */
    @NonNull
    public Advice advise(@NonNull List<Detection> detections) {
        return advise(detections, AnnounceRate.STANDARD.maxReported());
    }

    /**
     * 同 {@link #advise(List)}，但由调用方决定一句话里最多报几**簇**目标。
     *
     * <p>{@code maxReported} 来自用户的播报频率档位（见 {@link AnnounceRate}）。调低它意味着
     * 只报最近的那一簇——远处的、侧面的就不提了。这是用户自己选的档位，不是默认行为。
     */
    @NonNull
    public Advice advise(@NonNull List<Detection> detections, int maxReported) {
        return advise(detections, maxReported, null, null);
    }

    /**
     * 带深度图的版本：有米数时报米数，没有时与改造前逐字一致。
     *
     * @param depth 本帧深度图；{@code null} 表示没有深度模型，全部退回几何启发式
     */
    @NonNull
    public Advice advise(@NonNull List<Detection> detections, int maxReported,
                         @Nullable DepthMap depth, @Nullable Letterbox box) {
        List<Detection> obstacles = new ArrayList<>();
        for (Detection detection : detections) {
            if (CocoLabels.isObstacle(detection.label) && isRelevant(detection)) {
                obstacles.add(detection);
            }
        }
        // 由近到远：有深度时按米数，没有时按框底边（越靠下越近）。
        // 两种排序必须各自稳定：把两者混在同一比较器里会让"有的有米数、有的没有"
        // 的帧排出不连贯的顺序，而播报顺序就是用户听到的顺序。
        if (depth != null) {
            obstacles.sort((a, b) -> {
                float ma = DistancePolicy.metersOf(a, depth, box);
                float mb = DistancePolicy.metersOf(b, depth, box);
                if (Float.isNaN(ma) && Float.isNaN(mb)) {
                    return Float.compare(b.bottom, a.bottom);
                }
                if (Float.isNaN(ma)) {
                    return 1;
                }
                if (Float.isNaN(mb)) {
                    return -1;
                }
                return Float.compare(ma, mb);
            });
        } else {
            obstacles.sort((a, b) -> Float.compare(b.bottom, a.bottom));
        }

        float freeRatio = computeFreeRatio(obstacles);

        if (obstacles.isEmpty()) {
            boolean blocked = freeRatio < BLOCKED_RATIO;
            return new Advice(obstacles,
                    blocked ? "前方通路受阻，请谨慎前进" : "前方暂未检测到明显障碍物",
                    blocked ? Level.CAUTION : Level.CLEAR,
                    freeRatio,
                    blocked ? "static:blocked" : "static:clear");
        }

        List<Group> groups = group(obstacles, depth, box);
        boolean danger = freeRatio < BLOCKED_RATIO;
        StringBuilder message = new StringBuilder();
        StringBuilder key = new StringBuilder("static:");
        // 最多报几**簇**：两簇各报一句，胜过同一簇的名字被念两遍。
        // 至少报一簇：走到这里说明画面里确实有东西，一句话都不说比说多了危险。
        int reported = Math.max(1, Math.min(maxReported, groups.size()));
        for (int i = 0; i < reported; i++) {
            Group group = groups.get(i);
            if (group.near && "中央".equals(group.position)) {
                danger = true;
            }
            if (i > 0) {
                message.append('；');
                key.append(';');
            }
            message.append(describe(group));
            key.append(group.dedupKey());
        }

        return new Advice(obstacles, message.toString(),
                danger ? Level.DANGER : Level.CAUTION, freeRatio, key.toString());
    }

    /**
     * 按（方位，类别）把障碍物聚成簇，保持"由近到远"的顺序。
     *
     * <p>没有用 HashMap：障碍物最多几十个、簇更少，线性查找省掉了每帧建表与装箱；
     * 而且列表已经按距离排好序，簇的先后顺序天然就是"最近的簇先报"。
     */
    @NonNull
    private static List<Group> group(@NonNull List<Detection> obstacles, @Nullable DepthMap depth,
                                     @Nullable Letterbox box) {
        List<Group> groups = new ArrayList<>();
        for (Detection obstacle : obstacles) {
            String position = positionOf(obstacle);
            Group found = null;
            for (Group candidate : groups) {
                if (candidate.position.equals(position)
                        && candidate.label.equals(obstacle.label)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                found = new Group(position, obstacle.label);
                groups.add(found);
            }
            found.count++;
            found.near |= isNear(obstacle, depth, box);
            float meters = DistancePolicy.metersOf(obstacle, depth, box);
            if (!Float.isNaN(meters) && (Float.isNaN(found.meters) || meters < found.meters)) {
                found.meters = meters;
            }
        }
        return groups;
    }

    /**
     * 一簇目标的中文描述。有米数时报米数，没有时与改造前逐字一致。
     *
     * <p>米数只在这句话里说一次，且放在最前面 —— 盲人听一句话的注意力集中开头，
     * "1.5 米处中央有行人"比"中央行人较近，注意，大约 1.5 米"更能决定要不要停下。
     */
    @NonNull
    private static String describe(@NonNull Group group) {
        String label = CocoLabels.translate(group.label);
        String meters = DistancePolicy.describeMeters(group.meters);
        if (group.count == 1) {
            if (meters != null) {
                return meters + "处" + group.position + label
                        + (group.near ? "，注意" : "，请注意");
            }
            return group.position + label + (group.near ? "较近，注意" : "，请注意");
        }
        // 多目标时不报米数：一簇里各目标距离不同，报哪一个都是错的。
        return group.position + numberWord(group.count) + quantifierOf(group.label) + label + "，注意";
    }

    /** 量词按类别选：「两名行人」「两辆汽车」「两个背包」。 */
    @NonNull
    private static String quantifierOf(@NonNull String label) {
        if (CocoLabels.isPerson(label)) {
            return "名";
        }
        return CocoLabels.isVehicle(label) ? "辆" : "个";
    }

    /** 五以内用汉字，再多就说"多"——走路时听不出也记不住更大的数目。 */
    @NonNull
    private static String numberWord(int count) {
        switch (count) {
            case 2:
                return "两";
            case 3:
                return "三";
            case 4:
                return "四";
            case 5:
                return "五";
            default:
                return "多";
        }
    }

    /**
     * 目标是否离得近。
     *
     * <p>没有深度图时是**几何启发式**：框下沿够低或面积够大。它没有尺度——同样一个人，
     * 1 米和 3 米都可能满足或不满足，取决于手机举多高、镜头多广。有深度图时改用米，
     * 见 {@link DistancePolicy#isNear(Detection, DepthMap)}。
     */
    public static boolean isNear(@NonNull Detection detection) {
        return isNear(detection.left, detection.top, detection.right, detection.bottom);
    }

    /**
     * 有深度图时的"是否算近"。取不到有效深度就退回几何判据 ——
     * 不能因为深度缺失就一律当"很近"（假紧急）或一律当"很远"（该提示时沉默）。
     */
    public static boolean isNear(@NonNull Detection detection, @Nullable DepthMap depth,
                                 @Nullable Letterbox box) {
        return DistancePolicy.isNear(detection, depth, box);
    }

    /** 同 {@link #isNear(Detection)}，但用拆开的坐标，供只有快照的场景调用。 */
    public static boolean isNear(float left, float top, float right, float bottom) {
        return bottom > NEAR_BOTTOM_RATIO || (right - left) * (bottom - top) > NEAR_AREA_RATIO;
    }

    /** 目标在画面中的横向方位。 */
    @NonNull
    public static String positionOf(@NonNull Detection detection) {
        return positionOf(detection.centerX());
    }

    /** 同 {@link #positionOf(Detection)}，但直接给中心横坐标。 */
    @NonNull
    public static String positionOf(float centerX) {
        if (centerX < LEFT_BAND) {
            return "左侧";
        }
        if (centerX > RIGHT_BAND) {
            return "右侧";
        }
        return "中央";
    }

    /**
     * 过滤掉不值得提示的目标，对应 script.py 的 {@code _relevant}。
     *
     * <p>画面顶部的是远处背景；又小又偏上的多半是噪声；贴着画面左右边缘的目标
     * 不在行进方向上，播报出来只会干扰判断。
     */
    private static boolean isRelevant(@NonNull Detection detection) {
        if (detection.bottom < TOP_IGNORE_RATIO) {
            return false;
        }
        if (detection.areaRatio() < TINY_AREA_RATIO && detection.bottom < TINY_BOTTOM_RATIO) {
            return false;
        }
        return detection.right >= SIDE_MARGIN && detection.left <= 1f - SIDE_MARGIN;
    }

    /** 统计中央走廊中没有被任何障碍框覆盖的面积比例。 */
    private float computeFreeRatio(@NonNull List<Detection> obstacles) {
        return corridorCoverage.freeRatio(obstacles);
    }
}
