package com.sullivan.protectee.vision.detect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * COCO 80 类别名称、中文翻译，以及"哪些类别算障碍物"的判定。
 *
 * <p>关键词表整体搬运自 script.py，语义保持一致；只是把原来的子串匹配换成了
 * 按词匹配，否则 COCO 里的 "carrot" 会因为含有 "car" 被误判成车辆。
 */
public final class CocoLabels {

    /** ultralytics 导出的 COCO 模型，类别顺序即此数组下标。 */
    public static final String[] COCO_80 = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
    };

    /**
     * 构成通行障碍的类别关键词。
     *
     * <p>前半部分与 script.py 的 OBSTACLE_KEYWORDS 逐项对应，后半部分是 COCO 里
     * 同样会挡路的类别（原脚本用的是 ADE20K，类别体系不同）。
     */
    private static final String[] OBSTACLE_KEYWORDS = {
            // —— 与 script.py 一致 ——
            "person", "people", "pedestrian",
            "car", "truck", "bus", "van",
            "bicycle", "bike", "motorcycle", "motorbike", "scooter", "train",
            "animal", "dog", "cat",
            "chair", "bench", "table",
            "pole", "traffic light", "traffic signal", "sign",
            "barrier", "fence", "tree", "plant", "wall", "rock", "stone", "stair",
            // —— COCO 中同样构成障碍的类别 ——
            "fire hydrant", "parking meter", "suitcase", "backpack", "skateboard",
            "umbrella", "bottle", "handbag",
    };

    /** 人。COCO 只有 "person"，另两个词是为非 COCO 类别名表准备的。 */
    private static final String[] PERSON_KEYWORDS = {"person", "people", "pedestrian"};

    /** 车辆，用于合并重叠框时挑选代表类别。 */
    private static final String[] VEHICLE_KEYWORDS = {
            "car", "truck", "bus", "van", "bicycle", "bike", "motorcycle",
            "motorbike", "scooter", "train",
    };

    /** 与 {@link #HEIGHT_METERS} 一一对应；顺序有意义，更具体的词排在前面。 */
    private static final String[] HEIGHT_KEYWORDS = {
            "person", "pedestrian", "bicycle", "motorcycle", "car", "bus",
            "truck", "train", "dog", "cat",
    };
    /** 各类别的典型高度（米）。 */
    private static final float[] HEIGHT_METERS = {
            1.70f, 1.70f, 1.10f, 1.10f, 1.50f, 3.20f, 3.30f, 3.50f, 0.60f, 0.30f,
    };
    /** 认不出类别时的兜底高度。 */
    public static final float DEFAULT_HEIGHT_METERS = 1.50f;

    /** 英文类别 → 中文，顺序有意义：更具体的词要排在更笼统的词前面。 */
    private static final Map<String, String> TRANSLATIONS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("person", "行人");
        map.put("pedestrian", "行人");
        map.put("bicycle", "自行车");
        map.put("motorcycle", "摩托车");
        map.put("airplane", "飞机");
        map.put("bus", "公交车");
        map.put("train", "火车");
        map.put("truck", "卡车");
        map.put("car", "汽车");
        map.put("boat", "船");
        map.put("traffic light", "红绿灯");
        map.put("traffic signal", "红绿灯");
        map.put("stop sign", "停止标志");
        map.put("fire hydrant", "消防栓");
        map.put("parking meter", "停车计时器");
        map.put("bench", "长椅");
        map.put("chair", "椅子");
        map.put("dining table", "桌子");
        map.put("potted plant", "盆栽");
        map.put("couch", "沙发");
        map.put("bed", "床");
        map.put("toilet", "马桶");
        map.put("bird", "鸟");
        map.put("cat", "猫");
        map.put("dog", "狗");
        map.put("horse", "马");
        map.put("sheep", "羊");
        map.put("cow", "牛");
        map.put("elephant", "大象");
        map.put("bear", "熊");
        map.put("zebra", "斑马");
        map.put("giraffe", "长颈鹿");
        map.put("backpack", "背包");
        map.put("umbrella", "雨伞");
        map.put("handbag", "手提包");
        map.put("suitcase", "行李箱");
        map.put("skateboard", "滑板");
        map.put("bottle", "瓶子");
        map.put("cup", "杯子");
        map.put("bowl", "碗");
        map.put("knife", "刀");
        map.put("fork", "叉子");
        map.put("spoon", "勺子");
        map.put("tv", "电视");
        map.put("laptop", "笔记本电脑");
        map.put("cell phone", "手机");
        map.put("book", "书");
        map.put("clock", "时钟");
        map.put("vase", "花瓶");
        map.put("scissors", "剪刀");
        TRANSLATIONS = Collections.unmodifiableMap(map);
    }

    private CocoLabels() {
    }

    /** 模型未附带类别名文件时的兜底：类别数吻合则用 COCO 名称，否则用占位名。 */
    @NonNull
    public static String[] defaultLabels(int numClasses) {
        if (numClasses == COCO_80.length) {
            return COCO_80.clone();
        }
        String[] labels = new String[numClasses];
        for (int i = 0; i < numClasses; i++) {
            labels[i] = "class " + i;
        }
        return labels;
    }

    /** 该类别是否属于需要提示的障碍物。 */
    public static boolean isObstacle(@Nullable String label) {
        if (label == null) {
            return false;
        }
        String normalized = normalize(label);
        for (String keyword : OBSTACLE_KEYWORDS) {
            if (containsWord(normalized, keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 是否是人。动态告警只对"人"做"正在靠近"的提示。 */
    public static boolean isPerson(@Nullable String label) {
        return label != null && containsAny(normalize(label), PERSON_KEYWORDS);
    }

    /** 是否是车辆（合并重叠框时按 人 > 车 > 其他 挑代表类别）。 */
    public static boolean isVehicle(@Nullable String label) {
        return label != null && containsAny(normalize(label), VEHICLE_KEYWORDS);
    }

    /**
     * 类别的典型物理高度（米），用于把像素尺度变化折算成与距离无关的量。
     *
     * <p>单目画面拿不到真实尺寸，只能按类别取经验值。它只影响"自运动补偿"的尺度：
     * 取值偏大会低估接近速度（偏保守），偏小则会高估。取不准时用
     * {@link #DEFAULT_HEIGHT_METERS}。
     */
    public static float referenceHeightMeters(@Nullable String label) {
        if (label == null) {
            return DEFAULT_HEIGHT_METERS;
        }
        String normalized = normalize(label);
        for (int i = 0; i < HEIGHT_KEYWORDS.length; i++) {
            if (containsWord(normalized, HEIGHT_KEYWORDS[i])) {
                return HEIGHT_METERS[i];
            }
        }
        return DEFAULT_HEIGHT_METERS;
    }

    private static boolean containsAny(@NonNull String normalized, @NonNull String[] keywords) {
        for (String keyword : keywords) {
            if (containsWord(normalized, keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 尽力翻译成中文；没有对应词条时原样返回。 */
    @NonNull
    public static String translate(@Nullable String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        String normalized = normalize(label);
        String exact = TRANSLATIONS.get(normalized);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : TRANSLATIONS.entrySet()) {
            if (containsWord(normalized, entry.getKey())) {
                return entry.getValue();
            }
        }
        return label;
    }

    private static String normalize(String label) {
        return label.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
    }

    /** 按整词匹配，避免 "carrot" 命中 "car" 这类子串误判。 */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        int index;
        while ((index = haystack.indexOf(needle, from)) >= 0) {
            int end = index + needle.length();
            boolean startOk = index == 0 || !Character.isLetterOrDigit(haystack.charAt(index - 1));
            boolean endOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }
}
