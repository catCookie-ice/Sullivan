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
 *
 * <p><b>两条入口，一套结论。</b>只拿 {@link Detection} 的那条是改造前的行为，
 * 逐字保留（没有深度、没有轨迹时用）。带 {@link Obstacle} 的那条多了两件事：
 * <ol>
 *   <li><b>按预计相遇时间排序</b>，而不是按距离。距离只说"多远"，时间才说"还有多久"，
 *       而用户要决定的是"现在停不停"。</li>
 *   <li><b>把证明撞不上的目标剔掉</b>：预计时间超过 {@link CollisionModel#HORIZON_S} 的
 *       （暂时不会撞到），以及在剩余时间里横向扫不到用户行进线的（会从旁边过去）。
 *       剔除只发生在**算得出**的时候；算不出就保留 —— 宁可多报一句，不可该报不报。</li>
 * </ol>
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

    /**
     * 一条参与决策的障碍：本帧的框，加上追踪器测出来的运动量。
     *
     * <p>运动量全部允许为 {@link Float#NaN}：没有深度模型、轨迹太年轻、被裁切、自运动
     * 估计被拒，都会让某个通道失效。**每个 NaN 的含义都是"不知道"，不是"零"**，
     * 调用方据此决定要不要退回几何判据。
     */
    public static final class Obstacle {
        @NonNull
        public final Detection box;
        /** 由深度图得到的距离（米）；NaN = 没有可用深度。 */
        public final float meters;
        /** 尺度通道给出的剩余碰撞时间（秒，已做质心校正）；NaN = 没得出"正在靠近"。 */
        public final float ttc;
        /** 自运动补偿后的尺度增长率（1/s）；NaN = 拟合失败。 */
        public final float relativeRate;
        /** 横向速率 {@code d(centerX)/dt}（1/s）；NaN = 拟合失败。 */
        public final float lateralRate;
        /** 轨迹编号，用于界面高亮；-1 表示不是轨迹（只可能是旧入口传进来的框）。 */
        public final int trackId;

        public Obstacle(@NonNull Detection box) {
            this(box, Float.NaN, Float.NaN, Float.NaN, Float.NaN, -1);
        }

        public Obstacle(@NonNull Detection box, float meters) {
            this(box, meters, Float.NaN, Float.NaN, Float.NaN, -1);
        }

        public Obstacle(@NonNull Detection box, float meters, float ttc, float relativeRate,
                        float lateralRate, int trackId) {
            this.box = box;
            this.meters = meters;
            this.ttc = ttc;
            this.relativeRate = relativeRate;
            this.lateralRate = lateralRate;
            this.trackId = trackId;
        }
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
         * 语义去重键，由（方位、类别、数量、远近、紧急度）拼成。
         *
         * <p>**不能拿文案当键**：包围盒在门限上抖动时，"较近，注意"与"，请注意"会来回跳，
         * 键跟着跳，去重就会失效——那正是"同一件事反复播报"的成因之一。
         *
         * <p>**紧急度必须进键**：同一簇目标从"6 秒"降到"1 秒"时，文案变了、方位类别数量
         * 都没变；键里不带紧急度，这次升级就会被"同一句话"的冷却期吞掉，而它恰恰是
         * 最不该被吞掉的那一句。
         */
        @NonNull
        public final String dedupKey;
        /**
         * 本句话里最紧急那个目标的预计相遇时间（秒）。
         *
         * <p>给 {@code SceneAnalyzer} 用来和动态告警比较"谁先说"；也用于日志回放。
         * {@link Float#NaN} 表示这一帧没有任何时间结论（没有深度、轨迹太年轻等）。
         */
        public final float timeToImpact;

        Advice(@NonNull List<Detection> obstacles, @NonNull String message, @NonNull Level level,
               float freeRatio) {
            this(obstacles, message, level, freeRatio, "static:" + message, Float.NaN);
        }

        Advice(@NonNull List<Detection> obstacles, @NonNull String message, @NonNull Level level,
               float freeRatio, @NonNull String dedupKey) {
            this(obstacles, message, level, freeRatio, dedupKey, Float.NaN);
        }

        Advice(@NonNull List<Detection> obstacles, @NonNull String message, @NonNull Level level,
               float freeRatio, @NonNull String dedupKey, float timeToImpact) {
            this.obstacles = obstacles;
            this.message = message;
            this.level = level;
            this.freeRatio = freeRatio;
            this.dedupKey = dedupKey;
            this.timeToImpact = timeToImpact;
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
        /** 组内最紧急的预计相遇时间（秒）；NaN = 没有时间结论。 */
        float time = Float.NaN;

        Group(String position, String label) {
            this.position = position;
            this.label = label;
        }

        /** 去重键里的一段：方位、类别、数量、远近。紧急度由 {@link Advice} 统一追加。 */
        @NonNull
        String dedupKey() {
            return position + ':' + label + ':' + count + ':' + near;
        }
    }

    /** 通过筛选、等待聚类的一条障碍及其相遇时间。 */
    private static final class Candidate {
        final Obstacle obstacle;
        final float time;

        Candidate(Obstacle obstacle, float time) {
            this.obstacle = obstacle;
            this.time = time;
        }
    }

    /** 画面顶部这个比例以内的目标只当背景，避免播报远处的无关物体。 */
    private static final float TOP_IGNORE_RATIO = 0.38f;
    /**
     * 又小又靠上的目标视为噪声。
     *
     * <p>判据用**高度**而不是面积：面积同时混进了宽度，而宽度对姿态和遮挡敏感得多
     * （一个侧身的人宽度减半）。高度在小孔成像下与距离一一对应，作为"太远/太小"的
     * 尺度更稳。阈值从改造前的 0.0015 面积（约 25×25 px）换成 6% 高度（约 38 px），
     * 效果是**贴着地面的小目标不再被当成噪声**——一个 15 厘米高的路锥在 1.5 米处
     * 只有 0.04 的高度，按面积会被丢掉，按新判据（底边在 0.55 以下才算远）会报出来。
     */
    private static final float TINY_HEIGHT_RATIO = 0.06f;
    /** 框底边高于此比例才算"靠上"，与上一条联合使用。 */
    private static final float TINY_BOTTOM_RATIO = 0.55f;
    /**
     * 低置信度框的额外门槛。
     *
     * <p>检测器的置信度阈值已从 0.35 降到 0.28（见 {@code ProtecteeActivity}），为的是
     * 让更小的障碍有机会进入决策层。代价是噪声变多，所以这里补一道**位置**闸：低于
     * {@link #LOW_SCORE} 的框只有落在画面下部（贴近地面、真的可能绊倒人）才留下。
     * 远处低分框仍然丢掉——那里本来就有很多误检，而且用户离它还有好几秒。
     */
    private static final float LOW_SCORE = 0.45f;
    private static final float LOW_SCORE_BOTTOM = 0.68f;
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
        List<Obstacle> obstacles = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            float meters = DistancePolicy.metersOf(detection, depth, box);
            obstacles.add(new Obstacle(detection, meters));
        }
        return adviseTracked(obstacles, maxReported, Float.NaN);
    }

    /**
     * 带运动信息的版本：按**预计相遇时间**排序，并剔除证明撞不上的目标。
     *
     * @param obstacles 本帧已确认的障碍（框 + 距离 + 各测速通道）
     * @param egoSpeed  用户步速（米/秒）；NaN 或非正数时用
     *                  {@link CollisionModel#DEFAULT_EGO_SPEED_MPS}
     * @return 结论；{@link Advice#timeToImpact} 是这句话里最紧急的时间
     */
    @NonNull
    public Advice adviseTracked(@NonNull List<Obstacle> obstacles, int maxReported,
                                float egoSpeed) {
        List<Obstacle> relevant = new ArrayList<>(obstacles.size());
        for (Obstacle obstacle : obstacles) {
            if (CocoLabels.isObstacle(obstacle.box.label) && isRelevant(obstacle.box)) {
                relevant.add(obstacle);
            }
        }
        sortByDistance(relevant);

        // 通路占比用**全部**候选算，不用筛过的：被时间筛掉的目标仍然实实在在挡着路，
        // 若把它从覆盖率里去掉，用户会听到"通畅"，而画面里的东西一点没少。
        List<Detection> boxes = new ArrayList<>(relevant.size());
        for (Obstacle obstacle : relevant) {
            boxes.add(obstacle.box);
        }
        float freeRatio = corridorCoverage.freeRatio(boxes);
        boolean blocked = freeRatio < BLOCKED_RATIO;

        float ego = (egoSpeed > 0f) ? egoSpeed : CollisionModel.DEFAULT_EGO_SPEED_MPS;
        float soonest = Float.NaN;
        List<Candidate> kept = new ArrayList<>(relevant.size());
        for (Obstacle obstacle : relevant) {
            float time = timeOf(obstacle, ego);
            if (Float.isNaN(soonest) || time < soonest) {
                soonest = time;
            }
            if (isThreat(obstacle, time)) {
                kept.add(new Candidate(obstacle, time));
            }
        }

        // 播报顺序由**时间**决定，不由距离决定：先听见的是最先撞上的那个。
        // 算不出时间的排在最后（NaN 在比较里必须显式处理，否则会静默排到最前或最后）。
        kept.sort((a, b) -> {
            boolean aUnknown = Float.isNaN(a.time);
            boolean bUnknown = Float.isNaN(b.time);
            if (aUnknown || bUnknown) {
                return aUnknown == bUnknown ? 0 : (aUnknown ? 1 : -1);
            }
            return Float.compare(a.time, b.time);
        });

        if (kept.isEmpty()) {
            if (relevant.isEmpty() || blocked) {
                return new Advice(boxes,
                        blocked ? "前方通路受阻，请谨慎前进" : "前方暂未检测到明显障碍物",
                        blocked ? Level.CAUTION : Level.CLEAR,
                        freeRatio,
                        blocked ? "static:blocked" : "static:clear",
                        sooner(soonest, Float.NaN));
            }
            // 画面里有东西，但按现在的速度与方向都不会撞上。**这句只在"从危险变安全"
            // 那一次说**（见 AnnouncementPolicy：CLEAR 只在有过障碍之后才播），
            // 所以它不会变成一句新的、反复念叨的噪声。
            return new Advice(boxes, "前方有目标，暂时不会撞上", Level.CLEAR, freeRatio,
                    "static:notYet", soonest);
        }

        List<Group> groups = group(kept);
        boolean danger = blocked;
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
            if (CollisionModel.withinHorizon(group.time) && group.time <= CollisionModel.DANGER_S
                    && "中央".equals(group.position)) {
                danger = true;
            }
            if (i > 0) {
                message.append('；');
                key.append(';');
            }
            message.append(describe(group));
            key.append(group.dedupKey());
        }

        return new Advice(boxes, message.toString(),
                danger ? Level.DANGER : Level.CAUTION, freeRatio,
                key.append(':').append(danger ? 'd' : 'c').toString(),
                sooner(groups.get(0).time, Float.NaN));
    }

    /**
     * 这个目标值不值得报。
     *
     * <p>三种情况：算不出时间（NaN）→ **保留**，退回改造前的几何判据；时间超过视野
     * → 暂时不会撞到，剔除；横向扫不到行进线 → 会从旁边过去，剔除。
     */
    private static boolean isThreat(@NonNull Obstacle obstacle, float time) {
        if (Float.isNaN(time)) {
            return true;
        }
        if (!CollisionModel.withinHorizon(time)) {
            return false;
        }
        return CollisionModel.sweepsWalkingLine(obstacle.box.centerX(),
                obstacle.box.width() * 0.5f, obstacle.lateralRate, time, obstacle.meters);
    }

    /**
     * 预计相遇时间：有米数时用统一模型（{@code Z / (v_ego + λ·Z)}），没有米数时退回
     * 尺度通道的 TTC。两者都没有就是 NaN。
     */
    private static float timeOf(@NonNull Obstacle obstacle, float egoSpeed) {
        float time = CollisionModel.timeToMeet(obstacle.meters, obstacle.relativeRate, egoSpeed);
        if (Float.isNaN(time) && !Float.isNaN(obstacle.ttc)) {
            time = obstacle.ttc;
        }
        return time;
    }

    /** 取更小的那个时间（两个 NaN 得 NaN，一个 NaN 得另一个）。 */
    private static float sooner(float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        return Float.isNaN(b) ? a : Math.min(a, b);
    }

    /**
     * 由近到远排序。
     *
     * <p>有米数时按米数，没有时按框底边（越靠下越近）。两种排序必须各自稳定：把两者混在
     * 同一比较器里会让"有的有米数、有的没有"的帧排出不连贯的顺序，而播报顺序就是用户
     * 听到的顺序。真正的播报顺序随后由预计相遇时间决定，这里只保证同类输入的确定性。
     */
    private static void sortByDistance(@NonNull List<Obstacle> obstacles) {
        obstacles.sort((a, b) -> {
            float ma = a.meters;
            float mb = b.meters;
            if (Float.isNaN(ma) && Float.isNaN(mb)) {
                return Float.compare(b.box.bottom, a.box.bottom);
            }
            if (Float.isNaN(ma)) {
                return 1;
            }
            if (Float.isNaN(mb)) {
                return -1;
            }
            return Float.compare(ma, mb);
        });
    }

    /**
     * 按（方位，类别）把障碍物聚成簇，保持传入顺序（即"由近到远"或"由急到缓"）。
     *
     * <p>没有用 HashMap：障碍物最多几十个、簇更少，线性查找省掉了每帧建表与装箱；
     * 而且列表已经按时间排好序，簇的先后顺序天然就是"最急的簇先报"。
     */
    @NonNull
    private static List<Group> group(@NonNull List<Candidate> candidates) {
        List<Group> groups = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Detection box = candidate.obstacle.box;
            String position = positionOf(box);
            Group found = null;
            for (Group group : groups) {
                if (group.position.equals(position) && group.label.equals(box.label)) {
                    found = group;
                    break;
                }
            }
            if (found == null) {
                found = new Group(position, box.label);
                groups.add(found);
            }
            found.count++;
            found.near |= isNear(candidate.obstacle);
            float meters = candidate.obstacle.meters;
            if (!Float.isNaN(meters) && (Float.isNaN(found.meters) || meters < found.meters)) {
                found.meters = meters;
            }
            if (!Float.isNaN(candidate.time)
                    && (Float.isNaN(found.time) || candidate.time < found.time)) {
                found.time = candidate.time;
            }
        }
        return groups;
    }

    /**
     * 一簇目标的中文描述。
     *
     * <p>有时间就报时间，没有时间才退回米数/几何判据。时间放在最前面，而且只在
     * {@link CollisionModel#DANGER_S} 以内才用"停"开头——一句话的长度必须小于它描述的
     * 那个时间，否则"两秒后撞上"这五个字还没念完就已经撞上了。
     */
    @NonNull
    private static String describe(@NonNull Group group) {
        String label = CocoLabels.translate(group.label);
        boolean timed = CollisionModel.withinHorizon(group.time);
        if (group.count == 1) {
            if (timed) {
                if (group.time <= CollisionModel.DANGER_S) {
                    return "停，" + group.position + label;
                }
                return group.position + label + "，" + CollisionModel.wholeSeconds(group.time) + "秒";
            }
            String meters = DistancePolicy.describeMeters(group.meters);
            if (meters != null) {
                return meters + "处" + group.position + label
                        + (group.near ? "，注意" : "，请注意");
            }
            return group.position + label + (group.near ? "较近，注意" : "，请注意");
        }
        if (timed) {
            if (group.time <= CollisionModel.DANGER_S) {
                // 一秒半以内，数量不再影响该做什么：都是"停"。所以这句话里去掉量词与数字，
                // 换成最短的形式——"停，中央两名行人"要念 1.3 秒，而它描述的危险只剩 1.2 秒。
                return "停，" + group.position + label;
            }
            return group.position + numberWord(group.count) + quantifierOf(group.label) + label
                    + "，" + CollisionModel.wholeSeconds(group.time) + "秒";
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

    /** 带运动信息时的"是否算近"：有米数用米数，没有就退回几何判据。 */
    private static boolean isNear(@NonNull Obstacle obstacle) {
        if (DepthMap.isValid(obstacle.meters)) {
            return obstacle.meters <= DistancePolicy.NEAR_METERS;
        }
        return isNear(obstacle.box);
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
     * <p>画面顶部的是远处背景；又小又靠上的多半是噪声；贴着画面左右边缘的目标
     * 不在行进方向上，播报出来只会干扰判断。低置信度的框另外加一道"必须贴近地面"的闸。
     */
    private static boolean isRelevant(@NonNull Detection detection) {
        if (detection.bottom < TOP_IGNORE_RATIO) {
            return false;
        }
        if (detection.height() < TINY_HEIGHT_RATIO && detection.bottom < TINY_BOTTOM_RATIO) {
            return false;
        }
        if (detection.score < LOW_SCORE && detection.bottom < LOW_SCORE_BOTTOM) {
            return false;
        }
        return detection.right >= SIDE_MARGIN && detection.left <= 1f - SIDE_MARGIN;
    }
}
