package com.sullivan.android.ui.auth;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.AuthDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.login.LoginActivity;

import java.util.HashSet;

import retrofit2.Call;

/**
 * 注册监护人 / 志愿者账号，注册成功后顺手登录。
 *
 * <p><b>为什么注册完直接登录，而不是像 web 那样退回登录页</b>：web 退回登录页是因为
 * 浏览器里多一个输入动作没有成本；手机上用户刚填完一遍登录名和密码，
 * 再让他照着这两个字段重输一遍，唯一确定的结果是多一次输错和一次「密码错误」的困惑。
 * 密码就在内存里，直接拿它换令牌即可 —— 服务端对自然人注册的 {@code status} 恒为
 * {@code ENABLED}（机构才是 PENDING 待审核），所以这里不存在「注册完还不能登」的情况。
 *
 * <p><b>自动登录失败不当成注册失败</b>：注册已经成功了，账号是真的。失败时把登录名留在
 * 界面上并引导回登录页手动登，而不是回滚、也不是让用户再注册一遍同一个账号
 * （那会撞「登录名已存在」，而用户会以为两次都失败了）。
 *
 * <p><b>受护人不能从这里注册</b>：受护人账号只能由已登录的监护人代建，
 * 否则系统中没有任何一条「谁对他负责」的记录。角色单选项只有两个，这件事由界面本身表达。
 */
public class RegisterActivity extends AppCompatActivity {

    /** 从角色入口带过来的提示，只用来预选角色单选框。 */
    private static final String EXTRA_ROLE_HINT = "com.sullivan.android.extra.ROLE_HINT";

    @NonNull
    public static Intent intentFor(@NonNull Context context, @NonNull String roleHint) {
        return new Intent(context, RegisterActivity.class).putExtra(EXTRA_ROLE_HINT, roleHint);
    }

    private SessionStore session;
    private TextInputEditText accountInput;
    private TextInputEditText displayInput;
    private TextInputEditText passwordInput;
    private TextInputEditText passwordConfirmInput;
    private TextInputEditText recoveryPhoneInput;
    private TextInputEditText recoveryEmailInput;
    private RadioButton guardianRadio;
    private RadioButton volunteerRadio;
    private Button submit;
    private TextView statusText;

    /** 在飞的请求：注册或注册后的自动登录。非空时按钮禁用。 */
    @Nullable
    private Call<?> pendingCall;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        session = new SessionStore(this);
        accountInput = findViewById(R.id.register_account);
        displayInput = findViewById(R.id.register_display);
        passwordInput = findViewById(R.id.register_password);
        passwordConfirmInput = findViewById(R.id.register_password_confirm);
        recoveryPhoneInput = findViewById(R.id.register_recovery_phone);
        recoveryEmailInput = findViewById(R.id.register_recovery_email);
        guardianRadio = findViewById(R.id.register_role_guardian);
        volunteerRadio = findViewById(R.id.register_role_volunteer);
        submit = findViewById(R.id.register_submit);
        statusText = findViewById(R.id.register_status);

        applyRoleHint();
        submit.setOnClickListener(view -> submit());
        findViewById(R.id.register_go_login).setOnClickListener(view -> goLogin());
    }

    /**
     * 从志愿者入口进来时预选「志愿者」。
     *
     * <p>不校验 extra 内容：它只影响一个默认选中的单选框，选错了用户自己点一下就好；
     * 真正的角色由提交时选中的那一项决定，与这个 extra 无关。
     */
    private void applyRoleHint() {
        String hint = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ROLE_HINT);
        if (LoginActivity.ROLE_HINT_VOLUNTEER.equals(hint)) {
            volunteerRadio.setChecked(true);
        }
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
        String display = textOf(displayInput);
        String password = textOf(passwordInput);
        String confirm = textOf(passwordConfirmInput);
        String phone = textOf(recoveryPhoneInput);
        String email = textOf(recoveryEmailInput);

        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(display) || TextUtils.isEmpty(password)) {
            showStatus(getString(R.string.register_missing_fields), true);
            return;
        }
        if (account.length() < 4) {
            showStatus(getString(R.string.register_account_too_short), true);
            return;
        }
        if (password.length() < 8) {
            showStatus(getString(R.string.register_password_too_short), true);
            return;
        }
        if (!password.equals(confirm)) {
            showStatus(getString(R.string.register_password_mismatch), true);
            return;
        }

        // 留空就发 null（Gson 会整个略去这个字段）。**不能**发空串：
        // 服务端对这两个字段的校验是「@Size + @Pattern」，null 一律放行，
        // 而空串要走正则的空分支 —— 两种写法在服务端并不等价。
        AuthDtos.RegisterUserRequest request = new AuthDtos.RegisterUserRequest(
                account, password, display,
                volunteerRadio.isChecked() ? "VOLUNTEER" : "GUARDIAN",
                TextUtils.isEmpty(phone) ? null : phone,
                TextUtils.isEmpty(email) ? null : email);

        setBusy(true);
        showStatus(getString(R.string.register_in_progress), false);
        Call<AuthDtos.RegistrationResponse> call = ApiClient.get(this).registerUser(request);
        pendingCall = call;
        call.enqueue(new ResultCallback<AuthDtos.RegistrationResponse>() {
            @Override
            public void onSuccess(@Nullable AuthDtos.RegistrationResponse body) {
                pendingCall = null;
                // 响应体理论上带有 id，但这里不用它：登录换回的是**令牌**，
                // 令牌里的主体 ID 才是后续所有请求认的那个，注册响应里的 id 只是复核。
                autoLogin(account, password);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                showStatus(getString(R.string.register_failed, error.getMessage()), true);
            }
        });
    }

    /** 注册成功后立刻用同一组凭据换令牌。失败不回滚注册，只把登录名留给用户。 */
    private void autoLogin(@NonNull String account, @NonNull String password) {
        showStatus(getString(R.string.register_auto_login), false);
        Call<AuthDtos.TokenResponse> call = ApiClient.get(this)
                .login(new AuthDtos.LoginRequest(account, password));
        pendingCall = call;
        call.enqueue(new ResultCallback<AuthDtos.TokenResponse>() {
            @Override
            public void onSuccess(@Nullable AuthDtos.TokenResponse body) {
                pendingCall = null;
                if (body == null || TextUtils.isEmpty(body.accessToken)) {
                    onAutoLoginFailed(account, getString(R.string.login_failed_empty));
                    return;
                }
                session.saveLogin(body.accessToken, body.principalId, body.principalType,
                        body.roles == null ? new HashSet<>() : new HashSet<>(body.roles), account);
                goHome();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                onAutoLoginFailed(account, error.getMessage());
            }
        });
    }

    private void onAutoLoginFailed(@NonNull String account, @NonNull String reason) {
        setBusy(false);
        showStatus(getString(R.string.register_auto_login_failed, account, reason), true);
        accountInput.setText(account);
        passwordInput.setText("");
        passwordConfirmInput.setText("");
    }

    /** 注册成功后按服务端给的 roles 分流，与 {@link LoginActivity} 的口径一致。 */
    private void goHome() {
        startActivity(LoginActivity.homeIntentFor(this));
        finish();
    }

    private void goLogin() {
        // 已经从登录页跳过来的话回到原来那一页，而不是再摞一层登录页。
        if (isTaskRoot()) {
            startActivity(LoginActivity.intentFor(this, LoginActivity.ROLE_HINT_GUARDIAN));
        }
        finish();
    }

    private void setBusy(boolean busy) {
        submit.setEnabled(!busy);
    }

    private void showStatus(@NonNull String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        // an_sage / an_terracotta：level_clear / level_danger 是相机叠加层的高饱和绿黄红，
        // 语义是「走得过去 / 注意 / 停下」，不是「成功 / 失败」，不能复用。
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
