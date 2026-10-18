package com.sullivan.android.ui.common.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.sullivan.android.R;

/**
 * 列表/内容区的「加载中 / 空 / 失败」三态。
 *
 * <p><b>为什么抽出来</b>：绑定、活动、申请、会话四个列表页都需要这三种状态，
 * 而它们最容易出现的分歧不是外观，而是**「空」被当成「错误」**：
 * 用户第一次打开绑定管理时看到红色的「加载失败」，其实只是他还没绑过人。
 * 这里把两种状态的文案、颜色、是否给重试按钮都定死，各页只负责传一句具体的话。
 *
 * <p><b>空态不给重试按钮</b>：空不是失败，给一个「重试」只会让人反复点，
 * 而真正要做的动作（去绑定一位受护人）在页面上方。
 *
 * <p><b>隐藏时是 {@code GONE} 而不是 {@code INVISIBLE}</b>：它夹在标题和列表之间，
 * 占着位置会让本来就空的页面显得更像出错了。
 */
public class StateView extends LinearLayout {

    private final TextView titleView;
    private final TextView messageView;
    private final Button retryButton;

    public StateView(@NonNull Context context) {
        this(context, null);
    }

    public StateView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        setPadding(getResources().getDimensionPixelSize(R.dimen.an_space_xl),
                getResources().getDimensionPixelSize(R.dimen.an_space_xl),
                getResources().getDimensionPixelSize(R.dimen.an_space_xl),
                getResources().getDimensionPixelSize(R.dimen.an_space_xl));
        // view_state.xml 的根是 <merge>：布局参数在这里一次性给全，
        // 免得每个使用处再各写一遍 match_parent/wrap_content。
        LayoutInflater.from(context).inflate(R.layout.view_state, this, true);
        titleView = findViewById(R.id.state_title);
        messageView = findViewById(R.id.state_message);
        retryButton = findViewById(R.id.state_retry);
        setVisibility(GONE);
    }

    /** 加载中：只有一句话，没有按钮 —— 此时给「重试」会鼓励用户打断一次正在进行的请求。 */
    public void showLoading(@StringRes int messageRes) {
        show(getContext().getString(R.string.state_loading), getContext().getString(messageRes), null);
    }

    public void showLoading(@NonNull String message) {
        show(getContext().getString(R.string.state_loading), message, null);
    }

    /**
     * 空态：说明「为什么这里是空的、接下来该做什么」。
     *
     * @param message 具体的一句话。别传「暂无数据」—— 那等于什么都没说。
     */
    public void showEmpty(@StringRes int messageRes) {
        showEmpty(getContext().getString(messageRes));
    }

    public void showEmpty(@NonNull String message) {
        show(getContext().getString(R.string.state_empty_title), message, null);
    }

    /**
     * 失败态：给出服务端或网络层的那句话，并允许重试。
     *
     * @param retry 重试动作；传 null 则不给按钮（用于「重试也没用」的那类错误）。
     */
    public void showError(@NonNull String message, @Nullable Runnable retry) {
        show(getContext().getString(R.string.state_error_title), message, retry, true);
    }

    /** 收起三态，露出真正的内容。 */
    public void hide() {
        setVisibility(GONE);
    }

    private void show(@NonNull String title, @NonNull String message, @Nullable Runnable retry) {
        show(title, message, retry, false);
    }

    private void show(@NonNull String title, @NonNull String message, @Nullable Runnable retry,
                      boolean isError) {
        titleView.setText(title);
        messageView.setText(message);
        // 失败态标题用陶土色，其余用墨色：「失败」是唯一需要用户立刻注意的那一种。
        titleView.setTextColor(getContext().getColor(
                isError ? R.color.an_terracotta : R.color.an_ink));
        if (retry == null) {
            retryButton.setVisibility(GONE);
            retryButton.setOnClickListener(null);
        } else {
            retryButton.setVisibility(VISIBLE);
            retryButton.setOnClickListener(view -> retry.run());
        }
        setVisibility(VISIBLE);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // 三态视图自身可见时，不让 TalkBack 把「空」「加载中」读成装饰文字；
        // 它们恰恰是这一屏唯一的内容。
        setImportantForAccessibility(visibility == VISIBLE
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
}
