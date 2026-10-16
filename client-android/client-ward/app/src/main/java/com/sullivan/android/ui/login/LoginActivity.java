package com.sullivan.android.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.AuthDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.guardian.home.GuardianActivity;

import java.util.HashSet;

import retrofit2.Call;

/**
 * 监护人登录（账号 + 密码）。
 *
 * <p>为什么监护人必须用账密、不能也用扫码：扫码授权那一整套的前提是
 * 「扫码的那个人已经被认证过」。受护人的登录码要靠监护人确认才生效，
 * 若监护人也靠别人扫他来登录，这条信任链就没有起点。
 *
 * <p>服务器地址在这一页也让用户填：真机联调时后端跑在开发机上
 * （{@code http://<局域网IP>:8642}），地址因网络环境而异，写死一个默认值
 * 意味着每次换网都要重新出包。默认值取模拟器的 {@code 10.0.2.2}。
 */
public class LoginActivity extends AppCompatActivity {

    private SessionStore session;
    private TextInputEditText accountInput;
    private TextInputEditText passwordInput;
    private TextInputEditText serverInput;
    private TextView statusText;

    /** 登录请求在飞：期间禁用按钮，避免连点发出多次登录。 */
    @Nullable
    private Call<AuthDtos.TokenResponse> pendingCall;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionStore(this);
        accountInput = findViewById(R.id.login_account);
        passwordInput = findViewById(R.id.login_password);
        serverInput = findViewById(R.id.login_server);
        statusText = findViewById(R.id.login_status);
        Button submit = findViewById(R.id.login_submit);

        serverInput.setText(session.baseUrl());
        // 已登录过就直接进主页：从主页退出时会清掉令牌，所以这里不会成为
        // 「退出登录又被弹回来」的死循环。
        if (session.isLoggedIn()) {
            goHome();
            return;
        }

        submit.setOnClickListener(view -> submit());
        findViewById(R.id.login_protectee_qr).setOnClickListener(
                view -> startActivity(new Intent(this, QrLoginActivity.class)));
    }

    @Override
    protected void onDestroy() {
        if (pendingCall != null) {
            pendingCall.cancel();
            pendingCall = null;
        }
        super.onDestroy();
    }

    private void submit() {
        if (pendingCall != null) {
            return;
        }
        String account = textOf(accountInput);
        String password = textOf(passwordInput);
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
            showStatus(getString(R.string.login_missing_fields), true);
            return;
        }

        // 地址先落库再发请求：ApiClient 是按 baseUrl 缓存 Retrofit 实例的，
        // 晚一步 invalidate 就会把这次登录发到上一台服务器上去。
        String server = textOf(serverInput);
        if (!TextUtils.isEmpty(server) && !server.equals(session.baseUrl())) {
            session.setBaseUrl(server);
            ApiClient.invalidate();
        }

        showStatus(getString(R.string.login_in_progress), false);
        pendingCall = ApiClient.get(this)
                .login(new AuthDtos.LoginRequest(account, password));
        pendingCall.enqueue(new ResultCallback<AuthDtos.TokenResponse>() {
            @Override
            public void onSuccess(@Nullable AuthDtos.TokenResponse body) {
                pendingCall = null;
                if (body == null || TextUtils.isEmpty(body.accessToken)) {
                    showStatus(getString(R.string.login_failed_empty), true);
                    return;
                }
                // 服务端返回的 roles 为 null 时按空集合处理：saveLogin 要往
                // SharedPreferences 里放 StringSet，塞 null 会直接 NPE。
                session.saveLogin(body.accessToken, body.principalId, body.principalType,
                        body.roles == null ? new HashSet<>() : new HashSet<>(body.roles), account);
                showStatus(getString(R.string.login_succeeded), false);
                goHome();
            }

            @Override
            public void onError(@NonNull com.sullivan.android.data.api.ApiException error) {
                pendingCall = null;
                showStatus(error.getMessage(), true);
            }
        });
    }

    private void goHome() {
        startActivity(new Intent(this, GuardianActivity.class));
        finish();
    }

    private void showStatus(String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.level_danger : R.color.level_clear));
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
