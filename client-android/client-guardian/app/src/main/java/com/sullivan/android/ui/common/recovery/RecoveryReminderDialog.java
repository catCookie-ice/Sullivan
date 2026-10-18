package com.sullivan.android.ui.common.recovery;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.RecoveryDtos;

import retrofit2.Call;

/**
 * 登录后的「你还没设找回方式」提醒。
 *
 * <p><b>为什么值得为它做一个对话框</b>：后端没有别的找回途径 —— 账号一旦丢了密码就是永久丢失，
 * 受护人甚至没法自己重新注册（受护人账号只能由监护人代建）。所以这不是一个「营销式的完善资料」弹窗，
 * 而是把一条不可逆的后果摊在用户面前。
 *
 * <p><b>三个分支都要落库</b>：
 * <ul>
 *   <li>「下次再说」→ {@code POST .../remind-later}：本次登录不再提，下次登录还提；</li>
 *   <li>「不再提醒」→ {@code POST .../dismiss}：服务端记住，以后都不提；</li>
 *   <li>「现在设置」→ 展开表单 → {@code PUT .../recovery}。</li>
 * </ul>
 * 只把弹窗关掉而不调接口，会让它下一次登录又弹出来 —— 用户会觉得「点了没反应」，
 * 而这正是这个弹窗最不该给人的印象。
 *
 * <p><b>网络失败不阻断流程</b>：用户已经做出了选择，界面要立刻往下走。
 * 失败只意味着「服务端没记住这次选择」，下次登录再问一遍 —— 这比卡在一个转圈的对话框里好得多。
 * 所以 {@code remind-later} / {@code dismiss} 的结果只记日志；只有「现在设置」的失败会留在表单里
 * 让用户重试，因为那是他主动要做的事，失败了不说一声等于骗他存好了。
 */
public final class RecoveryReminderDialog {

    private static final String TAG = "RecoveryReminder";

    private RecoveryReminderDialog() {
    }

    /**
     * 弹出提醒。调用方负责先判断 {@code TokenResponse.recoveryReminder}
     * 或 {@code GET auth/me/recovery} 的 {@code remindRequired}，这里不做网络判断 ——
     * 判断点只有一处（登录后），多一处就会多一个「同一个账号有时弹有时不弹」的可能。
     *
     * @param onDone 用户处理完（选了任一项，或关掉了表单）后调用。**一定会被调用一次**，
     *               调用方可以把它当作「继续前往主页」的信号。
     */
    public static void show(@NonNull Activity activity, @NonNull Runnable onDone) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            onDone.run();
            return;
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.recovery_dialog_title)
                .setMessage(R.string.recovery_dialog_message)
                // 正/负/中性三键的默认顺序在 Material 下就是 左中性 / 中否定 / 右肯定，
                // 「现在设置」放在右（肯定）位，「下次再说」居中，「不再提醒」最左 ——
                // 最左边那个是唯一不可逆的选择，不该放在拇指最顺的位置上。
                .setPositiveButton(R.string.recovery_dialog_action_set,
                        (dialog, which) -> showSetForm(activity, onDone))
                .setNegativeButton(R.string.recovery_dialog_action_later, (dialog, which) -> {
                    dismissReminder(activity, false);
                    onDone.run();
                })
                .setNeutralButton(R.string.recovery_dialog_action_dismiss, (dialog, which) -> {
                    dismissReminder(activity, true);
                    onDone.run();
                })
                .setOnCancelListener(dialog -> onDone.run())
                .setCancelable(true)
                .show();
    }

    /** 「下次再说」与「不再提醒」只差一个端点，合并成一个方法免得两处写歪。 */
    private static void dismissReminder(@NonNull Activity activity, boolean forever) {
        // 两个端点返回同一个 ActionResponse 类型，所以这里用具体类型而不是通配符 Call<?>：
        // Call<?> 的 enqueue 需要 Callback<? extends CAP>，静态回调类对不上，编译期就失败。
        Call<RecoveryDtos.ActionResponse> call = forever
                ? ApiClient.get(activity).recoveryDismiss()
                : ApiClient.get(activity).recoveryRemindLater();
        call.enqueue(new SilentCallback());
    }

    // ------------------------------------------------------------------
    // 设置表单
    // ------------------------------------------------------------------

    private static void showSetForm(@NonNull Activity activity, @NonNull Runnable onDone) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            onDone.run();
            return;
        }
        View content = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_recovery_set, null, false);
        EditText phoneInput = content.findViewById(R.id.recovery_set_phone);
        EditText emailInput = content.findViewById(R.id.recovery_set_email);
        TextView status = content.findViewById(R.id.recovery_set_status);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.recovery_set_title)
                .setView(content)
                .setPositiveButton(R.string.action_ok, null)
                .setNegativeButton(R.string.action_cancel,
                        (d, which) -> onDone.run())
                .setOnCancelListener(d -> onDone.run())
                .create();
        // 用 setOnShowListener 拿默认按钮的点击权：默认的 positive 监听会无条件 dismiss，
        // 而这里在手机号和邮箱都为空时要留在表单里提示，不能关掉。
        dialog.setOnShowListener(showing -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> submitForm(activity, dialog, phoneInput, emailInput,
                        status, onDone)));
        dialog.show();
    }

    private static void submitForm(@NonNull Activity activity, @NonNull AlertDialog dialog,
                                   @NonNull EditText phoneInput, @NonNull EditText emailInput,
                                   @NonNull TextView status, @NonNull Runnable onDone) {
        String phone = textOf(phoneInput);
        String email = textOf(emailInput);
        if (TextUtils.isEmpty(phone) && TextUtils.isEmpty(email)) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.recovery_set_need_one);
            return;
        }

        status.setVisibility(View.VISIBLE);
        status.setTextColor(activity.getColor(R.color.an_ink_3));
        status.setText(R.string.recovery_set_in_progress);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        Call<RecoveryDtos.ActionResponse> call = ApiClient.get(activity)
                .setRecovery(new RecoveryDtos.SetRequest(
                        TextUtils.isEmpty(phone) ? null : phone,
                        TextUtils.isEmpty(email) ? null : email));
        call.enqueue(new ResultCallback<RecoveryDtos.ActionResponse>() {
            @Override
            public void onSuccess(@Nullable RecoveryDtos.ActionResponse body) {
                Toast.makeText(activity, R.string.recovery_set_succeeded, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                onDone.run();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                status.setTextColor(activity.getColor(R.color.an_terracotta));
                status.setText(activity.getString(
                        R.string.recovery_set_failed, error.getMessage()));
            }
        });
    }

    @NonNull
    private static String textOf(@Nullable EditText view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }

    // ------------------------------------------------------------------
    // 静默回调
    // ------------------------------------------------------------------

    /** 两个「稍后再提」的端点返回值没有意义，成功也不提示 —— 用户已经看到对话框关掉了。 */
    private static final class SilentCallback extends ResultCallback<RecoveryDtos.ActionResponse> {
        @Override
        public void onSuccess(@Nullable RecoveryDtos.ActionResponse body) {
        }

        @Override
        public void onError(@NonNull ApiException error) {
            Log.w(TAG, "记录提醒选择失败：" + error.getMessage());
        }
    }
}
