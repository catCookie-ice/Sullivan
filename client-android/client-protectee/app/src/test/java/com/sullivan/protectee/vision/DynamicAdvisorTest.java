package com.sullivan.protectee.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.protectee.vision.assist.DynamicAdvisor;
import com.sullivan.protectee.vision.assist.ObstacleAdvisor;
import com.sullivan.protectee.vision.scene.SceneSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动态告警的三条通道。
 *
 * <p>这些用例模拟的是决策层的输入：{@code SceneSnapshot} 里已经是**测好的量**（TTC、横向
 * 速率），所以这里不构造图像，直接喂数值。测速本身的正确性由 {@code TtcEstimatorTest} 与
 * {@code ObjectTrackerTest} 负责。
 */
public class DynamicAdvisorTest {

    /** 自运动速度代理：非 NaN 才允许把"靠近"升到 DANGER。 */
    private static final float EGO = 1.4f;

    private static SceneSnapshot.TrackSnapshot person(int id, float centerX, float bottom,
                                                      float ttc, float centerXRate, int hits) {
        float height = 0.20f;
        float rate = Float.isNaN(ttc) ? Float.NaN : 1f / (ttc + 0.2f);
        return new SceneSnapshot.TrackSnapshot(id, "person", 0.9f,
                centerX - 0.10f, bottom - height, centerX + 0.10f, bottom,
                rate, rate, ttc, height, centerXRate, 8, hits, false);
    }

    /** 静止不动的目标：TTC 与横向速率都算不出来。 */
    private static SceneSnapshot.TrackSnapshot stillPerson(int id, float centerX, float bottom,
                                                           int hits) {
        return person(id, centerX, bottom, Float.NaN, Float.NaN, hits);
    }

    private static SceneSnapshot scene(float ego, SceneSnapshot.TrackSnapshot... tracks) {
        List<SceneSnapshot.TrackSnapshot> list = new ArrayList<>();
        Collections.addAll(list, tracks);
        return new SceneSnapshot(0L, 720, 1280, 90, ego, 0.6f, list);
    }

    /** 空场景不该产生任何结论，也不该被当成"异常"。 */
    @Test
    public void emptySceneProducesNoAlert() {
        DynamicAdvisor advisor = new DynamicAdvisor();
        assertNull(advisor.evaluate(scene(EGO), 1000L));
        assertNull(advisor.evaluate(scene(Float.NaN), 2000L));
    }

    /**
     * 靠近档位的滞回阶梯：升档要越过进门限，降档要越过更松的出门限。
     *
     * <p>没有这层滞回的话，TTC 在 1.8 秒附近抖动就会让「行人接近」反复出现又消失——
     * 用户听到的是一串断断续续的"接近…接近…"，比不报更糟。
     */
    @Test
    public void approachLevelsClimbAndFallWithHysteresis() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        // 2.5 秒：进"注意"档（≤ 3.0），还没到"接近"（> 1.8）。
        DynamicAdvisor.Alert caution = advisor.evaluate(
                scene(EGO, person(1, 0.5f, 0.70f, 2.5f, 0f, 9)), 1000L);
        assertNotNull(caution);
        assertEquals(ObstacleAdvisor.Level.CAUTION, caution.level);
        assertEquals("行人正在靠近", caution.message);

        // 1.5 秒 ≤ 进门限 1.8：升到 DANGER，文案压到四字。
        DynamicAdvisor.Alert danger = advisor.evaluate(
                scene(EGO, person(1, 0.5f, 0.70f, 1.5f, 0f, 9)), 1200L);
        assertNotNull(danger);
        assertEquals(ObstacleAdvisor.Level.DANGER, danger.level);
        assertEquals("行人接近", danger.message);

        // 回到 2.5 秒：2.5 > 出门限 1.8，所以**保持** DANGER——这就是滞回。
        DynamicAdvisor.Alert held = advisor.evaluate(
                scene(EGO, person(1, 0.5f, 0.70f, 2.5f, 0f, 9)), 1400L);
        assertNotNull(held);
        assertEquals("2.5 秒还没越过 3.0 秒的出门限，不该降档", ObstacleAdvisor.Level.DANGER,
                held.level);

        // 3.5 秒 > 3.0：降回 CAUTION。
        DynamicAdvisor.Alert back = advisor.evaluate(
                scene(EGO, person(1, 0.5f, 0.70f, 3.5f, 0f, 9)), 1600L);
        assertNotNull(back);
        assertEquals(ObstacleAdvisor.Level.CAUTION, back.level);

        // 5 秒 > 4.2：彻底退出，安静下来。
        assertNull(advisor.evaluate(scene(EGO, person(1, 0.5f, 0.70f, 5f, 0f, 9)), 1800L));
    }

    /**
     * 没有自运动估计时，"接近"最多只能到 CAUTION。
     *
     * <p>单目下"他走向你"和"你走向他"的尺度变化在几何上完全一样。拿不到自运动估计，就
     * 无法证明这个目标比用户自己走路来得更快——此时喊"DANGER"是在赌。
     */
    @Test
    public void withoutEgoEstimateDangerDegradesToCaution() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        DynamicAdvisor.Alert alert = advisor.evaluate(
                scene(Float.NaN, person(1, 0.5f, 0.70f, 0.5f, 0f, 9)), 1000L);

        assertNotNull(alert);
        assertEquals(ObstacleAdvisor.Level.CAUTION, alert.level);
        assertEquals("行人正在靠近", alert.message);
    }

    /**
     * 横穿必须单独走一条通道：横向移动的目标高度恒定，尺度通道看不见它。
     *
     * <p>这里给的 ttc 是 NaN、rate 也是 NaN（正是一个横穿行人在测速器眼里的样子），
     * 只有 centerXRate 有值。告警能出来，就证明横向通道真的接上了。
     */
    @Test
    public void lateralMotionAloneTriggersCrossingAlert() {
        DynamicAdvisor advisor = new DynamicAdvisor();
        // 现在在 0.30，一秒后到 0.50——正好走进走廊中央。
        SceneSnapshot.TrackSnapshot crosser = person(1, 0.30f, 0.70f, Float.NaN, 0.20f, 9);

        DynamicAdvisor.Alert alert = advisor.evaluate(scene(EGO, crosser), 1000L);

        assertNotNull(alert);
        assertEquals(DynamicAdvisor.Motion.CROSSING, alert.motion);
        assertEquals(ObstacleAdvisor.Level.CAUTION, alert.level);
        assertEquals("行人横穿", alert.message);
    }

    /** 横穿同样要滞回：已经在报横穿时，用更松的出门限，否则会在门限附近闪断。 */
    @Test
    public void crossingAlertHoldsUntilRateFallsWellBelowTheGate() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        // 0.12 ≥ 进门限 0.10：进入。
        assertNotNull(advisor.evaluate(
                scene(EGO, person(1, 0.30f, 0.70f, Float.NaN, 0.12f, 9)), 1000L));
        // 0.08 < 0.10 但 ≥ 出门限 0.06：保持。
        assertNotNull("0.08 还没跌破 0.06 的出门限，不该中断",
                advisor.evaluate(scene(EGO, person(1, 0.30f, 0.70f, Float.NaN, 0.08f, 9)), 1200L));
        // 0.04 < 0.06：退出。
        assertNull(advisor.evaluate(
                scene(EGO, person(1, 0.30f, 0.70f, Float.NaN, 0.04f, 9)), 1400L));
    }

    /** 远处经过的人不该被当成横穿：预测位置要落在走廊里才算。 */
    @Test
    public void crossingOutsideTheCorridorIsIgnored() {
        DynamicAdvisor advisor = new DynamicAdvisor();
        // 中心 0.15，一秒后 0.25——虽然进了走廊左界，但 bottom 0.40 在走廊上界之上，
        // 那是画面远处的地面，不是用户要走的正前方。
        DynamicAdvisor.Alert alert = advisor.evaluate(
                scene(EGO, person(1, 0.15f, 0.40f, Float.NaN, 0.10f, 9)), 1000L);
        assertNull(alert);
    }

    /**
     * 兜底通道：算不出 TTC 时，用"1.5 秒前它在远处、现在到了近处"这个观测事实报一次。
     *
     * <p>它不需要最小二乘，也不需要自运动补偿，比任何单帧导数都稳——而这恰恰是旧版本
     * 唯一能给的东西。
     */
    @Test
    public void farToNearJumpTriggersFallbackWhenTtcIsUnavailable() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        // 第一帧：远处的行人（bottom 0.50 < 近处门限 0.78）。
        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.5f, 0.50f, 1)), 0L));

        // 1.6 秒后：同一个轨迹号已经贴到近处。
        DynamicAdvisor.Alert alert = advisor.evaluate(
                scene(Float.NaN, stillPerson(1, 0.5f, 0.85f, 5)), 1600L);

        assertNotNull(alert);
        assertEquals(DynamicAdvisor.Motion.APPROACHING, alert.motion);
        assertEquals(ObstacleAdvisor.Level.CAUTION, alert.level);
        assertEquals("行人正在靠近", alert.message);
    }

    /**
     * 一出现就在近处的目标，不该在 1.5 秒后凭空报一次"正在靠近"。
     *
     * <p>（这是真的会发生的 bug：如果 `wasNear` 初始为 false，站着不动的近处行人会在
     * 建轨 1.5 秒后触发一次假告警。）
     */
    @Test
    public void trackBornNearDoesNotFakeAJump() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.5f, 0.85f, 1)), 0L));
        assertNull("一直在近处，没有'由远及近'可言",
                advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.5f, 0.85f, 5)), 1600L));
        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.5f, 0.85f, 9)), 3000L));
    }

    /** 太年轻、太靠边的目标不满足兜底条件。 */
    @Test
    public void fallbackRespectsAgeHitsAndPosition() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        // 存活够久、也够近，但在画面右侧——不在行进方向上。
        advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.9f, 0.50f, 1)), 0L);
        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(1, 0.9f, 0.85f, 5)), 1600L));

        // 在正前方且够近，但只匹配了 2 帧（< FALLBACK_MIN_HITS）。
        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(2, 0.5f, 0.50f, 1)), 0L));
        assertNull(advisor.evaluate(scene(Float.NaN, stillPerson(2, 0.5f, 0.85f, 2)), 1600L));
    }

    /**
     * TTC 能算出来时就不走兜底通道。
     *
     * <p>分层降级的另一半：只有在升级通道**不可用**时才降级。两个通道同时生效会让同一次
     * 接近被报两遍，去重键还不一样。
     */
    @Test
    public void fallbackIsSkippedWhenTtcIsAvailable() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        advisor.evaluate(scene(EGO, person(1, 0.5f, 0.50f, 12f, 0f, 1)), 0L);
        assertNull("TTC = 12 秒，远得很，也没什么可升级的",
                advisor.evaluate(scene(EGO, person(1, 0.5f, 0.85f, 12f, 0f, 5)), 1600L));
    }

    /**
     * "兜底"是**降级**通道，不是并行通道：TTC 一旦能算出来（哪怕很慢），就只认它。
     *
     * <p>两个通道同时生效会让同一次接近被报两遍，而且两遍的去重键还不一样，节流也拦不住。
     * 这里的 ttc = 8 秒，属于"能算出来但一点都不急"，因此即使是贴到近处的跳变也不报——
     * 静态通道会照常说"较近，注意"，动态通道插嘴只会稀释这句话的分量。
     */
    @Test
    public void slowButMeasurableTtcSuppressesTheFallback() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        assertNull(advisor.evaluate(scene(EGO, person(1, 0.5f, 0.50f, 8f, 0f, 1)), 0L));
        assertNull(advisor.evaluate(scene(EGO, person(1, 0.5f, 0.60f, 8f, 0f, 5)), 800L));
        assertNull("TTC 能算出来时就只走尺度通道，8 秒 > 4.2 秒没有档位",
                advisor.evaluate(scene(EGO, person(1, 0.5f, 0.85f, 8f, 0f, 9)), 1800L));
    }

    /** 去重键要能区分档位：否则「正在靠近」升到「接近」会被当成同句而静默吞掉。 */
    @Test
    public void escalationChangesTheDedupKey() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        DynamicAdvisor.Alert caution = advisor.evaluate(
                scene(EGO, person(7, 0.5f, 0.70f, 2.5f, 0f, 9)), 1000L);
        DynamicAdvisor.Alert danger = advisor.evaluate(
                scene(EGO, person(7, 0.5f, 0.70f, 1.2f, 0f, 9)), 1200L);

        assertNotNull(caution);
        assertNotNull(danger);
        assertNotEquals(caution.dedupKey, danger.dedupKey);
        assertTrue("去重键要带轨迹号：" + caution.dedupKey, caution.dedupKey.contains("7"));
    }

    /** 多条轨迹时挑最急的那条。 */
    @Test
    public void mostUrgentTrackWins() {
        // 档位不同：DANGER 压过 CAUTION。
        DynamicAdvisor.Alert alert = new DynamicAdvisor().evaluate(scene(EGO,
                person(1, 0.5f, 0.70f, 2.6f, 0f, 9),
                person(2, 0.4f, 0.70f, 1.0f, 0f, 9)), 1000L);
        assertNotNull(alert);
        assertEquals(2, alert.trackId);
        assertEquals(ObstacleAdvisor.Level.DANGER, alert.level);
    }

    /**
     * 档位相同时比 TTC。
     *
     * <p>每条用例都用**全新的** advisor：滞回状态会跨帧累积，复用一个实例的话，第二条
     * 断言测到的就是上一条留下的档位，而不是"同档位比 TTC"这个规则。
     */
    @Test
    public void sameLevelFallsBackToSmallerTtc() {
        DynamicAdvisor.Alert alert = new DynamicAdvisor().evaluate(scene(EGO,
                person(1, 0.5f, 0.70f, 2.8f, 0f, 9),
                person(2, 0.4f, 0.70f, 2.1f, 0f, 9)), 1000L);

        assertNotNull(alert);
        assertEquals(2, alert.trackId);
        assertEquals(ObstacleAdvisor.Level.CAUTION, alert.level);
    }

    /** 尺度通道的档位优先于横穿：正在快速靠近的人比横穿的人更急。 */
    @Test
    public void approachOutranksCrossingOfAnotherTrack() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        DynamicAdvisor.Alert alert = advisor.evaluate(scene(EGO,
                person(1, 0.30f, 0.70f, Float.NaN, 0.20f, 9),   // 横穿
                person(2, 0.50f, 0.70f, 2.5f, 0f, 9)), 1000L);   // 靠近

        assertNotNull(alert);
        assertEquals(2, alert.trackId);
        assertEquals(DynamicAdvisor.Motion.APPROACHING, alert.motion);
    }

    /** 轨迹消失后滞回状态必须被丢掉：否则旧档位会一直挂在内存里，轨迹号一复用就漏。 */
    @Test
    public void vanishedTracksAreForgotten() {
        DynamicAdvisor advisor = new DynamicAdvisor();

        advisor.evaluate(scene(EGO, person(1, 0.5f, 0.70f, 2.5f, 0f, 9),
                person(2, 0.4f, 0.70f, 2.5f, 0f, 9)), 1000L);
        assertEquals(2, advisor.trackedStateCount());

        advisor.evaluate(scene(EGO, person(1, 0.5f, 0.70f, 2.5f, 0f, 9)), 1200L);
        assertEquals("轨迹 2 已经消失，状态要收掉", 1, advisor.trackedStateCount());

        advisor.evaluate(scene(EGO), 1400L);
        assertEquals(0, advisor.trackedStateCount());

        advisor.reset();
        assertEquals(0, advisor.trackedStateCount());
    }
}
