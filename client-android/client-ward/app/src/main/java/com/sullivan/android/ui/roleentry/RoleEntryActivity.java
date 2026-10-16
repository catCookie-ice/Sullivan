package com.sullivan.android.ui.roleentry;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.ui.login.LoginActivity;
import com.sullivan.android.ui.protectee.home.ProtecteeActivity;

/**
 * 角色选择入口 (docs/final/00 §4.2)
 *
 * Android 登录前必须提供三角色入口：受护人 / 监护人 / 志愿者
 * 登录后以令牌中的服务端角色为准，客户端选择不得提升权限。
 *
 * 每个角色入口分别注册对应账号，不要求同一账号多角色。
 *
 * <p>受护人入口走的是移植进来的端侧障碍检测（{@link ProtecteeActivity}），
 * <b>不需要登录</b>；监护人入口进 {@link LoginActivity} 做账号密码登录
 * （扫码授权要求监护人已被认证，这一步没有别的办法）。志愿者入口只给出
 * "尚未实现"提示——按 docs/final/16 N-07，不留点不进去却能看出是空壳的入口，
 * 所以这里明说而不是静默跳转。
 */
public class RoleEntryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_entry);

        findViewById(R.id.card_protectee).setOnClickListener(
                view -> startActivity(new Intent(this, ProtecteeActivity.class)));
        findViewById(R.id.card_guardian).setOnClickListener(
                view -> startActivity(new Intent(this, LoginActivity.class)));
        findViewById(R.id.card_volunteer).setOnClickListener(
                view -> showNotImplemented(R.string.role_volunteer));
    }

    private void showNotImplemented(int roleNameRes) {
        Toast.makeText(this, getString(R.string.role_not_implemented, getString(roleNameRes)),
                Toast.LENGTH_SHORT).show();
    }
}
