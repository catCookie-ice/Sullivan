package com.sullivan.protectee.service.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/**
 * 报警幂等键的回归测试。
 *
 * <p>这一组锁的是一个真实故障：**受护人有时候发不出紧急求助。**
 *
 * <p>成因不是按钮坏了，而是幂等键与"报警已结束"这件事冲突：{@link AlertKeys}
 * 原先只用时间桶（5 分钟），于是窗口内先误触一次、点「我没事」把报警结束掉之后，
 * 用户**真的**需要求助时算出的仍是同一个键，服务端按
 * {@code (protecteeUid, idempotencyKey)} 唯一把它当成重复提交，
 * 直接返回那条**已 RESOLVED 的旧报警**。界面上看就是"按了没反应"。
 */
public class AlertKeysTest {

    private static final String UID = "u_protectee";
    private static final String MANUAL = "MANUAL";

    /** 同一窗口内、且手上已有活动报警 → 复用同一个键（真正的重复提交）。 */
    @Test
    public void sameWindowWithActiveAlertReusesKey() {
        long now = 1_700_000_000_000L;
        assertEquals(AlertKeys.of(UID, MANUAL, now, true),
                AlertKeys.of(UID, MANUAL, now + 1_000L, true));
    }

    /**
     * 同一窗口内、但**已经没有**活动报警 → 必须换一个键。
     *
     * <p>这就是那个 bug 的修法：用户点过「我没事」之后，新的求助必须是新的一条。
     */
    @Test
    public void sameWindowWithoutActiveAlertGetsFreshKey() {
        long now = 1_700_000_000_000L;
        assertNotEquals(AlertKeys.of(UID, MANUAL, now, false),
                AlertKeys.of(UID, MANUAL, now + 1_000L, false));
    }

    /**
     * 没有活动报警时的键，与"有活动报警"时的键必须不同。
     *
     * <p>否则复用键的那条路径会把新报警当成旧报警的重复。
     */
    @Test
    public void freshKeyDiffersFromReusedKey() {
        long now = 1_700_000_000_000L;
        assertNotEquals(AlertKeys.of(UID, MANUAL, now, true),
                AlertKeys.of(UID, MANUAL, now, false));
    }

    /**
     * 跨窗口即使有活动报警也是新键 —— 一个人在同一个地方先后摔两次，
     * 第二次必须能发出去。
     */
    @Test
    public void differentWindowAlwaysGetsDifferentKey() {
        long now = 1_700_000_000_000L;
        assertNotEquals(AlertKeys.of(UID, MANUAL, now, true),
                AlertKeys.of(UID, MANUAL, now + AlertKeys.SAME_ALERT_WINDOW_MS, true));
    }

    /** 不同类型算两件事，即使同一时刻同一人。 */
    @Test
    public void differentTypesAreDifferentKeys() {
        long now = 1_700_000_000_000L;
        assertNotEquals(AlertKeys.of(UID, "MANUAL", now, true),
                AlertKeys.of(UID, "COLLISION", now, true));
    }

    /** 不同账号算两件事。 */
    @Test
    public void differentProtecteesAreDifferentKeys() {
        long now = 1_700_000_000_000L;
        assertNotEquals(AlertKeys.of("u_a", MANUAL, now, true),
                AlertKeys.of("u_b", MANUAL, now, true));
    }

    /** 键只含安全字符（会进 VARCHAR(100) 与日志）。 */
    @Test
    public void keyContainsOnlySafeCharacters() {
        String key = AlertKeys.of("u/with space\tand*chars", MANUAL,
                1_700_000_000_000L, false);
        assertEquals(key, key.replaceAll("[^A-Za-z0-9_-]", ""));
    }
}
