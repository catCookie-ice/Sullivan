package com.sullivan.android.ui.volunteer.home;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sullivan.android.R;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.common.activity.ActivityListActivity;
import com.sullivan.android.ui.common.realtime.RealtimeActivity;
import com.sullivan.android.ui.guardian.home.GuardianActivity;
import com.sullivan.android.ui.guardian.volunteer.VolunteerApplyActivity;
import com.sullivan.android.ui.login.LoginActivity;

/**
 * 志愿者主页。
 *
 * <p><b>为什么只有四张卡</b>：web 端的志愿者端除公益活动和实时会话之外全是占位页
 * （附近的协助请求、报名记录 —— 后端没有对应接口）。这一页不画那些入口，
 * 而是把「后端还没做」这件事直接写在页面顶部：一个点了没反应的入口，
 * 比一句诚实的说明更消耗用户。
 *
 * <p><b>两张条件卡</b>：服务端只接受监护人提交志愿者申请，纯志愿者账号
 * 也没有监护人端的数据可看。所以：
 * <ul>
 *   <li>「志愿者兼任申请」只在 {@code roles} 含 GUARDIAN 时出现；</li>
 *   <li>「切到监护人视角」同条件。</li>
 * </ul>
 * 两者都由服务端返回的 roles 决定，与「从哪个入口登录进来」无关 ——
 * 见 {@link LoginActivity#homeIntentFor}。
 */
public class VolunteerHomeActivity extends AppCompatActivity {

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, VolunteerHomeActivity.class);
    }

    private SessionStore session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volunteer_home);

        session = new SessionStore(this);
        if (!session.isLoggedIn()) {
            Toast.makeText(this, R.string.guardian_need_login, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        String label = session.accountLabel();
        ((TextView) findViewById(R.id.volunteer_home_account)).setText(
                TextUtils.isEmpty(label)
                        ? getString(R.string.account_unknown)
                        : getString(R.string.guardian_account, label));

        findViewById(R.id.volunteer_card_activities).setOnClickListener(
                view -> startActivity(ActivityListActivity.intentFor(this)));
        findViewById(R.id.volunteer_card_realtime).setOnClickListener(
                view -> startActivity(RealtimeActivity.intentFor(this)));

        // 兼任申请与监护人视角都是「兼职身份」才有的东西，按 roles 显隐。
        boolean alsoGuardian = session.roles().contains("GUARDIAN");
        View applyCard = findViewById(R.id.volunteer_card_apply);
        applyCard.setVisibility(alsoGuardian ? View.VISIBLE : View.GONE);
        if (alsoGuardian) {
            applyCard.setOnClickListener(
                    view -> startActivity(VolunteerApplyActivity.intentFor(this)));
        }
        View guardianCard = findViewById(R.id.volunteer_card_guardian);
        guardianCard.setVisibility(alsoGuardian ? View.VISIBLE : View.GONE);
        if (alsoGuardian) {
            guardianCard.setOnClickListener(
                    view -> startActivity(new Intent(this, GuardianActivity.class)));
        }

        findViewById(R.id.volunteer_home_logout).setOnClickListener(view -> {
            session.clearLogin();
            Toast.makeText(this, R.string.guardian_logged_out, Toast.LENGTH_SHORT).show();
            startActivity(LoginActivity.intentFor(this, LoginActivity.ROLE_HINT_VOLUNTEER));
            finish();
        });
    }
}
