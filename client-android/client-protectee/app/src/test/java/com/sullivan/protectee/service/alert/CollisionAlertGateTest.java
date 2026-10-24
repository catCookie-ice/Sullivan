package com.sullivan.protectee.service.alert;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 碰撞→自动报警的时序判定。
 *
 * <p>这些用例覆盖的是**最容易被改坏的那部分**：如果谁把冷却去掉、或者把「上升沿」
 * 改成「电平」，症状不会是崩溃，而是监护人的手机被连续打爆 —— 所以必须有测试盯着。
 */
public class CollisionAlertGateTest {

    @Test
    public void singleFrameDangerDoesNotFire() {
        CollisionAlertGate gate = new CollisionAlertGate();
        assertFalse(gate.onFrame(true, 1_000L));
    }

    /** 危险必须持续一段时间才算数：单帧误检不该变成一次报警。 */
    @Test
    public void sustainedDangerFires() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long start = 1_000L;
        assertFalse(gate.onFrame(true, start));
        assertFalse(gate.onFrame(true, start + 400L));
        assertTrue(gate.onFrame(true, start + CollisionAlertGate.SUSTAIN_MS));
    }

    /** 只报一次：同一段持续危险里，冷却期内不再触发。 */
    @Test
    public void firesOncePerDangerEpisode() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.onFrame(true, t);
        assertTrue(gate.onFrame(true, t + CollisionAlertGate.SUSTAIN_MS));

        // 危险继续存在，但已经报过了。
        assertFalse(gate.onFrame(true, t + CollisionAlertGate.SUSTAIN_MS + 100L));
        assertFalse(gate.onFrame(true, t + 60_000L));
    }

    /** 危险消失再出现，冷却期内仍然不重复报。 */
    @Test
    public void repeatedDangerWithinCooldownStaysSilent() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.onFrame(true, t);
        assertTrue(gate.onFrame(true, t + CollisionAlertGate.SUSTAIN_MS));

        assertFalse(gate.onFrame(false, t + 2_000L));
        // 重新持续满足，但仍在冷却期内。
        assertFalse(gate.onFrame(true, t + 3_000L));
        assertFalse(gate.onFrame(true, t + 3_000L + CollisionAlertGate.SUSTAIN_MS));
    }

    /** 冷却结束后可以再次触发（同一个人先后遇到两次真危险）。 */
    @Test
    public void firesAgainAfterCooldown() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.onFrame(true, t);
        assertTrue(gate.onFrame(true, t + CollisionAlertGate.SUSTAIN_MS));

        long afterCooldown = t + CollisionAlertGate.COOLDOWN_MS + 1_000L;
        gate.onFrame(true, afterCooldown);
        assertTrue(gate.onFrame(true, afterCooldown + CollisionAlertGate.SUSTAIN_MS));
    }

    /** 危险中断后重新计时：不能把中断前的累计时长接着用。 */
    @Test
    public void dangerTimerRestartsAfterGap() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.onFrame(true, t);
        // 中断。
        gate.onFrame(false, t + 700L);
        // 重新开始：这时候才 700ms，即使加上之前的不该满足。
        assertFalse(gate.onFrame(true, t + 800L));
        assertFalse(gate.onFrame(true, t + 800L + CollisionAlertGate.SUSTAIN_MS - 100L));
        assertTrue(gate.onFrame(true, t + 800L + CollisionAlertGate.SUSTAIN_MS));
    }

    /** 手动报警后，自动触发也进入冷却：不重复打扰监护人。 */
    @Test
    public void manualAlertSuppressesAutoTrigger() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.suppressUntil(t);

        assertTrue(gate.isCoolingDown(t + 1_000L));
        gate.onFrame(true, t + 1_000L);
        assertFalse(gate.onFrame(true, t + 1_000L + CollisionAlertGate.SUSTAIN_MS));
    }

    @Test
    public void resetClearsEverything() {
        CollisionAlertGate gate = new CollisionAlertGate();
        long t = 1_000L;
        gate.onFrame(true, t);
        gate.onFrame(true, t + CollisionAlertGate.SUSTAIN_MS);
        assertTrue(gate.isCoolingDown(t + 2_000L));

        gate.reset();
        assertFalse(gate.isCoolingDown(t + 2_000L));
        // 重置后重新累计，很快就能再次触发。
        gate.onFrame(true, t + 10_000L);
        assertTrue(gate.onFrame(true, t + 10_000L + CollisionAlertGate.SUSTAIN_MS));
    }

    /** 冷却必须短于幂等窗口，否则冷却结束后的新触发会变成一条**新的**报警。 */
    @Test
    public void cooldownShorterThanIdempotencyWindow() {
        assertTrue("冷却是 3 分钟而幂等窗口是 5 分钟，这个关系不能反过来",
                CollisionAlertGate.COOLDOWN_MS < AlertKeys.SAME_ALERT_WINDOW_MS);
    }

    @Test
    public void idempotencyKeyIsStableWithinWindow() {
        String uid = "01ABC";
        long base = 10_000_000L;
        String first = AlertKeys.of(uid, "MANUAL", base);
        // 同一窗口内的另一次调用必须得到同一个键，否则重试会变成两条报警。
        assertEquals(first, AlertKeys.of(uid, "MANUAL", base + 1_000L));
        // 跨窗口则是新事件。
        assertNotEquals(first, AlertKeys.of(uid, "MANUAL",
                base + AlertKeys.SAME_ALERT_WINDOW_MS));
        // 不同类型即使同时发生也是两件事。
        assertNotEquals(first, AlertKeys.of(uid, "COLLISION", base));
    }
}
