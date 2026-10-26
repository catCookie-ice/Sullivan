package com.sullivan.protectee.ui.common.realtime;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sullivan.protectee.R;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.BindingDtos;
import com.sullivan.protectee.data.api.dto.RealtimeDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.service.websocket.RealtimeSocket;
import com.sullivan.protectee.service.webrtc.RealtimeCall;
import com.sullivan.protectee.ui.common.components.ServerTime;
import com.sullivan.protectee.ui.common.components.StateView;
import com.sullivan.protectee.ui.common.components.StatusLabels;

import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;

/**
 * 实时会话：选人发起（语音 / 视频 / 查看受护人摄像头）、接听、挂断、文字中继。
 *
 * <p><b>这一页不出现 uid</b>。发起会话时选的是已绑定的人，列表里显示的是
 * 登录名或显示名；uid 只在提交那一刻用作参数。用户既不知道自己的 uid，
 * 也念不出别人的 —— 把它摆到界面上，等于要求用户记住一个不该存在的概念
 * （00 §7 第 11 条）。
 *
 * <p><b>接听只有一次点击</b>：接收方点「接听」就地进入会话，不再有第二道确认。
 * 会话本来就是「对方按了才响」的东西，再问一遍「你确定要接吗」只是多一次误触
 * 的机会。
 *
 * <p><b>发起方不会无限等待</b>：60 秒没人接就显示「对方未接听」并停止轮询。
 * 一直转圈的界面会被用户理解成「还在响」，而实际上什么都不会发生了。
 *
 * <p><b>为什么列表是 LinearLayout 而不是 RecyclerView</b>：一个人同时进行的会话
 * 是个位数，而且每一行的按钮组合都不同（见 {@link #bindRow}）。为它写一套
 * Adapter + ViewHolder 换不来任何东西。
 */
public class RealtimeActivity extends AppCompatActivity {

    /** 日志最多留多少行。中继不限速，聊久了不加这个上界就是一个无界增长的 String。 */
    private static final int MAX_LOG_LINES = 200;

    /** 呼叫超时。超过它就不再等，也不再轮询。 */
    private static final long RING_TIMEOUT_MS = 60_000L;

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
    private Spinner targetSpinner;
    private TextView targetEmpty;
    private RadioGroup typeGroup;
    private EditText messageInput;
    private Button createButton;
    private Button refreshButton;

    private LinearLayout chatCard;
    private TextView chatTitle;
    private TextView callStateView;
    private org.webrtc.SurfaceViewRenderer remoteVideo;
    private TextView logView;
    private ScrollView logScroll;
    private ScrollView pageScroll;
    private Button sendButton;
    private Button pingButton;
    private Button disconnectButton;

    private RealtimeSocket socket;
    @Nullable
    private RealtimeCall call;

    /** 当前这条连接对应的会话；null = 没连。 */
    @Nullable
    private RealtimeDtos.SessionResponse connected;

    /** 已绑定的对方（用于选人发起）。{@code label} 是给人看的，{@code uid} 是提交用的。 */
    private final List<Contact> contacts = new ArrayList<>();

    /** 我发起的、还在等对方接听的那条会话。超时后置空。 */
    @Nullable
    private String ringingSessionId;

    /** 日志行。带颜色，所以不能只存一个 String 直接往上拼。 */
    private final List<LogLine> logLines = new ArrayList<>();

    @Nullable
    private Call<?> pendingCall;

    private final android.os.Handler ringHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 选人下拉的一个候选项。 */
    private static final class Contact {
        final String uid;
        final String label;

        Contact(String uid, String label) {
            this.uid = uid;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

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
        targetSpinner = findViewById(R.id.session_target);
        targetEmpty = findViewById(R.id.session_target_empty);
        typeGroup = findViewById(R.id.session_types);
        messageInput = findViewById(R.id.session_message);
        createButton = findViewById(R.id.session_create);
        refreshButton = findViewById(R.id.session_refresh);

        chatCard = findViewById(R.id.session_chat);
        chatTitle = findViewById(R.id.session_chat_title);
        callStateView = findViewById(R.id.session_call_state);
        remoteVideo = findViewById(R.id.session_remote_video);
        logView = findViewById(R.id.session_log);
        logScroll = findViewById(R.id.session_log_scroll);
        pageScroll = findViewById(R.id.session_page);
        sendButton = findViewById(R.id.session_send);
        pingButton = findViewById(R.id.session_ping);
        disconnectButton = findViewById(R.id.session_disconnect);

        socket = new RealtimeSocket();
        RealtimeCall.attachRenderer(remoteVideo);

        // 默认语音：三种形态里最省权限、最不容易失败的一种。
        // 默认给视频会让没有摄像头权限的用户第一次点下去就撞在权限弹窗上。
        typeGroup.check(R.id.session_type_voice);

        createButton.setOnClickListener(view -> createSession());
        refreshButton.setOnClickListener(view -> load());
        sendButton.setOnClickListener(view -> sendMessage());
        pingButton.setOnClickListener(view -> sendPing());
        disconnectButton.setOnClickListener(view -> {
            hangupCurrent();
            toast(getString(R.string.session_disconnected));
        });

        load();
        loadContacts();
    }

    @Override
    protected void onStop() {
        // 退到后台就断开：连接是「当面用一下」的东西，留着它会得到一个
        // 用户看不见、服务端还占着的心跳，而且摄像头还开着。
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
        ringHandler.removeCallbacksAndMessages(null);
        closeCall();
        socket.close();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 联系人（选人）
    // ------------------------------------------------------------------

    /**
     * 拉已生效的绑定，把对方放进下拉里。
     *
     * <p>为什么不在这一页让人手填登录名：会话的前提就是「双方已绑定」，
     * 那么可选的对象本来就只有那几个。让人手填等于允许输入一个必然被服务端
     * 拒绝的字符串。
     */
    private void loadContacts() {
        ApiClient.get(this).listBindings()
                .enqueue(new ResultCallback<List<BindingDtos.BindingResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<BindingDtos.BindingResponse> body) {
                        contacts.clear();
                        if (body != null) {
                            for (BindingDtos.BindingResponse binding : body) {
                                Contact contact = contactOf(binding);
                                if (contact != null) {
                                    contacts.add(contact);
                                }
                            }
                        }
                        renderContacts();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        handleUnauthorized(error);
                        // 联系人拉不到不影响看已有会话，所以不弹错误页，
                        // 只让下方的「没有可联系的监护人」提示接管。
                        renderContacts();
                    }
                });
    }

    @Nullable
    private Contact contactOf(@NonNull BindingDtos.BindingResponse binding) {
        if (!"ACTIVE".equalsIgnoreCase(binding.status)) {
            // 只有已生效的绑定能通话。把待确认的也列出来，用户选了必然失败。
            return null;
        }
        boolean iAmGuardian = myUid.equals(binding.guardianId);
        String otherUid = iAmGuardian ? binding.protecteeId : binding.guardianId;
        if (TextUtils.isEmpty(otherUid)) {
            return null;
        }
        String name = iAmGuardian ? binding.protecteeName : binding.guardianName;
        String loginName = iAmGuardian ? binding.protecteeLoginName : binding.guardianLoginName;
        // 显示名优先，其次登录名。两个都没有就显示「已绑定的对方」而不是 uid。
        String label = !TextUtils.isEmpty(name) ? name
                : !TextUtils.isEmpty(loginName) ? loginName
                : getString(R.string.session_contact_unnamed);
        return new Contact(otherUid, label);
    }

    private void renderContacts() {
        boolean empty = contacts.isEmpty();
        targetEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        targetSpinner.setVisibility(empty ? View.GONE : View.VISIBLE);
        createButton.setEnabled(!empty);
        if (empty) {
            return;
        }
        ArrayAdapter<Contact> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, contacts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetSpinner.setAdapter(adapter);
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
                stateView.showError(error.getMessage(), () -> load());
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

        StatusLabels.Label status = StatusLabels.session(item.status);
        TextView statusView = row.findViewById(R.id.session_status);
        statusView.setText(status.text);
        statusView.setBackgroundResource(StatusLabels.chipBackground(status.tone));
        statusView.setTextColor(getColor(StatusLabels.textColor(status.tone)));

        ((TextView) row.findViewById(R.id.session_role)).setText(
                mine ? R.string.session_row_role_mine : R.string.session_row_role_theirs);
        ((TextView) row.findViewById(R.id.session_counterpart)).setText(
                getString(R.string.session_row_counterpart, counterpartLabel(item)));

        TextView meta = row.findViewById(R.id.session_meta);
        String typeLabel = typeLabelOf(item.sessionType);
        if (STATUS_REQUESTED.equalsIgnoreCase(item.status)) {
            // 待接受时，比会话 ID 有用的是「现在卡在谁那里」。
            meta.setText(getString(R.string.session_row_waiting_type,
                    typeLabel, mine ? getString(R.string.session_row_waiting_mine)
                            : getString(R.string.session_row_waiting_theirs)));
        } else {
            meta.setText(getString(R.string.session_row_meta_type,
                    typeLabel, ServerTime.shortForm(item.createdAt)));
        }

        // ACTIVE 时主按钮是「接通」。REQUESTED 只有接收方能接/拒（发起方没有撤回接口）。
        boolean requestedToMe = STATUS_REQUESTED.equalsIgnoreCase(item.status) && !mine;
        boolean active = STATUS_ACTIVE.equalsIgnoreCase(item.status);
        bindAction(row, R.id.session_accept, requestedToMe,
                () -> acceptAndConnect(item));
        bindAction(row, R.id.session_reject, requestedToMe, () -> reject(item));
        bindAction(row, R.id.session_connect, active, () -> connect(item));
        bindAction(row, R.id.session_end, active, () -> end(item));
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

    /**
     * 会话里的另一方**叫什么**。
     *
     * <p>会话响应现在带着双方的名字了，所以这里不再拼 uid ——
     * 旧实现会把 {@code u_1759...} 直接摆在一行的大标题上，那是给开发者看的，
     * 不是给用户看的。
     */
    @NonNull
    private String counterpartLabel(@NonNull RealtimeDtos.SessionResponse item) {
        boolean mine = myUid.equals(item.initiatorUid);
        String name = mine ? item.targetName : item.initiatorName;
        String loginName = mine ? item.targetLoginName : item.initiatorLoginName;
        if (!TextUtils.isEmpty(name)) {
            return name;
        }
        if (!TextUtils.isEmpty(loginName)) {
            return loginName;
        }
        return getString(R.string.session_contact_unnamed);
    }

    @NonNull
    private String typeLabelOf(@Nullable String sessionType) {
        if (sessionType == null) {
            return getString(R.string.session_type_basic);
        }
        return switch (sessionType.toUpperCase()) {
            case "VOICE" -> getString(R.string.session_type_voice_short);
            case "VIDEO" -> getString(R.string.session_type_video_short);
            case "CAMERA" -> getString(R.string.session_type_camera_short);
            case "EMERGENCY" -> getString(R.string.session_type_emergency_short);
            default -> getString(R.string.session_type_basic);
        };
    }

    // ------------------------------------------------------------------
    // 发起与状态迁移
    // ------------------------------------------------------------------

    private void createSession() {
        Contact target = (Contact) targetSpinner.getSelectedItem();
        if (target == null) {
            showStatus(getString(R.string.session_create_need_contact), true);
            return;
        }
        if (pendingCall != null) {
            return;
        }
        String type = selectedType();
        createButton.setEnabled(false);
        showStatus(getString(R.string.state_loading), false);
        Call<RealtimeDtos.SessionResponse> call = ApiClient.get(this)
                .createSession(new RealtimeDtos.SessionCreateRequest(target.uid, type));
        pendingCall = call;
        call.enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
            @Override
            public void onSuccess(@Nullable RealtimeDtos.SessionResponse body) {
                pendingCall = null;
                createButton.setEnabled(true);
                String who = body == null ? target.label : counterpartLabel(body);
                showStatus(getString(R.string.session_calling, who), false);
                startRingTimeout(body == null ? null : body.id);
                load();
                if (body != null) {
                    // 发起方在 REQUESTED 阶段就挂上长连接，
                    // 否则对端点「接听」时服务端推不到我这里。
                    socket.connect(session.baseUrl(), tokenOrEmpty(), body.id, myUid, listener);
                }
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

    /**
     * 开始时长为 {@link #RING_TIMEOUT_MS} 的计时。
     *
     * <p>时间到了就把状态改成「对方未接听」。这一步是**界面自己的事**：
     * 服务端不会替用户挂断一条没人接的请求，所以不在这里收尾，
     * 那条 {@code REQUESTED} 会话会永远挂在列表里看起来还在响。
     */
    private void startRingTimeout(@Nullable String sessionId) {
        ringHandler.removeCallbacksAndMessages(null);
        if (sessionId == null) {
            return;
        }
        ringingSessionId = sessionId;
        ringHandler.postDelayed(() -> {
            if (!sessionId.equals(ringingSessionId)) {
                return;
            }
            ringingSessionId = null;
            showStatus(getString(R.string.session_no_answer), true);
            // 顺手结束掉：让列表里那条也变成终态，避免用户以后还去点它。
            ApiClient.get(RealtimeActivity.this).endSession(sessionId)
                    .enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
                        @Override
                        public void onSuccess(@Nullable RealtimeDtos.SessionResponse body) {
                            load();
                        }

                        @Override
                        public void onError(@NonNull ApiException error) {
                            load();
                        }
                    });
        }, RING_TIMEOUT_MS);
    }

    /**
     * 接听。**一次点击就进入会话**。
     *
     * <p>旧实现是「接受 → 状态变成 ACTIVE → 再去列表里点连接」，中间那一步
     * 对用户毫无意义：他已经表达了「我要接」，剩下的就是把通话接起来。
     */
    private void acceptAndConnect(@NonNull RealtimeDtos.SessionResponse item) {
        if (pendingCall != null) {
            return;
        }
        // 用具体类型的局部变量而不是 pendingCall（它是 Call<?>）：
        // 通配符的 enqueue 只接受 Callback<CAP#>，塞不进 ResultCallback<SessionResponse>。
        Call<RealtimeDtos.SessionResponse> call = ApiClient.get(this).acceptSession(item.id);
        pendingCall = call;
        call.enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
            @Override
            public void onSuccess(@Nullable RealtimeDtos.SessionResponse body) {
                pendingCall = null;
                showStatus(getString(R.string.session_done_accept), false);
                load();
                if (body != null) {
                    connect(body);
                }
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                showStatus(getString(R.string.session_action_failed, error.getMessage()), true);
            }
        });
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

    /** reject / end 两个接口的请求与响应形状相同，差异只有那句提示。 */
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

    @NonNull
    private String selectedType() {
        int checked = typeGroup.getCheckedRadioButtonId();
        if (checked == R.id.session_type_video) {
            return RealtimeDtos.TYPE_VIDEO;
        }
        if (checked == R.id.session_type_camera) {
            return RealtimeDtos.TYPE_CAMERA;
        }
        return RealtimeDtos.TYPE_VOICE;
    }

    // ------------------------------------------------------------------
    // 连接、媒体与消息
    // ------------------------------------------------------------------

    private void connect(@NonNull RealtimeDtos.SessionResponse item) {
        if (pendingCall != null) {
            return;
        }
        releaseConnected();
        connected = item;
        ringingSessionId = null;
        ringHandler.removeCallbacksAndMessages(null);

        chatTitle.setText(getString(R.string.session_chat_title, counterpartLabel(item)));
        logLines.clear();
        renderLog();
        chatCard.setVisibility(View.VISIBLE);
        messageInput.setText("");
        // 位置先清空再异步查：否则上一通会话的位置会短暂留在下一条会话上，
        // 那是**错误的信息** —— 会把人指向另一个地点。

        RealtimeCall.Mode mode = mediaModeOf(item);
        boolean hasMedia = mode != null;
        callStateView.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        remoteVideo.setVisibility(hasMedia && wantsVideoView(mode) ? View.VISIBLE : View.GONE);
        if (hasMedia) {
            // 先摆一句诚实的初始状态：「正在接通」而不是「已接通」。
            callStateView.setText(R.string.session_call_connecting);
        }

        // 会话面板在页面下方，连接成功后滚过去 —— 不滚的话，用户在页面上半部分
        // 看到的是「什么都没发生」，而实际上连接已经建立了。
        chatCard.post(() -> pageScroll.smoothScrollTo(0, chatCard.getTop()));
        socket.connect(session.baseUrl(), tokenOrEmpty(), item.id, myUid, listener);

        if (hasMedia) {
            prepareCall(mode);
        }
    }

    /**
     * 这条会话该用哪种媒体形态；{@code null} 表示这是纯文字会话（BASIC）。
     *
     * <p>EMERGENCY 按产品定义是视频通话（受护人报警时的紧急视频），
     * 所以这里的两个分支都要认它。
     */
    /**
     * 这条会话该用哪种媒体形态；{@code null} 表示这是纯文字会话（BASIC）。
     *
     * <p>EMERGENCY 按产品定义是视频通话（受护人报警时的紧急视频），
     * 所以 VIDEO 与 EMERGENCY 共用一条分支。
     *
     * <p><b>CAMERA 的方向由角色定，不由「谁发起」定</b>：受护人永远是被看的那一侧，
     * 监护人永远是看的那一侧。按发起方判断会在「监护人主动要求看看家里」时
     * 把摄像头开在监护人手机上 —— 那正好是反的。
     */
    @Nullable
    private RealtimeCall.Mode mediaModeOf(@NonNull RealtimeDtos.SessionResponse item) {
        String type = item.sessionType == null ? "BASIC" : item.sessionType.toUpperCase();
        boolean iAmGuardian = !myUid.equals(item.initiatorUid);
        return switch (type) {
            case "VOICE" -> RealtimeCall.Mode.VOICE;
            case "VIDEO", "EMERGENCY" -> RealtimeCall.Mode.VIDEO;
            // 本端是受护人端，CAMERA 会话里本机通常是拍摄方。
            // 但会话类型是双向的：如果这条 CAMERA 会话的对端也是受护人（理论上不会），
            // 就按「谁是监护人」来定，避免两个人都开摄像头。
            case "CAMERA" -> iAmGuardian
                    ? RealtimeCall.Mode.CAMERA_RECEIVE : RealtimeCall.Mode.CAMERA_SEND;
            default -> null;
        };
    }

    /** 哪些形态需要一块画面。纯语音只出状态文字，一块黑框没有意义。 */
    private boolean wantsVideoView(@NonNull RealtimeCall.Mode mode) {
        return mode != RealtimeCall.Mode.VOICE;
    }

    /**
     * 取 ICE 配置后建 PeerConnection。
     *
     * <p><b>每次现取</b>：服务端下发的 TURN 凭据是短时有效的，缓存到本地
     * 过一会儿就会失效，而失效的表现是「怎么都连不上」—— 那种问题用户查不出来。
     */
    private void prepareCall(@NonNull RealtimeCall.Mode mode) {
        ApiClient.get(this).iceServers()
                .enqueue(new ResultCallback<RealtimeDtos.IceServerResponse>() {
                    @Override
                    public void onSuccess(@Nullable RealtimeDtos.IceServerResponse body) {
                        startCall(mode, body == null
                                ? List.of() : List.of(body));
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 拿不到配置不等于一定打不通（同局域网可以靠本机候选直连），
                        // 所以仍然尝试，但把话说明白。
                        handleUnauthorized(error);
                        startCall(mode, List.of());
                        postCallState(getString(R.string.session_call_no_ice), false);
                    }
                });
    }

    private void startCall(@NonNull RealtimeCall.Mode mode,
                           @NonNull List<RealtimeDtos.IceServerResponse> iceServers) {
        if (connected == null) {
            return;
        }
        call = new RealtimeCall(this, mode, callListener, this::sendSignalEnvelope);
        // 由「发起方」发 offer，接收方等 offer。倒过来两端会互相等，
        // 表现是一直停在「正在接通」。
        boolean initiator = myUid.equals(connected.initiatorUid);
        call.start(iceServers, initiator);
    }

    private final RealtimeCall.Listener callListener = new RealtimeCall.Listener() {
        @Override
        public void onLocalDescription(@NonNull String kind, @NonNull String sdp) {
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", kind);
            payload.addProperty("sdp", sdp);
            sendSignalEnvelope(payload);
        }

        @Override
        public void onLocalCandidate(@NonNull String sdpMid, int sdpMLineIndex,
                                     @NonNull String candidate) {
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", "candidate");
            payload.addProperty("sdpMid", sdpMid);
            payload.addProperty("sdpMLineIndex", sdpMLineIndex);
            payload.addProperty("candidate", candidate);
            sendSignalEnvelope(payload);
        }

        @Override
        public void onRemoteVideoTrack(@NonNull VideoTrack track) {
            remoteVideo.setVisibility(View.VISIBLE);
            track.addSink(remoteVideo);
        }

        @Override
        public void onState(@NonNull String message, boolean connectedNow) {
            postCallState(message, connectedNow);
        }

        @Override
        public void onError(@NonNull String message) {
            // 通话层失败不要静默：用户会以为对方在听。
            postCallState(message, false);
            appendLog(getString(R.string.session_log_error, message), true);
        }
    };

    /** 把信令塞进当前连接。没连上时返回 false —— 调用方据此知道这一条丢了。 */
    private boolean sendSignalEnvelope(@NonNull JsonObject payload) {
        RealtimeDtos.SessionResponse current = connected;
        if (current == null || !socket.sendSignal(peerUidOf(current), payload)) {
            appendLog(getString(R.string.session_log_error,
                    getString(R.string.session_not_connected)), true);
            return false;
        }
        return true;
    }

    private void postCallState(@NonNull String message, boolean connectedNow) {
        callStateView.setVisibility(View.VISIBLE);
        callStateView.setText(message);
        callStateView.setTextColor(getColor(
                connectedNow ? R.color.an_sage : R.color.an_terracotta));
    }

    /** 会话里的另一方 uid。信令与 relay 都要填它，服务端会校验。 */
    @NonNull
    private String peerUidOf(@NonNull RealtimeDtos.SessionResponse item) {
        String other = myUid.equals(item.initiatorUid) ? item.targetUid : item.initiatorUid;
        return other == null ? "" : other;
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
                case "signal" -> {
                    // 信令不写进日志：SDP 与候选是又长又没意义的技术细节，
                    // 写进去会把真正的对话顶出屏幕。
                    if (call != null && envelope.payload != null
                            && envelope.payload.isJsonObject()) {
                        call.onRemoteSignal(envelope.payload.getAsJsonObject());
                    }
                }
                case "session.joined" -> appendLog(getString(R.string.session_log_joined), false);
                case "session.accepted" -> {
                    // 发起方专用：对方点了「接听」。这之后不需要我再点任何东西。
                    appendLog(getString(R.string.session_log_accepted), false);
                    handleAcceptedByPeer(envelope.sessionId);
                }
                case "session.rejected" -> {
                    ringHandler.removeCallbacksAndMessages(null);
                    ringingSessionId = null;
                    showStatus(getString(R.string.session_peer_rejected), true);
                    disconnect();
                    load();
                }
                case "session.ended" -> {
                    ringHandler.removeCallbacksAndMessages(null);
                    ringingSessionId = null;
                    showStatus(getString(R.string.session_peer_ended), true);
                    disconnect();
                    load();
                }
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

    /**
     * 对方接受了会话：把那条会话重新取回来，然后直接进会。
     *
     * <p>推送信封里只有会话 ID，而 {@link #connect} 需要完整的会话对象
     * （类型决定媒体形态）。拿一个「只有 ID 的假对象」去连，
     * 媒体形态会退化成纯文字。
     */
    private void handleAcceptedByPeer(@Nullable String sessionId) {
        if (sessionId == null) {
            return;
        }
        ringHandler.removeCallbacksAndMessages(null);
        ringingSessionId = null;
        ApiClient.get(this).listSessions()
                .enqueue(new ResultCallback<List<RealtimeDtos.SessionResponse>>() {
                    @Override
                    public void onSuccess(
                            @Nullable List<RealtimeDtos.SessionResponse> body) {
                        if (body == null) {
                            return;
                        }
                        for (RealtimeDtos.SessionResponse item : body) {
                            if (sessionId.equals(item.id)) {
                                showStatus(getString(R.string.session_peer_accepted), false);
                                connect(item);
                                return;
                            }
                        }
                        load();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        handleUnauthorized(error);
                        showStatus(getString(R.string.session_action_failed,
                                error.getMessage()), true);
                    }
                });
    }

    private void sendMessage() {
        String text = text(messageInput);
        if (TextUtils.isEmpty(text)) {
            toast(getString(R.string.session_message_need_text));
            return;
        }
        RealtimeDtos.SessionResponse current = connected;
        if (current == null || !socket.sendRelay(peerUidOf(current), text)) {
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

    /**
     * 挂断：先告诉对方，再释放媒体，最后收面板。
     *
     * <p>顺序不能反。先关 PeerConnection 再发 hangup，那条 hangup 就没有通道了，
     * 对方会一直等到 ICE 自己超时（几十秒），期间他以为自己还在通话中。
     */
    private void hangupCurrent() {
        if (call != null && connected != null) {
            JsonObject payload = new JsonObject();
            payload.addProperty("kind", "hangup");
            socket.sendSignal(peerUidOf(connected), payload);
        }
        disconnect();
    }

    /** 收起来：断开通话与连接，并把会话面板整个隐藏（消息不留存，留着反而误导）。 */
    private void disconnect() {
        closeCall();
        socket.leave();
        releaseConnected();
    }

    private void closeCall() {
        if (call != null) {
            call.close();
            call = null;
        }
        remoteVideo.setVisibility(View.GONE);
    }

    private void releaseConnected() {
        connected = null;
        chatCard.setVisibility(View.GONE);
        callStateView.setVisibility(View.GONE);
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
