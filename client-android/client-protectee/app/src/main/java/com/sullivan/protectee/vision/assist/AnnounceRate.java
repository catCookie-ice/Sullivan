package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 播报频率档位：界面上那个「少 / 标准 / 多」。
 *
 * <p>把三组间隔从 {@link AnnouncementPolicy} 的编译期常量变成用户可选的档位。
 * {@link #STANDARD} 逐字等于改造前的默认值，所以**不选任何档位时行为与原来完全一致**。
 *
 * <p><b>档位只调"间隔"和"一句话里报几簇"，不屏蔽任何等级。</b>听不到的东西比听腻了更危险：
 * 「少」档下右侧的汽车照样会说，只是说得稀。要减到"只报正前方"是产品决策，不该藏在频率
 * 档位里——那会让人以为身后是安全的。
 *
 * <p>也不动 {@link AnnouncementPolicy#STABLE_MS}：那是抖动抑制（检测框在门限上抖动会让文案
 * 横跳），不是频率。调它等于把"同一件事反复播报"放回来。
 *
 * <p>纯 Java、不可变，可以安全地跨线程发布（UI 线程写、推理线程读）。
 */
public enum AnnounceRate {

    /** 少：间隔拉开，一句话只报最近的一簇。 */
    QUIET(2500L, 12000L, 4000L, 2500L, 1),
    /** 标准：改造前的默认值。 */
    STANDARD(800L, 4000L, 2000L, 1200L, 2),
    /** 多：说得勤一些。 */
    CHATTY(500L, 2500L, 1500L, 800L, 2);

    private final long differentGapMs;
    private final long sameMessageCooldownMs;
    private final long minInterruptGapMs;
    private final long hapticCooldownMs;
    private final int maxReported;

    AnnounceRate(long differentGapMs, long sameMessageCooldownMs, long minInterruptGapMs,
                 long hapticCooldownMs, int maxReported) {
        this.differentGapMs = differentGapMs;
        this.sameMessageCooldownMs = sameMessageCooldownMs;
        this.minInterruptGapMs = minInterruptGapMs;
        this.hapticCooldownMs = hapticCooldownMs;
        this.maxReported = maxReported;
    }

    /** 换一句播报时，距上一句**播完**的最小间隔。 */
    public long differentGapMs() {
        return differentGapMs;
    }

    /** 同一句话重复播报的最小间隔，同样从播完起算。 */
    public long sameMessageCooldownMs() {
        return sameMessageCooldownMs;
    }

    /** 两次抢断之间的最小间隔。 */
    public long minInterruptGapMs() {
        return minInterruptGapMs;
    }

    /** 两次振动之间的最小间隔。 */
    public long hapticCooldownMs() {
        return hapticCooldownMs;
    }

    /** 一条静态提示里最多报几簇障碍物。 */
    public int maxReported() {
        return maxReported;
    }

    /** 下一档，循环切换——界面上的按钮点一下换一档。 */
    @NonNull
    public AnnounceRate next() {
        AnnounceRate[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * 按名字还原存档里的档位，认不出来就用 {@code fallback}。
     *
     * <p>存名字而不存序号：以后往中间插一档，序号会整体错位，名字不会。
     */
    @NonNull
    public static AnnounceRate fromName(@Nullable String name, @NonNull AnnounceRate fallback) {
        if (name != null) {
            for (AnnounceRate rate : values()) {
                if (rate.name().equals(name)) {
                    return rate;
                }
            }
        }
        return fallback;
    }
}
