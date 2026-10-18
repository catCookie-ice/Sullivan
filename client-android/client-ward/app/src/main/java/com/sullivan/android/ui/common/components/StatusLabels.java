package com.sullivan.android.ui.common.components;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.R;

/**
 * 后端状态码 → 中文文案 + 视觉色档。**纯逻辑，无 Android 依赖**（只有 R 里的常量），
 * 所以可以直接跑 JUnit 单元测试，见 {@code StatusLabelsTest}。
 *
 * <p><b>为什么值得单独一个类</b>：同一批状态码会出现在四个列表页里
 * （绑定、会话、申请、活动），而且服务端对它们的取值是**松散字符串**
 * （{@code String status}，不是枚举）。如果每个页面各写一份 switch，
 * 迟早会出现「PENDING 在绑定页叫待确认、在会话页叫等待中」这种不一致 ——
 * 用户看到的是同一个后端字段，两种叫法只会让他以为这是两回事。
 *
 * <p><b>取不到的状态一律走 NEUTRAL + 原样显示</b>：服务端加一个新状态值时，
 * 正确行为是「界面照旧能跑，多出一个不认识的中文/英文词」，
 * 而不是崩溃或显示空白。所以这里所有 switch 都有 default 分支。
 */
public final class StatusLabels {

    private StatusLabels() {
    }

    /** 状态色档。决定标签底色与可选的文字颜色。 */
    public enum Tone {
        /** 中性：只是「当前是什么」，不需要用户做任何事。 */
        NEUTRAL,
        /** 成功/生效：绿。 */
        SUCCESS,
        /** 待办：等用户或等对方动作，金。 */
        WARNING,
        /** 终止：拒绝/解除/取消，陶土红。 */
        DANGER
    }

    /** 一条状态的中文文案与色档。 */
    public static final class Label {
        @NonNull
        public final String text;
        @NonNull
        public final Tone tone;

        Label(@NonNull String text, @NonNull Tone tone) {
            this.text = text;
            this.tone = tone;
        }
    }

    // ------------------------------------------------------------------
    // 绑定关系（RegistrationDtos.BindingResponse.status）
    // ------------------------------------------------------------------

    @NonNull
    public static Label binding(@Nullable String status) {
        return switch (normalize(status)) {
            case "PENDING" -> new Label("待确认", Tone.WARNING);
            case "ACTIVE" -> new Label("已生效", Tone.SUCCESS);
            case "REJECTED" -> new Label("已拒绝", Tone.DANGER);
            case "CANCELLED" -> new Label("已撤回", Tone.DANGER);
            case "EXPIRED" -> new Label("已过期", Tone.DANGER);
            case "TERMINATED" -> new Label("已解除", Tone.DANGER);
            default -> unknown(status);
        };
    }

    // ------------------------------------------------------------------
    // 实时会话（RealtimeDtos.SessionResponse.status）
    // ------------------------------------------------------------------

    @NonNull
    public static Label session(@Nullable String status) {
        return switch (normalize(status)) {
            case "REQUESTED" -> new Label("待接受", Tone.WARNING);
            case "ACTIVE" -> new Label("进行中", Tone.SUCCESS);
            case "REJECTED" -> new Label("已拒绝", Tone.DANGER);
            case "ENDED" -> new Label("已结束", Tone.NEUTRAL);
            default -> unknown(status);
        };
    }

    // ------------------------------------------------------------------
    // 志愿者申请（VolunteerDtos.ApplicationResponse.status）
    // ------------------------------------------------------------------

    @NonNull
    public static Label application(@Nullable String status) {
        return switch (normalize(status)) {
            case "PENDING" -> new Label("待审核", Tone.WARNING);
            case "APPROVED" -> new Label("已通过", Tone.SUCCESS);
            case "REJECTED" -> new Label("未通过", Tone.DANGER);
            // 「已撤销」是用户自己按的，用中性色：它不是一次失败，
            // 和「未通过」放在一起会让人以为被驳回了。
            case "CANCELLED" -> new Label("已撤销", Tone.NEUTRAL);
            default -> unknown(status);
        };
    }

    // ------------------------------------------------------------------
    // 公益活动（ActivityDtos.ActivityResponse.status）
    // ------------------------------------------------------------------

    @NonNull
    public static Label activity(@Nullable String status) {
        return switch (normalize(status)) {
            case "RECRUITING" -> new Label("招募中", Tone.SUCCESS);
            case "IN_PROGRESS" -> new Label("进行中", Tone.WARNING);
            case "ENDED" -> new Label("已结束", Tone.NEUTRAL);
            case "CANCELLED" -> new Label("已取消", Tone.DANGER);
            default -> unknown(status);
        };
    }

    // ------------------------------------------------------------------
    // 角色
    // ------------------------------------------------------------------

    /** 角色代码 → 中文。用在「当前账号」「申请人」这类地方。 */
    @NonNull
    public static String role(@Nullable String roleCode) {
        return switch (normalize(roleCode)) {
            case "PROTECTEE" -> "受护人";
            case "GUARDIAN" -> "监护人";
            case "VOLUNTEER" -> "志愿者";
            case "ORGANIZATION" -> "公益机构";
            case "ADMIN" -> "管理员";
            default -> roleCode == null ? "未知" : roleCode;
        };
    }

    /** 账号状态 → 中文。 */
    @NonNull
    public static String accountStatus(@Nullable String status) {
        return switch (normalize(status)) {
            case "ENABLED" -> "正常";
            case "DISABLED" -> "已禁用";
            case "PENDING" -> "待审核";
            default -> status == null ? "未知" : status;
        };
    }

    // ------------------------------------------------------------------
    // 色档 → 资源
    // ------------------------------------------------------------------

    /**
     * 状态标签的底色 drawable。
     *
     * <p>四个 drawable 的形状完全相同，只有颜色不同；之所以不做成
     * 「一个 drawable + 运行时染色」，是因为 {@code setBackgroundTintList}
     * 在 API 26 上对 XML shape 的行为与 {@code setBackgroundResource} 交替使用时
     * 会留下上一次的 tint。四份 XML 比一个随调用顺序变化的 bug 便宜。
     */
    public static int chipBackground(@NonNull Tone tone) {
        return switch (tone) {
            case SUCCESS -> R.drawable.an_chip_success;
            case WARNING -> R.drawable.an_chip_warning;
            case DANGER -> R.drawable.an_chip_danger;
            default -> R.drawable.an_chip_neutral;
        };
    }

    /** 色档对应的文字颜色，供不带背景的纯文字状态使用。 */
    public static int textColor(@NonNull Tone tone) {
        return switch (tone) {
            case SUCCESS -> R.color.an_sage;
            case WARNING -> R.color.an_gold_deep;
            case DANGER -> R.color.an_terracotta;
            default -> R.color.an_ink_2;
        };
    }

    // ------------------------------------------------------------------

    /**
     * 归一化：去空白、转大写。
     *
     * <p>不返回 null —— 所有 {@code switch} 都拿它的结果做匹配，
     * 空值在这里收敛成空串，落到 default 分支。服务端理论上不会给空状态，
     * 但 DTO 是 public 字段，任何一处忘了赋值都会变成 null 传进来。
     */
    @NonNull
    private static String normalize(@Nullable String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 不认识的取值：原样显示（有值的话），色档取中性。 */
    @NonNull
    private static Label unknown(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Label("状态未知", Tone.NEUTRAL);
        }
        return new Label(raw.trim(), Tone.NEUTRAL);
    }
}
