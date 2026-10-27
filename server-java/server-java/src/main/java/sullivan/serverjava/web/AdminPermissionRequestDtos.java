package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/** 权限申请 DTO。发起不要求任何权限，只要是管理员；审批须 ADMIN_GOVERN。 */
public final class AdminPermissionRequestDtos {
    private AdminPermissionRequestDtos() {
    }

    @Schema(description = "发起权限申请：申请的是「我」要这项权限，不涉及角色")
    public record CreateRequest(
            @Schema(description = "申请开通的权限代码（须在权限字典中）", example = "ABUSE_REVIEW")
            @NotBlank @Size(max = 64) String permissionCode,
            @Schema(description = "申请理由", maxLength = 500) @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(description = "审批请求")
    public record DecisionRequest(
            @Schema(description = "true=批准并给申请人开通；false=驳回", example = "true") @NotNull Boolean approve,
            @Schema(description = "审批意见", maxLength = 500) @Size(max = 500) String note) {
    }

    @Schema(description = "权限申请记录")
    public record PermissionRequestResponse(
            String id,
            String permissionCode,
            @Schema(description = "权限显示名（来自字典）") String permissionName,
            String status,
            String reason,
            String decisionNote,
            String requestedBy,
            String handledBy,
            LocalDateTime handledAt,
            LocalDateTime createdAt) {
    }

    @Schema(description = "权限字典项（申请表单下拉用）")
    public record PermissionOption(String code, String name) {
    }
}
