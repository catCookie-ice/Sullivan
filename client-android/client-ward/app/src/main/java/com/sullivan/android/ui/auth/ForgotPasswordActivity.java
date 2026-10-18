package com.sullivan.android.ui.auth;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.RecoveryDtos;

import java.util.List;

import retrofit2.Call;

/**
 * 找回密码：三步向导，与 web 的 {@code ForgotPasswordView} 同构。
 *
 * <p><b>后端没有重置密码的接口，这里就没有「重设密码」</b>：web 端走到最后一步只显示
 * 「验证通过！重置密码功能正在开发中，后续将开放」。安卓端照搬这个口径 ——
 * 第三步必须如实说「验证通过 ≠ 密码已改」。若在这里写成「密码已重置」，
 * 用户回去拿新密码登录会失败，然后怀疑是自己记错了。
 *
 * <p><b>这套流程没有验证码</b>：{@code start} 只返回掩码前缀（如 {@code ****8000}），
 * {@code verify} 要求用户把完整号码/邮箱<em>补全</em>，服务端比对命中才算通过。
 * 它不是「你能收到短信」的持有题，而是「你知道自己填过什么」的知识题。
 * 所以界面上不出现任何「发送验证码」「收不到短信」的字样。
 *
 * <p><b>账号不存在与没设找回方式，在服务端是同一个响应</b>（空 methods），
 * 否则这个接口就成了「登录名是否已注册」的探测器。界面也照这个口径给一句统一的话，
 * 不去猜、不区分。
 */
public class ForgotPasswordActivity extends AppCompatActivity {

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, ForgotPasswordActivity.class);
    }

    private LinearLayout stepOne;
    private LinearLayout stepTwo;
    private LinearLayout stepThree;
    private TextView stepLabel;
    private TextView statusText;
    private TextInputEditText loginInput;
    private RadioGroup methodsGroup;
    private TextView methodHint;
    private TextInputLayout contactWrap;
    private TextInputEditText contactInput;
    private Button nextButton;
    private Button verifyButton;

    /** 第一步查到的登录名，后面两步都用这一份，避免用户回去改输入框造成前后不一致。 */
    private String loginName;
    /** 当前选中的方式（PHONE / EMAIL）；null 表示还没选。 */
    @Nullable
    private String selectedType;

    @Nullable
    private Call<?> pendingCall;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        stepOne = findViewById(R.id.forgot_step_one);
        stepTwo = findViewById(R.id.forgot_step_two);
        stepThree = findViewById(R.id.forgot_step_three);
        stepLabel = findViewById(R.id.forgot_step_label);
        statusText = findViewById(R.id.forgot_status);
        loginInput = findViewById(R.id.forgot_login);
        methodsGroup = findViewById(R.id.forgot_methods);
        methodHint = findViewById(R.id.forgot_method_hint);
        contactWrap = findViewById(R.id.forgot_contact_wrap);
        contactInput = findViewById(R.id.forgot_contact);
        nextButton = findViewById(R.id.forgot_next);
        verifyButton = findViewById(R.id.forgot_verify);

        nextButton.setOnClickListener(view -> startLookup());
        verifyButton.setOnClickListener(view -> verify());
        findViewById(R.id.forgot_restart).setOnClickListener(view -> toStepOne());
        findViewById(R.id.forgot_back_login).setOnClickListener(view -> {
            // 从登录页跳过来的话回到原来的登录页，而不是再摞一层。
            setResult(RESULT_OK);
            finish();
        });

        methodsGroup.setOnCheckedChangeListener((group, checkedId) -> onMethodSelected());
        toStepOne();
    }

    @Override
    protected void onDestroy() {
        if (pendingCall != null) {
            pendingCall.cancel();
            pendingCall = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 步骤切换
    // ------------------------------------------------------------------

    private void toStepOne() {
        loginName = null;
        selectedType = null;
        contactInput.setText("");
        methodsGroup.removeAllViews();
        stepOne.setVisibility(View.VISIBLE);
        stepTwo.setVisibility(View.GONE);
        stepThree.setVisibility(View.GONE);
        stepLabel.setText(R.string.forgot_step_login);
        showStatus("", false, false);
    }

    private void toStepTwo(@NonNull String loginName, @NonNull List<RecoveryDtos.Method> methods) {
        this.loginName = loginName;
        methodsGroup.removeAllViews();
        selectedType = null;

        if (methods.isEmpty()) {
            // 账号不存在与没设找回方式共用这一句。别在这里加「是不是登录名打错了」之类的推测。
            stepOne.setVisibility(View.GONE);
            stepTwo.setVisibility(View.VISIBLE);
            stepThree.setVisibility(View.GONE);
            stepLabel.setText(R.string.forgot_step_method);
            methodHint.setText(R.string.forgot_no_methods);
            methodHint.setVisibility(View.VISIBLE);
            methodsGroup.setVisibility(View.GONE);
            contactWrap.setVisibility(View.GONE);
            verifyButton.setVisibility(View.GONE);
            showStatus("", false, false);
            return;
        }

        for (RecoveryDtos.Method method : methods) {
            if (method == null || TextUtils.isEmpty(method.type)) {
                continue;
            }
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setTag(method.type);
            radio.setText(methodLabel(method));
            radio.setTextColor(getColor(R.color.an_ink));
            radio.setTextSize(17f);
            radio.setButtonTintList(getColorStateList(R.color.an_radio_tint));
            radio.setMinHeight(getResources().getDimensionPixelSize(R.dimen.an_touch_min));
            methodsGroup.addView(radio, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
        }

        stepOne.setVisibility(View.GONE);
        stepTwo.setVisibility(View.VISIBLE);
        stepThree.setVisibility(View.GONE);
        stepLabel.setText(R.string.forgot_step_method);
        methodHint.setVisibility(View.VISIBLE);
        methodHint.setText(R.string.forgot_complete_hint);
        methodsGroup.setVisibility(View.VISIBLE);
        // 输入框先不显示：必须先选一种方式，才知道该问手机号还是邮箱。
        contactWrap.setVisibility(View.GONE);
        verifyButton.setVisibility(View.GONE);
        showStatus("", false, false);
    }

    private void toStepThree() {
        stepOne.setVisibility(View.GONE);
        stepTwo.setVisibility(View.GONE);
        stepThree.setVisibility(View.VISIBLE);
        stepLabel.setText(R.string.forgot_step_result);
        showStatus("", false, false);
    }

    // ------------------------------------------------------------------
    // 第一步：查方式
    // ------------------------------------------------------------------

    private void startLookup() {
        if (pendingCall != null) {
            return;
        }
        String account = textOf(loginInput);
        if (TextUtils.isEmpty(account)) {
            showStatus(getString(R.string.login_missing_fields), true);
            return;
        }

        setBusy(true);
        showStatus(getString(R.string.login_in_progress), false);
        Call<RecoveryDtos.StartResponse> call = ApiClient.get(this)
                .recoveryStart(new RecoveryDtos.StartRequest(account));
        pendingCall = call;
        call.enqueue(new ResultCallback<RecoveryDtos.StartResponse>() {
            @Override
            public void onSuccess(@Nullable RecoveryDtos.StartResponse body) {
                pendingCall = null;
                setBusy(false);
                toStepTwo(account, body == null || body.methods == null
                        ? java.util.Collections.emptyList() : body.methods);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                showStatus(getString(R.string.forgot_start_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 第二步：选方式 + 补全
    // ------------------------------------------------------------------

    private void onMethodSelected() {
        int checkedId = methodsGroup.getCheckedRadioButtonId();
        View checked = checkedId == View.NO_ID ? null : methodsGroup.findViewById(checkedId);
        Object tag = checked == null ? null : checked.getTag();
        selectedType = tag == null ? null : tag.toString();

        if (selectedType == null) {
            contactWrap.setVisibility(View.GONE);
            verifyButton.setVisibility(View.GONE);
            return;
        }
        // 换方式就把旧输入清掉：手机号和邮箱混在同一个框里，留着上一个的内容
        // 只会在验证时得到一句「不匹配」，而用户以为是自己填错了新的那一个。
        contactInput.setText("");
        if ("PHONE".equals(selectedType)) {
            contactWrap.setHint(getString(R.string.forgot_field_complete_phone));
            contactInput.setInputType(InputType.TYPE_CLASS_PHONE);
        } else {
            contactWrap.setHint(getString(R.string.forgot_field_complete_email));
            contactInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        }
        contactWrap.setVisibility(View.VISIBLE);
        verifyButton.setVisibility(View.VISIBLE);
        showStatus("", false, false);
    }

    private void verify() {
        if (pendingCall != null) {
            return;
        }
        if (TextUtils.isEmpty(loginName)) {
            // 只有第 2 步的入口会调到这里，理论上到不了；真到了就退回第一步，
            // 而不是带着一个空登录名去请求（服务端只会回「参数不合法」）。
            toStepOne();
            return;
        }
        if (TextUtils.isEmpty(selectedType)) {
            showStatus(getString(R.string.forgot_need_method), true);
            return;
        }
        String contact = textOf(contactInput);
        if (TextUtils.isEmpty(contact)) {
            showStatus(getString(R.string.forgot_need_contact), true);
            return;
        }

        setBusy(true);
        showStatus(getString(R.string.login_in_progress), false);
        Call<RecoveryDtos.VerifyResponse> call = ApiClient.get(this)
                .recoveryVerify(new RecoveryDtos.VerifyRequest(loginName, selectedType, contact));
        pendingCall = call;
        call.enqueue(new ResultCallback<RecoveryDtos.VerifyResponse>() {
            @Override
            public void onSuccess(@Nullable RecoveryDtos.VerifyResponse body) {
                pendingCall = null;
                setBusy(false);
                // 不通过是 200 + verified=false，不是 4xx —— 别按状态码判断。
                if (body != null && body.verified) {
                    toStepThree();
                } else {
                    showStatus(getString(R.string.forgot_mismatch), true);
                }
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                showStatus(getString(R.string.forgot_verify_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    @NonNull
    private String methodLabel(@NonNull RecoveryDtos.Method method) {
        String masked = TextUtils.isEmpty(method.maskedPrefix) ? "" : method.maskedPrefix;
        return "EMAIL".equals(method.type)
                ? getString(R.string.forgot_method_email, masked)
                : getString(R.string.forgot_method_phone, masked);
    }

    private void setBusy(boolean busy) {
        nextButton.setEnabled(!busy);
        verifyButton.setEnabled(!busy);
    }

    private void showStatus(@NonNull String message, boolean isError) {
        showStatus(message, isError, true);
    }

    private void showStatus(@NonNull String message, boolean isError, boolean visible) {
        if (!visible || TextUtils.isEmpty(message)) {
            statusText.setVisibility(View.GONE);
            return;
        }
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
