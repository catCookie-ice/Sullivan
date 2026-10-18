package com.sullivan.android.ui.login;

import android.content.Context;
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
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.AuthDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.auth.ForgotPasswordActivity;
import com.sullivan.android.ui.auth.RegisterActivity;
import com.sullivan.android.ui.common.recovery.RecoveryReminderDialog;
import com.sullivan.android.ui.guardian.home.GuardianActivity;
import com.sullivan.android.ui.volunteer.home.VolunteerHomeActivity;

import java.util.HashSet;
import java.util.Set;

import retrofit2.Call;

/**
 * 监护人 / 志愿者登录（账号 + 密码）。
 *
 * <p>为什么监护人必须用账密、不能也用扫码：扫码授权那一整套的前提是
 * 「扫码的那个人已经被认证过」。受护人的登录码要靠监护人确认才生效，
 * 若监护人也靠别人扫他来登录，这条信任链就没有起点。
 *
 * <p><b>角色提示不是权限声明</b>：{@link #EXTRA_ROLE_HINT} 只决定标题文案和
 * 登录成功后的落地页。真正的身份取自服务端返回的 {@code roles}
 * （{@code SessionStore.saveLogin}），客户端选的入口改不了它。
 *
 * <p><b>本页不提供服务器地址输入</b>：后端地址是构建期常量
 * （{@code BuildConfig.SERVER_BASE_URL}），由打包的人决定，不交给用户。
 * 用户能改地址意味着「这台设备连的是哪台服务器」由使用现场决定，
 * 而这本该是部署事实。见 {@code SessionStore} 的类注释。
 */
public class LoginActivity extends AppCompatActivity {

    /** 角色提示：进登录页前在角色入口选的那一项，只影响文案与落地页。 */
    public static final String EXTRA_ROLE_HINT = "com.sullivan.android.extra.ROLE_HINT";

    public static final String ROLE_HINT_GUARDIAN = "GUARDIAN";
    public static final String ROLE_HINT_VOLUNTEER = "VOLUNTEER";

    public static Intent intentFor(@NonNull Context context, @NonNull String roleHint) {
        return new Intent(context, LoginActivity.class).putExtra(EXTRA_ROLE_HINT, roleHint);
    }

    /**
     * 「已登录该去哪」的**唯一**判断点：账密登录、注册后自动登录、已登录直接进主页，
     * 三条路径都从这里取 Intent，避免出现「从注册进去看到监护人主页、从登录进去看到别的」。
     *
     * <p><b>按服务端返回的 roles 分流，不看入口提示</b>：{@link #EXTRA_ROLE_HINT} 是用户
     * 在角色入口点的那一下，而 {@code roles} 是服务端认定的身份。
     *
     * <p>只有「是志愿者且不是监护人」才落到志愿者主页；监护人可以兼任志愿者
     * （{@code roles} 同时含两项），这类账号仍然落到监护人主页 —— 志愿者主页是它的子集，
     * 而监护人主页上会给出「志愿者申请」入口，两边都能走。
     */
    @NonNull
    public static Intent homeIntentFor(@NonNull Context context) {
        SessionStore session = new SessionStore(context);
        Set<String> roles = session.roles();
        if (roles.contains(ROLE_VOLUNTEER) && !roles.contains(ROLE_GUARDIAN)) {
            return new Intent(context, VolunteerHomeActivity.class);
        }
        return new Intent(context, GuardianActivity.class);
    }

    /** 服务端 roles 里的角色代码（与入口提示 {@link #ROLE_HINT_*} 是两回事）。 */
    private static final String ROLE_VOLUNTEER = "VOLUNTEER";
    private static final String ROLE_GUARDIAN = "GUARDIAN";

    private SessionStore session;
    private TextInputEditText accountInput;
    private TextInputEditText passwordInput;
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
        statusText = findViewById(R.id.login_status);
        Button submit = findViewById(R.id.login_submit);

        applyRoleHint();

        // 已登录过就直接进主页：从主页退出时会清掉令牌，所以这里不会成为
        // 「退出登录又被弹回来」的死循环。
        if (session.isLoggedIn()) {
            goHome();
            return;
        }

        submit.setOnClickListener(view -> submit());
        findViewById(R.id.login_protectee_qr).setOnClickListener(
                view -> startActivity(new Intent(this, QrLoginActivity.class)));
        findViewById(R.id.login_register).setOnClickListener(view -> startActivity(
                RegisterActivity.intentFor(this, hintOf(this))));
        findViewById(R.id.login_forgot).setOnClickListener(
                view -> startActivity(ForgotPasswordActivity.intentFor(this)));
    }

    /** 把入口提示原样传给注册页，让它预选同一个角色。认不出来的按监护人处理。 */
    @NonNull
    private static String hintOf(@NonNull LoginActivity activity) {
        String hint = activity.getIntent() == null
                ? null : activity.getIntent().getStringExtra(EXTRA_ROLE_HINT);
        return ROLE_HINT_VOLUNTEER.equals(hint) ? ROLE_HINT_VOLUNTEER : ROLE_HINT_GUARDIAN;
    }

    /**
     * 按入口带进来的角色提示换标题。
     *
     * <p>不校验内容：这个 extra 只影响文案，认不出来就按监护人显示，
     * 判错的最坏后果是标题写错，而它<em>不是</em>权限来源，所以不必为它写失败分支。
     */
    private void applyRoleHint() {
        String hint = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ROLE_HINT);
        if (!ROLE_HINT_VOLUNTEER.equals(hint)) {
            return;
        }
        ((TextView) findViewById(R.id.login_title))
                .setText(R.string.login_title_volunteer);
        ((TextView) findViewById(R.id.login_subtitle))
                .setText(R.string.login_subtitle_volunteer);
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
                // 服务端说「这个账号还没设找回方式」时才提。判断只在这里做一次：
                // 登录响应里的 recoveryReminder 已经是服务端算好的结论，
                // 再自己查一遍 GET auth/me/recovery 只会多一个可能不一致的来源。
                if (body.recoveryReminder) {
                    // LoginActivity.this 而不是 this：这一段在 ResultCallback 匿名类里，
                    // 裸 this 指回调对象，编译期就找不到 goHome()。
                    RecoveryReminderDialog.show(LoginActivity.this, LoginActivity.this::goHome);
                } else {
                    goHome();
                }
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                showStatus(error.getMessage(), true);
            }
        });
    }

    private void goHome() {
        startActivity(homeIntentFor(this));
        finish();
    }

    private void showStatus(String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        // an_sage / an_terracotta 而不是 level_clear / level_danger：
        // level_* 是相机叠加层用的高饱和绿黄红，在羊皮纸底上刺眼，
        // 且它们的语义是「走得过去 / 注意 / 停下」，不是「成功 / 失败」。
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
