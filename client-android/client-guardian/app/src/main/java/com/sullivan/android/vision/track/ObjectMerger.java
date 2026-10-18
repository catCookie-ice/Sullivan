package com.sullivan.android.vision.track;

import androidx.annotation.NonNull;

import com.sullivan.android.vision.detect.CocoLabels;
import com.sullivan.android.vision.detect.Detection;

import java.util.ArrayList;
import java.util.List;

/**
 * 把同一帧里指着同一个东西的多个框合成一个目标。
 *
 * <p>模型每帧给出的框并不保证"一个物体一个框"：
 *
 * <ul>
 *   <li>YOLO 的 NMS 是**类别内**的，所以同一个行人的两个框（IoU 低于 0.45）都会留下；
 *   <li>行人和自行车（骑车的人）、行人和雨伞（撑伞的人）这类不同类别的框会高度重叠，
 *       因为跨类别不会被 NMS 抑制。
 * </ul>
 *
 * <p>不合并就会播成「中央行人较近，注意；中央自行车较近，注意」——说的是同一个人。
 * 合并规则一律要求"框几乎重合"，宁可少合并（播两句）也不要错合并（漏报一个目标）。
 */
public final class ObjectMerger {

    /** 两个框重叠到这个程度就当作同一个目标。 */
    public static final float MERGE_IOU = 0.25f;
    /** 交集占较小框的比例达到这个程度（大框套小框）也算同一个目标。 */
    public static final float MERGE_CONTAINMENT = 0.55f;
    /** 中心横向距离小于此值、且纵向大幅重叠、且**类别相同**时合并（重复检出的框）。 */
    public static final float MERGE_CENTER_DX = 0.05f;
    public static final float MERGE_VERTICAL_OVERLAP = 0.5f;

    /** 一个合并后的目标。 */
    public static final class Merged {
        /** 簇内所有框的并集（外接矩形），置信度取簇内最高。 */
        @NonNull
        public final Detection box;
        /** 代表类别，按 人 &gt; 车 &gt; 其他 挑选；同一优先级取置信度最高的。 */
        @NonNull
        public final String label;
        /** 这个目标由几个原始框合并而来。大于 1 说明确实发生了重复检出。 */
        public final int memberCount;

        Merged(@NonNull Detection box, @NonNull String label, int memberCount) {
            this.box = box;
            this.label = label;
            this.memberCount = memberCount;
        }
    }

    private ObjectMerger() {
    }

    /**
     * 合并一帧的检测框。传入的列表不会被修改，返回的顺序由输入顺序决定（确定性）。
     */
    @NonNull
    public static List<Merged> merge(@NonNull List<Detection> detections) {
        int size = detections.size();
        if (size <= 1) {
            List<Merged> single = new ArrayList<>(size);
            for (Detection detection : detections) {
                single.add(new Merged(detection, detection.label, 1));
            }
            return single;
        }

        int[] parent = new int[size];
        for (int i = 0; i < size; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (shouldMerge(detections.get(i), detections.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        // 按"簇内最小下标"归组，保证输出顺序只取决于输入顺序。
        List<Merged> merged = new ArrayList<>();
        boolean[] claimed = new boolean[size];
        for (int i = 0; i < size; i++) {
            if (claimed[i]) {
                continue;
            }
            List<Detection> members = new ArrayList<>();
            for (int j = i; j < size; j++) {
                if (!claimed[j] && find(parent, j) == find(parent, i)) {
                    claimed[j] = true;
                    members.add(detections.get(j));
                }
            }
            merged.add(combine(members));
        }
        return merged;
    }

    private static boolean shouldMerge(@NonNull Detection a, @NonNull Detection b) {
        if (a.iou(b) >= MERGE_IOU) {
            return true;
        }
        if (a.intersectionOverMin(b) >= MERGE_CONTAINMENT) {
            return true;
        }
        // 中心几乎重合只能是重复检出；要求同类，避免把并排的行人和车辆并成一个。
        return a.label.equals(b.label)
                && a.centerDistanceX(b) < MERGE_CENTER_DX
                && verticalOverlap(a, b) >= MERGE_VERTICAL_OVERLAP;
    }

    private static float verticalOverlap(@NonNull Detection a, @NonNull Detection b) {
        float overlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        float smaller = Math.min(a.height(), b.height());
        return smaller <= 0f || overlap <= 0f ? 0f : overlap / smaller;
    }

    @NonNull
    private static Merged combine(@NonNull List<Detection> members) {
        Detection box = members.get(0);
        Detection representative = members.get(0);
        for (int i = 1; i < members.size(); i++) {
            Detection member = members.get(i);
            box = box.union(member);
            if (isBetterRepresentative(member, representative)) {
                representative = member;
            }
        }
        // union() 会带走优先级的类别，这里统一用挑出来的代表类别，语义才一致。
        Detection labeled = new Detection(
                representative.classId,
                representative.label,
                box.score,
                box.left,
                box.top,
                box.right,
                box.bottom);
        return new Merged(labeled, representative.label, members.size());
    }

    /**
     * 挑代表类别。行人优先——一个骑车的人同时被检出 person 和 bicycle 时，该播的是"行人"。
     */
    private static boolean isBetterRepresentative(@NonNull Detection candidate,
                                                  @NonNull Detection current) {
        int candidateRank = rank(candidate.label);
        int currentRank = rank(current.label);
        if (candidateRank != currentRank) {
            return candidateRank < currentRank;
        }
        return candidate.score > current.score;
    }

    private static int rank(@NonNull String label) {
        if (CocoLabels.isPerson(label)) {
            return 0;
        }
        return CocoLabels.isVehicle(label) ? 1 : 2;
    }

    private static int find(@NonNull int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != root) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(@NonNull int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }
}
