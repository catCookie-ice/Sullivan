package com.sullivan.protectee.ui.common.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * 状态文案的回归测试。
 *
 * <p>这些断言看起来「只是在测常量表」，但它们锁住的是**跨页面一致性**：
 * 服务端某一处把状态名改了（比如 PENDING → WAITING），这四个测试会同时红掉，
 * 而不是等到用户在绑定页看到「待确认」、在会话页看到「WAITING」才发现。
 */
public class StatusLabelsTest {

    @Test
    public void bindingStatusesAreChineseAndTonallyCorrect() {
        assertEquals("待确认", StatusLabels.binding("PENDING").text);
        assertEquals(StatusLabels.Tone.WARNING, StatusLabels.binding("PENDING").tone);

        assertEquals("已生效", StatusLabels.binding("ACTIVE").text);
        assertEquals(StatusLabels.Tone.SUCCESS, StatusLabels.binding("ACTIVE").tone);

        // 四个终态都归到 DANGER：它们对用户的含义都是「这条关系没成/没了」。
        assertEquals(StatusLabels.Tone.DANGER, StatusLabels.binding("REJECTED").tone);
        assertEquals(StatusLabels.Tone.DANGER, StatusLabels.binding("CANCELLED").tone);
        assertEquals(StatusLabels.Tone.DANGER, StatusLabels.binding("EXPIRED").tone);
        assertEquals(StatusLabels.Tone.DANGER, StatusLabels.binding("TERMINATED").tone);
    }

    @Test
    public void sessionStatusesAreChinese() {
        assertEquals("待接受", StatusLabels.session("REQUESTED").text);
        assertEquals("进行中", StatusLabels.session("ACTIVE").text);
        assertEquals(StatusLabels.Tone.SUCCESS, StatusLabels.session("ACTIVE").tone);
        assertEquals("已拒绝", StatusLabels.session("REJECTED").text);
        assertEquals("已结束", StatusLabels.session("ENDED").text);
        // 正常结束不是一次失败，用中性色而不是红色。
        assertEquals(StatusLabels.Tone.NEUTRAL, StatusLabels.session("ENDED").tone);
    }

    @Test
    public void applicationStatusesDistinguishCancelledFromRejected() {
        assertEquals("待审核", StatusLabels.application("PENDING").text);
        assertEquals("已通过", StatusLabels.application("APPROVED").text);
        assertEquals("未通过", StatusLabels.application("REJECTED").text);
        assertEquals(StatusLabels.Tone.DANGER, StatusLabels.application("REJECTED").tone);
        // 「已撤销」是用户自己按的，不能和「未通过」同色，否则看起来像被驳回了。
        assertEquals("已撤销", StatusLabels.application("CANCELLED").text);
        assertEquals(StatusLabels.Tone.NEUTRAL, StatusLabels.application("CANCELLED").tone);
    }

    @Test
    public void activityStatusesAreChinese() {
        assertEquals("招募中", StatusLabels.activity("RECRUITING").text);
        assertEquals("进行中", StatusLabels.activity("IN_PROGRESS").text);
        assertEquals("已结束", StatusLabels.activity("ENDED").text);
        assertEquals("已取消", StatusLabels.activity("CANCELLED").text);
    }

    /**
     * 服务端加了新状态值时，界面必须还能跑。
     *
     * <p>正确行为是「原样显示那个不认识的词 + 中性色」，
     * 而不是崩溃、抛异常或显示空白 —— 空白会让用户以为这条记录坏了。
     */
    @Test
    public void unknownStatusNeverThrowsAndKeepsRawValue() {
        assertEquals("WAITING_ROOM", StatusLabels.binding("WAITING_ROOM").text);
        assertEquals(StatusLabels.Tone.NEUTRAL, StatusLabels.binding("WAITING_ROOM").tone);

        // 大小写与空白都要能归一化。
        assertEquals("已生效", StatusLabels.binding("  active  ").text);
        assertEquals("已生效", StatusLabels.binding("Active").text);

        // 所有取值入口在收到 null 时都不能抛。
        assertNotNull(StatusLabels.binding(null));
        assertNotNull(StatusLabels.session(null));
        assertNotNull(StatusLabels.application(null));
        assertNotNull(StatusLabels.activity(null));
        assertEquals("状态未知", StatusLabels.binding(null).text);
        assertEquals("状态未知", StatusLabels.session("   ").text);
    }

    @Test
    public void roleAndAccountStatusFallBackToRawValue() {
        assertEquals("监护人", StatusLabels.role("GUARDIAN"));
        assertEquals("受护人", StatusLabels.role("PROTECTEE"));
        assertEquals("志愿者", StatusLabels.role("VOLUNTEER"));
        // 不认识的机构类角色原样透出，而不是硬塞一个「未知」把信息抹掉。
        assertEquals("HR_ADMIN", StatusLabels.role("HR_ADMIN"));
        assertEquals("未知", StatusLabels.role(null));

        assertEquals("正常", StatusLabels.accountStatus("ENABLED"));
        assertEquals("已禁用", StatusLabels.accountStatus("DISABLED"));
        assertEquals("未知", StatusLabels.accountStatus(null));
    }

    @Test
    public void toneMapsToDistinctChipBackgrounds() {
        int neutral = StatusLabels.chipBackground(StatusLabels.Tone.NEUTRAL);
        int success = StatusLabels.chipBackground(StatusLabels.Tone.SUCCESS);
        int warning = StatusLabels.chipBackground(StatusLabels.Tone.WARNING);
        int danger = StatusLabels.chipBackground(StatusLabels.Tone.DANGER);

        // 四个色档必须给出四个不同的 drawable，否则「色档」这个概念就没意义了。
        assertEquals(4, java.util.Set.of(neutral, success, warning, danger).size());
    }
}
