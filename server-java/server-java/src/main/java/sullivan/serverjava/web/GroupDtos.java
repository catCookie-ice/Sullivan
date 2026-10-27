package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动群聊 DTO：志愿者群列表、消息收发（仅文字）、成员、举报与机构处理。
 */
public final class GroupDtos {
    private GroupDtos() {
    }

    @Schema(description = "发送群消息请求（只允许文字）")
    public record SendMessageRequest(
            @Schema(description = "纯文字内容，1-2000 字", maxLength = 2000)
            @NotBlank @Size(max = 2000) String content) {
    }

    @Schema(description = "活动群")
    public record GroupResponse(
            @Schema(description = "群唯一标识", example = "grp_1234567890") String id,
            @Schema(description = "活动唯一标识", example = "act_1234567890") String activityId,
            @Schema(description = "活动标题", example = "朝阳区视障人士徒步陪伴") String activityTitle,
            @Schema(description = "群管理员机构唯一标识", example = "o_1234567890") String organizationId,
            @Schema(description = "机构展示名", example = "朝阳盲协") String organizationPublicName,
            @Schema(description = "状态：OPEN 开放 / CLOSED 已封闭（活动结束）", example = "OPEN") String status,
            @Schema(description = "活动开始时间") LocalDateTime startsAt,
            @Schema(description = "活动结束时间；到点后群自动封闭") LocalDateTime endsAt,
            @Schema(description = "当前群成员数", example = "18") long memberCount,
            @Schema(description = "建群时间") LocalDateTime createdAt) {
    }

    @Schema(description = "群消息（含已撤回的墓碑：withdrawn=true、content 为空，前端显示「xx 撤回了一条消息」）")
    public record GroupMessageResponse(
            @Schema(description = "消息唯一标识", example = "msg_1234567890") String id,
            @Schema(description = "所属群唯一标识", example = "grp_1234567890") String groupId,
            @Schema(description = "发送者唯一标识（志愿者或机构）", example = "u_1234567890") String senderUid,
            @Schema(description = "发送者类型：VOLUNTEER / ORGANIZATION", example = "VOLUNTEER") String senderType,
            @Schema(description = "发送者展示名（昵称/登录名或机构名）", example = "张三") String senderName,
            @Schema(description = "文字内容；已撤回的消息为空串", example = "周六早上八点南门集合") String content,
            @Schema(description = "发送时间") LocalDateTime createdAt,
            @Schema(description = "是否已撤回（墓碑：只显示「xx 撤回了一条消息」，不下发原文）",
                    example = "false") boolean withdrawn) {
    }

    @Schema(description = "群消息页（游标分页，按时间升序返回）")
    public record GroupMessagePage(
            @Schema(description = "消息列表（时间升序）") List<GroupMessageResponse> records,
            @Schema(description = "是否还有更早的消息", example = "true") boolean hasMore) {
    }

    @Schema(description = "群成员（仅志愿者；机构管理员不落成员行）")
    public record GroupMemberResponse(
            @Schema(description = "成员行唯一标识", example = "gm_1234567890") String id,
            @Schema(description = "志愿者账号唯一标识", example = "u_1234567890") String volunteerUid,
            @Schema(description = "志愿者登录名", example = "zhangsan") String loginName,
            @Schema(description = "志愿者展示昵称", example = "张三") String displayName,
            @Schema(description = "入群时间") LocalDateTime joinedAt) {
    }

    @Schema(description = "举报消息请求")
    public record ReportRequest(
            @Schema(description = "举报理由，必填", maxLength = 500)
            @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "举报记录")
    public record ReportResponse(
            @Schema(description = "举报唯一标识", example = "rpt_1234567890") String id,
            @Schema(description = "被举报消息唯一标识", example = "msg_1234567890") String messageId,
            @Schema(description = "消息所在群唯一标识", example = "grp_1234567890") String groupId,
            @Schema(description = "举报人唯一标识", example = "u_1234567890") String reporterUid,
            @Schema(description = "举报人展示名", example = "李四") String reporterName,
            @Schema(description = "被举报消息内容", example = "……") String messageContent,
            @Schema(description = "举报理由", example = "疑似诈骗") String reason,
            @Schema(description = "状态：PENDING 待处理 / RESOLVED 已受理（撤回消息）/ DISMISSED 已驳回",
                    example = "PENDING") String status,
            @Schema(description = "处理意见", example = "已撤回并提醒当事人") String decisionNote,
            @Schema(description = "处理时间") LocalDateTime handledAt,
            @Schema(description = "举报时间") LocalDateTime createdAt) {
    }

    @Schema(description = "机构处理举报请求")
    public record ReportDecisionRequest(
            @Schema(description = "true=受理并撤回被举报消息；false=驳回", example = "true")
            @NotNull Boolean withdrawMessage,
            @Schema(description = "处理意见（可选）", maxLength = 500)
            @Size(max = 500) String note) {
    }
}
