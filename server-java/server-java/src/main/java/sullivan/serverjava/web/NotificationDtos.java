package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/** 站内通知 DTO。 */
public final class NotificationDtos {
    private NotificationDtos() {
    }

    @Schema(description = "站内通知")
    public record NotificationResponse(
            @Schema(description = "通知唯一标识", example = "ntf_1234567890") String id,
            @Schema(description = "类型：ENROLLMENT_PENDING 新报名待审核 / ENROLLMENT_APPROVED 报名通过 / ENROLLMENT_REJECTED 报名未通过 / GROUP_MESSAGE_WITHDRAWN 群消息被撤回",
                    example = "ENROLLMENT_APPROVED") String type,
            @Schema(description = "标题（hover 简述第一行）", example = "报名审核通过") String title,
            @Schema(description = "正文", example = "你报名的「徒步陪伴」已通过审核，快去活动群聊看看吧") String content,
            @Schema(description = "点击跳转的前端路由", example = "/volunteer/group-chat?activityId=act_1") String link,
            @Schema(description = "状态：UNREAD 未读 / READ 已读", example = "UNREAD") String status,
            @Schema(description = "已读时间") LocalDateTime readAt,
            @Schema(description = "创建时间") LocalDateTime createdAt) {
    }

    @Schema(description = "通知页（游标分页，按时间倒序=最新在前）")
    public record NotificationPage(
            @Schema(description = "通知列表（最新在前）") List<NotificationResponse> records,
            @Schema(description = "是否还有更早的通知", example = "true") boolean hasMore) {
    }

    @Schema(description = "未读数")
    public record UnreadCountResponse(
            @Schema(description = "未读通知数（铃铛角标，上限展示 99+ 由前端处理）", example = "3") long count) {
    }
}
