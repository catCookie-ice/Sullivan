package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Set;

public final class AdminManagementDtos {
    private AdminManagementDtos() {
    }

    public record AdminUserResponse(
            @Schema(description = "自然人账号唯一标识", example = "u_1234567890") String id,
            @Schema(description = "账号状态：ENABLED（启用）/ DISABLED（禁用）",
                    example = "ENABLED", allowableValues = {"ENABLED", "DISABLED"}) String status,
            @Schema(description = "登录名（全局唯一）", example = "zhangsan") String loginName,
            @Schema(description = "显示名（昵称）", example = "张三") String displayName,
            @Schema(description = "账号主角色：PROTECTEE / GUARDIAN / VOLUNTEER",
                    example = "GUARDIAN", allowableValues = {"PROTECTEE", "GUARDIAN", "VOLUNTEER"}) String roleCode,
            @Schema(description = "当前生效角色集合（含主角色；监护人可兼任志愿者）",
                    example = "[\"GUARDIAN\",\"VOLUNTEER\"]") Set<String> roles,
            @Schema(description = "是否兼任志愿者：同时具备 GUARDIAN 与 VOLUNTEER 角色",
                    example = "true") boolean alsoVolunteer,
            @Schema(description = "注册时间，格式 yyyy-MM-dd HH:mm:ss",
                    example = "2026-08-08 15:00:00") LocalDateTime createdAt) {
    }

    public record AdminOrgResponse(
            @Schema(description = "机构唯一标识", example = "o_1234567890") String id,
            @Schema(description = "机构状态：PENDING / APPROVED / REJECTED / DISABLED",
                    example = "PENDING",
                    allowableValues = {"PENDING", "APPROVED", "REJECTED", "DISABLED"}) String status,
            @Schema(description = "机构登录名（全局唯一）", example = "blindsociety") String loginName,
            @Schema(description = "机构注册全称（法律名称）", example = "北京市朝阳区盲人协会") String legalName,
            @Schema(description = "机构对外展示名称", example = "朝阳盲协") String publicName,
            @Schema(description = "注册时间，格式 yyyy-MM-dd HH:mm:ss",
                    example = "2026-08-08 15:00:00") LocalDateTime createdAt) {
    }

    public record UserDeleteResult(
            @Schema(description = "账号是否被逻辑删除：false 表示仅取消了志愿者兼任",
                    example = "false") boolean accountDeleted,
            @Schema(description = "操作结果：ACCOUNT_DELETED（账号已逻辑删除）/ VOLUNTEER_ROLE_REVOKED（仅取消志愿者兼任）",
                    example = "VOLUNTEER_ROLE_REVOKED",
                    allowableValues = {"ACCOUNT_DELETED", "VOLUNTEER_ROLE_REVOKED"}) String action,
            @Schema(description = "中文说明", example = "该用户是兼任志愿者，仅取消其志愿者角色，账号保留") String message) {
    }
}
