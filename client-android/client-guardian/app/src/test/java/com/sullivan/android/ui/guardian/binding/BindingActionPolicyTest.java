package com.sullivan.android.ui.guardian.binding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.data.api.dto.BindingDtos;

import org.junit.Test;

import java.util.Set;

/**
 * 绑定关系可用动作的规则测试。
 *
 * <p>规则是「身份 × 状态 × 是否永久」的乘积，靠点界面走一遍要十几分钟，
 * 而且最容易漏掉的正是「永久绑定不该有解除按钮」这类反直觉的一条。
 */
public class BindingActionPolicyTest {

    private static final String ME = "u_me";
    private static final String OTHER = "u_other";

    private static BindingDtos.BindingResponse binding(String status, String requestedBy,
                                                       boolean permanent) {
        BindingDtos.BindingResponse binding = new BindingDtos.BindingResponse();
        binding.id = "b_1";
        binding.status = status;
        binding.guardianId = "u_guardian";
        binding.protecteeId = "u_protectee";
        binding.requestedBy = requestedBy;
        binding.permanent = permanent;
        return binding;
    }

    @Test
    public void pendingInitiatedByMeOffersOnlyCancel() {
        Set<BindingActionPolicy.Action> actions =
                BindingActionPolicy.actionsFor(binding("PENDING", ME, false), ME);

        assertEquals(Set.of(BindingActionPolicy.Action.CANCEL), actions);
        assertFalse(BindingActionPolicy.isIncomingRequest(binding("PENDING", ME, false), ME));
    }

    @Test
    public void pendingInitiatedByTheOtherSideOffersAcceptAndReject() {
        BindingDtos.BindingResponse incoming = binding("PENDING", OTHER, false);
        Set<BindingActionPolicy.Action> actions = BindingActionPolicy.actionsFor(incoming, ME);

        assertEquals(Set.of(BindingActionPolicy.Action.ACCEPT, BindingActionPolicy.Action.REJECT),
                actions);
        assertTrue(BindingActionPolicy.isIncomingRequest(incoming, ME));
    }

    @Test
    public void activeNonPermanentCanBeTerminated() {
        Set<BindingActionPolicy.Action> actions =
                BindingActionPolicy.actionsFor(binding("ACTIVE", OTHER, false), ME);

        assertEquals(Set.of(BindingActionPolicy.Action.TERMINATE), actions);
    }

    /**
     * 永久绑定（监护人代建受护人时建立的主绑定）不可解除，服务端回 403。
     * 按钮在界面上就不该出现 —— 让用户点一个必然失败的动作，
     * 他会以为是自己操作错了，而真正的原因是规则不允许。
     */
    @Test
    public void activePermanentOffersNothingBecauseServerRejectsTerminateWith403() {
        assertTrue(BindingActionPolicy.actionsFor(binding("ACTIVE", OTHER, true), ME).isEmpty());
    }

    @Test
    public void terminalStatesOfferNothingRegardlessOfSide() {
        for (String status : new String[]{"REJECTED", "CANCELLED", "EXPIRED", "TERMINATED"}) {
            assertTrue("状态 " + status + " 不该有可用动作",
                    BindingActionPolicy.actionsFor(binding(status, ME, false), ME).isEmpty());
            assertTrue("状态 " + status + " 不该有可用动作",
                    BindingActionPolicy.actionsFor(binding(status, OTHER, false), ME).isEmpty());
            assertFalse(BindingActionPolicy.isIncomingRequest(binding(status, OTHER, false), ME));
        }
    }

    /** 服务端加新状态时，界面应当什么都不给，而不是乱猜一个动作出来。 */
    @Test
    public void unknownStatusOffersNothing() {
        assertTrue(BindingActionPolicy.actionsFor(binding("FROZEN", OTHER, false), ME).isEmpty());
        // 大小写与空白照旧归一化。
        assertEquals(Set.of(BindingActionPolicy.Action.ACCEPT, BindingActionPolicy.Action.REJECT),
                BindingActionPolicy.actionsFor(binding(" pending ", OTHER, false), ME));
    }

    @Test
    public void missingInputsOfferNothingInsteadOfThrowing() {
        assertTrue(BindingActionPolicy.actionsFor(null, ME).isEmpty());
        assertTrue(BindingActionPolicy.actionsFor(binding("PENDING", OTHER, false), null).isEmpty());
        assertTrue(BindingActionPolicy.actionsFor(binding(null, OTHER, false), ME).isEmpty());
    }

    /**
     * {@code requestedBy} 缺失时按「不是发起方」处理：界面给出确认/拒绝。
     * 万一判错，用户确认不了（服务端会拒绝），但他不会误撤回一条别人发来的请求。
     */
    @Test
    public void missingRequestedByFallsBackToReceiverSide() {
        Set<BindingActionPolicy.Action> actions =
                BindingActionPolicy.actionsFor(binding("PENDING", null, false), ME);

        assertEquals(Set.of(BindingActionPolicy.Action.ACCEPT, BindingActionPolicy.Action.REJECT),
                actions);
    }

    @Test
    public void counterpartIsWhicheverSideIsNotMe() {
        BindingDtos.BindingResponse binding = binding("ACTIVE", OTHER, false);

        assertEquals("u_protectee", BindingActionPolicy.counterpartId(binding, "u_guardian"));
        assertEquals("u_guardian", BindingActionPolicy.counterpartId(binding, "u_protectee"));
        // 既不是监护人也不是受护人（不该发生，但别崩）——返回 null 让调用方显示占位。
        assertNull(BindingActionPolicy.counterpartId(binding, "u_stranger"));
        assertNull(BindingActionPolicy.counterpartId(null, ME));
    }

    /**
     * 对方的可显示名字：显示名优先，回落到登录名。
     *
     * <p>这条规则的反面就是“把 uid 显示出来”，所以这里额外钉一下
     * “两个都没有时志必须返回 null”—— 调用方宁可显示占位符，
     * 也不能拿 ID 顶上去。
     */
    @Test
    public void counterpartLabelPrefersDisplayNameThenLoginNameAndNeverFallsBackToId() {
        BindingDtos.BindingResponse binding = binding("ACTIVE", OTHER, false);
        binding.guardianLoginName = "guardian01";
        binding.guardianName = "张三";
        binding.protecteeLoginName = "protectee01";
        binding.protecteeName = "李四";

        assertEquals("李四", BindingActionPolicy.counterpartLabel(binding, "u_guardian"));
        assertEquals("张三", BindingActionPolicy.counterpartLabel(binding, "u_protectee"));

        // 显示名缺失时用登录名，而不是用 uid。
        binding.protecteeName = null;
        assertEquals("protectee01", BindingActionPolicy.counterpartLabel(binding, "u_guardian"));
        binding.protecteeLoginName = "   ";
        assertNull(BindingActionPolicy.counterpartLabel(binding, "u_guardian"));

        assertNull(BindingActionPolicy.counterpartLabel(binding, "u_stranger"));
        assertNull(BindingActionPolicy.counterpartLabel(null, ME));
        assertNull(BindingActionPolicy.counterpartLabel(binding, null));
    }
}
