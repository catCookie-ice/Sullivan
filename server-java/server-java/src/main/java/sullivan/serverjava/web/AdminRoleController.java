package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AdminGovernService;
import sullivan.serverjava.web.AdminGovernDtos.PermissionResponse;
import sullivan.serverjava.web.AdminGovernDtos.RoleCreateRequest;
import sullivan.serverjava.web.AdminGovernDtos.RolePermissionsRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleResponse;

import java.util.List;

/**
 * 管理员角色与权限集治理接口。
 *
 * <p>角色是权限集的具名打包（HR_ADMIN、OPERATIONS 是出厂例子，可新建自定义角色）。
 * 查看角色/权限字典要求 ADMIN_MANAGE（注册码签发页也用它取角色清单）；
 * 新建角色、调整权限集、停用角色要求 ADMIN_GOVERN。
 * 权限集变更经 AdminAccessService 逐请求生效，无需等旧 JWT 过期。</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "管理员·角色与权限集", description = "角色=权限集的具名打包；查看需 ADMIN_MANAGE，变更需 ADMIN_GOVERN。")
@SecurityRequirement(name = "bearerAuth")
public class AdminRoleController {
    private final AdminGovernService governService;

    public AdminRoleController(AdminGovernService governService) {
        this.governService = governService;
    }

    @GetMapping("/roles")
    @Operation(summary = "角色列表",
            description = "列出全部启用中的角色，含各自当前权限集与生效成员数；系统内置角色带 system 标记。"
                    + "需 ADMIN_MANAGE 权限（注册码签发页用本接口取可选角色）。")
    public List<RoleResponse> listRoles(Authentication authentication) {
        require(authentication, "ADMIN_MANAGE");
        return governService.listRoles();
    }

    @GetMapping("/permissions")
    @Operation(summary = "权限代码字典",
            description = "列出全部启用中的权限代码及其说明，供角色编辑器勾选。需 ADMIN_MANAGE 权限。")
    public List<PermissionResponse> listPermissions(Authentication authentication) {
        require(authentication, "ADMIN_MANAGE");
        return governService.listPermissions();
    }

    @PostMapping("/roles")
    @Operation(summary = "新建角色",
            description = "新建自定义角色并指定初始权限集（角色代码：大写字母开头，仅大写字母/数字/下划线，2–64 位）。"
                    + "需 ADMIN_GOVERN 权限。")
    public RoleResponse createRole(@Valid @RequestBody RoleCreateRequest request, Authentication authentication) {
        require(authentication, "ADMIN_GOVERN");
        return governService.createRole(request);
    }

    @PutMapping("/roles/{roleCode}/permissions")
    @Operation(summary = "调整角色权限集（全量覆盖）",
            description = "以请求中的权限代码集合全量覆盖该角色的权限：缺少的授予、多余的撤销，变更立即生效。"
                    + "SUPER_ADMIN 角色固定全量不可调整。需 ADMIN_GOVERN 权限。")
    public RoleResponse updateRolePermissions(
            @Parameter(description = "角色代码", example = "OPERATIONS")
            @PathVariable String roleCode,
            @Valid @RequestBody RolePermissionsRequest request,
            Authentication authentication) {
        require(authentication, "ADMIN_GOVERN");
        return governService.updateRolePermissions(roleCode, request);
    }

    @DeleteMapping("/roles/{roleCode}")
    @Operation(summary = "停用角色",
            description = "停用自定义角色（非物理删除：角色行被注册码与授权关系引用）。"
                    + "仍有生效成员或可用注册码时返回 409；系统内置角色不可停用。需 ADMIN_GOVERN 权限。")
    public void disableRole(
            @Parameter(description = "角色代码", example = "AUDITOR")
            @PathVariable String roleCode,
            Authentication authentication) {
        require(authentication, "ADMIN_GOVERN");
        governService.disableRole(roleCode);
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
