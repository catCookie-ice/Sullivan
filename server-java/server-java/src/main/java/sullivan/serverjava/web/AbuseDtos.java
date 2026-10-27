package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 举报治理 DTO（志愿者/机构发起与申诉 + 管理员处置共用）。 */
public final class AbuseDtos {
    private AbuseDtos() {
    }

    @Schema(description = "发起举报请求")
    public record CreateReportRequest(
            @Schema(description = "举报类别：ACTIVITY 活动 / ORGANIZATION 机构 / GROUP 群聊 / USER 个人",
                    example = "ACTIVITY") @NotBlank @Size(max = 16) String category,
            @Schema(description = "举报对象唯一标识（按类别填对应 id）", example = "act_1234567890")
            @NotBlank @Size(max = 26) String targetId,
            @Schema(description = "举报理由", maxLength = 500) @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "反向举报（恶意举报反诉）请求")
    public record CounterReportRequest(
            @Schema(description = "反诉理由", maxLength = 500) @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "我的举报记录")
    public record ReportResponse(
            @Schema(description = "举报唯一标识") String id,
            @Schema(description = "类别：ACTIVITY/ORGANIZATION/GROUP/USER/REPORT_ABUSE") String category,
            @Schema(description = "状态：PENDING/RESOLVED/DISMISSED") String status,
            String reason,
            @Schema(description = "处置意见") String decisionNote,
            LocalDateTime handledAt,
            LocalDateTime createdAt) {
    }

    @Schema(description = "针对我的举报（只返回被驳回的，供反诉；举报人匿名，不下发处置意见）")
    public record ReceivedReportResponse(
            String id,
            String category,
            String reason,
            LocalDateTime handledAt,
            @Schema(description = "可反诉：尚未反诉过", example = "true") boolean counterReportable,
            LocalDateTime createdAt) {
    }

    @Schema(description = "管理员视角的举报记录（含举报人与目标明细）")
    public record AdminReportResponse(            String id,
            String category,
            String status,
            String reporterUid,
            @Schema(description = "举报人登录名") String reporterName,
            String reason,
            String targetActivityId,
            String targetOrgId,
            String targetGroupId,
            String targetUserId,
            @Schema(description = "目标摘要（标题/名称/登录名）") String targetSummary,
            String sourceReportId,
            String handledBy,
            LocalDateTime handledAt,
            String decisionNote,
            String penaltyId,
            LocalDateTime createdAt) {
    }

    @Schema(description = "同一对象的举报合并视图：一个对象一条，展开看每条举报")
    public record MergedReportGroup(
            String category,
            @Schema(description = "目标摘要（标题/名称/登录名）") String targetSummary,
            String targetActivityId,
            String targetOrgId,
            String targetGroupId,
            String targetUserId,
            @Schema(description = "该对象的举报总数") long reportCount,
            @Schema(description = "其中待处理数") long pendingCount,
            @Schema(description = "最新一条举报的状态") String latestStatus,
            LocalDateTime latestCreatedAt,
            @Schema(description = "按时间倒序的全部举报") List<AdminReportResponse> reports) {
    }

    @Schema(description = "预设处罚的附带组件：作用于另一对象类型，取该对象的基础惩罚集")
    public record PresetPartRequest(
            @Schema(description = "附带对象：ACTIVITY / ORGANIZATION / GROUP（须与预设对象不同）") @NotBlank String targetKind,
            @Schema(description = "该对象的基础惩罚") @NotBlank String action,
            @Schema(description = "附带时长天数；不填=跟随主处罚时长") @Min(0) Integer durationDays) {
    }

    @Schema(description = "附带组件（查看）")
    public record PresetPartResponse(
            String targetKind,
            String action,
            Integer durationDays) {
    }

    @Schema(description = "预设处罚申请/查看")
    public record PresetRequest(
            @Schema(description = "处罚名，如「永久封群并下架活动」", maxLength = 100)
            @NotBlank @Size(max = 100) String name,
            @Schema(description = "作用对象：ACTIVITY / ORGANIZATION / GROUP / USER") @NotBlank @Size(max = 16) String category,
            @Schema(description = "主基础惩罚（该对象的基础惩罚集之一）") @NotBlank @Size(max = 32) String action,
            @Schema(description = "主处罚时长天数（纯永久动作不填；不填=永久）", example = "3") @Min(0) Integer durationDays,
            @Schema(description = "附带组件（跨对象，可复合共存）") @Size(max = 3) List<PresetPartRequest> parts,
            @Schema(description = "适用场景说明", maxLength = 500) @Size(max = 500) String description) {
    }

    @Schema(description = "预设处罚")
    public record PresetResponse(
            String id,
            String name,
            String category,
            String action,
            Integer durationDays,
            @Schema(description = "附带组件") List<PresetPartResponse> parts,
            String description,
            String status,
            String createdBy,
            String approvedBy,
            LocalDateTime handledAt,
            LocalDateTime createdAt) {
    }

    @Schema(description = "举报处置请求：驳回，或从预设处罚中选择一项施加")
    public record PenaltyDecisionRequest(
            @Schema(description = "true=驳回举报；false=施加处罚", example = "true") @NotNull Boolean dismiss,
            @Schema(description = "预设处罚 id（dismiss=false 时必填）") @Size(max = 26) String presetId,
            @Schema(description = "处置意见", maxLength = 500) @Size(max = 500) String note) {
    }

    @Schema(description = "预设处罚审批请求")
    public record PresetApprovalRequest(
            @Schema(description = "true=批准生效；false=驳回", example = "true") @NotNull Boolean approve) {
    }

    @Schema(description = "申诉请求")
    public record AppealCreateRequest(
            @Schema(description = "申诉理由", maxLength = 500) @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "申诉处理请求")
    public record AppealDecisionRequest(
            @Schema(description = "true=维持处罚；false=撤销处罚", example = "false") @NotNull Boolean uphold,
            @Schema(description = "处理意见", maxLength = 500) @Size(max = 500) String note) {
    }

    @Schema(description = "处罚记录（被处罚方视角）")
    public record PenaltyResponse(
            String id,
            String category,
            String action,
            String presetName,
            Integer durationDays,
            String reason,
            String status,
            LocalDateTime appliedAt,
            LocalDateTime expiresAt,
            @Schema(description = "可申诉：处罚生效中且尚未申诉过", example = "true") boolean appealable) {
    }

    @Schema(description = "申诉记录")
    public record AppealResponse(
            String id,
            String penaltyId,
            String appellantType,
            String status,
            String reason,
            String decisionNote,
            LocalDateTime handledAt,
            LocalDateTime createdAt) {
    }

    @Schema(description = "目标档案：历史被举报记录 + 违规（处罚）记录")
    public record TargetHistoryResponse(
            List<AdminReportResponse> reports,
            List<PenaltyResponse> penalties) {
    }
}
