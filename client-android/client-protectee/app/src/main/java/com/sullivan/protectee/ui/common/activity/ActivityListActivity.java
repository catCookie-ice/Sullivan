package com.sullivan.protectee.ui.common.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sullivan.protectee.R;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.ActivityDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.ui.common.components.ServerTime;
import com.sullivan.protectee.ui.common.components.SimpleListAdapter;
import com.sullivan.protectee.ui.common.components.StateView;
import com.sullivan.protectee.ui.common.components.StatusLabels;

import java.util.List;

import retrofit2.Call;

/**
 * 公益活动列表（只读）。监护人与志愿者、受护人都用这一页。
 *
 * <p><b>为什么只有读</b>：活动由公益机构在机构端发布与审核报名，后端给 Android 的
 * 只有 {@code GET /activities} 一个公开接口。所以这里不出现「报名」按钮 ——
 * 放一个点了会失败的按钮，比没有按钮更糟。
 *
 * <p><b>需要登录</b>：服务端对这个接口 {@code requireLoggedIn}。未登录直接退回去，
 * 不在页面上做「登录后查看」的占位：入口只在登录后的主页出现（见各主页的卡片可见性）。
 *
 * <p><b>筛选与分页</b>：五个筛选项（全部 + 四个状态）排成一行；分页用「加载更多」，
 * 因为手机上的列表是连续滚动的，给页码就得让用户在滚动和点页码之间来回切。
 */
public class ActivityListActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 20;

    /** 筛选项：null 表示「全部」（交给服务端的默认值）。 */
    private static final String[] STATUSES = {
            null, "RECRUITING", "IN_PROGRESS", "ENDED", "CANCELLED"
    };

    @NonNull
    public static Intent intentFor(@NonNull Context context) {
        return new Intent(context, ActivityListActivity.class);
    }

    private SessionStore session;
    private RecyclerView listView;
    private StateView stateView;
    private LinearLayout filters;
    private TextView summary;
    private Button moreButton;
    private SimpleListAdapter<ActivityDtos.ActivityResponse> adapter;

    /** 当前筛选；null = 全部。 */
    @Nullable
    private String status;
    /** 已经加载到第几页（1 起）。 */
    private long page = 1;
    private long totalPages = 1;
    private long totalRecords;

    @Nullable
    private Call<ActivityDtos.PageResult<ActivityDtos.ActivityResponse>> pendingCall;
    /** 本次加载是「首屏/换筛选」还是「加载更多」—— 决定失败时把状态画在哪里。 */
    private boolean appending;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activity_list);

        session = new SessionStore(this);
        if (!session.isLoggedIn()) {
            finish();
            return;
        }

        listView = findViewById(R.id.activity_list);
        stateView = findViewById(R.id.activity_state);
        filters = findViewById(R.id.activity_filters);
        summary = findViewById(R.id.activity_summary);
        moreButton = findViewById(R.id.activity_more);

        adapter = new SimpleListAdapter<>(R.layout.row_activity, this::bindRow);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        buildFilters();
        moreButton.setOnClickListener(view -> load(false, true));
        load(true, false);
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
    // 筛选
    // ------------------------------------------------------------------

    private void buildFilters() {
        String[] labels = {
                getString(R.string.activity_filter_all),
                getString(R.string.activity_filter_recruiting),
                getString(R.string.activity_filter_in_progress),
                getString(R.string.activity_filter_ended),
                getString(R.string.activity_filter_cancelled),
        };
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(14f);
            chip.setTextColor(getColor(R.color.an_ink_2));
            chip.setBackgroundResource(R.drawable.an_filter_chip);
            int padH = getResources().getDimensionPixelSize(R.dimen.an_space_m);
            int padV = getResources().getDimensionPixelSize(R.dimen.an_space_s);
            chip.setPadding(padH, padV, padH, padV);
            // 触控目标下限：视觉上是一个小标签，但可点区域必须有 48dp 高。
            chip.setMinHeight(getResources().getDimensionPixelSize(R.dimen.an_touch_min));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSelected(i == 0);
            final String filterStatus = STATUSES[i];
            chip.setOnClickListener(view -> selectFilter(filterStatus, view));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(getResources().getDimensionPixelSize(R.dimen.an_space_s));
            filters.addView(chip, params);
        }
    }

    private void selectFilter(@Nullable String filterStatus, @NonNull View chip) {
        if (pendingCall != null) {
            return;
        }
        if (TextUtils.equals(status, filterStatus) && adapter.size() > 0) {
            return;
        }
        status = filterStatus;
        for (int i = 0; i < filters.getChildCount(); i++) {
            filters.getChildAt(i).setSelected(filters.getChildAt(i) == chip);
        }
        load(true, false);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /**
     * @param reset  true 表示换筛选/首屏：清空列表并回到第 1 页；
     *               false 表示加载更多：追加到下一页。
     */
    private void load(boolean reset, boolean append) {
        if (pendingCall != null) {
            return;
        }
        if (reset) {
            page = 1;
            adapter.clear();
        }
        appending = append;
        if (reset) {
            listView.setVisibility(View.GONE);
            summary.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
            stateView.showLoading(getString(R.string.state_loading));
        } else {
            moreButton.setEnabled(false);
            moreButton.setText(R.string.state_loading);
        }

        Call<ActivityDtos.PageResult<ActivityDtos.ActivityResponse>> call =
                ApiClient.get(this).listActivities(status, page, PAGE_SIZE);
        pendingCall = call;
        call.enqueue(new ResultCallback<ActivityDtos.PageResult<ActivityDtos.ActivityResponse>>() {
            @Override
            public void onSuccess(
                    @Nullable ActivityDtos.PageResult<ActivityDtos.ActivityResponse> body) {
                pendingCall = null;
                render(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                pendingCall = null;
                if (error.isUnauthorized()) {
                    session.clearLogin();
                }
                if (appending) {
                    // 追加失败时保留已经看到的列表：把整屏换成错误页，
                    // 等于把用户刚翻到的内容也一起没收了。
                    moreButton.setEnabled(true);
                    moreButton.setText(R.string.state_retry);
                    moreButton.setVisibility(View.VISIBLE);
                    return;
                }
                listView.setVisibility(View.GONE);
                stateView.showError(error.getMessage(),
                        () -> load(true, false));
            }
        });
    }

    private void render(@Nullable ActivityDtos.PageResult<ActivityDtos.ActivityResponse> body) {
        List<ActivityDtos.ActivityResponse> records = body == null ? null : body.records;
        if (page == 1 && (records == null || records.isEmpty())) {
            listView.setVisibility(View.GONE);
            summary.setVisibility(View.GONE);
            moreButton.setVisibility(View.GONE);
            stateView.showEmpty(getString(R.string.activity_empty));
            return;
        }
        if (records != null && !records.isEmpty()) {
            // SimpleListAdapter 只能整份换掉，所以「加载更多」时把已有数据拼上再提交。
            // 列表最多几百条，这个拷贝的代价远小于为它写一套 diff。
            adapter.submit(appendList(records));
        }
        stateView.hide();
        listView.setVisibility(View.VISIBLE);

        long pages = body == null ? 1 : Math.max(1, body.pages);
        totalRecords = body == null ? adapter.size() : body.total;
        totalPages = pages;
        summary.setText(getString(R.string.activity_summary, totalRecords));
        summary.setVisibility(View.VISIBLE);

        boolean hasMore = page < totalPages;
        moreButton.setEnabled(true);
        moreButton.setText(R.string.activity_action_more);
        moreButton.setVisibility(hasMore ? View.VISIBLE : View.GONE);
    }

    /** 拼接：adapter 里已有的行 + 新一页。 */
    @NonNull
    private List<ActivityDtos.ActivityResponse> appendList(
            @NonNull List<ActivityDtos.ActivityResponse> incoming) {
        java.util.ArrayList<ActivityDtos.ActivityResponse> merged =
                new java.util.ArrayList<>(adapter.size() + incoming.size());
        for (int i = 0; i < adapter.size(); i++) {
            merged.add(adapter.item(i));
        }
        merged.addAll(incoming);
        page++;
        return merged;
    }

    // ------------------------------------------------------------------
    // 行
    // ------------------------------------------------------------------

    private void bindRow(@NonNull View row, @NonNull ActivityDtos.ActivityResponse activity) {
        StatusLabels.Label status = StatusLabels.activity(activity.status);
        TextView statusView = row.findViewById(R.id.activity_status);
        statusView.setText(status.text);
        statusView.setBackgroundResource(StatusLabels.chipBackground(status.tone));
        statusView.setTextColor(getColor(StatusLabels.textColor(status.tone)));

        TextView quota = row.findViewById(R.id.activity_quota);
        if (activity.maxVolunteers == null) {
            quota.setText(R.string.activity_quota_unknown);
        } else {
            quota.setText(getString(R.string.activity_quota,
                    activity.approvedCount == null ? 0 : activity.approvedCount,
                    activity.maxVolunteers));
        }

        ((TextView) row.findViewById(R.id.activity_title)).setText(
                fallback(activity.title, getString(R.string.activity_title_unknown)));
        ((TextView) row.findViewById(R.id.activity_time)).setText(
                ServerTime.range(activity.startsAt, activity.endsAt));
        ((TextView) row.findViewById(R.id.activity_location)).setText(
                fallback(activity.locationText, getString(R.string.activity_location_unknown)));
        ((TextView) row.findViewById(R.id.activity_org)).setText(
                fallback(activity.organizationPublicName,
                        getString(R.string.activity_org_unknown)));

        TextView description = row.findViewById(R.id.activity_description);
        if (TextUtils.isEmpty(activity.description)) {
            description.setVisibility(View.GONE);
        } else {
            description.setText(activity.description);
            description.setVisibility(View.VISIBLE);
        }
    }

    @NonNull
    private static String fallback(@Nullable String value, @NonNull String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }
}
