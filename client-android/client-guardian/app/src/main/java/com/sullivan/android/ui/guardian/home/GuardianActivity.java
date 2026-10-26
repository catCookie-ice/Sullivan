package com.sullivan.android.ui.guardian.home;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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
import androidx.core.content.ContextCompat;

import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.AlertDtos;
import com.sullivan.android.data.api.dto.QrDtos;
import com.sullivan.android.data.api.dto.RealtimeDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.qr.QrPayload;
import com.sullivan.android.service.call.IncomingCallService;
import com.sullivan.android.ui.common.activity.ActivityListActivity;
import com.sullivan.android.ui.common.realtime.RealtimeActivity;
import com.sullivan.android.ui.common.scan.QrScanActivity;
import com.sullivan.android.ui.common.voice.VoiceMessageActivity;
import com.sullivan.android.ui.guardian.binding.BindingListActivity;
import com.sullivan.android.ui.guardian.binding.CreateProtecteeActivity;
import com.sullivan.android.ui.guardian.call.AlertLocationSummary;
import com.sullivan.android.ui.guardian.call.AlertQuery;
import com.sullivan.android.ui.guardian.call.IncomingCallFilter;
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

    /**
     * 来电轮询间隔。
     *
     * <p>5 秒是"报警 5 秒延迟"的直接对齐：再短没有意义（会话本身要 5 秒后才建），
     * 再长就会让受护人等太久。这个值决定了"最坏情况下多久能知道有人呼救"。
     */
    private static final long CALL_POLL_INTERVAL_MS = 5_000L;

    private SessionStore session;
    private TextView statusText;
    private ActivityResultLauncher<Intent> bindScanner;
    private ActivityResultLauncher<Intent> loginScanner;
    /** 「登录已失效」只弹一次：网络层与各回调可能同时发现同一件事。 */
    private boolean sessionExpiredDialogShown;
    /** 401 发生时本页不在前台：已经发过 Toast，等回到前台再补弹窗。 */
    private boolean pendingSessionExpiredDialog;

    /**
     * 来电轮询。**这是"监护人收不到通知"那个故障的修法。**
     *
     * <p>服务端在受护人报警 5 秒后会建一条 {@code REQUESTED} 会话，但监护人端
     * 原本只在用户**手动点开「实时会话」页**时才拉列表（见 {@code RealtimeActivity}）。
     * 人不去点就永远不知道有人呼救 —— 服务端日志里会话建得好好的，手机上什么都不响。
     *
     * <p><b>为什么是轮询而不是长连接推送。</b>项目里已有 WebSocket（`RealtimeSocket`），
     * 但它必须绑定一个 `sessionId` 才能连 —— 而这里恰恰是"还不知道有哪些会话"
     * 的场景，鸡生蛋问题。要做真推送得让服务端按账号建一条常驻通道，
     * 那是另一个量级的改动（鉴权、心跳、重连、后台存活）。轮询 5 秒一次足够：
     * 报警本身有 5 秒延迟，再加 5 秒轮询最多 10 秒可知，
     * 对这个场景完全够用，且实现上没有任何新依赖。
     *
     * <p>只在本页 {@code onResume}~{@code onPause} 之间跑，用户不在主页时不轮询。
     */
    private final android.os.Handler callPollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());



    private final Runnable callPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollIncomingCalls();
            callPollHandler.postDelayed(this, CALL_POLL_INTERVAL_MS);
        }
    };

    /** Android 13+ 通知权限。拒绝也能用，只是退到后台收不到来电。 */
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    /**
     * 网络层报「登录失效」时的处理。**在 OkHttp 工作线程**，所以必须切回主线程。
     *
     * <p>用 {@code runOnUiThread} 而不是直接弹窗：Android 的 Dialog 只能在主线程操作。
     * 页面已经销毁时 {@code runOnUiThread} 仍会执行，所以回调里还要判一次
     * {@code isFinishing/isDestroyed}（见 {@link #showSessionExpired()}）。
     */
    private final ApiClient.LoginExpiredListener loginExpiredListener =
            () -> runOnUiThread(this::showSessionExpired);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian);

        session = new SessionStore(this);
        if (!session.isLoggedIn()) {
            Toast.makeText(this, R.string.guardian_need_login, Toast.LENGTH_SHORT).show();
            // toRoleEntry 而不是直接进登录页：本页可能正是冷启动的落地页
            // （见 RoleEntryActivity），此时它的正下方还留着那个入口页。
            // 返回键应当回到「选角色」那一屏（用户可以换一个账号的方向），
            // 而不是把一个永远只会自动弹回主页的登录页留在栈里。
            startActivity(LoginActivity.toRoleEntry(this));
            finish();
            return;
        }

        statusText = findViewById(R.id.guardian_status);
        TextView accountText = findViewById(R.id.guardian_account);
        String label = session.accountLabel();
        accountText.setText(getString(R.string.guardian_account,
                TextUtils.isEmpty(label) ? "—" : label));

        // 从这里开始，**任何**请求遇到 401 都会立刻弹「登录已失效」。
        // 放在取 accountText 之后、各 launcher 之前：本页一进来就可能发请求，
        // 订阅晚一步就漏掉。
        ApiClient.setLoginExpiredListener(loginExpiredListener);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    // 拒绝也不影响前台功能：轮询仍会在本页弹出对话框。
                    // 只有"退到后台也能收到"这条依赖它。
                });
        requestNotificationPermissionIfNeeded();
        // 常驻服务：应用退到后台/锁屏时仍然能发现有人在呼叫我。
        // 只在前台轮询是不够的 —— 受护人求助时监护人极可能没打开应用。
        IncomingCallService.start(this);

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
        findViewById(R.id.guardian_card_voice).setOnClickListener(
                view -> startActivity(VoiceMessageActivity.intentFor(this)));

        // 已经是志愿者就不显示「申请兼任志愿者」—— 一个永远只会得到
        // 「已存在待审核/已通过申请」的入口，比没有这个入口更让人困惑。
        View volunteerCard = findViewById(R.id.guardian_card_volunteer_apply);
        volunteerCard.setVisibility(session.roles().contains("VOLUNTEER")
                ? View.GONE : View.VISIBLE);
        findViewById(R.id.guardian_logout).setOnClickListener(view -> {
            // 退出登录必须先停掉常驻服务：它持续用旧令牌发请求只会一直 401，
            // 而且状态栏那条"正在守着"会一直挂着，用户会以为还在被守着。
            IncomingCallService.stop(this);
            session.clearLogin();
            Toast.makeText(this, R.string.guardian_logged_out, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        // 必须解订阅：ApiClient 是进程级单例，它持有 listener 就等于持有本 Activity，
        // 不解会让每次进出主页都泄漏一个已经销毁的界面；
        // 而下次 401 会弹到一个早就没了的页面上。
        ApiClient.setLoginExpiredListener(null);
        super.onDestroy();
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
                        if (handleUnauthorized(error)) {
                            return;
                        }
                        showStatus(getString(R.string.guardian_bind_failed, error.getMessage()),
                                true);
                    }
                });
    }

    /**
     * 401：登录已失效。清掉本地会话，并**用对话框**告诉用户，而不是写进页面底部的状态行。
     *
     * <p><b>为什么必须用对话框。</b>这个方法的出现是修一个真实故障：监护人扫码后
     * 什么都没发生 —— 没有选人列表、受护人端也没登录。根因是监护人端的登录早已失效
     * （令牌过期），而 {@code handleUnauthorized} 只有部分页面写了，本页的扫码回调里
     * **一份都没有**，于是 401 被当成普通错误塞进 {@code statusText}。
     * 那个 TextView 在页面最末尾，用户扫码时视线在屏幕上半部分/对方手机上，
     * 根本不会看到 —— 表现就是"扫完直接结束，什么都没发生"。
     *
     * <p>现在有两条路径进到这里：① 网络层的 {@link ApiClient.LoginExpiredListener}
     * 统一捕获（覆盖**任何**请求）；② 各回调里的显式判断（兜住网络层订阅尚未就绪的
     * 极早期调用）。两者都走 {@link #showSessionExpired()}，且后者做幂等，
     * 不会弹两次。
     *
     * @return 是否已按 401 处理。{@code true} 时调用方**不要**再显示自己的错误文案，
     *         否则会出现「登录已失效」后面紧跟一句「扫码失败：401」的重复提示。
     */
    private boolean handleUnauthorized(@NonNull ApiException error) {
        if (!error.isUnauthorized()) {
            return false;
        }
        // 网络层通常已经清过了；这里再清一次是幂等的，且能兜住"令牌本来就没带上"的情形。
        session.clearLogin();
        showSessionExpired();
        return true;
    }

    /**
     * 弹「登录已失效」并要求重新登录。幂等：已经弹着就不再弹。
     *
     * <p><b>分前台/后台两种处理。</b>网络层的 401 可能发生在**别的页面**上
     * （用户从本页点进了绑定列表，而本页只是 stopped 还没 destroy）。
     * 直接 {@code Dialog.show()} 会弹到一个用户看不见的页面上：
     * 轻则用户回到本页才莫名其妙看到一个弹窗，重则触发 {@code WindowLeaked}。
     *
     * <p>所以：在前台就弹窗；不在前台就先给一条 Toast（Toast 是系统级的，
     * 会显示在用户当前真正在看的那个页面上），并记下来，等本页回到前台再补弹窗。
     * Toast 这一条很关键 —— 它保证**无论 401 从哪个页面冒出来，用户都
     * 立刻知道发生了什么**，不会再出现"点了没反应"。
     */
    private void showSessionExpired() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (sessionExpiredDialogShown) {
            return;
        }
        if (!isResumed()) {
            pendingSessionExpiredDialog = true;
            Toast.makeText(this, R.string.guardian_session_expired_title,
                    Toast.LENGTH_LONG).show();
            return;
        }
        sessionExpiredDialogShown = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.guardian_session_expired_title)
                .setMessage(R.string.guardian_session_expired_message)
                .setCancelable(false)
                .setPositiveButton(R.string.guardian_session_expired_action,
                        (dialog, which) -> {
                            startActivity(LoginActivity.toRoleEntry(this));
                            finish();
                        })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时补弹此前因不在前台而只发了 Toast 的那个提示。
        if (pendingSessionExpiredDialog && !sessionExpiredDialogShown) {
            pendingSessionExpiredDialog = false;
            showSessionExpired();
        }
        // 登录态还在才轮询来电：401 时轮询只会白刷日志。
        if (session.isLoggedIn() && !sessionExpiredDialogShown) {
            callPollHandler.removeCallbacks(callPollRunnable);
            // 立刻查一次，不等第一个 5 秒。
            callPollHandler.post(callPollRunnable);
        }
    }

    @Override
    protected void onPause() {
        callPollHandler.removeCallbacks(callPollRunnable);
        super.onPause();
    }

    /**
     * 申请通知权限（Android 13+）。
     *
     * <p>没有它，前台服务的通知**完全不显示** —— 而前台服务正是"退到后台也能收到
     * 来电"的基础。拒绝不阻断其它功能：应用仍在前台时靠
     * {@link #pollIncomingCalls()} 弹窗提醒，只是退到后台就收不到了。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * 拉一次会话列表，把「正在呼叫我」的挑出来提醒用户。
     *
     * <p>失败不提示：这是后台轮询，网络抖动时弹错误只会骚扰人，
     * 下一轮自然会重试。401 由网络层的统一出口处理。
     */
    private void pollIncomingCalls() {
        ApiClient.get(this).listSessions()
                .enqueue(new ResultCallback<List<RealtimeDtos.SessionResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<RealtimeDtos.SessionResponse> body) {
                        java.util.List<RealtimeDtos.SessionResponse> pending =
                                IncomingCallFilter.pending(body, session.principalId());
                        if (pending.isEmpty()) {
                            // 清理交给常驻服务统一做：两条路径都清会互相干扰 ——
                            // Activity 刚清掉，服务那边立刻又把同一条记录加回来，
                            // 反而让"是否提醒过"的判断变得不可预测。
                            return;
                        }
                        RealtimeDtos.SessionResponse latest = pending.get(0);
                        if (!IncomingCallService.claimAlert(latest.id)) {
                            return;
                        }
                        // 收到会话之后**再去拿报警**，因为位置在报警上而不在会话上。
                        // 两次请求而不是一次：会话接口是既有的、报警接口是这次才给
                        // 监护人端加的，串起来比改服务端把它们合并进会话响应要小得多。
                        loadAlertFor(latest);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 后台轮询，静默失败。
                    }
                });
    }

    /**
     * 取这条会话对应的报警，把位置一起展示出来。
     *
     * <p><b>为什么必须先有会话再查报警。</b>会话响应里没有 `alertId`，
     * 两者的关联是 {@code alert_delivery.session_id → realtime_session.id}。
     * 客户端反查的办法是：拉「投递给我的报警」列表，找 {@code deliveries}
     * 里 sessionId 与当前会话相同的那一条。列表规模很小（监护人的报警记录），
     * 比再加一个服务端接口划算。
     *
     * <p>拿不到报警（网络抖动、或确实没有对应报警）时**照样提示来电**，
     * 只是没有位置。这是刻意的：位置是增强，呼叫才是主功能。
     */
    private void loadAlertFor(@NonNull RealtimeDtos.SessionResponse call) {
        ApiClient.get(this).listAlerts()
                .enqueue(new ResultCallback<List<AlertDtos.AlertResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<AlertDtos.AlertResponse> body) {
                        AlertDtos.AlertResponse matched = AlertQuery.alertForSession(body, call.id);
                        showIncomingCall(call, matched);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        showIncomingCall(call, null);
                    }
                });
    }



    /**
     * 提示有人来电，并**带上受护人的位置**。
     *
     * <p>用**不可取消的对话框 + 震动/提示音**而不是 Toast：受护人端的报警是紧急事件，
     * 而监护人很可能没在看屏幕。一个几秒就消失的 Toast 会让他彻底错过。
     *
     * <p>不用全屏 Activity 抢焦点：那需要 {@code SYSTEM_ALERT_WINDOW} 或前台服务，
     * 权限成本与"应用正在前台"这个前提都不成立。
     *
     * <p><b>位置放在对话框里而不是让他点进去自己找。</b>监护人这一刻要判断的
     * 是"往哪儿去"，而地点信息一旦需要多点两下才看到，很多人就不会看了。
     * 没有位置时也要说清楚"没有"——沉默会让人以为位置正在加载。
     */
    private void showIncomingCall(@NonNull RealtimeDtos.SessionResponse call,
                                  @Nullable AlertDtos.AlertResponse alert) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        AlertDtos.AlertLocation location = (alert == null) ? null : alert.location;
        String locationDetail = AlertLocationSummary.detail(location);

        String message = getString(R.string.guardian_incoming_call_message);
        if (locationDetail != null) {
            String headline = AlertLocationSummary.headline(location);
            message = message + "\n\n" + (headline == null ? "" : headline + "\n")
                    + locationDetail;
        } else {
            // 明确说出"没有位置"，而不是留白。留白会被理解成"还在加载"。
            message = message + "\n\n" + getString(R.string.guardian_incoming_call_no_location);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.guardian_incoming_call_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(R.string.guardian_incoming_call_open,
                        (dialog, which) -> startActivity(
                                RealtimeActivity.intentFor(this)))
                .setNegativeButton(R.string.guardian_incoming_call_ignore, null)
                .show();
    }

    /** 本页是否处于「用户正看着」的状态。用于决定弹窗还是 Toast。 */
    private boolean isResumed() {
        return getLifecycle().getCurrentState().isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED);
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
                        if (handleUnauthorized(error)) {
                            return;
                        }
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
                        if (handleUnauthorized(error)) {
                            return;
                        }
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
