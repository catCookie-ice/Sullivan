package com.sullivan.android.ui.roleentry;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.ui.login.LoginActivity;

/**
 * 角色选择入口 (docs/final/00 §4.2)
 *
 * Android 登录前必须提供角色入口：监护人 / 志愿者
 * 登录后以令牌中的服务端角色为准，客户端选择不得提升权限。
 *
 * <p>两者都进 {@link LoginActivity}，只是带上不同的角色提示
 * （{@link LoginActivity#EXTRA_ROLE_HINT}）用来换标题文案 —— 两者共用同一套
 * 账密登录接口，服务端返回的 roles 才是最终身份，客户端这边选的角色只是一个
 * 「我先想看哪一端的入口」的提示，不是权限声明。
 *
 * <p><b>受护人卡片已移除</b>：受护人端（端侧障碍检测 + 扫码登录 + 绑定码）
 * 已拆成独立工程 {@code client-android/client-protectee}，它是装在受护人自己
 * 那台手机上的另一个应用，不再从这里进入。本应用里没有第三个角色可切。
 *
 * <p><b>本页不提供服务器地址设置</b>：后端地址是**构建期常量**
 * （{@code BuildConfig.SERVER_BASE_URL}，见 {@code app/build.gradle.kts}），
 * 不交给用户选择。历史上这里曾有过一个地址入口，已撤销——理由见
 * {@code SessionStore} 的类注释。
 */
public class RoleEntryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_entry);

        findViewById(R.id.card_guardian).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_GUARDIAN)));
        findViewById(R.id.card_volunteer).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_VOLUNTEER)));
    }
}
