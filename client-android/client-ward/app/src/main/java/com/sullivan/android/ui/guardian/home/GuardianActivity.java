package com.sullivan.android.ui.guardian.home;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.QrDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.qr.QrPayload;
import com.sullivan.android.ui.common.activity.ActivityListActivity;
import com.sullivan.android.ui.common.realtime.RealtimeActivity;
import com.sullivan.android.ui.common.scan.QrScanActivity;
import com.sullivan.android.ui.guardian.binding.BindingListActivity;
import com.sullivan.android.ui.guardian.binding.CreateProtecteeActivity;
import com.sullivan.android.ui.guardian.volunteer.VolunteerApplyActivity;
import com.sullivan.android.ui.login.LoginActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 监护人主页：扫码绑定受护人 / 扫受护人登录码 / 退出登录。
 *
 * <p>本页是功能 A 与功能 B 的<b>扫码端</b>。两条流程在此分叉：
 * <ul>
 *   <li>功能 A：扫到的绑定码 → 一次请求就完事（服务端在同一个事务里建立关系）。</li>
 *   <li>功能 B：扫到的登录码 → 服务端返回「你名下已绑定、可作为授权对象」的受护人列表
 *       → 本页让监护人**选一个** → 再确认。选择在客户端做，但**授权判定在服务端重做一遍**
 *       （见 {@code QrLoginService.confirm}），客户端传来的 protecteeId 只是一个「想授权谁」。</li>
 * </ul>
 */
public class GuardianActivity extends AppCompatActivity {

    private SessionStore session;
    private TextView statusText;
    private ActivityResultLauncher<Intent> bindScanner;
    private ActivityResultLauncher<Intent> loginScanner;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian);

        session = new SessionStore(this);
        if (!session.isLoggedIn()) {
            Toast.makeText(this, R.string.guardian_need_login, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        statusText = findViewById(R.id.guardian_status);
        TextView accountText = findViewById(R.id.guardian_account);
        String label = session.accountLabel();
        accountText.setText(getString(R.string.guardian_account,
                TextUtils.isEmpty(label) ? "—" : label));

        bindScanner = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        return;
                    }
                    String text = result.getData().getStringExtra(QrScanActivity.EXTRA_RESULT_TEXT);
                    QrPayload payload = QrPayload.parse(text);
                    if (payload != null && payload.kind() == QrPayload.Kind.BIND) {
                        submitBindScan(payload.value());
                    }
                });
        loginScanner = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        return;
                    }
                    String text = result.getData().getStringExtra(QrScanActivity.EXTRA_RESULT_TEXT);
                    QrPayload payload = QrPayload.parse(text);
                    if (payload != null && payload.kind() == QrPayload.Kind.LOGIN) {
                        submitLoginScan(payload.value());
                    }
                });

        findViewById(R.id.guardian_card_scan_bind).setOnClickListener(view -> bindScanner.launch(
                QrScanActivity.intentFor(this, QrPayload.Kind.BIND, R.string.scan_title_bind)));
        findViewById(R.id.guardian_card_scan_login).setOnClickListener(view -> loginScanner.launch(
                QrScanActivity.intentFor(this, QrPayload.Kind.LOGIN, R.string.scan_title_login)));
        findViewById(R.id.guardian_card_bindings).setOnClickListener(
                view -> startActivity(BindingListActivity.intentFor(this)));
        findViewById(R.id.guardian_card_create_protectee).setOnClickListener(
                view -> startActivity(CreateProtecteeActivity.intentFor(this)));
        findViewById(R.id.guardian_card_volunteer_apply).setOnClickListener(
                view -> startActivity(VolunteerApplyActivity.intentFor(this)));
        findViewById(R.id.guardian_card_activities).setOnClickListener(
                view -> startActivity(ActivityListActivity.intentFor(this)));
        findViewById(R.id.guardian_card_realtime).setOnClickListener(
                view -> startActivity(RealtimeActivity.intentFor(this)));

        // 已经是志愿者就不显示「申请兼任志愿者」—— 一个永远只会得到
        // 「已存在待审核/已通过申请」的入口，比没有这个入口更让人困惑。
        View volunteerCard = findViewById(R.id.guardian_card_volunteer_apply);
        volunteerCard.setVisibility(session.roles().contains("VOLUNTEER")
                ? View.GONE : View.VISIBLE);
        findViewById(R.id.guardian_logout).setOnClickListener(view -> {
            session.clearLogin();
            Toast.makeText(this, R.string.guardian_logged_out, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    // ------------------------------------------------------------------
    // 功能 A：扫码绑定
    // ------------------------------------------------------------------

    private void submitBindScan(@NonNull String code) {
        ApiClient.get(this).scanBindCode(new QrDtos.BindScanRequest(code))
                .enqueue(new ResultCallback<QrDtos.BindScanResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.BindScanResponse body) {
                        if (body == null) {
                            return;
                        }
                        // 服务端已经把「新建成功」与「你已经是 TA 的监护人」两种情形
                        // 都写成了一句人话，界面不重复判断 alreadyBound。
                        showStatus(body.message, false);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        showStatus(getString(R.string.guardian_bind_failed, error.getMessage()),
                                true);
                    }
                });
    }

    // ------------------------------------------------------------------
    // 功能 B：扫登录码 + 选人 + 确认
    // ------------------------------------------------------------------

    private void submitLoginScan(@NonNull String ticket) {
        ApiClient.get(this).scanLoginTicket(ticket)
                .enqueue(new ResultCallback<QrDtos.LoginTicketScanResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.LoginTicketScanResponse body) {
                        List<QrDtos.ProtecteeCandidate> candidates =
                                body == null || body.candidates == null
                                        ? new ArrayList<>() : body.candidates;
                        if (candidates.isEmpty()) {
                            showStatus(getString(R.string.guardian_pick_empty), true);
                            return;
                        }
                        pickProtectee(ticket, candidates);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        showStatus(getString(R.string.guardian_scan_failed, error.getMessage()),
                                true);
                    }
                });
    }

    private void pickProtectee(@NonNull String ticket,
                               @NonNull List<QrDtos.ProtecteeCandidate> candidates) {
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            QrDtos.ProtecteeCandidate candidate = candidates.get(i);
            labels[i] = TextUtils.isEmpty(candidate.displayName)
                    ? candidate.loginName : candidate.displayName;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.guardian_pick_protectee)
                .setItems(labels, (dialog, which) -> confirmAuthorization(ticket, candidates.get(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmAuthorization(@NonNull String ticket,
                                      @NonNull QrDtos.ProtecteeCandidate candidate) {
        String name = TextUtils.isEmpty(candidate.displayName)
                ? candidate.loginName : candidate.displayName;
        new AlertDialog.Builder(this)
                .setTitle(R.string.guardian_confirm_title)
                .setMessage(getString(R.string.guardian_confirm_message, name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok,
                        (dialog, which) -> submitConfirm(ticket, candidate.protecteeId))
                .show();
    }

    private void submitConfirm(@NonNull String ticket, @NonNull String protecteeId) {
        ApiClient.get(this).confirmLoginTicket(ticket,
                        new QrDtos.LoginTicketConfirmRequest(protecteeId))
                .enqueue(new ResultCallback<QrDtos.LoginTicketStatusResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.LoginTicketStatusResponse body) {
                        // 这句话必须说清楚「登录成功的是对方那台设备」：
                        // 扫码时监护人的视线在自己的手机上，最容易得出的错误结论是
                        // 「我按了确认，怎么我这边没登进去」。
                        showStatus(getString(R.string.guardian_authorized), false);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        showStatus(getString(R.string.guardian_authorize_failed, error.getMessage()),
                                true);
                    }
                });
    }

    private void showStatus(@NonNull String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        // 用 an_sage / an_terracotta 而不是 level_clear / level_danger：
        // level_* 是给相机预览叠加层用的高饱和绿黄红，压在羊皮纸上会刺眼；
        // 那三个颜色只在检测页出现，语义是「走得过去 / 注意 / 停下」，不是「成功 / 失败」。
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }
}
