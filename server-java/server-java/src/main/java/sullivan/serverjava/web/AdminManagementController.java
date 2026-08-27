package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AdminManagementService;
import sullivan.serverjava.web.AdminManagementDtos.AdminOrgResponse;
import sullivan.serverjava.web.AdminManagementDtos.AdminUserResponse;
import sullivan.serverjava.web.AdminManagementDtos.OrgReviewRequest;
import sullivan.serverjava.web.AdminManagementDtos.UserDeleteResult;

/**
 * 管理端用户与机构管理接口。
 *
 * <p>用户管理（监护/受护/志愿者）要求 USER_MANAGE 权限，机构管理要求 ORG_MANAGE 权限。</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "管理员·用户与机构管理", description = "需 ADMIN 登录并具备 USER_MANAGE（用户管理）/ ORG_MANAGE（机构管理）权限。")
@SecurityRequirement(name = "bearerAuth")
public class AdminManagementController {
    private final AdminManagementService managementService;

    public AdminManagementController(AdminManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/users")
    @Operation(summary = "用户列表（监护人/受护人/志愿者）",
            description = "按角色分页列出用户。role=VOLUNTEER 时一并返回兼任志愿者的监护人；"
                    + "每个用户均带 roles（当前生效角色）与 alsoVolunteer（是否兼任志愿者）字段。"
                    + "支持按登录名或显示名关键字搜索。")
    public IPage<AdminUserResponse> listUsers(
            @Parameter(description = "角色过滤：GUARDIAN / PROTECTEE / VOLUNTEER", example = "VOLUNTEER")
            @RequestParam(required = false) String role,
            @Parameter(description = "关键字：匹配登录名或显示名", example = "张")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        require(authentication, "USER_MANAGE");
        return managementService.listUsers(role, keyword, page, size);
    }

    @DeleteMapping("/users/{userId}")
    @Operation(summary = "删除用户（逻辑删除）",
            description = "逻辑删除用户账号。若目标用户是兼任志愿者的监护人，则仅取消其志愿者兼任"
                    + "（撤销 VOLUNTEER 角色），账号保留；其余情况逻辑删除整个账号。"
                    + "响应中的 action 区分两种结果。")
    public UserDeleteResult deleteUser(
            @Parameter(description = "自然人账号唯一标识", example = "u_1234567890")
            @PathVariable String userId,
            Authentication authentication) {
        require(authentication, "USER_MANAGE");
        return managementService.deleteUser(userId);
    }

    @GetMapping("/organizations")
    @Operation(summary = "机构列表",
            description = "按状态分页列出机构，支持 status 过滤（PENDING / APPROVED / REJECTED / DISABLED）。")
    public IPage<AdminOrgResponse> listOrganizations(
            @Parameter(description = "状态过滤：PENDING / APPROVED / REJECTED / DISABLED", example = "PENDING")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        require(authentication, "ORG_MANAGE");
        return managementService.listOrganizations(status, page, size);
    }

    @DeleteMapping("/organizations/{organizationId}")
    @Operation(summary = "删除机构（逻辑删除）",
            description = "逻辑删除机构账号（写入 deleted_at，查询不再可见）。")
    public void deleteOrganization(
            @Parameter(description = "机构唯一标识", example = "o_1234567890")
            @PathVariable String organizationId,
            Authentication authentication) {
        require(authentication, "ORG_MANAGE");
        managementService.deleteOrganization(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/review")
    @Operation(summary = "审核机构（通过/驳回）",
            description = "仅 PENDING 状态的机构可审。通过置为 APPROVED；驳回置为 REJECTED 且必须填写审核意见。"
                    + "记录审核人、审核时间与审核意见。需 ORG_REVIEW 权限。")
    public AdminOrgResponse reviewOrganization(
            @Parameter(description = "机构唯一标识", example = "o_1234567890")
            @PathVariable String organizationId,
            @RequestBody OrgReviewRequest request,
            Authentication authentication) {
        AuthPrincipal principal = require(authentication, "ORG_REVIEW");
        return managementService.reviewOrganization(principal.id(), organizationId, request);
    }

    private AuthPrincipal require(Authentication authentication, String permission) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!"ADMIN".equals(principal.principalType()) || !principal.hasPermission(permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要 " + permission + " 权限");
        }
        return principal;
    }
}
