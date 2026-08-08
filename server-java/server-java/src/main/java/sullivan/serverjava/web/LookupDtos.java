package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

public final class LookupDtos {
    private LookupDtos() {
    }

    public record UserLookupResponse(
            @Schema(description = "自然人账号唯一标识", example = "u_1234567890") String id,
            @Schema(description = "账号状态：ENABLED（启用）/ DISABLED（禁用）",
                    example = "ENABLED", allowableValues = {"ENABLED", "DISABLED"}) String status,
            @Schema(description = "登录名（全局唯一）", example = "zhangsan") String loginName,
            @Schema(description = "显示名（昵称）", example = "张三") String displayName,
            @Schema(description = "账号主角色：PROTECTEE（受护人）/ GUARDIAN（监护人）/ VOLUNTEER（志愿者）",
                    example = "GUARDIAN",
                    allowableValues = {"PROTECTEE", "GUARDIAN", "VOLUNTEER"}) String roleCode,
            @Schema(description = "当前生效的角色集合（监护人可兼任志愿者）",
                    example = "[\"GUARDIAN\",\"VOLUNTEER\"]") Set<String> roles,
            @Schema(description = "是否兼任志愿者：同时具备 GUARDIAN 与 VOLUNTEER 角色",
                    example = "true") boolean alsoVolunteer) {
    }

    public record OrganizationLookupResponse(
            @Schema(description = "机构唯一标识", example = "o_1234567890") String id,
            @Schema(description = "机构状态：PENDING（待审核）/ APPROVED（已通过）/ REJECTED（已拒绝）/ DISABLED（已禁用）",
                    example = "APPROVED",
                    allowableValues = {"PENDING", "APPROVED", "REJECTED", "DISABLED"}) String status,
            @Schema(description = "机构登录名（全局唯一）", example = "blindsociety") String loginName,
            @Schema(description = "机构注册全称（法律名称）", example = "北京市朝阳区盲人协会") String legalName,
            @Schema(description = "机构对外展示名称", example = "朝阳盲协") String publicName) {
    }

    public record AdminLookupResponse(
            @Schema(description = "管理员账号唯一标识", example = "a_1234567890") String id,
            @Schema(description = "管理员状态：ENABLED（启用）/ DISABLED（禁用）",
                    example = "ENABLED", allowableValues = {"ENABLED", "DISABLED"}) String status,
            @Schema(description = "管理员登录名（全局唯一）", example = "admin01") String loginName,
            @Schema(description = "管理员显示名", example = "张管理") String displayName,
            @Schema(description = "管理员当前生效角色代码集合",
                    example = "[\"HR_ADMIN\"]") Set<String> roles) {
    }
}
