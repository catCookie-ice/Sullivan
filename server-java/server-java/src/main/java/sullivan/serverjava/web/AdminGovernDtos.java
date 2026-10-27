package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 管理员治理（管理员账号管理 + 角色与权限集管理）的请求/响应 DTO。
 *
 * <p>读取接口要求 ADMIN_MANAGE（HR 管理员可查看），写接口要求 ADMIN_GOVERN（管理员治理）。</p>
 */
public final class AdminGovernDtos {
    private AdminGovernDtos() {
    }

    @Schema(description = "管理员账号（列表项与详情共用；不含任何凭据字段）")
    public record AdminAccountResponse(
            @Schema(description = "管理员账号唯一标识", example = "a_1234567890") String id,
            @Schema(description = "账号状态：ENABLED（启用）/ DISABLED（禁用）",
                    example = "ENABLED", allowableValues = {"ENABLED", "DISABLED"}) String status,
            @Schema(description = "登录名（全局唯一）", example = "hr01") String loginName,
            @Schema(description = "显示名", example = "人事张姐") String displayName,
            @Schema(description = "当前生效角色代码集合（ACTIVE 且未过期）",
                    example = "[\"HR_ADMIN\"]") Set<String> roles,
            @Schema(description = "上述角色汇总到的生效权限代码集合",
                    example = "[\"ADMIN_MANAGE\",\"ADMIN_REGISTRATION_CODE_CREATE\"]") Set<String> permissions) {
    }

    public record AdminStatusRequest(
            @Schema(description = "目标状态：ENABLED 启用 / DISABLED 禁用",
                    example = "DISABLED", allowableValues = {"ENABLED", "DISABLED"})
            @NotBlank
            @Pattern(regexp = "ENABLED|DISABLED", message = "status 只能是 ENABLED 或 DISABLED")
            String status) {
    }

    public record RoleGrantRequest(
            @Schema(description = "要授予的角色代码", example = "OPERATIONS")
            @NotBlank
            String roleCode,
            @Schema(description = "过期时间；空表示永久授予。必须是未来时间", example = "2026-12-31T23:59:59")
            LocalDateTime expiresAt) {
    }

    @Schema(description = "管理员角色（权限集的具名打包；运行期可由 ADMIN_GOVERN 调整）")
    public record RoleResponse(
            @Schema(description = "角色唯一标识", example = "r_1234567890") String id,
            @Schema(description = "角色代码（全局唯一）", example = "OPERATIONS") String roleCode,
            @Schema(description = "角色显示名", example = "运营管理员") String roleName,
            @Schema(description = "是否系统内置角色；内置角色不可删除，SUPER_ADMIN 权限集固定全量",
                    example = "true") boolean system,
            @Schema(description = "角色当前持有的权限代码集合",
                    example = "[\"USER_MANAGE\",\"ORG_MANAGE\"]") Set<String> permissions,
            @Schema(description = "当前持有该角色（生效关系）的管理员数量", example = "2") long memberCount) {
    }

    @Schema(description = "权限代码字典项")
    public record PermissionResponse(
            @Schema(description = "权限代码", example = "ORG_REVIEW") String code,
            @Schema(description = "权限说明", example = "机构审核") String name) {
    }

    public record RoleCreateRequest(
            @Schema(description = "角色代码：大写字母开头，仅大写字母/数字/下划线，2–64 位",
                    example = "AUDITOR")
            @NotBlank
            @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "角色代码必须是大写字母开头的大写字母/数字/下划线组合（2–64 位）")
            String roleCode,
            @Schema(description = "角色显示名", example = "审计员")
            @NotBlank
            @Size(max = 100, message = "角色显示名不能超过 100 字")
            String roleName,
            @Schema(description = "初始权限代码集合；可为空集")
            Set<String> permissionCodes) {
    }

    public record RolePermissionsRequest(
            @Schema(description = "角色应持有的完整权限代码集合（全量覆盖式设置）",
                    example = "[\"USER_MANAGE\",\"OPERATION_STATS\"]")
            @NotNull(message = "permissionCodes 不能为 null（清空请传空数组）")
            Set<String> permissionCodes) {
    }
}
