package com.sullivan.android.ui.guardian.binding;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.data.api.dto.BindingDtos;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一条绑定关系上「我现在能做什么」。**纯逻辑，无 Android 依赖，可单测**
 * （见 {@code BindingActionPolicyTest}）。
 *
 * <p>抽出来的理由与 {@code vision/} 包一致：这种规则一旦散进 Activity，
 * 就只能靠点界面来验证，而它的分支恰好是「身份 × 状态 × 是否永久」的乘积 ——
 * 手点一遍要十几分钟，写下来是二十行 if。
 *
 * <p><b>三项输入，都由服务端数据推导，客户端不额外记录状态</b>：
 * <ul>
 *   <li>{@code status}：PENDING 还是 ACTIVE，还是已经终态；</li>
 *   <li>{@code requestedBy} 与 {@code myUid}：判断我是发起方还是接收方。
 *       服务端<em>不</em>返回「我在这条关系里是监护人还是受护人」，
 *       因为它对双方返回同一条记录，只有拿本机 principalId 去比才能知道；</li>
 *   <li>{@code permanent}：永久绑定不可解除。</li>
 * </ul>
 *
 * <p><b>「不可解除」为什么在客户端就不显示按钮</b>：服务端对永久绑定的 terminate
 * 回 403，功能上够安全。但让用户点一个必然失败的按钮，他会以为是自己操作错了 ——
 * 而真正的原因是「这是代建受护人时建立的主绑定，本来就不能解除」，
 * 这句话只有界面能解释，所以按钮在界面上就不该存在。
 */
public final class BindingActionPolicy {

    /** 一条关系上可能的动作。 */
    public enum Action {
        /** 接收方确认。 */
        ACCEPT,
        /** 接收方拒绝。 */
        REJECT,
        /** 发起方撤回自己尚未被确认的请求。 */
        CANCEL,
        /** 任一方解除已生效的关系。 */
        TERMINATE
    }

    private BindingActionPolicy() {
    }

    /**
     * 计算可用动作。返回的是不可变集合，可能是空的
     * （已终态、或永久绑定）—— **空集合是正常结果，不是错误**，
     * 界面据此把按钮区整块隐藏即可。
     */
    @NonNull
    public static Set<Action> actionsFor(@Nullable BindingDtos.BindingResponse binding,
                                         @Nullable String myUid) {
        if (binding == null || binding.status == null || myUid == null) {
            return Collections.emptySet();
        }
        switch (binding.status.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PENDING":
                return isInitiator(binding, myUid)
                        ? EnumSet.of(Action.CANCEL)
                        : EnumSet.of(Action.ACCEPT, Action.REJECT);
            case "ACTIVE":
                // 永久绑定（监护人代建受护人时建立的主绑定）不可解除：服务端回 403。
                return binding.permanent
                        ? Collections.<Action>emptySet()
                        : EnumSet.of(Action.TERMINATE);
            default:
                // REJECTED / CANCELLED / EXPIRED / TERMINATED，以及任何将来新增的终态。
                return Collections.emptySet();
        }
    }

    /**
     * 这是一条「等我确认」的请求吗？
     *
     * <p>列表页用它在待确认的行上加一句提示。与 {@link #actionsFor} 的区别是
     * 它只看 PENDING，不看具体能点哪些按钮。
     */
    public static boolean isIncomingRequest(@Nullable BindingDtos.BindingResponse binding,
                                            @Nullable String myUid) {
        return binding != null
                && "PENDING".equals(trimUpper(binding.status))
                && !isInitiator(binding, myUid);
    }

    /**
     * 我是不是这条关系的发起方。
     *
     * <p>{@code requestedBy} 缺失（老数据、字段没填）时按<em>不是</em>发起方处理：
     * 那样界面给出的是「确认/拒绝」，比给出「撤回」更安全 ——
     * 万一判错，用户确认不了（服务端会拒绝），但他不会误撤回一条别人发来的请求。
     */
    public static boolean isInitiator(@Nullable BindingDtos.BindingResponse binding,
                                      @Nullable String myUid) {
        return binding != null && myUid != null && myUid.equals(binding.requestedBy);
    }

    /**
     * 我在一条关系里的对方 ID。用来显示「与谁的关系」。
     *
     * <p>不判断我自己是监护人还是受护人，只是「不是我那个 id」——
     * 因为同一条记录对双方是一样的，服务端也没告诉我我是哪一方。
     */
    @Nullable
    public static String counterpartId(@Nullable BindingDtos.BindingResponse binding,
                                       @Nullable String myUid) {
        if (binding == null || myUid == null) {
            return null;
        }
        if (myUid.equals(binding.guardianId)) {
            return binding.protecteeId;
        }
        if (myUid.equals(binding.protecteeId)) {
            return binding.guardianId;
        }
        return null;
    }

    @NonNull
    private static String trimUpper(@Nullable String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
