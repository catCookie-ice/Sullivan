package com.sullivan.android.ui.roleentry;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.ui.login.LoginActivity;
import com.sullivan.android.ui.protectee.home.ProtecteeHomeActivity;

/**
 * 角色选择入口 (docs/final/00 §4.2)
 *
 * Android 登录前必须提供三角色入口：受护人 / 监护人 / 志愿者
 * 登录后以令牌中的服务端角色为准，客户端选择不得提升权限。
 *
 * <p>监护人与志愿者都进 {@link LoginActivity}，只是带上不同的角色提示
 * （{@link LoginActivity#EXTRA_ROLE_HINT}）用来换标题文案 —— 两者共用同一套
 * 账密登录接口，服务端返回的 roles 才是最终身份，客户端这边选的角色只是一个
 * 「我先想看哪一端的入口」的提示，不是权限声明。
 *
 * <p>受护人入口进 {@link ProtecteeHomeActivity}，它把「不需要账号的端侧障碍检测」
 * 和「跟着账号走的绑定码/绑定关系/公益活动/实时会话」分成上下两块。
 * 障碍检测本身<b>仍然不需要登录</b>：检测完全在本机跑，画面不上传，
 * 让一个低视力用户为了「识别前方有没有台阶」先注册账号是不合理的 ——
 * 所以主页上那张卡永远可点，另一块在未登录时先引导扫码登录。
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

        findViewById(R.id.card_protectee).setOnClickListener(
                view -> startActivity(new Intent(this, ProtecteeHomeActivity.class)));
        findViewById(R.id.card_guardian).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_GUARDIAN)));
        findViewById(R.id.card_volunteer).setOnClickListener(
                view -> startActivity(LoginActivity.intentFor(this,
                        LoginActivity.ROLE_HINT_VOLUNTEER)));
    }
}
