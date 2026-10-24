package com.sullivan.android.ui.guardian.call;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.data.api.dto.AlertDtos;

import java.util.List;

/**
 * 从「投递给我的报警」列表里，找出与某条实时会话对应的那一条。
 *
 * <p><b>为什么需要它。</b>会话响应里没有 {@code alertId}；两者的关联在服务端是
 * {@code alert_delivery.session_id → realtime_session.id}。
 * 客户端能拿到的唯一线索就是报警详情里的 {@code deliveries} 数组
 * （每个投递带着它为哪位监护人创建的 {@code sessionId}）。
 *
 * <p><b>为什么要抽成纯函数。</b>同一段查找在三处要用：来电对话框、后台通知、
 * 会话页的位置条。三处各写一遍必然出现"某处忘了判空"或者"某处只查第一条投递"
 * 这种偏差 —— 而偏差的表现是"有时候有位置、有时候没有"，
 * 是最难排查的一类问题。这里只写一遍，并单测它的边界。
 */
public final class AlertQuery {

    private AlertQuery() {
    }

    /**
     * 找出 {@code deliveries} 里 {@code sessionId} 等于给定值的那条报警。
     *
     * <p>遍历**所有**报警的所有投递，不是只看"最新的那条报警"或"第一条投递"：
     * 同一个受护人可能先后报警多次，而一位监护人只会在其中某几条上出现。
     *
     * @return 找不到时返回 null
     */
    @Nullable
    public static AlertDtos.AlertResponse alertForSession(
            @Nullable List<AlertDtos.AlertResponse> alerts, @Nullable String sessionId) {
        if (alerts == null || sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        for (AlertDtos.AlertResponse alert : alerts) {
            if (alert == null || alert.deliveries == null) {
                continue;
            }
            for (AlertDtos.AlertDeliveryView delivery : alert.deliveries) {
                if (delivery != null && sessionId.equals(delivery.sessionId)) {
                    return alert;
                }
            }
        }
        return null;
    }

    /**
     * 直接取出那条报警的位置快照。
     *
     * @return 报警不存在、或报警本身没有位置时返回 null
     */
    @Nullable
    public static AlertDtos.AlertLocation locationForSession(
            @Nullable List<AlertDtos.AlertResponse> alerts, @Nullable String sessionId) {
        AlertDtos.AlertResponse alert = alertForSession(alerts, sessionId);
        return alert == null ? null : alert.location;
    }
}
