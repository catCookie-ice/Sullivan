package com.sullivan.android.ui.guardian.volunteer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.VolunteerDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.common.components.ServerTime;
import com.sullivan.android.ui.common.components.StateView;
import com.sullivan.android.ui.common.components.StatusLabels;

import java.util.List;

import retrofit2.Call;

/**
 * 监护人申请兼任志愿者：提交一次申请 + 我的申请记录 + 撤销。
 *
 * <p><b>审核通过后要重新登录才生效</b>：VOLUNTEER 角色是编进 JWT 的，
 * 已签发的令牌里没有它，服务端也不会中途改签。这句话写在页面顶上，
 * 否则用户会以为「审核通过了但按钮还是灰的」是客户端坏了。
 *
 * <p><b>列表用 LinearLayout 手填而不是 RecyclerView</b>：申请记录一个人一辈子
 * 也就三五条（服务端还拒绝重复的 PENDING），套一层 Adapter 只是多一个文件。
 * 复用 {@link LayoutInflater} 逐行 inflate {@code row_application}。
 */
public class VolunteerApplyActivity extends AppCompatActivity {

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, VolunteerApplyActivity.class);
    }

    private SessionStore session;
    private TextInputEditText reasonInput;
    private Button submit;
    private TextView statusText;
    private StateView stateView;
    private LinearLayout listView;

    /** 提交或撤销在飞时的请求。拉取列表时也用它，保证同一时刻只有一个在跑。 */
    @Nullable
    private Call<?> pendingCall;

    /** 列表里「可撤销」的申请 id，按行按钮的 tag 取回。 */
    private String pendingApplicationId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volunteer_apply);

        session = new SessionStore(this);
        if (!session.isLoggedIn()) {
            finish();
            return;
        }

        reasonInput = findViewById(R.id.volunteer_reason);
        submit = findViewById(R.id.volunteer_submit);
        statusText = findViewById(R.id.volunteer_status);
        stateView = findViewById(R.id.volunteer_state);
        listView = findViewById(R.id.volunteer_list);

        submit.setOnClickListener(view -> submitApplication());
        loadApplications();
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
    // 提交
    // ------------------------------------------------------------------

    private void submitApplication() {
        if (pendingCall != null) {
            return;
        }
        String reason = textOf(reasonInput);
        // 理由留空发 null：服务端 body 可为 null，且空串和 null 在校验上等价，
        // 但 null 不会在请求体里多出一个空字段。
        Call<VolunteerDtos.ApplicationResponse> call = ApiClient.get(this)
                .applyVolunteer(new VolunteerDtos.ApplyRequest(
                        TextUtils.isEmpty(reason) ? null : reason));
        pendingCall = call;
        setBusy(true);
        showStatus(getString(R.string.volunteer_submitting), false);
        call.enqueue(new ResultCallback<VolunteerDtos.ApplicationResponse>() {
            @Override
            public void onSuccess(@Nullable VolunteerDtos.ApplicationResponse body) {
                pendingCall = null;
                setBusy(false);
                showStatus(getString(R.string.volunteer_submitted), false);
                reasonInput.setText("");
                loadApplications();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                handleUnauthorized(error);
                showStatus(getString(R.string.volunteer_submit_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 我的申请记录
    // ------------------------------------------------------------------

    private void loadApplications() {
        if (pendingCall != null) {
            return;
        }
        stateView.showLoading(getString(R.string.state_loading));
        listView.setVisibility(View.GONE);
        Call<List<VolunteerDtos.ApplicationResponse>> call =
                ApiClient.get(this).myVolunteerApplications();
        pendingCall = call;
        call.enqueue(new ResultCallback<List<VolunteerDtos.ApplicationResponse>>() {
            @Override
            public void onSuccess(@Nullable List<VolunteerDtos.ApplicationResponse> body) {
                pendingCall = null;
                render(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                listView.setVisibility(View.GONE);
                stateView.showError(error.getMessage(), VolunteerApplyActivity.this::loadApplications);
            }
        });
    }

    private void render(@Nullable List<VolunteerDtos.ApplicationResponse> applications) {
        listView.removeAllViews();
        pendingApplicationId = null;
        if (applications == null || applications.isEmpty()) {
            stateView.showEmpty(getString(R.string.volunteer_empty));
            listView.setVisibility(View.GONE);
            return;
        }
        stateView.hide();
        listView.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (VolunteerDtos.ApplicationResponse application : applications) {
            View row = inflater.inflate(R.layout.row_application, listView, false);
            bindRow(row, application);
            listView.addView(row);
        }
    }

    private void bindRow(@NonNull View row, @NonNull VolunteerDtos.ApplicationResponse application) {
        StatusLabels.Label status = StatusLabels.application(application.status);
        TextView statusView = row.findViewById(R.id.application_status);
        statusView.setText(status.text);
        statusView.setBackgroundResource(StatusLabels.chipBackground(status.tone));
        statusView.setTextColor(getColor(StatusLabels.textColor(status.tone)));

        ((TextView) row.findViewById(R.id.application_created)).setText(
                ServerTime.shortForm(application.createdAt));

        TextView reason = row.findViewById(R.id.application_reason);
        if (TextUtils.isEmpty(application.reason)) {
            // 理由本来就是选填的。不写一句反而会让人以为没提交上去。
            reason.setText(R.string.volunteer_reason_empty);
        } else {
            reason.setText(application.reason);
        }

        Button cancel = row.findViewById(R.id.application_cancel);
        // 只有 PENDING 能撤销：撤销一条已通过的申请没有语义，而服务端不保证会拒绝。
        if (!"PENDING".equalsIgnoreCase(application.status)) {
            cancel.setVisibility(View.GONE);
            return;
        }
        cancel.setVisibility(View.VISIBLE);
        cancel.setOnClickListener(view -> confirmCancel(application.id));
    }

    private void confirmCancel(@NonNull String applicationId) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.volunteer_action_cancel)
                .setMessage(R.string.volunteer_confirm_cancel)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> cancel(applicationId))
                .show();
    }

    private void cancel(@NonNull String applicationId) {
        if (pendingCall != null) {
            return;
        }
        pendingApplicationId = applicationId;
        Call<VolunteerDtos.ApplicationResponse> call =
                ApiClient.get(this).cancelVolunteer(applicationId);
        pendingCall = call;
        setBusy(true);
        call.enqueue(new ResultCallback<VolunteerDtos.ApplicationResponse>() {
            @Override
            public void onSuccess(@Nullable VolunteerDtos.ApplicationResponse body) {
                pendingCall = null;
                setBusy(false);
                Toast.makeText(VolunteerApplyActivity.this, R.string.volunteer_cancelled,
                        Toast.LENGTH_SHORT).show();
                loadApplications();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                setBusy(false);
                handleUnauthorized(error);
                showStatus(getString(R.string.volunteer_cancel_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private void handleUnauthorized(@NonNull ApiException error) {
        if (error.isUnauthorized()) {
            session.clearLogin();
        }
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
