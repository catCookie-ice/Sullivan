package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.assist.AnnounceRate;
import com.sullivan.android.vision.assist.AnnouncementPolicy;
import com.sullivan.android.vision.assist.ObstacleAdvisor;

import org.junit.Before;
import org.junit.Test;

/**
 * 播报节流规则的契约。用户的抱怨是"上一句还没播完，下一句就开始了"，
 * 这些用例逐条钉住当时的修法。
 */
public class AnnouncementPolicyTest {

    private static final ObstacleAdvisor.Level CAUTION = ObstacleAdvisor.Level.CAUTION;
    private static final ObstacleAdvisor.Level DANGER = ObstacleAdvisor.Level.DANGER;
    private static final ObstacleAdvisor.Level CLEAR = ObstacleAdvisor.Level.CLEAR;

    private static final String MSG_A = "中央行人较近，注意";
    private static final String MSG_B = "右侧汽车，请注意";

    /** 不设置任何档位时的间隔。用户没动过设置时用的就是这一组。 */
    private static final AnnounceRate DEFAULT_RATE = AnnounceRate.STANDARD;

    private AnnouncementPolicy policy;

    @Before
    public void setUp() {
        policy = new AnnouncementPolicy();
    }

    /** 让一条消息"稳定"下来，即越过抖动抑制窗口。 */
    private AnnouncementPolicy.Decision settle(String message, ObstacleAdvisor.Level level,
                                               String key, long startMs) {
        policy.decide(message, level, key, startMs);
        return policy.decide(message, level, key,
                startMs + AnnouncementPolicy.STABLE_MS);
    }

    /** 模拟"这句话播完了"。 */
    private void finishSpeaking(long nowMs) {
        policy.onUtteranceStarted(nowMs - 100L);
        policy.onUtteranceFinished(nowMs);
    }

    @Test
    public void freshMessageIsSpokenOnceStable() {
        // 刚出现时还不稳定，不能播。
        assertEquals(AnnouncementPolicy.Decision.DROP,
                policy.decide(MSG_A, CAUTION, "k1", 0L));
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_A, CAUTION, "k1", AnnouncementPolicy.STABLE_MS));
        assertEquals(AnnouncementPolicy.State.WAITING_START, policy.state());
    }

    /** 文案在边界上抖动（每帧都变）时一直不播，这是"重复播报"的主要来源。 */
    @Test
    public void flickeringMessagesAreNeverSpoken() {
        long now = 0L;
        for (int i = 0; i < 20; i++) {
            String key = i % 2 == 0 ? "k1" : "k2";
            assertEquals(AnnouncementPolicy.Decision.DROP,
                    policy.decide(MSG_A, CAUTION, key, now));
            now += 100L;
        }
        assertFalse(policy.isSpeaking());
    }

    /** 上一句没播完之前，下一句不能开口——这是用户抱怨的核心。 */
    @Test
    public void nothingIsSpokenWhileTheCurrentSentenceIsPlaying() {
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                settle(MSG_A, CAUTION, "k1", 0L));

        policy.onUtteranceStarted(1000L);
        // 播报中途来了另一句（同级），必须等。
        policy.decide(MSG_B, CAUTION, "k2", 1100L);
        assertEquals(AnnouncementPolicy.Decision.DROP,
                policy.decide(MSG_B, CAUTION, "k2", 1100L + AnnouncementPolicy.STABLE_MS));
        assertEquals(AnnouncementPolicy.State.PLAYING, policy.state());
    }

    /** 同级不能打断同级，否则两句"注意"会互相掐断。 */
    @Test
    public void sameLevelNeverInterrupts() {
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(0L);

        long now = AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS + 5000L;
        policy.decide(MSG_B, CAUTION, "k2", now - AnnouncementPolicy.STABLE_MS);
        assertFalse(policy.decide(MSG_B, CAUTION, "k2", now).preempt);
    }

    /** 危险可以打断"注意"，但要等当前这句播满最短时长。 */
    @Test
    public void dangerInterruptsCautionOnlyAfterMinimumPlayTime() {
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(1000L);

        // 刚开始播：还不允许打断。
        policy.decide(MSG_B, DANGER, "k2", 1100L);
        assertFalse(policy.decide(MSG_B, DANGER, "k2",
                1100L + AnnouncementPolicy.STABLE_MS).preempt);

        // 播够 700ms 之后可以。
        long now = 1000L + AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS + 10L;
        assertEquals(AnnouncementPolicy.Decision.INTERRUPT,
                policy.decide(MSG_B, DANGER, "k2", now));
    }

    /** 还没出声的句子随时可以换掉：此时打断没有任何听感损失。 */
    @Test
    public void queuedButNotYetAudibleSentenceCanBeReplacedImmediately() {
        settle(MSG_A, CAUTION, "k1", 0L);
        assertEquals(AnnouncementPolicy.State.WAITING_START, policy.state());

        policy.decide(MSG_B, DANGER, "k2", 10L);
        assertEquals(AnnouncementPolicy.Decision.INTERRUPT,
                policy.decide(MSG_B, DANGER, "k2", 10L + AnnouncementPolicy.STABLE_MS));
    }

    /**
     * 自带滞回的通道（动态告警）跳过抖动抑制，第一帧就能说。
     *
     * <p>350ms 看起来不长，但「行人接近」只在剩余碰撞时间不到 1.8 秒时才说——半秒就是
     * 三分之一的余量。而那层滞回（进门限 1.8 秒、出门限 3.0 秒）已经保证了键不会来回跳，
     * 抖动抑制在这里没有任何东西可抑制。
     */
    @Test
    public void hystereticAlertSkipsTheStabilityDelay() {
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(0L);

        long now = AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS + 10L;
        assertEquals(AnnouncementPolicy.Decision.INTERRUPT,
                policy.decide(MSG_B, DANGER, "dyn:7:near", now, true));
    }

    /**
     * 但静态通道的 DANGER **不能**跳过抖动抑制。
     *
     * <p>检测框在"较近"的门限上下抖动时，同一句话的 DANGER 与 CAUTION 会交替出现；
     * 只看等级豁免的话，这种抖动会变成每 800ms 一条的播报——正是用户抱怨的"太频繁"。
     */
    @Test
    public void staticDangerStillWaitsForStability() {
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(0L);

        long now = AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS + 10L;
        assertFalse(policy.decide(MSG_B, DANGER, "static:person:中央:near", now, false).preempt);
    }

    /** 抢断限流：危险提示不能连环切断，否则会变成另一种刷屏。 */
    @Test
    public void interruptionsAreRateLimited() {
        // 一句"注意"正在播。
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(1000L);

        // 第一次抢断：危险打断"注意"，此时它已播够最短时长。
        policy.decide(MSG_B, DANGER, "k2", 1000L);
        long firstInterrupt = 1000L + AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS;
        assertTrue(policy.decide(MSG_B, DANGER, "k2", firstInterrupt).preempt);

        // 危险提示很短，很快念完，接着又轮到一个"注意"。
        policy.onUtteranceStarted(firstInterrupt);
        policy.onUtteranceFinished(firstInterrupt + 100L);
        long cautionStart = firstInterrupt + 100L + DEFAULT_RATE.differentGapMs()
                + AnnouncementPolicy.STABLE_MS;
        policy.decide(MSG_A, CAUTION, "k4", firstInterrupt + 100L);
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_A, CAUTION, "k4", cautionStart));
        policy.onUtteranceStarted(cautionStart);

        // 这句"注意"已经播够最短时长，但距上次抢断还不到 2 秒，必须忍住。
        policy.decide("行人接近", DANGER, "k5", cautionStart + 150L);
        long tooSoon = cautionStart + AnnouncementPolicy.MIN_PLAYED_BEFORE_INTERRUPT_MS;
        assertFalse(policy.decide("行人接近", DANGER, "k5", tooSoon).preempt);

        // 限流窗口一过就能抢断。
        long allowed = firstInterrupt + DEFAULT_RATE.minInterruptGapMs() + 10L;
        assertTrue("限流窗口应当晚于最短播出时长", allowed > tooSoon);
        assertTrue(policy.decide("行人接近", DANGER, "k5", allowed).preempt);
    }

    /** 冷却从"播完"起算，不是从"开始播"起算——原实现错了这一条。 */
    @Test
    public void cooldownIsMeasuredFromWhenSpeechEnded() {
        settle(MSG_A, CAUTION, "k1", 0L);
        // 这句念了 3 秒才念完。
        policy.onUtteranceStarted(1000L);
        policy.onUtteranceFinished(4000L);

        // 播完后 0.5 秒内不能开口。
        policy.decide(MSG_B, CAUTION, "k2", 4100L);
        assertEquals(AnnouncementPolicy.Decision.DROP,
                policy.decide(MSG_B, CAUTION, "k2", 4500L));

        // 过了换句间隔就可以。
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_B, CAUTION, "k2",
                        4000L + DEFAULT_RATE.differentGapMs() + 100L));
    }

    /** 同一句话要隔得更久才能重复，同样从播完起算。 */
    @Test
    public void sameMessageRepeatsOnlyAfterLongCooldown() {
        settle(MSG_A, CAUTION, "k1", 0L);
        finishSpeaking(4000L);

        // 换句间隔已过，但同一句还不该重复。
        assertEquals(AnnouncementPolicy.Decision.DROP,
                policy.decide(MSG_A, CAUTION, "k1",
                        4000L + DEFAULT_RATE.differentGapMs() + 100L));
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_A, CAUTION, "k1",
                        4000L + DEFAULT_RATE.sameMessageCooldownMs() + 100L));
    }

    /** 通畅状态不反复播报：只有"刚才是障碍、现在没了"那一次才说。 */
    @Test
    public void clearIsAnnouncedOnlyAfterAnObstacleWasAnnounced() {
        assertEquals(AnnouncementPolicy.Decision.DROP,
                settle("前方暂未检测到明显障碍物", CLEAR, "clear", 0L));

        // 播报过一次障碍……
        settle(MSG_A, CAUTION, "k1", 0L);
        finishSpeaking(4000L);

        // ……之后的通畅才值得说一声。
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                settle("前方暂未检测到明显障碍物", CLEAR, "clear",
                        4000L + DEFAULT_RATE.differentGapMs() + 100L));

        // 再说通畅就没人听了。
        finishSpeaking(9000L);
        assertEquals(AnnouncementPolicy.Decision.DROP,
                settle("前方暂未检测到明显障碍物", CLEAR, "clear",
                        9000L + DEFAULT_RATE.sameMessageCooldownMs() + 100L));
    }

    /** 引擎没回调时看门狗会复位状态，之后必须还能继续播报。 */
    @Test
    public void policyRecoversAfterWatchdogReset() {
        settle(MSG_A, CAUTION, "k1", 0L);
        policy.onUtteranceStarted(1000L);
        // 看门狗强行复位。
        policy.onUtteranceFinished(6000L);
        assertEquals(AnnouncementPolicy.State.IDLE, policy.state());

        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                settle(MSG_B, CAUTION, "k2", 6000L + DEFAULT_RATE.differentGapMs() + 100L));
    }

    @Test
    public void emptyMessageIsIgnored() {
        assertEquals(AnnouncementPolicy.Decision.DROP, policy.decide("", CAUTION, "k1", 0L));
        assertEquals(AnnouncementPolicy.Decision.DROP,
                policy.decide(MSG_A, CAUTION, "", 0L));
        assertFalse(policy.isSpeaking());
    }

    // ------------------------------------------------------------------
    // 频率档位
    // ------------------------------------------------------------------

    /** 用户没动过设置时必须是「标准」。默认档悄悄变成"话痨"或"沉默"是最难发现的回归。 */
    @Test
    public void defaultRateIsStandard() {
        assertEquals(AnnounceRate.STANDARD, policy.rate());
    }

    /**
     * 档位真的在起作用：同一段时序在「多」下说得出口，在「标准」「少」下被压住。
     *
     * <p>时序：A 在 1000ms 播完；1100ms 时 B 成为候选（抖动窗口开始计时）；1700ms 时问
     * B 能不能播——距上一句播完 700ms。「多」的换句间隔是 500ms，能播；「标准」要 800ms，
     * 「少」要 2500ms，都不能。
     */
    @Test
    public void theRateChangesHowOftenItSpeaks() {
        assertEquals(AnnouncementPolicy.Decision.SPEAK, askBSoonAfterA(AnnounceRate.CHATTY));
        assertEquals(AnnouncementPolicy.Decision.DROP, askBSoonAfterA(AnnounceRate.STANDARD));
        assertEquals(AnnouncementPolicy.Decision.DROP, askBSoonAfterA(AnnounceRate.QUIET));
    }

    /** 播完 A，再在距 A 播完 600ms 处问一次 B。 */
    private static AnnouncementPolicy.Decision askBSoonAfterA(AnnounceRate rate) {
        AnnouncementPolicy fresh = policyWith(rate);
        fresh.decide(MSG_A, CAUTION, "k1", 0L);
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                fresh.decide(MSG_A, CAUTION, "k1", AnnouncementPolicy.STABLE_MS));
        fresh.onUtteranceStarted(400L);
        fresh.onUtteranceFinished(1000L);

        fresh.decide(MSG_B, CAUTION, "k2", 1100L);
        return fresh.decide(MSG_B, CAUTION, "k2", 1700L);
    }

    /**
     * 「少」档下同一句话要等久得多。这正是用户抱怨"同一件事反复播报"的那个旋钮。
     */
    @Test
    public void quietHoldsTheSameMessageMuchLonger() {
        AnnouncementPolicy quiet = policyWith(AnnounceRate.QUIET);
        quiet.decide(MSG_A, CAUTION, "k1", 0L);
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                quiet.decide(MSG_A, CAUTION, "k1", AnnouncementPolicy.STABLE_MS));
        quiet.onUtteranceStarted(400L);
        quiet.onUtteranceFinished(1000L);

        // 「标准」到这里就该重播了（4000ms 冷却）：「少」要等到 12000ms。
        assertEquals(AnnouncementPolicy.Decision.DROP,
                quiet.decide(MSG_A, CAUTION, "k1", 1000L + 4100L));
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                quiet.decide(MSG_A, CAUTION, "k1", 1000L + 12100L));
    }

    /** 换档位立即生效，但不会让正在冷却的那句插队。 */
    @Test
    public void setRateTakesEffectImmediately() {
        policy.decide(MSG_A, CAUTION, "k1", 0L);
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_A, CAUTION, "k1", AnnouncementPolicy.STABLE_MS));
        finishSpeaking(1000L);

        policy.setRate(AnnounceRate.CHATTY);
        assertEquals(AnnounceRate.CHATTY, policy.rate());
        policy.decide(MSG_B, CAUTION, "k2", 1000L + 200L);
        assertEquals(AnnouncementPolicy.Decision.SPEAK,
                policy.decide(MSG_B, CAUTION, "k2", 1000L + 600L));
    }

    private static AnnouncementPolicy policyWith(AnnounceRate rate) {
        AnnouncementPolicy fresh = new AnnouncementPolicy();
        fresh.setRate(rate);
        return fresh;
    }
}
