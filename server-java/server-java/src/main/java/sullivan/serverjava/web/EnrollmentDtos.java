package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动报名 DTO：志愿者报名/撤回/我的报名，机构审核与名额统计。
 *
 * <p>名额（approved_count）只统计 APPROVED；开启「需审核」的活动先进 PENDING，
 * 机构批准时占名额并入活动群。</p>
 */
public final class EnrollmentDtos {
    private EnrollmentDtos() {
    }

    @Schema(description = "机构对报名的审核决定")
    public record DecisionRequest(
            @Schema(description = "true=通过（占名额并入群），false=拒绝", example = "true")
            @NotNull Boolean approve,
            @Schema(description = "审核意见/拒绝原因（可选）", maxLength = 1000)
            @Size(max = 1000) String reason) {
    }

    @Schema(description = "报名记录")
    public record EnrollmentResponse(
            @Schema(description = "报名唯一标识", example = "enr_1234567890") String id,
            @Schema(description = "活动唯一标识", example = "act_1234567890") String activityId,
            @Schema(description = "活动标题", example = "朝阳区视障人士徒步陪伴") String activityTitle,
            @Schema(description = "主办机构展示名", example = "朝阳盲协") String organizationPublicName,
            @Schema(description = "志愿者账号唯一标识", example = "u_1234567890") String volunteerUid,
            @Schema(description = "志愿者登录名", example = "zhangsan") String volunteerLoginName,
            @Schema(description = "志愿者展示昵称", example = "张三") String volunteerDisplayName,
            @Schema(description = "状态：PENDING 待审 / APPROVED 已通过 / REJECTED 已拒绝 / CANCELLED 已撤回",
                    example = "PENDING") String status,
            @Schema(description = "审核意见/拒绝原因", example = "欢迎加入") String decisionReason,
            @Schema(description = "审核时间") LocalDateTime decidedAt,
            @Schema(description = "报名时间") LocalDateTime createdAt) {
    }

    @Schema(description = "某活动的报名统计（名额只统计 APPROVED）")
    public record EnrollmentSummary(
            @Schema(description = "总报名人数（全部状态）", example = "35") long total,
            @Schema(description = "待审核人数", example = "8") long pending,
            @Schema(description = "已通过人数（=当前占用名额）", example = "20") long approved,
            @Schema(description = "已拒绝人数", example = "5") long rejected,
            @Schema(description = "已撤回人数", example = "2") long cancelled) {
    }

    @Schema(description = "机构视角的报名分页（附统计）")
    public record EnrollmentPage(
            @Schema(description = "当前页报名记录") List<EnrollmentResponse> records,
            @Schema(description = "过滤后的总条数", example = "35") long total,
            @Schema(description = "页码，从 1 开始", example = "1") long page,
            @Schema(description = "每页条数", example = "20") long size,
            @Schema(description = "各状态统计") EnrollmentSummary summary) {
    }
}
