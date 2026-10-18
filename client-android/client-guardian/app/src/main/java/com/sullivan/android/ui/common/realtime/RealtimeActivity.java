package com.sullivan.android.ui.common.realtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.RealtimeDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.service.websocket.RealtimeSocket;
import com.sullivan.android.ui.common.components.ServerTime;
import com.sullivan.android.ui.common.components.StateView;
import com.sullivan.android.ui.common.components.StatusLabels;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;

/**
 * 实时会话：列表 + 发起/接受/拒绝/结束 + 连接后的消息中继。
 *
 * <p>对应 web 的 {@code guardian/RealtimeDemo.vue}，但有一处刻意的差别：
 * <b>不要求用户手填「对方 UID」</b>。web 端让用户填，因为那一页是调试页；
 * 手机上的问题永远是「和谁说话」，而对方在会话记录里本来就有 ——
 * 再让用户从别处抄一遍账号 ID，只会多一个抄错的入口。
 *
 * <p><b>为什么列表是 LinearLayout 而不是 RecyclerView</b>：一个人同时进行的会话
 * 是个位数，而且每一行的按钮组合都不同（见 {@link #bindRow}）。为它写一套
 * Adapter + ViewHolder 换不来任何东西，反而要多写一个和 row_session 一一对应的类。
 *
 * <p><b>长连接不重连</b>：见 {@link RealtimeSocket} 的类注释。断开就是断开，
 * 界面上说清楚，用户重新点「连接」。
 *
 * <p><b>消息不留存</b>：中继是纯转发，服务端不落库。所以断开时把日志面板整个收起来，
 * 而不是留着 —— 留着会让人以为「这些还能翻回来」。
 */
public class RealtimeActivity extends AppCompatActivity {

    /** 日志最多留多少行。中继不限速，聊久了不加这个上界就是一个无界增长的 String。 */
    private static final int MAX_LOG_LINES = 200;

    private static final String STATUS_REQUESTED = "REQUESTED";
    private static final String STATUS_ACTIVE = "ACTIVE";

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, RealtimeActivity.class);
    }

    private SessionStore session;
    private String myUid;

    private LinearLayout listContainer;
    private StateView stateView;
    private TextView statusText;
    private EditText targetInput;
    private EditText messageInput;
    private Button createButton;
    private Button refreshButton;

    private LinearLayout chatCard;
    private TextView chatTitle;
    private TextView logView;
    private ScrollView logScroll;
    private ScrollView pageScroll;
    private Button sendButton;
    private Button pingButton;
    private Button disconnectButton;

    private RealtimeSocket socket;

    /** 当前这条连接对应的会话；null = 没连。 */
    @Nullable
    private RealtimeDtos.SessionResponse connected;

    /** 日志行。带颜色，所以不能只存一个 String 直接往上拼。 */
    private final List<LogLine> logLines = new ArrayList<>();

    @Nullable
    private Call<?> pendingCall;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_realtime);

        session = new SessionStore(this);
        myUid = session.principalId();
        if (!session.isLoggedIn() || TextUtils.isEmpty(myUid)) {
            // 没有账号 ID 就没法判断「这条会话里我是哪一方」，按钮该出哪几个全是错的。
            finish();
            return;
        }

        listContainer = findViewById(R.id.session_list);
        stateView = findViewById(R.id.session_state);
        statusText = findViewById(R.id.session_status_text);
        targetInput = findViewById(R.id.session_target);
        messageInput = findViewById(R.id.session_message);
        createButton = findViewById(R.id.session_create);
        refreshButton = findViewById(R.id.session_refresh);

        chatCard = findViewById(R.id.session_chat);
        chatTitle = findViewById(R.id.session_chat_title);
        logView = findViewById(R.id.session_log);
        logScroll = findViewById(R.id.session_log_scroll);
        pageScroll = findViewById(R.id.session_page);
        sendButton = findViewById(R.id.session_send);
        pingButton = findViewById(R.id.session_ping);
        disconnectButton = findViewById(R.id.session_disconnect);

        socket = new RealtimeSocket();

        createButton.setOnClickListener(view -> createSession());
        refreshButton.setOnClickListener(view -> load());
        sendButton.setOnClickListener(view -> sendMessage());
        pingButton.setOnClickListener(view -> sendPing());
        disconnectButton.setOnClickListener(view -> {
            disconnect();
            toast(getString(R.string.session_disconnected));
        });

        load();
    }

    @Override
    protected void onStop() {
        // 退到后台就断开：连接是「当面用一下」的东西，留着它会得到一个
        // 用户看不见、服务端还占着的心跳。回来时重新点「连接」即可。
        if (connected != null) {
            disconnect();
        } else {
            socket.close();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (pendingCall != null) {
            pendingCall.cancel();
            pendingCall = null;
        }
        socket.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private void load() {
        if (pendingCall != null) {
            return;
        }
        listContainer.setVisibility(View.GONE);
        refreshButton.setEnabled(false);
        stateView.showLoading(getString(R.string.state_loading));
        Call<List<RealtimeDtos.SessionResponse>> call = ApiClient.get(this).listSessions();
        pendingCall = call;
        call.enqueue(new ResultCallback<List<RealtimeDtos.SessionResponse>>() {
            @Override
            public void onSuccess(@Nullable List<RealtimeDtos.SessionResponse> body) {
                pendingCall = null;
                render(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                stateView.showError(error.getMessage(), RealtimeActivity.this::load);
            }
        });
    }

    private void render(@Nullable List<RealtimeDtos.SessionResponse> items) {
        refreshButton.setEnabled(true);
        listContainer.removeAllViews();
        if (items == null || items.isEmpty()) {
            stateView.showEmpty(getString(R.string.session_empty));
            return;
        }
        stateView.hide();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RealtimeDtos.SessionResponse item : items) {
            View row = inflater.inflate(R.layout.row_session, listContainer, false);
            bindRow(row, item);
            listContainer.addView(row);
        }
        listContainer.setVisibility(View.VISIBLE);
    }

    private void bindRow(@NonNull View row, @NonNull RealtimeDtos.SessionResponse item) {
        boolean mine = myUid.equals(item.initiatorUid);
        String counterpart = counterpartOf(item);

        StatusLabels.Label status = StatusLabels.session(item.status);
        TextView statusView = row.findViewById(R.id.session_status);
        statusView.setText(status.text);
        statusView.setBackgroundResource(StatusLabels.chipBackground(status.tone));
        statusView.setTextColor(getColor(StatusLabels.textColor(status.tone)));

        ((TextView) row.findViewById(R.id.session_role)).setText(
                mine ? R.string.session_row_role_mine : R.string.session_row_role_theirs);
        ((TextView) row.findViewById(R.id.session_counterpart)).setText(
                TextUtils.isEmpty(counterpart)
                        ? getString(R.string.session_row_counterpart_unknown)
                        : getString(R.string.session_row_counterpart, counterpart));

        TextView meta = row.findViewById(R.id.session_meta);
        if (STATUS_REQUESTED.equalsIgnoreCase(item.status)) {
            // 待接受时，比会话 ID 有用的是「现在卡在谁那里」。
            meta.setText(mine ? R.string.session_row_waiting_mine
                    : R.string.session_row_waiting_theirs);
        } else {
            meta.setText(getString(R.string.session_row_meta,
                    item.id == null ? "—" : item.id, ServerTime.shortForm(item.createdAt)));
        }

        // 三个动作按钮在布局里都已声明，这里只决定谁露面。
        // 与 web 的规则一致：REQUESTED 只有**接收方**能接/拒（发起方没有撤回接口），
        // ACTIVE 双方都能连接与结束，终态没有任何动作。
        boolean requestedToMe = STATUS_REQUESTED.equalsIgnoreCase(item.status) && !mine;
        boolean active = STATUS_ACTIVE.equalsIgnoreCase(item.status);
        bindAction(row, R.id.session_accept, requestedToMe,
                () -> confirm(R.string.session_confirm_accept, () -> accept(item)));
        bindAction(row, R.id.session_reject, requestedToMe,
                () -> confirm(R.string.session_confirm_reject, () -> reject(item)));
        bindAction(row, R.id.session_connect, active, () -> connect(item));
        bindAction(row, R.id.session_end, active,
                () -> confirm(R.string.session_confirm_end, () -> end(item)));
    }

    private void bindAction(@NonNull View row, int buttonId, boolean visible,
                            @NonNull Runnable action) {
        Button button = row.findViewById(buttonId);
        if (!visible) {
            // 用 GONE 而不是 INVISIBLE：一行里可能只剩一个按钮，
            // 空出来的位置应该让给它，而不是留一个洞。
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(view -> action.run());
    }

    /** 会话里的另一方。发起方看到的对方是 targetUid，接收方看到的对方是 initiatorUid。 */
    @NonNull
    private String counterpartOf(@NonNull RealtimeDtos.SessionResponse item) {
        String other = myUid.equals(item.initiatorUid) ? item.targetUid : item.initiatorUid;
        return other == null ? "" : other;
    }

    private void confirm(int messageRes, @NonNull Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setMessage(messageRes)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> onConfirm.run())
                .show();
    }

    // ------------------------------------------------------------------
    // 发起与状态迁移
    // ------------------------------------------------------------------

    private void createSession() {
        String target = text(targetInput);
        if (TextUtils.isEmpty(target)) {
            showStatus(getString(R.string.session_create_need_id), true);
            return;
        }
        if (pendingCall != null) {
            return;
        }
        createButton.setEnabled(false);
        showStatus(getString(R.string.state_loading), false);
        Call<RealtimeDtos.SessionResponse> call = ApiClient.get(this)
                .createSession(new RealtimeDtos.SessionCreateRequest(target));
        pendingCall = call;
        call.enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
            @Override
            public void onSuccess(@Nullable RealtimeDtos.SessionResponse body) {
                pendingCall = null;
                createButton.setEnabled(true);
                // 清空输入框：这条请求已经变成列表里的一行了，留着它只会让人以为没发出去。
                targetInput.setText("");
                showStatus(getString(R.string.session_created), false);
                load();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                createButton.setEnabled(true);
                handleUnauthorized(error);
                showStatus(getString(R.string.session_create_failed, error.getMessage()), true);
            }
        });
    }

    private void accept(RealtimeDtos.SessionResponse item) {
        transition(ApiClient.get(this).acceptSession(item.id), R.string.session_done_accept);
    }

    private void reject(RealtimeDtos.SessionResponse item) {
        transition(ApiClient.get(this).rejectSession(item.id), R.string.session_done_reject);
    }

    private void end(RealtimeDtos.SessionResponse item) {
        if (isConnected(item)) {
            // 先断开再结束：反过来客户端会收到一条服务端的 error 帧，
            // 在日志里留下一条其实一切正常的「错误」。
            disconnect();
        }
        transition(ApiClient.get(this).endSession(item.id), R.string.session_done_end);
    }

    /** accept / reject / end 三个接口的请求与响应形状相同，差异只有那句提示。 */
    private void transition(@NonNull Call<RealtimeDtos.SessionResponse> call, int doneRes) {
        if (pendingCall != null) {
            return;
        }
        pendingCall = call;
        call.enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
            @Override
            public void onSuccess(@Nullable RealtimeDtos.SessionResponse body) {
                pendingCall = null;
                showStatus(getString(doneRes), false);
                load();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                showStatus(getString(R.string.session_action_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 连接与消息
    // ------------------------------------------------------------------

    private void connect(@NonNull RealtimeDtos.SessionResponse item) {
        if (pendingCall != null) {
            return;
        }
        releaseConnected();
        connected = item;
        String counterpart = counterpartOf(item);
        chatTitle.setText(getString(R.string.session_chat_title,
                TextUtils.isEmpty(counterpart) ? "—" : counterpart));
        logLines.clear();
        renderLog();
        chatCard.setVisibility(View.VISIBLE);
        messageInput.setText("");
        // 会话面板在页面下方，连接成功后滚过去 —— 不滚的话，用户在页面上半部分
        // 看到的是「什么都没发生」，而实际上连接已经建立了。
        chatCard.post(() -> pageScroll.smoothScrollTo(0, chatCard.getTop()));
        socket.connect(session.baseUrl(), tokenOrEmpty(), item.id, myUid, listener);
    }

    private final RealtimeSocket.Listener listener = new RealtimeSocket.Listener() {
        @Override
        public void onOpen() {
            appendLog(getString(R.string.session_log_connected), false);
        }

        @Override
        public void onEnvelope(@NonNull RealtimeDtos.Envelope envelope) {
            String type = envelope.type == null ? "" : envelope.type;
            switch (type) {
                case "relay" -> {
                    boolean mine = myUid.equals(envelope.fromUid);
                    appendLog(getString(mine ? R.string.session_log_mine : R.string.session_log_peer,
                            payloadText(envelope.payload)), false);
                }
                case "session.joined" -> appendLog(getString(R.string.session_log_joined), false);
                case "leave" -> appendLog(getString(R.string.session_log_left), false);
                case "ping" -> appendLog(getString(R.string.session_log_ping), false);
                case "pong" -> appendLog(getString(R.string.session_log_pong), false);
                // 不认识的类型照原样写出来：以后服务端加了新的控制帧，
                // 这里是唯一能看见它的地方，静默丢掉等于调试时两眼一抹黑。
                default -> appendLog(getString(R.string.session_log_other, type), false);
            }
        }

        @Override
        public void onError(@NonNull String message) {
            appendLog(getString(R.string.session_log_error, message), true);
        }

        @Override
        public void onClosed() {
            appendLog(getString(R.string.session_log_closed), false);
        }
    };

    private void sendMessage() {
        String text = text(messageInput);
        if (TextUtils.isEmpty(text)) {
            toast(getString(R.string.session_message_need_text));
            return;
        }
        if (connected == null || !socket.sendRelay(counterpartOf(connected), text)) {
            appendLog(getString(R.string.session_log_error,
                    getString(R.string.session_not_connected)), true);
            return;
        }
        appendLog(getString(R.string.session_log_mine, text), false);
        messageInput.setText("");
    }

    private void sendPing() {
        if (connected == null || !socket.ping()) {
            appendLog(getString(R.string.session_log_error,
                    getString(R.string.session_not_connected)), true);
        }
    }

    /** 收起来：断开连接并把会话面板整个隐藏（消息不留存，留着反而误导）。 */
    private void disconnect() {
        socket.leave();
        releaseConnected();
    }

    private void releaseConnected() {
        connected = null;
        chatCard.setVisibility(View.GONE);
    }

    private boolean isConnected(@Nullable RealtimeDtos.SessionResponse item) {
        return connected != null && item.id != null && item.id.equals(connected.id);
    }

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    /**
     * 日志行。
     *
     * <p>不用带颜色的 Spannable 逐行 setText，而是存下「文字 + 是否是错误」，
     * 每次重画整块 —— 最多 200 行，重画的代价远小于维护一个增量拼接的算法，
     * 而且增量拼接最容易出的错就是颜色跨行残留。
     */
    private static final class LogLine {
        final String text;
        final boolean error;

        LogLine(@NonNull String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    private void appendLog(@NonNull String line, boolean error) {
        logLines.add(new LogLine(line, error));
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }
        renderLog();
    }

    private void renderLog() {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (LogLine line : logLines) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            int start = builder.length();
            builder.append(line.text);
            builder.setSpan(new ForegroundColorSpan(getColor(
                            line.error ? R.color.an_log_error : R.color.an_log_text)),
                    start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        logView.setText(builder);
        // 滚到底：新消息在末尾，不滚过去用户会以为没收到。
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 从 payload 里取一句话；取不出来时给一句中文兜底，不要把原始 JSON 甩到界面上。 */
    @NonNull
    private String payloadText(@Nullable JsonElement payload) {
        if (payload == null || payload.isJsonNull()) {
            return getString(R.string.session_log_other, "空消息");
        }
        try {
            if (payload.isJsonPrimitive()) {
                return payload.getAsString();
            }
            JsonObject object = payload.getAsJsonObject();
            if (object.has("text")) {
                return object.get("text").getAsString();
            }
        } catch (RuntimeException ignored) {
            // 落到兜底文案。
        }
        return getString(R.string.session_log_other, "无法显示的负载");
    }

    // ------------------------------------------------------------------

    private void showStatus(@NonNull String message, boolean isError) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    /**
     * 401 时只清本地会话，**不**跳转登录页。
     *
     * <p>跳转会连带清掉这一页已经拉到的列表，而用户此时多半只是想再看一眼
     * 刚才那条会话；留在原地，他至少还能把已经拿到的内容看完，
     * 再自己决定去登录。
     */
    private void handleUnauthorized(@NonNull ApiException error) {
        if (error.isUnauthorized()) {
            session.clearLogin();
        }
    }

    @NonNull
    private String tokenOrEmpty() {
        String token = session.accessToken();
        return token == null ? "" : token;
    }

    @NonNull
    private static String text(@Nullable EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void toast(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
