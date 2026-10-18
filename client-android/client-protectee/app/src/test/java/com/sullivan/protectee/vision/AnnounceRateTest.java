package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sullivan.protectee.vision.assist.AnnounceRate;

import org.junit.Test;

/**
 * 频率档位表的契约。
 *
 * <p>这里的用例看起来在"复述常量"，但它们是**唯一**能挡住"默认档被悄悄改掉"的东西：
 * 上一轮把 {@code DIFFERENT_GAP_MS} 这类常量挪进了枚举，字面量就不在
 * {@code AnnouncementPolicy} 里了，谁手滑改一下没人会发现。
 */
public class AnnounceRateTest {

    /**
     * 「标准」必须逐字等于改造前的常量。改这里之前先想清楚：老用户没动过设置，
     * 他听到的频率就是这一组数。
     */
    @Test
    public void standardIsExactlyTheOldDefaults() {
        assertEquals(800L, AnnounceRate.STANDARD.differentGapMs());
        assertEquals(4000L, AnnounceRate.STANDARD.sameMessageCooldownMs());
        assertEquals(2000L, AnnounceRate.STANDARD.minInterruptGapMs());
        assertEquals(1200L, AnnounceRate.STANDARD.hapticCooldownMs());
        assertEquals(2, AnnounceRate.STANDARD.maxReported());
    }

    /** 档位之间的单调性：少 → 标准 → 多，四个间隔依次变短。 */
    @Test
    public void quietIsQuieterThanStandardAndChattyIsChattier() {
        assertTrue(AnnounceRate.QUIET.differentGapMs() > AnnounceRate.STANDARD.differentGapMs());
        assertTrue(AnnounceRate.STANDARD.differentGapMs() > AnnounceRate.CHATTY.differentGapMs());

        assertTrue(AnnounceRate.QUIET.sameMessageCooldownMs()
                > AnnounceRate.STANDARD.sameMessageCooldownMs());
        assertTrue(AnnounceRate.STANDARD.sameMessageCooldownMs()
                > AnnounceRate.CHATTY.sameMessageCooldownMs());

        assertTrue(AnnounceRate.QUIET.minInterruptGapMs()
                > AnnounceRate.STANDARD.minInterruptGapMs());
        assertTrue(AnnounceRate.STANDARD.minInterruptGapMs()
                > AnnounceRate.CHATTY.minInterruptGapMs());

        assertTrue(AnnounceRate.QUIET.hapticCooldownMs()
                > AnnounceRate.STANDARD.hapticCooldownMs());
        assertTrue(AnnounceRate.STANDARD.hapticCooldownMs()
                > AnnounceRate.CHATTY.hapticCooldownMs());

        assertTrue(AnnounceRate.QUIET.maxReported() <= AnnounceRate.STANDARD.maxReported());
    }

    /** 界面上的按钮就是靠 next() 循环切换的，转一圈必须回到原处。 */
    @Test
    public void nextCyclesThroughEveryRateAndReturns() {
        AnnounceRate rate = AnnounceRate.QUIET;
        for (int i = 0; i < AnnounceRate.values().length; i++) {
            rate = rate.next();
        }
        assertEquals("转一圈应当回到起点", AnnounceRate.QUIET, rate);

        assertEquals(AnnounceRate.STANDARD, AnnounceRate.QUIET.next());
        assertEquals(AnnounceRate.CHATTY, AnnounceRate.STANDARD.next());
    }

    /**
     * 存档里读到认不出来的名字不能崩。
     *
     * <p>换版本、装回旧版、存档被改坏都会走到这里。用 {@code valueOf} 的话
     * {@code IllegalArgumentException} 会在启动时把应用直接打掉。
     */
    @Test
    public void unknownStoredNameFallsBackInsteadOfThrowing() {
        // 认得出的名字要原样认出来——包括不是 fallback 的那几档。
        assertEquals(AnnounceRate.CHATTY, AnnounceRate.fromName("CHATTY", AnnounceRate.STANDARD));
        assertEquals(AnnounceRate.QUIET, AnnounceRate.fromName("QUIET", AnnounceRate.STANDARD));

        // 认不出的走 fallback，且**不许抛异常**。
        assertEquals(AnnounceRate.STANDARD, AnnounceRate.fromName(null, AnnounceRate.STANDARD));
        assertEquals(AnnounceRate.STANDARD, AnnounceRate.fromName("", AnnounceRate.STANDARD));
        assertEquals(AnnounceRate.STANDARD, AnnounceRate.fromName("whisper", AnnounceRate.STANDARD));
        assertEquals(AnnounceRate.CHATTY, AnnounceRate.fromName("nope", AnnounceRate.CHATTY));
    }
}
