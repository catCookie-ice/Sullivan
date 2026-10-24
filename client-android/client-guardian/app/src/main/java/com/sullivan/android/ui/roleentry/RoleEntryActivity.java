package com.sullivan.android.ui.roleentry;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.data.session.SessionStore;
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
 *
 * <p><b>已登录就跳过本页</b>：本页是 LAUNCHER，每次冷启动都会先经过它。
 * 在此之前，已登录的账号也会停在这里被要求再选一次角色，而那张卡片对已登录的账号
 * **什么也不决定**（{@link LoginActivity#ROLE_HINT_GUARDIAN} 只换标题文案，
 * 落地页看的是服务端 roles）。于是用户每次重开应用都要先答一道没有作用的题。
 * 现在改为：本地有令牌就直接进 {@link LoginActivity#homeIntentFor} 算出的主页。
 *
 * <p>不在这里判「令牌是否仍然有效」：那需要一次网络请求，而本页的职责只是
 * 决定先给用户看哪一屏。令牌失效由各页面收到 {@code 401} 时自行处理
 * （清登录态），与本页无关。
 */
public class RoleEntryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 必须在 setContentView 之前判断：已登录时本页根本不该出现在屏幕上，
        // 先建视图再跳转会闪一下「选择你的角色」。
        if (new SessionStore(this).isLoggedIn()) {
            startActivity(LoginActivity.homeIntentFor(this));
            finish();
            return;
        }

        setContentView(R.layout.activity_role_entry);

        findViewById(R.id.card_guardian).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_GUARDIAN)));
        findViewById(R.id.card_volunteer).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_VOLUNTEER)));
    }
}
