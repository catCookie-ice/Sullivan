package com.sullivan.protectee.service.alert;

import androidx.annotation.NonNull;

/**
 * 决定「要不要因为一次碰撞预警而自动报警」的状态机。
 *
 * <p>纯函数式的判定，不碰网络也不碰 View，所以能在 JVM 单测里把整条时间线跑完。
 *
 * <p><b>为什么需要它而不是「DANGER 就报警」。</b>{@code DynamicAdvisor} 的 DANGER 会
 * 在同一个障碍物持续靠近时反复触发（每一帧都成立）。直接报警等于每秒发一次，
 * 监护人会被打到关机。这里做三件事：
 *
 * <ol>
 *   <li><b>只在上升沿触发</b>：从「不危险」变成「危险」的那一刻算一次，持续危险不算；</li>
 *   <li><b>冷却</b>：触发后一段时间内不再触发，即使危险消失了又重新出现；</li>
 *   <li><b>要求持续</b>：单帧的 DANGER 可能是误检，要求连续满足一段时间才算数。</li>
 * </ol>
 *
 * <p><b>为什么冷却期比「危险持续」长得多。</b>危险持续是防误检（几百毫秒），
 * 冷却是防轰炸（分钟级）。一个用户在马路上走十分钟，可能遇到好几次真实的近距离
 * 障碍；每次都自动报警仍然过分 —— 所以自动报警只报第一次，后续靠用户手动。
 */
public final class CollisionAlertGate {

    /** 连续危险多久才算数。太低会让单帧误检变成报警，太高会漏掉真正的突发。 */
    public static final long SUSTAIN_MS = 800L;

    /**
     * 触发后的冷却时长。
     *
     * <p>3 分钟：与 {@link AlertKeys#SAME_ALERT_WINDOW_MS}（5 分钟）配合 ——
     * 冷却短于幂等窗口，意味着冷却结束时新的触发会落在**同一个**幂等桶里，
     * 服务端仍然把它当成同一次报警。这是刻意的：短时间内的第二次自动触发
     * 不该再骚扰一轮监护人。
     */
    public static final long COOLDOWN_MS = 3 * 60 * 1000L;

    /** 危险开始的时刻；{@code 0} 表示当前不危险。 */
    private long dangerSinceMs;

    /** 上一次自动触发报警的时刻；{@code 0} 表示从未触发。 */
    private long lastFiredMs;

    /**
     * 喂入一帧的危险状态。
     *
     * @param danger  本帧是否处于 DANGER
     * @param nowMs   当前时刻（单调时钟）
     * @return 这一帧是否应该**新开**一条报警
     */
    public boolean onFrame(boolean danger, long nowMs) {
        if (!danger) {
            // 危险消失：重置持续计时。下一次危险重新从零开始累计。
            dangerSinceMs = 0L;
            return false;
        }
        if (dangerSinceMs == 0L) {
            dangerSinceMs = nowMs;
            // 刚进入危险，还没满足持续时间，这一帧不触发。
            return false;
        }
        if (nowMs - dangerSinceMs < SUSTAIN_MS) {
            return false;
        }
        if (lastFiredMs != 0L && nowMs - lastFiredMs < COOLDOWN_MS) {
            // 冷却中：危险是持续的，但已经报过了，不再重复打扰。
            return false;
        }
        lastFiredMs = nowMs;
        // 触发后把持续计时清掉，避免下一次 onFrame 在同一段危险里再次满足条件。
        dangerSinceMs = 0L;
        return true;
    }

    /**
     * 手动报警之后调用，让自动触发也进入冷却。
     *
     * <p>用户刚刚自己按了报警，说明他已经处理了这件事；此时再自动报一次，
     * 监护人收到的是两条不同 ID 的报警，只会更混乱。
     */
    public void suppressUntil(long nowMs) {
        lastFiredMs = nowMs;
        dangerSinceMs = 0L;
    }

    /** 状态复位（换模型、切页面时用）。 */
    public void reset() {
        dangerSinceMs = 0L;
        lastFiredMs = 0L;
    }

    /** 供测试与调试观察。 */
    public boolean isCoolingDown(long nowMs) {
        return lastFiredMs != 0L && nowMs - lastFiredMs < COOLDOWN_MS;
    }
}
