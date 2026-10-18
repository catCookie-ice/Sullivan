package com.sullivan.android.ui.guardian.binding;

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

import retrofit2.Call;

/**
 * 监护人代建受护人账号。
 *
 * <p><b>创建即绑定，且不可解除</b>：服务端在同一个事务里建立 {@code permanent=true}
 * 的主绑定，所以这里不需要（也不能）再走一次扫码。这条关系解除会 403，
 * 所以副标题把「不能解除」写在创建按钮之前 —— 用户以为还能反悔，
 * 和用户知道这是一锤子买卖，是两种完全不同的决定。
 *
 * <p><b>不自动登录成受护人</b>：建的是**另一个人的账号**。监护人手上这台设备
 * 仍然是他自己的身份，受护人要用自己的设备登录。这里建完只回上一页，
 * 让用户去受护人那台设备上完成登录（扫码登录或账密登录都可以）。
 *
 * <p>受护人自己注册这条路不做：受护人账号必须挂在一个监护人下面，
 * 否则系统里没有任何一条「谁对他负责」的记录。
 */
public class CreateProtecteeActivity extends AppCompatActivity {

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, CreateProtecteeActivity.class);
    }

    private TextInputEditText accountInput;
    private TextInputEditText passwordInput;
    private TextInputEditText displayInput;
    private TextInputEditText noteInput;
    private Button submit;
    private TextView statusText;

    @Nullable
    private Call<AuthDtos.RegistrationResponse> pendingCall;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_protectee);

        // 未登录的监护人调这个接口只会得到 401，不如在进页面前就退回去。
        if (!new SessionStore(this).isLoggedIn()) {
            finish();
            return;
        }

        accountInput = findViewById(R.id.create_protectee_account);
        passwordInput = findViewById(R.id.create_protectee_password);
        displayInput = findViewById(R.id.create_protectee_display);
        noteInput = findViewById(R.id.create_protectee_note);
        submit = findViewById(R.id.create_protectee_submit);
        statusText = findViewById(R.id.create_protectee_status);

        submit.setOnClickListener(view -> submit());
        findViewById(R.id.create_protectee_back).setOnClickListener(view -> finish());
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
        String display = textOf(displayInput);
        String note = textOf(noteInput);

        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password) || TextUtils.isEmpty(display)) {
            showStatus(getString(R.string.create_protectee_missing), true);
            return;
        }
        if (account.length() < 4) {
            showStatus(getString(R.string.create_protectee_account_too_short), true);
            return;
        }
        if (password.length() < 8) {
            showStatus(getString(R.string.create_protectee_password_too_short), true);
            return;
        }

        // 备注留空发 null 而不是空串：服务端对它的校验是 @Size(max=500)，
        // 两种写法都过，但 null 在响应里不会变成一个多余的空字段。
        AuthDtos.ProtecteeCreateRequest request = new AuthDtos.ProtecteeCreateRequest(
                account, password, display, TextUtils.isEmpty(note) ? null : note);

        setBusy(true);
        showStatus(getString(R.string.create_protectee_in_progress), false);
        Call<AuthDtos.RegistrationResponse> call = ApiClient.get(this).createProtectee(request);
        pendingCall = call;
        call.enqueue(new ResultCallback<AuthDtos.RegistrationResponse>() {
            @Override
            public void onSuccess(@Nullable AuthDtos.RegistrationResponse body) {
                pendingCall = null;
                setBusy(false);
                // 清掉密码但保留登录名与显示名：这是**另一个人的账号**，
                // 用户多半要拿着登录名去受护人那台设备上登录，留着比清空有用。
                passwordInput.setText("");
                String created = body == null || TextUtils.isEmpty(body.id) ? account : body.id;
                showStatus(getString(R.string.create_protectee_succeeded, created), false);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                if (error.isUnauthorized()) {
                    new SessionStore(CreateProtecteeActivity.this).clearLogin();
                }
                showStatus(getString(R.string.create_protectee_failed, error.getMessage()), true);
            }
        });
    }

    private void setBusy(boolean busy) {
        submit.setEnabled(!busy);
    }

    private void showStatus(@NonNull String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
