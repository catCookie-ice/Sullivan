package com.sullivan.android.ui.guardian.call;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.data.api.dto.RealtimeDtos;

import java.util.ArrayList;
import java.util.List;

/**
 * 从「我的会话列表」里挑出**正在呼叫我、而我还没处理**的那些。
 *
 * <p>抽成纯函数是为了能单测：这段判断此前根本不存在。
 *
 * <p><b>它修的是什么。</b>受护人按下紧急求助后，服务端会建一条会话并 5 秒后
 * 自动发起呼叫（见服务端 {@code AlertAutoDispatchJob}），但监护人端
 * **只在用户手动点开「实时会话」页时才去拉列表**（`RealtimeActivity`）。
 * 于是人不去点那个入口，就永远不知道有人在呼救 —— 服务端日志里会话建得好好的，
 * 手机上什么都不响。这就是"服务器端已经建立了会话、监护人却收不到通知"。
 *
 * <p>判定三条，缺一不可：
 * <ol>
 *   <li>状态是 {@code REQUESTED} —— 已被接受/拒绝/结束的都不该再提醒；</li>
 *   <li>接收方是**我** —— 列表同时包含我发起的会话，那些不该弹"来电"；</li>
 *   <li>不是我自己发起的 —— 与上一条互为兜底，防止服务端字段缺失时误弹。</li>
 * </ol>
 */
public final class IncomingCallFilter {

    private IncomingCallFilter() {
    }

    /**
     * @param sessions 服务端返回的全部会话，可为 {@code null}
     * @param myUid    当前登录账号的 principalId
     * @return 需要提醒用户的来电，按原顺序；没有则返回空列表（不返回 null）
     */
    @NonNull
    public static List<RealtimeDtos.SessionResponse> pending(
            @Nullable List<RealtimeDtos.SessionResponse> sessions, @Nullable String myUid) {
        List<RealtimeDtos.SessionResponse> result = new ArrayList<>();
        if (sessions == null || myUid == null || myUid.isEmpty()) {
            return result;
        }
        for (RealtimeDtos.SessionResponse session : sessions) {
            if (session == null || session.id == null) {
                continue;
            }
            if (!"REQUESTED".equals(session.status)) {
                continue;
            }
            if (!myUid.equals(session.targetUid)) {
                continue;
            }
            if (myUid.equals(session.initiatorUid)) {
                continue;
            }
            result.add(session);
        }
        return result;
    }
}
