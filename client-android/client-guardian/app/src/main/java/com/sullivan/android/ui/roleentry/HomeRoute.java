package com.sullivan.android.ui.roleentry;

import androidx.annotation.NonNull;

/**
 * 「登录之后该去哪个主页」的判定。
 *
 * <p><b>为什么抽成独立的纯函数</b>：这条判定曾经有两个实现 —— 登录成功时走
 * {@code LoginActivity.homeIntentFor}，而冷启动时走 {@code RoleEntryActivity} 里
 * 一份自己的分支。两份的输入不一样：前者看服务端返回的 roles，后者只能看用户
 * 在入口页点的那张卡片，于是同一个账号从不同路径进来会落到不同页面。
 * 现在只留这一份，并由 {@link HomeRouteTest} 钉住每一种角色组合。
 *
 * <p><b>判定只看服务端 roles，不看入口提示</b>：{@code GUARDIAN}/{@code VOLUNTEER}
 * 是客户端在角色入口上点时给的「提示」，服务端 roles 才是身份。
 *
 * <p><b>为什么不按「有过哪个角色」来记</b>：roles 是集合，同一个账号可以同时是
 * 监护人和志愿者（兼任）。要记住「上次从哪张卡片进的」就得额外存一个状态，
 * 而这个状态与 roles 可能互相矛盾（例如志愿者身份被管理员撤销后，
 * 记着的那条提示仍指向志愿者主页）。按 roles 分流没有这个不一致面。
 */
public final class HomeRoute {

    /** 服务端 roles 里的角色代码。 */
    public static final String ROLE_GUARDIAN = "GUARDIAN";
    public static final String ROLE_VOLUNTEER = "VOLUNTEER";

    private HomeRoute() {
    }

    /**
     * 是否应落到志愿者主页。
     *
     * <p>只有「是志愿者且不是监护人」才为 true。兼任两者的账号仍然去监护人主页 ——
     * 志愿者主页能做的它都能做（志愿者主页上有「切到监护人视角」的入口），
     * 反过来则不成立：监护人主页独有的扫码绑定、创建受护人、绑定管理，志愿者主页都没有。
     *
     * <p>roles 为空（服务端没返回，或本地尚未写入）时也返回 true 以外的一侧，
     * 即默认监护人主页：那是最早实现、也是能力最全的一页。
     *
     * @param roles 服务端登录响应里的 {@code roles}，可为 {@code null}
     */
    public static boolean isVolunteerOnly(@NonNull Iterable<String> roles) {
        boolean guardian = false;
        boolean volunteer = false;
        for (String role : roles) {
            if (ROLE_VOLUNTEER.equals(role)) {
                volunteer = true;
            } else if (ROLE_GUARDIAN.equals(role)) {
                guardian = true;
            }
        }
        return volunteer && !guardian;
    }

    /**
     * 「当前账号」在入口页上的一句话描述，例如「监护人」「志愿者」「监护人 · 志愿者」。
     *
     * <p>只在角色入口页用得上：那里要在**不登录**的前提下告诉用户
     * 「上次登录的是谁」，而本地能拿到的身份事实只有 roles 与登录名。
     */
    @NonNull
    public static String describe(@NonNull Iterable<String> roles) {
        boolean guardian = false;
        boolean volunteer = false;
        for (String role : roles) {
            if (ROLE_VOLUNTEER.equals(role)) {
                volunteer = true;
            } else if (ROLE_GUARDIAN.equals(role)) {
                guardian = true;
            }
        }
        if (guardian && volunteer) {
            return "监护人 · 志愿者";
        }
        if (volunteer) {
            return "志愿者";
        }
        return "监护人";
    }
}
