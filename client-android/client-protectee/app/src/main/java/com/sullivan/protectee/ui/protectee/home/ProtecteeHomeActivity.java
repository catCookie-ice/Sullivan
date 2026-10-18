package com.sullivan.protectee.ui.protectee.home;

import android.content.Context;
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
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.protectee.R;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.ui.common.activity.ActivityListActivity;
import com.sullivan.protectee.ui.common.realtime.RealtimeActivity;
import com.sullivan.protectee.ui.common.binding.BindingListActivity;
import com.sullivan.protectee.ui.protectee.login.QrLoginActivity;
import com.sullivan.protectee.ui.protectee.binding.BindCodeActivity;

/**
 * 受护人主页（登录后）。
 *
 * <p><b>为什么要有这一页</b>：{@link ProtecteeActivity}（障碍检测）是离线可用的，
 * 它不能也不需要知道账号的存在。但绑定码、绑定关系、公益活动、实时会话这四件事
 * 全部挂在账号上 —— 此前受护人登完录无处可去，只能回到检测界面自己找。
 * 这一页把「不需要账号的」和「需要账号的」明确分成上下两块。
 *
 * <p><b>第一张卡永远是障碍检测，且永远可点</b>：未登录、令牌过期、网络不通
 * 都不影响它。这是本页唯一一条不能被登录态左右的规则。
 *
 * <p><b>未登录时下面四张卡不隐藏</b>：受护人看不到「登录之后能得到什么」，
 * 就没有理由去登录。点它们会先走扫码登录，登完直接进目标页
 * （{@link QrLoginActivity} 返回 RESULT_OK，本页记住要去的地方）。
 *
 * <p><b>本页不查任何接口</b>：只有一条「当前账号」的本地显示。绑定/活动/会话
 * 各自在目标页里加载，本页多拉一遍只会多一份可能过期的数据。
 */
public class ProtecteeHomeActivity extends AppCompatActivity {

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, ProtecteeHomeActivity.class);
    }

    private SessionStore session;
    private TextView accountText;
    private View guestPanel;
    private View memberPanel;
    private ActivityResultLauncher<Intent> loginLauncher;

    /** 登录成功后要去的目的地；用户中途放弃则为 null。 */
    @Nullable
    private Class<?> pendingDestination;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protectee_home);

        session = new SessionStore(this);
        accountText = findViewById(R.id.protectee_home_account);
        guestPanel = findViewById(R.id.protectee_account_guest);
        memberPanel = findViewById(R.id.protectee_account_member);

        loginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Class<?> destination = pendingDestination;
                    pendingDestination = null;
                    refreshAccount();
                    if (result.getResultCode() == RESULT_OK && destination != null) {
                        startActivity(new Intent(this, destination));
                    }
                    // 登录没成功就把人留在本页：他刚刚已经表达了想去哪，
                    // 页面上的那几张卡还在，再点一次即可，不需要额外提示。
                });

        findViewById(R.id.protectee_card_detect).setOnClickListener(
                view -> startActivity(new Intent(this, ProtecteeActivity.class)));
        findViewById(R.id.protectee_home_login).setOnClickListener(
                view -> loginLauncher.launch(QrLoginActivity.intentFor(this, false)));
        findViewById(R.id.protectee_home_logout).setOnClickListener(view -> {
            session.clearLogin();
            refreshAccount();
            Toast.makeText(this, R.string.protectee_home_logged_out, Toast.LENGTH_SHORT).show();
        });

        bindMemberCard(R.id.protectee_card_bind_code, BindCodeActivity.class);
        bindMemberCard(R.id.protectee_card_bindings, BindingListActivity.class);
        bindMemberCard(R.id.protectee_card_activities, ActivityListActivity.class);
        bindMemberCard(R.id.protectee_card_realtime, RealtimeActivity.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「我的绑定码」那次登录回来、或令牌被别处清掉，都要重画。
        refreshAccount();
    }

    /**
     * 一张「需要登录」的卡片。
     *
     * <p>不在这里判断「有没有权限做这件事」：服务端才是判定方，
     * 客户端的登录态只是决定「要不要先走一趟扫码登录」。
     */
    private void bindMemberCard(int viewId, @NonNull Class<?> destination) {
        findViewById(viewId).setOnClickListener(view -> {
            if (session.isLoggedIn()) {
                startActivity(new Intent(this, destination));
                return;
            }
            pendingDestination = destination;
            loginLauncher.launch(QrLoginActivity.intentFor(this, false));
        });
    }

    private void refreshAccount() {
        boolean loggedIn = session.isLoggedIn();
        guestPanel.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        memberPanel.setVisibility(loggedIn ? View.VISIBLE : View.GONE);

        String label = session.accountLabel();
        if (!loggedIn) {
            accountText.setText(R.string.protectee_home_account_guest);
        } else if (TextUtils.isEmpty(label)) {
            // 扫码登录拿到的响应里没有登录名（那条路径本来就没有「输入过登录名」这一步），
            // 所以这里不能写一个空的「当前账号：」。
            accountText.setText(getString(R.string.protectee_home_account_unknown));
        } else {
            accountText.setText(getString(R.string.guardian_account, label));
        }
    }
}
