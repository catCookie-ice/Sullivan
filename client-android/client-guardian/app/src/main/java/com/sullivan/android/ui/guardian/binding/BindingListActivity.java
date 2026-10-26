package com.sullivan.android.ui.guardian.binding;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.sullivan.android.R;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.BindingDtos;
import com.sullivan.android.data.api.dto.LookupDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.common.components.SimpleListAdapter;
import com.sullivan.android.ui.common.components.StateView;
import com.sullivan.android.ui.common.components.StatusLabels;

import java.util.List;
import java.util.Set;

import retrofit2.Call;

/**
 * 绑定管理：我的绑定 / 发起绑定 / 查找账号。
 *
 * <p><b>三个页签对应 web 端 {@code BindingManage.vue} 的三个 tab</b>，接口完全一致
 * （{@code GET|POST /bindings}、{@code POST /bindings/{id}/...}、{@code GET /users/{id}}）。
 * 差别只在呈现：web 用表格一列列出全部动作，手机上一列放不下四个按钮，
 * 所以每行按 {@link BindingActionPolicy} **只画当前真正能用的那些动作**，
 * 已终态与永久绑定的行不出现按钮区 —— 让用户点一个必然 403 的按钮，
 * 他只会以为是自己操作错了。
 *
 * <p><b>「对方」显示账号 ID</b>：绑定接口只给双方的 ID，不给显示名。
 * 想显示名字得对每条关系再查一次 {@code GET /users/{id}}，十条关系就是十个请求，
 * 而用户多半只是扫一眼。所以名字留给「查找账号」页按需查 —— 那里本来就是用来核对 ID 的。
 */
public class BindingListActivity extends AppCompatActivity {

    private static final int TAB_LIST = 0;
    private static final int TAB_REQUEST = 1;
    private static final int TAB_LOOKUP = 2;

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, BindingListActivity.class);
    }

    private SessionStore session;
    private View panelList;
    private View panelRequest;
    private View panelLookup;
    private RecyclerView listView;
    private StateView stateView;
    private SimpleListAdapter<BindingDtos.BindingResponse> adapter;
    private TextView requestStatus;
    private TextView lookupStatus;
    private View lookupResult;
    private TextView lookupResultTitle;
    private TextView lookupResultLines;
    private TextInputEditText requestProtecteeInput;
    private TextInputEditText lookupInput;

    /** 任何在飞的请求。任一时刻只允许一个 —— 三个页签共用一个「忙」状态。 */
    @Nullable
    private Call<?> pendingCall;

    private String myUid;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_binding_list);

        session = new SessionStore(this);
        myUid = session.principalId();
        // 401 时清会话并回登录页由 ApiException.isUnauthorized() 触发，
        // 但「没有令牌」这一步在这里就该拦下：没有 principalId 就算不出
        // 「我是发起方还是接收方」，全部按钮都会画错。
        if (!session.isLoggedIn() || TextUtils.isEmpty(myUid)) {
            finish();
            return;
        }

        panelList = findViewById(R.id.binding_panel_list);
        panelRequest = findViewById(R.id.binding_panel_request);
        panelLookup = findViewById(R.id.binding_panel_lookup);
        listView = findViewById(R.id.binding_list);
        stateView = findViewById(R.id.binding_state);
        requestStatus = findViewById(R.id.binding_request_status);
        lookupStatus = findViewById(R.id.binding_lookup_status);
        lookupResult = findViewById(R.id.binding_lookup_result);
        lookupResultTitle = findViewById(R.id.binding_lookup_result_title);
        lookupResultLines = findViewById(R.id.binding_lookup_result_lines);
        requestProtecteeInput = findViewById(R.id.binding_request_protectee_id);
        lookupInput = findViewById(R.id.binding_lookup_id);

        adapter = new SimpleListAdapter<>(R.layout.row_binding, this::bindRow);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        // 固定高度：每行的动作按钮数量随状态变，变高时列表若在重算，
        // 滚动位置会跳。绑定关系最多几十条，不做这个优化的代价可以忽略。
        listView.setHasFixedSize(false);

        TabLayout tabs = findViewById(R.id.binding_tabs);
        tabs.addTab(tabs.newTab().setText(R.string.binding_tab_list));
        tabs.addTab(tabs.newTab().setText(R.string.binding_tab_request));
        tabs.addTab(tabs.newTab().setText(R.string.binding_tab_lookup));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPanel(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        findViewById(R.id.binding_refresh).setOnClickListener(view -> loadBindings());
        // 监护人端只有「以监护人身份绑定受护人」这一个方向。反方向（以受护人身份
        // 申请监护人）是受护人那台设备上的事，放在这里只会让人以为本端能替对方做主。
        findViewById(R.id.binding_request_as_guardian).setOnClickListener(
                view -> requestBindingAsGuardian());
        findViewById(R.id.binding_lookup_action).setOnClickListener(view -> lookup());

        loadBindings();
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
    // 页签
    // ------------------------------------------------------------------

    private void showPanel(int position) {
        panelList.setVisibility(position == TAB_LIST ? View.VISIBLE : View.GONE);
        panelRequest.setVisibility(position == TAB_REQUEST ? View.VISIBLE : View.GONE);
        panelLookup.setVisibility(position == TAB_LOOKUP ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    private void loadBindings() {
        if (pendingCall != null) {
            return;
        }
        stateView.showLoading(getString(R.string.state_loading));
        listView.setVisibility(View.GONE);
        Call<List<BindingDtos.BindingResponse>> call = ApiClient.get(this).listBindings();
        pendingCall = call;
        call.enqueue(new ResultCallback<List<BindingDtos.BindingResponse>>() {
            @Override
            public void onSuccess(@Nullable List<BindingDtos.BindingResponse> body) {
                pendingCall = null;
                if (body == null || body.isEmpty()) {
                    listView.setVisibility(View.GONE);
                    stateView.showEmpty(getString(R.string.binding_empty));
                    return;
                }
                stateView.hide();
                listView.setVisibility(View.VISIBLE);
                adapter.submit(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                listView.setVisibility(View.GONE);
                handleUnauthorized(error);
                stateView.showError(error.getMessage(), BindingListActivity.this::loadBindings);
            }
        });
    }

    /** 往一行上画一条绑定关系。每次刷新都会重新调用，所以这里不能累积状态。 */
    private void bindRow(@NonNull View row, @NonNull BindingDtos.BindingResponse binding) {
        StatusLabels.Label status = StatusLabels.binding(binding.status);
        TextView statusView = row.findViewById(R.id.binding_row_status);
        statusView.setText(status.text);
        statusView.setBackgroundResource(StatusLabels.chipBackground(status.tone));
        statusView.setTextColor(getColor(StatusLabels.textColor(status.tone)));

        TextView badge = row.findViewById(R.id.binding_row_badge);
        if (BindingActionPolicy.isIncomingRequest(binding, myUid)) {
            badge.setText(R.string.binding_row_incoming);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }

        // 对方只显示姓名 / 登录名，不显示 uid。
        String counterpart = BindingActionPolicy.counterpartLabel(binding, myUid);
        ((TextView) row.findViewById(R.id.binding_row_counterpart)).setText(
                getString(R.string.binding_row_counterpart,
                        counterpart == null ? "—" : counterpart));

        TextView side = row.findViewById(R.id.binding_row_side);
        if (myUid.equals(binding.guardianId)) {
            side.setText(R.string.binding_row_side_guardian);
        } else if (myUid.equals(binding.protecteeId)) {
            side.setText(R.string.binding_row_side_protectee);
        } else {
            // 不该发生：接口只返回我参与的绑定。真出现了也不猜，直说。
            side.setText(R.string.binding_row_side_none);
        }

        StringBuilder meta = new StringBuilder(getString(
                BindingActionPolicy.isInitiator(binding, myUid)
                        ? R.string.binding_row_initiator_me
                        : R.string.binding_row_initiator_other));
        if (binding.permanent) {
            meta.append('\n').append(getString(R.string.binding_row_permanent));
        }
        if (binding.primary) {
            meta.append('\n').append(getString(R.string.binding_row_primary));
        }
        ((TextView) row.findViewById(R.id.binding_row_meta)).setText(meta);

        // 四个按钮一次画全，按可用动作显隐。任何一条关系上最多同时出现两颗
        //（PENDING → 确认+拒绝 或 撤回；ACTIVE → 解除 或不给），所以一行放得下，
        // 不必做「按数量动态排布」那套。
        LinearLayout actions = row.findViewById(R.id.binding_row_actions);
        Set<BindingActionPolicy.Action> available =
                BindingActionPolicy.actionsFor(binding, myUid);
        bindAction(row, R.id.binding_row_accept, BindingActionPolicy.Action.ACCEPT, binding, available);
        bindAction(row, R.id.binding_row_reject, BindingActionPolicy.Action.REJECT, binding, available);
        bindAction(row, R.id.binding_row_cancel, BindingActionPolicy.Action.CANCEL, binding, available);
        bindAction(row, R.id.binding_row_terminate, BindingActionPolicy.Action.TERMINATE, binding, available);
        actions.setVisibility(available.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindAction(@NonNull View row, int buttonId,
                            @NonNull BindingActionPolicy.Action action,
                            @NonNull BindingDtos.BindingResponse binding,
                            @NonNull Set<BindingActionPolicy.Action> available) {
        Button button = row.findViewById(buttonId);
        if (!available.contains(action)) {
            // 不参与布局（而不是 INVISIBLE）：剩下的那颗按钮会占满整行，
            // 拇指本来就该落在这里，不该留一半空白。
            button.setVisibility(View.GONE);
            button.setOnClickListener(null);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(view -> confirm(binding, action));
    }

    private int labelOf(@NonNull BindingActionPolicy.Action action) {
        return switch (action) {
            case ACCEPT -> R.string.binding_action_accept;
            case REJECT -> R.string.binding_action_reject;
            case CANCEL -> R.string.binding_action_cancel;
            case TERMINATE -> R.string.binding_action_terminate;
        };
    }

    private int confirmMessageOf(@NonNull BindingActionPolicy.Action action) {
        return switch (action) {
            case ACCEPT -> R.string.binding_confirm_accept;
            case REJECT -> R.string.binding_confirm_reject;
            case CANCEL -> R.string.binding_confirm_cancel;
            case TERMINATE -> R.string.binding_confirm_terminate;
        };
    }

    /**
     * 四个动作全都先确认一次。
     *
     * <p>确认（accept）本身也要问，因为它不是「撤销得掉的」：一旦生效，
     * 对方就能看到我的受护人信息、能发起实时会话，而解除要另走一次 terminate。
     */
    private void confirm(@NonNull BindingDtos.BindingResponse binding,
                         @NonNull BindingActionPolicy.Action action) {
        new AlertDialog.Builder(this)
                .setTitle(labelOf(action))
                .setMessage(confirmMessageOf(action))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> perform(binding, action))
                .show();
    }

    private void perform(@NonNull BindingDtos.BindingResponse binding,
                         @NonNull BindingActionPolicy.Action action) {
        if (pendingCall != null) {
            return;
        }
        // reject / terminate 的 body 服务端标成 required=false，传 null 即可；
        // 界面上目前不收集原因，但保留这个参数是为了将来不用改接口签名。
        Call<BindingDtos.BindingResponse> call = switch (action) {
            case ACCEPT -> ApiClient.get(this).acceptBinding(binding.id);
            case REJECT -> ApiClient.get(this).rejectBinding(binding.id, null);
            case CANCEL -> ApiClient.get(this).cancelBinding(binding.id);
            case TERMINATE -> ApiClient.get(this).terminateBinding(binding.id, null);
        };
        pendingCall = call;
        call.enqueue(new ResultCallback<BindingDtos.BindingResponse>() {
            @Override
            public void onSuccess(@Nullable BindingDtos.BindingResponse body) {
                pendingCall = null;
                Toast.makeText(BindingListActivity.this, doneMessageOf(action),
                        Toast.LENGTH_SHORT).show();
                loadBindings();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                Toast.makeText(BindingListActivity.this,
                        getString(R.string.binding_action_failed, error.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int doneMessageOf(@NonNull BindingActionPolicy.Action action) {
        return switch (action) {
            case ACCEPT -> R.string.binding_done_accept;
            case REJECT -> R.string.binding_done_reject;
            case CANCEL -> R.string.binding_done_cancel;
            case TERMINATE -> R.string.binding_done_terminate;
        };
    }

    // ------------------------------------------------------------------
    // 发起绑定
    // ------------------------------------------------------------------

    private void requestBindingAsGuardian() {
        if (pendingCall != null) {
            return;
        }
        String targetId = textOf(requestProtecteeInput);
        if (TextUtils.isEmpty(targetId)) {
            showStatus(requestStatus, getString(R.string.binding_request_need_id), true);
            return;
        }

        Call<BindingDtos.BindingResponse> call = ApiClient.get(this).requestBinding(
                new BindingDtos.Request(targetId, "PROTECTEE"));
        pendingCall = call;
        showStatus(requestStatus, getString(R.string.state_loading), false);
        call.enqueue(new ResultCallback<BindingDtos.BindingResponse>() {
            @Override
            public void onSuccess(@Nullable BindingDtos.BindingResponse body) {
                pendingCall = null;
                showStatus(requestStatus, getString(R.string.binding_request_sent), false);
                requestProtecteeInput.setText("");
                loadBindings();
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                showStatus(requestStatus,
                        getString(R.string.binding_request_failed, error.getMessage()), true);
            }
        });
    }

    // ------------------------------------------------------------------
    // 查找账号
    // ------------------------------------------------------------------

    private void lookup() {
        if (pendingCall != null) {
            return;
        }
        String loginName = textOf(lookupInput);
        if (TextUtils.isEmpty(loginName)) {
            showStatus(lookupStatus, getString(R.string.binding_lookup_need_id), true);
            return;
        }
        lookupResult.setVisibility(View.GONE);
        showStatus(lookupStatus, getString(R.string.state_loading), false);

        Call<LookupDtos.UserResponse> call = ApiClient.get(this).getUserByLoginName(loginName);
        pendingCall = call;
        call.enqueue(new ResultCallback<LookupDtos.UserResponse>() {
            @Override
            public void onSuccess(@Nullable LookupDtos.UserResponse body) {
                pendingCall = null;
                hideStatus(lookupStatus);
                if (body == null) {
                    showStatus(lookupStatus, getString(R.string.binding_lookup_none), true);
                    return;
                }
                renderLookup(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                handleUnauthorized(error);
                showStatus(lookupStatus,
                        getString(R.string.binding_lookup_failed, error.getMessage()), true);
            }
        });
    }

    private void renderLookup(@NonNull LookupDtos.UserResponse user) {
        lookupResultTitle.setText(getString(R.string.binding_lookup_result,
                TextUtils.isEmpty(user.loginName) ? "—" : user.loginName));

        StringBuilder lines = new StringBuilder();
        lines.append(getString(R.string.binding_lookup_display,
                user.displayName == null ? "—" : user.displayName));
        lines.append('\n').append(getString(R.string.binding_lookup_role,
                StatusLabels.role(user.roleCode)));
        if (user.roles != null && !user.roles.isEmpty()) {
            StringBuilder roles = new StringBuilder();
            for (String role : user.roles) {
                if (roles.length() > 0) {
                    roles.append(" / ");
                }
                roles.append(StatusLabels.role(role));
            }
            lines.append('\n').append(getString(R.string.binding_lookup_roles, roles));
        }
        lines.append('\n').append(getString(R.string.binding_lookup_status,
                StatusLabels.accountStatus(user.status)));
        lines.append('\n').append(getString(R.string.binding_lookup_volunteer,
                getString(user.alsoVolunteer
                        ? R.string.binding_lookup_yes : R.string.binding_lookup_no)));

        lookupResultLines.setText(lines);
        lookupResult.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 401 就地清掉本地会话。
     *
     * <p>本页不自动跳登录页：用户可能正对着一条刚发起的请求，被弹走会让他不知道
     * 那条请求到底发出去没有。所以在原地把错误说清楚（「登录已失效，请重新登录」），
     * 由用户自己返回 —— 返回后各主页都会因为 {@code isLoggedIn()==false} 进登录页。
     */
    private void handleUnauthorized(@NonNull ApiException error) {
        if (error.isUnauthorized()) {
            session.clearLogin();
        }
    }

    private void showStatus(@NonNull TextView view, @NonNull String message, boolean isError) {
        view.setVisibility(View.VISIBLE);
        view.setText(message);
        view.setTextColor(getColor(isError ? R.color.an_terracotta : R.color.an_sage));
    }

    private void hideStatus(@NonNull TextView view) {
        view.setVisibility(View.GONE);
    }

    @NonNull
    private static String textOf(@Nullable TextView view) {
        return view == null || view.getText() == null ? "" : view.getText().toString().trim();
    }
}
