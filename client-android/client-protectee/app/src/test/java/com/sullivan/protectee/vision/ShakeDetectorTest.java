package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.sullivan.protectee.vision.motion.ShakeDetector;

import org.junit.Test;

/**
 * 晃动判据的契约。
 *
 * <p>这里的用例分成两组，对应判据的两半：
 * <ul>
 *   <li><b>不该报的</b>：走路、慢速转身、单次转身、规律摆动、静止。这是误报的全部来源——
 *       一个动不动就报警的报警器，用户三天就会把它当成背景噪声。</li>
 *   <li><b>该报的</b>：又猛又乱的抖动，以及各种"不算数"的边界（样本太少、断点、冷却中、
 *       用户正在操作界面）。</li>
 * </ul>
 *
 * <p>信号全是合成的正弦与分段常值，时间戳由用例自己给，所以每条断言都能精确复现。
 */
public class ShakeDetectorTest {

    /** 50 Hz，与 {@code SENSOR_DELAY_GAME} 一致。 */
    private static final long STEP_MS = 20L;

    /** 绕 x 轴：单轴就够用了，向量点积那部分由不规则用例覆盖。 */
    private static final float[] AXIS_X = {1f, 0f, 0f};

    // ------------------------------------------------------------------
    // 信号源
    // ------------------------------------------------------------------

    private interface Signal {
        /** @param tMs 相对这段信号起点的毫秒数 */
        float[] at(long tMs);
    }

    /** 等幅等周期的摆动——"有规律"的样子。 */
    private static Signal sine(final float[] axis, final float amplitude, final double freqHz) {
        return new Signal() {
            @Override
            public float[] at(long tMs) {
                float value = (float) (amplitude
                        * Math.sin(2.0 * Math.PI * freqHz * tMs / 1000.0));
                return new float[]{axis[0] * value, axis[1] * value, axis[2] * value};
            }
        };
    }

    /** 分段常值：每段持续多久、角速度多大。反转点就落在段边界上，位置一眼可见。 */
    private static Signal segments(final float[] axis, final long[] durationsMs,
                                   final float[] amplitudes) {
        return new Signal() {
            @Override
            public float[] at(long tMs) {
                int index = amplitudes.length - 1;
                long end = 0L;
                for (int i = 0; i < durationsMs.length; i++) {
                    end += durationsMs[i];
                    if (tMs < end) {
                        index = i;
                        break;
                    }
                }
                return new float[]{axis[0] * amplitudes[index], axis[1] * amplitudes[index],
                        axis[2] * amplitudes[index]};
            }
        };
    }

    /**
     * 一段"剧烈且无规则"的晃动：符号交替，每段的时长与幅度都不一样。
     *
     * <p>反转点落在 280 / 370 / 570 / 630 / 950 / 1060 毫秒，间隔依次是
     * 90 / 200 / 60 / 320 / 110 —— 最长 320、最短 60，比值 5.3，远在
     * {@link ShakeDetector#IRREGULARITY_RATIO} 之上。
     */
    private static Signal irregularShake() {
        return segments(AXIS_X,
                new long[]{280L, 90L, 200L, 60L, 320L, 110L, 240L},
                new float[]{5f, -3f, 6f, -3.5f, 4.5f, -3f, 5f});
    }

    /** 两个信号相加：真实的陀螺仪读数 = 运动 + 噪声。 */
    private static Signal plus(final Signal first, final Signal second) {
        return new Signal() {
            @Override
            public float[] at(long tMs) {
                float[] a = first.at(tMs);
                float[] b = second.at(tMs);
                return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
            }
        };
    }

    /** 静止的手机：传感器噪声级别的小抖动。确定性伪随机，方便复现。 */
    private static Signal noise(final float amplitude) {
        return new Signal() {
            private long seed = 12345L;

            @Override
            public float[] at(long tMs) {
                return new float[]{next() * amplitude, next() * amplitude, next() * amplitude};
            }

            private float next() {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                return ((seed >>> 40) / (float) (1 << 24)) * 2f - 1f;
            }
        };
    }

    /** 按 {@link #STEP_MS} 喂一段信号，返回这段时间里判出晃动的次数。 */
    private static int feed(ShakeDetector detector, long startMs, long durationMs, Signal signal) {
        return feed(detector, startMs, durationMs, STEP_MS, signal);
    }

    private static int feed(ShakeDetector detector, long startMs, long durationMs, long stepMs,
                            Signal signal) {
        int triggers = 0;
        for (long t = 0L; t < durationMs; t += stepMs) {
            long nowMs = startMs + t;
            float[] w = signal.at(t);
            if (detector.addSample(w[0], w[1], w[2], nowMs * 1_000_000L, nowMs)) {
                triggers++;
            }
        }
        return triggers;
    }

    // ------------------------------------------------------------------
    // 不该报的
    // ------------------------------------------------------------------

    /** 手机搁在桌上、拿在手里不动：噪声不该被当成晃动。 */
    @Test
    public void aStillPhoneNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 3000L, noise(0.05f)));
    }

    /**
     * 走路：手持摆动约 2 Hz、半 rad/s 上下。
     *
     * <p>这是最重要的一条误报防线——用户走路的时间远多于摔倒的时间。实测（把三个门限逐个
     * 临时调松）它会被峰值、角加速度、规律性三道门分别挡住：把谁单独调松都还能拦住，全部
     * 调松才会漏。误报防线叠了三层，这是有意的。
     */
    @Test
    public void walkingNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 5000L, sine(AXIS_X, 0.5f, 2.0)));
    }

    /**
     * 慢悠悠地转一大圈：角速度一直是 3 rad/s，**超过峰值门限**，但角加速度接近零。
     *
     * <p>这条用例专门钉住"猛"的第二道门。少了它，一个边走边慢慢转头的用户就会报警。
     */
    @Test
    public void aSlowSteadyRotationNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 3000L,
                segments(AXIS_X, new long[]{3000L}, new float[]{3f})));
    }

    /**
     * 单次转身：0.4 秒 +3、接着 0.4 秒 −3。
     *
     * <p>峰值够、角加速度够、时间也够长，只有反转次数不够——一次方向改变不是"乱"。
     */
    @Test
    public void aSingleTurnNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 800L,
                segments(AXIS_X, new long[]{400L, 400L}, new float[]{3f, -3f})));
    }

    /**
     * **规律**的大幅摆动：4 Hz、±3 rad/s，比真晃动的幅度还大，但不该报警。
     *
     * <p>这是"无规则"这道门存在的唯一理由。峰值、角加速度、反转次数它全都满足——只有
     * 反转间隔整齐得像节拍器。少了这道门，用户正常甩手就会触发紧急报警。
     *
     * <p>50 Hz 采样下 `125 ms` 的半周期会被量化成 120/140 毫秒的交替间隔，比值约 1.17，
     * 离 1.5 的门限还留着余量。
     */
    @Test
    public void aRegularLargeOscillationNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 4000L, sine(AXIS_X, 3.0f, 4.0)));
    }

    /**
     * 规律摆动**加上真实的传感器噪声**，仍然不该报。
     *
     * <p>这才是真机上信号的样子。过零点附近角速度几乎是零，噪声会让它在连续几个样本里
     * 来回跳，凑出"间隔 20 毫秒的反转对"——没有 {@link ShakeDetector#MIN_REVERSAL_INTERVAL_MS}
     * 这道去抖，那些假反转会把反转间隔的极值比从 1.17 顶到 5 以上，**规律**晃动反而被当成
     * 无规则晃动报出去。这是本类里唯一一处"加了东西才不误报"的地方。
     */
    @Test
    public void aNoisyRegularOscillationNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 4000L,
                plus(sine(AXIS_X, 3.0f, 4.0), noise(0.3f))));
    }

    // ------------------------------------------------------------------
    // 该报的
    // ------------------------------------------------------------------

    /** 又猛又乱：一次就报，且不连报（冷却期内不再触发）。 */
    @Test
    public void anIrregularShakeTriggersExactlyOnce() {
        assertEquals(1, feed(new ShakeDetector(), 0L, 1300L, irregularShake()));
    }

    /** 一段连续采样必须自己够长：只喂 0.4 秒就下结论，等于拿半截数据说话。 */
    @Test
    public void aBurstShorterThanTheFastWindowNeverTriggers() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 400L, irregularShake()));
        assertEquals(1, feed(new ShakeDetector(), 0L, 1300L, irregularShake()));
    }

    /**
     * 采样中断过就不算一段连续的：断点两边不能拼成一个窗口。
     *
     * <p>第三段用一个干净的检测器喂**连续**的 0.8 秒作对照：同样的信号、同样没有额外信息，
     * 只是中间没有断点，就该报出来。
     */
    @Test
    public void aSampleGapForcesTheBurstToStartOver() {
        ShakeDetector detector = new ShakeDetector();
        assertEquals(0, feed(detector, 0L, 400L, irregularShake()));

        // 断 1.6 秒（远超 MAX_GAP_NS）再喂 0.4 秒：加起来 0.8 秒也不该报。
        assertEquals(0, feed(detector, 2000L, 400L, irregularShake()));

        // 连续的 0.8 秒则应当报出来。
        assertEquals(1, feed(new ShakeDetector(), 0L, 800L, irregularShake()));
    }

    /** 冷却期内不重复触发；冷却一过就恢复。 */
    @Test
    public void theCooldownSuppressesARepeat() {
        ShakeDetector detector = new ShakeDetector();
        assertEquals("第一段应当报一次", 1, feed(detector, 0L, 1300L, irregularShake()));
        assertEquals("2 秒后再晃：还在 6 秒冷却里",
                0, feed(detector, 2000L, 1300L, irregularShake()));
        assertEquals("8 秒后再晃：冷却已过", 1, feed(detector, 8000L, 1300L, irregularShake()));
    }

    /**
     * {@code clear()} 连冷却一起清掉，并接受从头开始的时间戳。
     *
     * <p>传感器重新注册（{@code onResume}）后时间戳会回到很小甚至重新计数；不 clear 的话
     * 每个样本都会被当成"时间倒流"丢掉，报警从此永久失效。
     */
    @Test
    public void clearResetsTheClockAndTheCooldown() {
        ShakeDetector detector = new ShakeDetector();
        assertEquals(1, feed(detector, 0L, 1300L, irregularShake()));

        detector.clear();
        assertEquals("传感器重启就是新的一页", 1, feed(detector, 1000L, 1300L, irregularShake()));
    }

    /**
     * 用户正在操作界面时不做判定。
     *
     * <p>把手机从兜里掏出来、按一下按钮、塞回去，这一串动作的角速度完全够得上"剧烈且无规则"。
     * 用户明摆着醒着、正在用手指跟应用说话，此时弹报警只会教人忽略报警。
     */
    @Test
    public void aSuppressedWindowIgnoresEverything() {
        ShakeDetector detector = new ShakeDetector();
        detector.suppressUntil(0L, 2000L);
        assertEquals("正在操作界面", 0, feed(detector, 0L, 1300L, irregularShake()));
        assertEquals("操作完之后照常判", 1, feed(detector, 3000L, 1300L, irregularShake()));
    }

    /** 时间倒流与重复的样本一律丢掉，且不影响之后的判定。 */
    @Test
    public void samplesWithAStaleTimestampAreDropped() {
        ShakeDetector detector = new ShakeDetector();
        assertFalse(detector.addSample(5f, 0f, 0f, 500_000_000L, 500L));
        assertFalse("重复的时间戳", detector.addSample(5f, 0f, 0f, 500_000_000L, 520L));
        assertFalse("时间倒流", detector.addSample(5f, 0f, 0f, 400_000_000L, 540L));

        assertEquals(1, feed(detector, 1000L, 1300L, irregularShake()));
    }

    /**
     * 采样率太低的设备不可信。
     *
     * <p>同样的信号，每 100 毫秒才来一个样本（10 Hz）：快窗里只有 5 个样本，峰值可能被
     * 漏采、反转可能被数漏。宁可在这类设备上不报，也不要在它们身上赌。
     */
    @Test
    public void aDeviceThatSamplesTooSlowlyIsNotTrusted() {
        assertEquals(0, feed(new ShakeDetector(), 0L, 1500L, 100L, irregularShake()));
        assertEquals(1, feed(new ShakeDetector(), 0L, 1500L, STEP_MS, irregularShake()));
    }
}
