package com.sullivan.android.ui.roleentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 冷启动落地页判定的回归测试。
 *
 * <p>这一组断言锁住的是**一条曾经坏过的规则**：应用冷启动先经过角色入口页，
 * 已登录的账号不该再被要求选一次角色。判定本身在 {@link HomeRoute}，
 * 角色入口页与登录页都调它 —— 两份实现各自演化，正是当初「登录一次是监护人主页、
 * 冷启动又回角色入口」的成因。
 *
 * <p>测试用 JVM 跑（Android 类不可用），所以只能测纯函数；Activity 里的调用顺序
 * 靠 {@code onCreate} 里「先判断再 setContentView」的注释与人工验证清单约束。
 */
public class HomeRouteTest {

    @Test
    public void guardianOnlyGoesToGuardianHome() {
        assertFalse(HomeRoute.isVolunteerOnly(roles("GUARDIAN")));
        // roles 为空（服务端没返回 / 本地尚未写入）也落到监护人主页 —— 见
        // emptyOrUnknownRolesFallBackToGuardianHome。
        assertFalse(HomeRoute.isVolunteerOnly(Collections.emptyList()));
    }

    @Test
    public void volunteerOnlyGoesToVolunteerHome() {
        assertTrue(HomeRoute.isVolunteerOnly(roles("VOLUNTEER")));
    }

    @Test
    public void dualRoleStaysOnGuardianHome() {
        // 兼任时监护人主页是超集：志愿者主页能做的它都能做，反过来不成立。
        assertFalse(HomeRoute.isVolunteerOnly(roles("GUARDIAN", "VOLUNTEER")));
        // 顺序无关（服务端返回的是 Set，迭代顺序不保证）。
        assertFalse(HomeRoute.isVolunteerOnly(roles("VOLUNTEER", "GUARDIAN")));
    }

    @Test
    public void emptyOrUnknownRolesFallBackToGuardianHome() {
        assertFalse(HomeRoute.isVolunteerOnly(Collections.emptySet()));
        assertFalse(HomeRoute.isVolunteerOnly(roles("PROTECTEE")));
        // 大小写不同不算同一角色：服务端给的是大写代码，这里不做宽松匹配。
        assertFalse(HomeRoute.isVolunteerOnly(roles("volunteer")));
    }

    @Test
    public void describeNamesEveryRoleCombination() {
        assertEquals("监护人", HomeRoute.describe(Collections.emptySet()));
        assertEquals("监护人", HomeRoute.describe(roles("GUARDIAN")));
        assertEquals("志愿者", HomeRoute.describe(roles("VOLUNTEER")));
        assertEquals("监护人 · 志愿者", HomeRoute.describe(roles("GUARDIAN", "VOLUNTEER")));
    }

    private static Set<String> roles(String... codes) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, codes);
        return Collections.unmodifiableSet(set);
    }

    /** 真实的 roles 来自 Gson 反序列化的 Set，不保证具体类型，所以按 Iterable 收。 */
    @Test
    public void acceptsAnyIterableImplementation() {
        assertTrue(HomeRoute.isVolunteerOnly(new LinkedHashSet<>(roles("VOLUNTEER"))));
        assertFalse(HomeRoute.isVolunteerOnly(List.of("GUARDIAN", "VOLUNTEER")));
    }
}
