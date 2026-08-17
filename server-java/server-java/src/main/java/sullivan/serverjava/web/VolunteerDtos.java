package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 志愿者兼任申请 DTO：提交、我的申请、管理员审核。
 */
public final class VolunteerDtos {
    private VolunteerDtos() {
    }

    public record VolunteerApplicationRequest(
            @Schema(description = "申请理由（可选）", example = "希望周末参与社区志愿活动", maxLength = 1000)
            @Size(max = 1000) String reason) {
    }

    public record VolunteerApplicationResponse(
            @Schema(description = "申请唯一标识", example = "va_1234567890") String id,
            @Schema(description = "状态：PENDING（待审核）/ APPROVED（已通过）/ REJECTED（已拒绝）/ CANCELLED（已撤销）",
                    example = "PENDING", allowableValues = {"PENDING", "APPROVED", "REJECTED", "CANCELLED"}) String status,
            @Schema(description = "申请人账号 ID", example = "u_1111111111") String applicantUid,
            @Schema(description = "申请理由", example = "希望周末参与社区志愿活动") String reason,
            @Schema(description = "申请时间", example = "2026-08-17T10:00:00") LocalDateTime createdAt) {
    }

    public record VolunteerApplicationAdminResponse(
            @Schema(description = "申请唯一标识", example = "va_1234567890") String id,
            @Schema(description = "状态", example = "PENDING") String status,
            @Schema(description = "申请人账号 ID", example = "u_1111111111") String applicantUid,
            @Schema(description = "申请人登录名", example = "zhangsan") String loginName,
            @Schema(description = "申请人显示名", example = "张三") String displayName,
            @Schema(description = "申请人账号注册时间（用于审核注册时长）",
                    example = "2026-04-01T09:00:00") LocalDateTime applicantCreatedAt,
            @Schema(description = "申请理由", example = "希望周末参与社区志愿活动") String reason,
            @Schema(description = "审核管理员 ID", example = "a_1234567890") String decidedByAdminId,
            @Schema(description = "审核时间", example = "2026-08-17T11:00:00") LocalDateTime decidedAt,
            @Schema(description = "审核意见/拒绝原因", example = "资料齐全，同意") String decisionNote) {
    }
}
