package com.sullivan.protectee.vision.motion;

/**
 * 「剧烈且无规则的晃动」判据——陀螺仪版本。
 *
 * <p>用户要的其实不是"手机动了"，而是"出事了"。这两件事的差别就是本类存在的全部理由：
 * 走路、转身、上下楼梯都会让角速度冲到一两 rad/s，而踩空、绊倒、被撞、手机脱手是另一副
 * 样子——<b>又猛又乱</b>。所以判据由两半组成：一半看"猛"（{@link #PEAK_RAD_PER_SEC} 与
 * {@link #MIN_JERK_RAD_PER_SEC2}），一半看"乱"（{@link #MIN_REVERSALS} 与
 * {@link #IRREGULARITY_RATIO}）。缺了后一半，用户随手甩两下手机就会报警；缺了前一半，
 * 正常走路就会报警。
 *
 * <p><b>为什么是陀螺仪而不是视觉</b>：相机只有 10 FPS，按采样定理根本看不见 3 Hz 的晃动；
 * 单目画面也分不清"相机晃了"和"场景在动"。陀螺仪直接测角速度，与摄像头是否被遮挡无关，
 * 不需要任何权限，代价接近零。
 *
 * <p><b>三个时间刻度是故意的</b>：
 * <ul>
 *   <li><b>快窗</b> {@link #TRIGGER_WINDOW_MS}：只看"最近有多猛"。0.5 秒长到能滤掉单个尖峰
 *       噪声，又短到不会把两秒前的一次磕碰算进来。</li>
 *   <li><b>慢窗</b> {@link #IRREGULARITY_WINDOW_MS}：数反转点、量间隔。0.5 秒里只有两三个
 *       反转点，据此算出来的间隔统计基本是噪声；拉到 1.5 秒才有意义。</li>
 *   <li><b>冷却</b> {@link #TRIGGER_COOLDOWN_MS}：真摔一跤会在好几秒里持续抖，判据会一直
 *       成立。报警器自己负责"用户没点掉就再报一次"，这里只负责别每 20 毫秒喊一次。</li>
 * </ul>
 *
 * <p><b>两个时钟也是故意的</b>：统计量用传感器时间戳（{@code SensorEvent.timestamp}，纳秒，
 * 单调、与采样同源），冷却与抑制用调用方的墙钟（{@code SystemClock.uptimeMillis}）。混用会
 * 让"采样间隔"这种几十毫秒量级的数字被线程调度抖动污染。
 *
 * <p>纯 Java，无 Android 依赖。<b>单线程访问</b>：都在传感器回调线程上（见 {@code ShakeSensor}）。
 */
public final class ShakeDetector {

    /** 环形缓冲槽数。按时间淘汰，容量只决定"最长能记住多久"（512 × 20ms ≈ 10 秒）。 */
    public static final int CAPACITY = 512;

    /** 相邻样本间隔超过它就认为采样中断过：中间发生了什么没人知道，历史作废。 */
    public static final long MAX_GAP_NS = 150_000_000L;

    /** 快窗：只看这段时间里"有多猛"。 */
    public static final long TRIGGER_WINDOW_MS = 500L;

    /** 慢窗：反转点与间隔统计用这段时间。 */
    public static final long IRREGULARITY_WINDOW_MS = 1500L;

    /**
     * 快窗里至少要有的样本数。
     *
     * <p>这是"设备采样率地板"：0.5 秒里不足 20 个样本意味着实际速率低于 40 Hz，此时峰值
     * 可能被漏采、反转可能被数漏，宁可不报。真遇到这种设备，把 {@code ShakeSensor} 的
     * 采样率提到 {@code SENSOR_DELAY_FASTEST}，别改这里。
     */
    public static final int MIN_SAMPLES = 20;

    /**
     * "猛"：快窗内的角速度**大小**的峰值。
     *
     * <p>工程估计：走路时手持摆动约 0.3–0.8 rad/s，转身 1.5–3.5，绊倒或撞击 3–8。
     *
     * <p><b>2026-10-24 从 2.5 提到 4.0。</b>用户反馈"剧烈晃动判定太过于宽松"，
     * 也就是日常动作被误报成摔倒。2.5 的问题在于它落进了**快速转身**的区间
     * （1.5–3.5）里 —— 转身是每个人每天做几十次的动作，把它算进摔倒等于误报率
     * 高到用户会直接关掉这个功能。4.0 高于转身上沿、低于真实撞击下沿（3–8 偏上），
     * 落在"只有真的磕碰才够得着"的位置。
     *
     * <p>为什么宁可漏报也不要多报：这是**求助**功能，不是安全认证。漏报的代价是
     * 回到"没有这个功能"的状态（用户还能手动按求助），而多报的代价是每一次转身
     * 都给监护人打一通电话 —— 后者会让人直接卸载应用，那才是真正的失效。
     *
     * <p>取向量长度而不是"某个主轴上的投影"：晃动起来主轴自己在转，挑哪个轴都是赌。
     */
    public static final float PEAK_RAD_PER_SEC = 4.0f;

    /**
     * "猛"的第二道门：角速度大小的变化率（rad/s²）。
     *
     * <p>光看峰值挡不住"慢悠悠转一大圈"：匀速转体 3 rad/s 轻轻松松越过峰值门限。但那种动作
     * 的角加速度只有几 rad/s²，而真正的晃动哪怕只算 4 Hz、3 rad/s，每个样本间也能差出
     * 1 rad/s 以上（≈75 rad/s²）。
     *
     * <p><b>2026-10-24 从 20 提到 30。</b>峰值门限提高后，这道门承担的过滤比例也随之上升，
     * 而 20 rad/s² 对"手里攥着手机走路时的一次正常摆臂"来说够低（摆臂的角加速度在
     * 15–25 之间）。30 把它排除掉，同时仍远低于真实撞击的 75+。
     */
    public static final float MIN_JERK_RAD_PER_SEC2 = 30f;

    /** "乱"：慢窗内角速度**向量**掉头的次数。 */
    public static final int MIN_REVERSALS = 3;

    /**
     * 两次反转至少隔这么久才算数。
     *
     * <p>没有这道门的话，规律摆动过零点附近的传感器噪声会凑出"间隔 20 毫秒的反转对"，
     * 把 {@link #IRREGULARITY_RATIO} 凭空顶上去——**规律**晃动反而被当成无规则晃动报出去。
     * 50 毫秒（两个半采样周期）拦得住噪声，拦不住真晃动：人的手再快也快不过 20 Hz 的反转。
     */
    public static final long MIN_REVERSAL_INTERVAL_MS = 50L;

    /**
     * "乱"的核心：反转间隔的最大值 ÷ 最小值。
     *
     * <p>这就是"无规则"的量化。走路、甩手、规律摆动都是整齐的节奏，比值贴着 1；失衡、绊倒、
     * 被撞出来的抖动没有节奏，比值轻松上 2–3。
     *
     * <p>用极值比而不是变异系数：慢窗里往往只有两三个间隔，变异系数的估计方差大到没法用。
     *
     * <p><b>2026-10-24 从 1.5 提到 1.8。</b>1.5 留的余量是按 50 Hz 采样估的，但实际设备
     * 常见 SENSOR_DELAY_GAME（约 50–100 Hz 抖动），量化误差比预想大。配合上面两条
     * 提高的门限，1.8 让"规律但幅度大"的动作（甩手、快速转身、上下楼梯的磕碰）
     * 明确落在门外。
     */
    public static final float IRREGULARITY_RATIO = 1.8f;

    /**
     * 触发之后这么久内不再触发（按墙钟算）。
     *
     * <p>2026-10-24 从 6 秒提到 15 秒：判据变严之后，同一次真实摔倒仍可能在数秒内
     * 反复满足条件（人还在挣扎、手机还在晃），而报警面板本身需要用户阅读与反应的时间。
     * 15 秒足够覆盖一次摔倒的余震，又不会长到让第二次真实事件被吞掉。
     */
    public static final long TRIGGER_COOLDOWN_MS = 15000L;

    /** 慢窗内最多可能出现的反转点数：1.5s ÷ 50ms + 余量。 */
    private static final int REVERSAL_CAPACITY = 64;

    private final long[] timeNs = new long[CAPACITY];
    private final float[] wx = new float[CAPACITY];
    private final float[] wy = new float[CAPACITY];
    private final float[] wz = new float[CAPACITY];

    /** 已反转点的传感器时刻，按时间递增。复用，不每次分配。 */
    private final long[] reversalNs = new long[REVERSAL_CAPACITY];

    /** 有效样本数，最多 {@link #CAPACITY}。 */
    private int size;
    /** 下一个写入位置。 */
    private int head;

    private boolean hasPrevious;
    private long lastStampNs;
    /** 本段连续采样的起点：快窗要"够长"，靠的是它。 */
    private long burstStartNs;

    private long lastTriggerMs = -TRIGGER_COOLDOWN_MS;
    private long suppressedUntilMs = Long.MIN_VALUE;

    /**
     * 喂一个陀螺仪样本。
     *
     * @param gx          绕 x 轴的角速度（rad/s），即 {@code SensorEvent.values[0]}
     * @param gy          绕 y 轴
     * @param gz          绕 z 轴
     * @param timestampNs 传感器时间戳（纳秒），即 {@code SensorEvent.timestamp}
     * @param receiveTimeMs 收到这个样本时的墙钟（{@code SystemClock.uptimeMillis}），只用于冷却
     * @return 这一帧是否判出"剧烈且无规则的晃动"
     */
    public boolean addSample(float gx, float gy, float gz, long timestampNs, long receiveTimeMs) {
        if (receiveTimeMs < suppressedUntilMs) {
            // 用户刚碰过屏幕：这一段不算数。见 suppressUntil。
            clearRing();
            return false;
        }
        if (hasPrevious) {
            long delta = timestampNs - lastStampNs;
            if (delta <= 0) {
                // 时间倒流或重复的样本一律丢。传感器重启导致的时钟重来要调 clear()。
                return false;
            }
            if (delta > MAX_GAP_NS) {
                // 采样中断过：中间发生了什么没人知道，别拿跨过中断的两段去做统计。
                clearRing();
            }
        }
        push(gx, gy, gz, timestampNs);

        if (receiveTimeMs - lastTriggerMs < TRIGGER_COOLDOWN_MS) {
            return false;
        }
        if (!isShaking(timestampNs)) {
            return false;
        }
        lastTriggerMs = receiveTimeMs;
        clearRing();
        return true;
    }

    /**
     * 丢掉全部历史，并把冷却清掉。
     *
     * <p>传感器重启（{@code onResume} 里重新注册）后必须调一次：新的时间戳会从零开始，
     * 不调的话每一个样本都会被当成"时间倒流"丢掉。
     */
    public void clear() {
        clearRing();
        hasPrevious = false;
        lastTriggerMs = -TRIGGER_COOLDOWN_MS;
    }

    /**
     * 从现在起 {@code durationMs} 内不做判定，并把已有的历史作废。
     *
     * <p>给"用户正在操作界面"用：把手机从兜里掏出来、按一下按钮、塞回兜里，这一串动作的
     * 角速度完全够得上"剧烈且无规则"。用户明摆着醒着、正在用手指跟应用说话，此时弹报警
     * 只会教人忽略报警。
     */
    public void suppressUntil(long receiveTimeMs, long durationMs) {
        suppressedUntilMs = receiveTimeMs + durationMs;
        clearRing();
    }

    // ------------------------------------------------------------------
    // 判据
    // ------------------------------------------------------------------

    /** @param newestNs 最新样本的时刻；窗口都相对它取 */
    private boolean isShaking(long newestNs) {
        long fastStartNs = newestNs - TRIGGER_WINDOW_MS * 1_000_000L;
        long slowStartNs = newestNs - IRREGULARITY_WINDOW_MS * 1_000_000L;

        // "够长"这一条必须单独判：样本数门槛是按采样率定的，和时长不是一回事。
        if (newestNs - burstStartNs < TRIGGER_WINDOW_MS * 1_000_000L) {
            return false;
        }

        float peak = 0f;
        float maxJerk = 0f;
        int count = 0;
        int previous = -1;
        for (int back = 0; back < size; back++) {
            int index = indexOf(back);
            if (timeNs[index] < fastStartNs) {
                break;
            }
            float magnitude = magnitudeAt(index);
            count++;
            if (magnitude > peak) {
                peak = magnitude;
            }
            if (previous >= 0) {
                long dtNs = timeNs[previous] - timeNs[index];
                float jerk = Math.abs(magnitudeAt(previous) - magnitude) / (dtNs / 1e9f);
                if (jerk > maxJerk) {
                    maxJerk = jerk;
                }
            }
            previous = index;
        }
        if (count < MIN_SAMPLES || peak < PEAK_RAD_PER_SEC || maxJerk < MIN_JERK_RAD_PER_SEC2) {
            return false;
        }

        int reversals = countReversals(slowStartNs);
        if (reversals < MIN_REVERSALS) {
            return false;
        }
        return isIrregular(reversals);
    }

    /**
     * 慢窗内角速度向量掉头的次数，结果同时留在 {@link #reversalNs} 里。
     *
     * <p>"掉头"= 相邻两个样本的点积为负。用点积而不是"主轴过零"：晃动的主轴自己在转，
     * 挑哪个轴都是赌；点积对整个向量生效，方向真的翻过去才算数。
     *
     * <p><b>不做去均值</b>：去了均值，一段匀速转动（平均值就是它本身）会只剩噪声，而噪声的
     * 点积符号是随机的——那等于凭空制造反转点。不去均值时，匀速转动的点积恒为正，0 次反转，
     * 正是想要的结果。
     */
    private int countReversals(long startNs) {
        int oldest = oldestBack(startNs);
        int count = 0;
        long previousNs = 0L;
        boolean hasPrevious = false;
        for (int back = oldest; back >= 1; back--) {
            int newer = indexOf(back - 1);
            int older = indexOf(back);
            float dot = wx[older] * wx[newer] + wy[older] * wy[newer] + wz[older] * wz[newer];
            if (dot >= 0f) {
                continue;
            }
            long at = timeNs[newer];
            if (hasPrevious && at - previousNs < MIN_REVERSAL_INTERVAL_MS * 1_000_000L) {
                continue;
            }
            if (count < REVERSAL_CAPACITY) {
                reversalNs[count] = at;
            }
            count++;
            previousNs = at;
            hasPrevious = true;
        }
        return count;
    }

    /** 反转间隔是不是参差不齐。调用前 {@link #countReversals} 必须已经跑过。 */
    private boolean isIrregular(int reversals) {
        int usable = Math.min(reversals, REVERSAL_CAPACITY);
        if (usable < 2) {
            return false;
        }
        long shortest = Long.MAX_VALUE;
        long longest = 0L;
        for (int i = 1; i < usable; i++) {
            long interval = reversalNs[i] - reversalNs[i - 1];
            if (interval < shortest) {
                shortest = interval;
            }
            if (interval > longest) {
                longest = interval;
            }
        }
        return (float) longest >= IRREGULARITY_RATIO * (float) shortest;
    }

    /** 慢窗里最老的那个样本的 back 序号；窗口是空的就返回 -1。 */
    private int oldestBack(long startNs) {
        int oldest = -1;
        for (int back = 0; back < size; back++) {
            if (timeNs[indexOf(back)] < startNs) {
                break;
            }
            oldest = back;
        }
        return oldest;
    }

    // ------------------------------------------------------------------
    // 环形缓冲
    // ------------------------------------------------------------------

    private void push(float gx, float gy, float gz, long timestampNs) {
        if (size == 0) {
            burstStartNs = timestampNs;
        }
        timeNs[head] = timestampNs;
        wx[head] = gx;
        wy[head] = gy;
        wz[head] = gz;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
        lastStampNs = timestampNs;
        hasPrevious = true;
    }

    private void clearRing() {
        size = 0;
        head = 0;
    }

    /** 第 {@code back} 个"最新"的样本在环形缓冲里的下标；0 就是最新的那个。 */
    private int indexOf(int back) {
        int index = head - 1 - back;
        return index < 0 ? index + CAPACITY : index;
    }

    private float magnitudeAt(int index) {
        return (float) Math.sqrt(wx[index] * wx[index]
                + wy[index] * wy[index] + wz[index] * wz[index]);
    }
}
