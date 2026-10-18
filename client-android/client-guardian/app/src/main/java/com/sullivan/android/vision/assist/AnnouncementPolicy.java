package com.sullivan.android.vision.assist;

import androidx.annotation.NonNull;

/**
 * 决定"这句话现在该不该说"。纯 Java，不含任何 Android 依赖，便于把节流规则完整测到。
 *
 * <p>{@link PromptAnnouncer} 只负责把这里的决定翻译成 TTS 调用。
 *
 * <p>要解决的问题是"上一句还没播完，下一句就开始了"。三层机制：
 *
 * <ol>
 *   <li><b>一句说完再说下一句</b>：{@link State} 跟踪播报状态，播报期间返回
 *       {@link Action#DROP}，由调用方在后续帧上重试。所以间隔是从**上一句播完**起算的
 *       ——这正是原实现最大的问题（它从"开始播"起算，而一句中文提示要读 3 秒，
 *       冷却期只有 1 秒）。</li>
 *   <li><b>抖动抑制</b>：一句话要连续保持 {@link #STABLE_MS} 不变才有资格被播报。
 *       检测框在方位/远近的边界上抖动会让文案反复横跳，那是"重复播报"的主要来源。
 *       自带滞回的通道（动态告警）跳过这一层，见 {@code decide} 的 {@code hysteretic} 参数。</li>
 *   <li><b>限定抢断</b>：只有**更高**优先级能打断正在播的那句，且要满足最短已播时长与
 *       抢断间隔。否则紧急提示会被前面一句长提示挡上好几秒。</li>
 * </ol>
 *
 * <p>本类没有"待播队列"：调用方每帧都会带着**当前**场景重新问一次，从而永远只播最新的
 * 那句。若要缓存一句在播报期间攒下的消息，等播完再念，那句描述的场景很可能已经过去了。
 *
 * <p>上面提到的三组间隔不是常量而是 {@link AnnounceRate} 档位——"太频繁"是个体差异，
 * 得让用户自己选。{@link AnnounceRate#STANDARD} 就是改造前的默认值。
 */
public final class AnnouncementPolicy {

    /** 一句话要连续保持这么久不变，才有资格被播报。文案抖动的主要抑制手段。 */
    public static final long STABLE_MS = 350L;
    /** 正在播的这句至少播满这么久，才允许被更高优先级抢断。 */
    public static final long MIN_PLAYED_BEFORE_INTERRUPT_MS = 700L;

    public enum Action {
        /** 现在就说。 */
        SPEAK,
        /** 现在不说；调用方下一帧再问一次即可。 */
        DROP
    }

    /** 播报状态。{@code WAITING_START} 表示已交给引擎、但还没真正出声。 */
    public enum State { IDLE, WAITING_START, PLAYING }

    /** 一次决策。 */
    public static final class Decision {
        @NonNull
        public final Action action;
        /** 是否用"打断当前这句"的方式播报。 */
        public final boolean preempt;

        private Decision(@NonNull Action action, boolean preempt) {
            this.action = action;
            this.preempt = preempt;
        }

        public static final Decision DROP = new Decision(Action.DROP, false);
        /** 队列空闲，正常排队播报。 */
        public static final Decision SPEAK = new Decision(Action.SPEAK, false);
        /** 打断正在播的那句。 */
        public static final Decision INTERRUPT = new Decision(Action.SPEAK, true);
    }

    /** 用户选的频率档位。默认档等于改造前的常量。 */
    @NonNull
    private AnnounceRate rate = AnnounceRate.STANDARD;

    private State state = State.IDLE;
    /** 本句真正出声的时刻；{@code PLAYING} 期间用来判断"播够久了没"。 */
    private long utteranceStartedAt;
    /** 上一句播完的时刻；所有间隔都从这里起算。 */
    private long speechEndedAt;
    private long lastInterruptAt;
    /** 是否播报过。用显式标志而不是拿 0 当哨兵，时间基准从 0 开始时才不会算错。 */
    private boolean hasSpoken;
    private boolean hasInterrupted;

    private int currentPriority;

    private String lastSpokenKey = "";
    /** 是否播报过障碍提示。用于让"通畅"只在障碍消失的那一次说一声。 */
    private boolean obstacleAnnounced;

    private String candidateKey = "";
    private long candidateSince;

    /**
     * 换一个频率档位。立即生效，不影响已经记住的冷却起点——把间隔拉长之后，正在等的那句
     * 会按新间隔继续等，不会因为改了设置就立刻插队播出来。
     */
    public void setRate(@NonNull AnnounceRate rate) {
        this.rate = rate;
    }

    @NonNull
    public AnnounceRate rate() {
        return rate;
    }

    /**
     * 现在该不该说这句话。等价于 {@code decide(message, level, dedupKey, nowMs, false)}。
     */
    @NonNull
    public Decision decide(@NonNull String message, @NonNull ObstacleAdvisor.Level level,
                           @NonNull String dedupKey, long nowMs) {
        return decide(message, level, dedupKey, nowMs, false);
    }

    /**
     * 现在该不该说这句话。
     *
     * @param dedupKey   语义去重键（如 {@code "static:person:中央:near"}）。**不要用消息文本**
     *                   当键：距离/时间分档会让文本频繁变化，去重会直接失效。
     * @param hysteretic 这个键是否来自**自带滞回**的通道（见 {@link DynamicAdvisor}）。是的话
     *                   跳过抖动抑制：那层滞回已经保证了键不会来回跳，再叠 350ms 只会让
     *                   「行人接近」晚半秒说出口，而那时剩余碰撞时间可能只有 1.5 秒。
     *                   **不能只看等级**：静态通道也会给 DANGER，而检测框在"较近"的门限上
     *                   抖动时，它的 DANGER/CAUTION 会交替出现，那种抖动必须继续被抑制。
     */
    @NonNull
    public Decision decide(@NonNull String message, @NonNull ObstacleAdvisor.Level level,
                           @NonNull String dedupKey, long nowMs, boolean hysteretic) {
        if (message.isEmpty() || dedupKey.isEmpty()) {
            return Decision.DROP;
        }

        // 通畅状态本身没有反复播报的价值，只播"刚才是障碍、现在通了"这一次。
        if (level == ObstacleAdvisor.Level.CLEAR && !obstacleAnnounced) {
            return Decision.DROP;
        }

        if (!dedupKey.equals(candidateKey)) {
            candidateKey = dedupKey;
            candidateSince = nowMs;
        }
        if (!hysteretic && nowMs - candidateSince < STABLE_MS) {
            return Decision.DROP;
        }

        int priority = priorityOf(level);
        if (state != State.IDLE) {
            return decideWhileSpeaking(priority, nowMs);
        }

        long gap = dedupKey.equals(lastSpokenKey) ? rate.sameMessageCooldownMs() : rate.differentGapMs();
        if (hasSpoken && nowMs - speechEndedAt < gap) {
            return Decision.DROP;
        }
        beginUtterance(level, dedupKey);
        return Decision.SPEAK;
    }

    @NonNull
    private Decision decideWhileSpeaking(int priority, long nowMs) {
        boolean higherPriority = priority > currentPriority;
        // 还没出声的句子随时可以换掉：此时打断没有任何听感损失。
        boolean playedLongEnough = state == State.WAITING_START
                || nowMs - utteranceStartedAt >= MIN_PLAYED_BEFORE_INTERRUPT_MS;
        boolean interruptAllowed = !hasInterrupted
                || nowMs - lastInterruptAt >= rate.minInterruptGapMs();

        if (higherPriority && playedLongEnough && interruptAllowed) {
            lastInterruptAt = nowMs;
            hasInterrupted = true;
            return Decision.INTERRUPT;
        }
        // 优先级不够就等它播完，下一帧会重新问一次。
        return Decision.DROP;
    }

    /** 记下"要播这句了"。调用方随后必须调 TTS。 */
    private void beginUtterance(@NonNull ObstacleAdvisor.Level level, @NonNull String dedupKey) {
        state = State.WAITING_START;
        utteranceStartedAt = 0L;
        currentPriority = priorityOf(level);
        lastSpokenKey = dedupKey;
        hasSpoken = true;
        obstacleAnnounced = level != ObstacleAdvisor.Level.CLEAR;
    }

    /** 引擎报告真正开始出声。最短已播时长从这一刻起算，而不是从 {@code speak()} 起算。 */
    public void onUtteranceStarted(long nowMs) {
        state = State.PLAYING;
        utteranceStartedAt = nowMs;
    }

    /**
     * 引擎报告这句结束（播完、被打断、出错或被看门狗复位）。
     *
     * <p>{@code nowMs} 会成为新一轮冷却的起点。
     */
    public void onUtteranceFinished(long nowMs) {
        state = State.IDLE;
        utteranceStartedAt = 0L;
        speechEndedAt = nowMs;
    }

    /** 当前是否有一句在播（含已交给引擎但还没出声）。 */
    public boolean isSpeaking() {
        return state != State.IDLE;
    }

    @NonNull
    public State state() {
        return state;
    }

    /** 优先级：CLEAR &lt; CAUTION &lt; DANGER，直接用枚举声明顺序。 */
    private static int priorityOf(@NonNull ObstacleAdvisor.Level level) {
        return level.ordinal();
    }
}
