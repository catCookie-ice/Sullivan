package com.sullivan.android.ui.roleentry;

import android.app.Activity;

/**
 * 角色选择入口 (docs/final/00 §4.2)
 *
 * Android 登录前必须提供三角色入口：受护人 / 监护人 / 志愿者
 * 登录后以令牌中的服务端角色为准，客户端选择不得提升权限。
 *
 * 每个角色入口分别注册对应账号，不要求同一账号多角色。
 */
public class RoleEntryActivity extends Activity {
    // TODO(M2): Compose UI — 三个角色卡片 + 已登录用户直接跳转
}
