package com.sullivan.protectee.vision.assist;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 播报语速档位。
 *
 * <p><b>为什么要能调，而且默认就比系统快。</b>一句中文提示按 5 字/秒念，
 * "中央行人较近，注意"要 1.7 秒——而它描述的危险可能只剩 1.8 秒。语速不是"悦耳度"
 * 的偏好，它直接决定这句提示还来不来得及起作用，所以：
 *
 * <ol>
 *   <li>三档都可选，默认 {@link #STANDARD} 已经比平台默认（1.0）快 {@value #DEFAULT_BOOST}
 *       倍——改造前用的是系统默认，念得比危险来得慢；</li>
 *   <li>紧急等级还会在档位之上再乘一次 {@link #URGENT_BOOST}（见 {@code PromptAnnouncer}）。
 *       档位是用户的长期偏好，紧急加速是这件事本身的要求，两者不该互相覆盖。</li>
 * </ol>
 *
 * <p>上限刻意压在 1.45×：再快，中文的声调会被合成器吃掉，老年人（受护人的主体）聽辨
 * 反而更差。宁可把句子缩短（见 {@code ObstacleAdvisor.describe}），不要一味加速。
 */
public enum SpeechPace {

    /** 慢：听不清或环境嘈杂时用。 */
    SLOW(0.85f),
    /** 标准：比平台默认快 15%，是改造后的默认档。 */
    STANDARD(1.15f),
    /** 快：熟悉之后想听更短的停顿。 */
    FAST(1.45f);

    /** 默认档相对平台默认的倍率，只用于文档，不参与计算。 */
    public static final float DEFAULT_BOOST = 1.15f;
    /** 紧急提示在档位之上的额外倍率。 */
    public static final float URGENT_BOOST = 1.25f;
    /** 合成器接受的语速范围，超出会被系统静默夹取，这里先夹住以免调试时看不出问题。 */
    public static final float MIN_RATE = 0.5f;
    public static final float MAX_RATE = 2.0f;

    private final float rate;

    SpeechPace(float rate) {
        this.rate = rate;
    }

    /** 交给 {@code TextToSpeech.setSpeechRate} 的倍率。 */
    public float rate() {
        return rate;
    }

    /** 在这一档之上再加速（用于紧急提示）；结果已夹到合法范围。 */
    public float urgentRate() {
        return clamp(rate * URGENT_BOOST);
    }

    /** 下一档，循环切换——界面上的按钮点一下换一档。 */
    @NonNull
    public SpeechPace next() {
        SpeechPace[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    @NonNull
    public SpeechPace previous() {
        SpeechPace[] all = values();
        return all[(ordinal() + all.length - 1) % all.length];
    }

    /** 按名字还原存档里的档位，认不出来就用 {@code fallback}。 */
    @NonNull
    public static SpeechPace fromName(@Nullable String name, @NonNull SpeechPace fallback) {
        if (name != null) {
            for (SpeechPace pace : values()) {
                if (pace.name().equals(name)) {
                    return pace;
                }
            }
        }
        return fallback;
    }

    static float clamp(float rate) {
        if (rate < MIN_RATE) {
            return MIN_RATE;
        }
        return Math.min(rate, MAX_RATE);
    }
}
