package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AdminGovernService;
import sullivan.serverjava.web.AdminGovernDtos.AdminAccountResponse;
import sullivan.serverjava.web.AdminGovernDtos.AdminStatusRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleGrantRequest;

/**
 * 管理员账号治理接口。
 *
 * <p>查看（列表/详情）要求 ADMIN_MANAGE（HR 管理员的只读窗口）；
 * 变更（启停/授予/撤销角色）要求 ADMIN_GOVERN（管理员治理，出厂仅 SUPER_ADMIN 持有，
 * 可经角色管理授予其他角色）。角色与权限逐请求从库中解析，变更对下一个请求立即生效。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/admins")
@Tag(name = "管理员·账号治理", description = "查看需 ADMIN_MANAGE；启停与角色授予/撤销需 ADMIN_GOVERN。")
@SecurityRequirement(name = "bearerAuth")
public class AdminAccountController {
    private final AdminGovernService governService;

    public AdminAccountController(AdminGovernService governService) {
        this.governService = governService;
    }

    @GetMapping
    @Operation(summary = "管理员列表",
            description = "分页列出管理员账号（不含任何凭据字段），每个账号带当前生效角色与汇总权限。"
                    + "支持按登录名或显示名关键字搜索。需 ADMIN_MANAGE 权限。")
    public IPage<AdminAccountResponse> listAdmins(
            @Parameter(description = "关键字：匹配登录名或显示名", example = "hr")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        require(authentication, "ADMIN_MANAGE");
        return governService.listAdmins(keyword, page, size);
    }

    @GetMapping("/{adminId}")
    @Operation(summary = "管理员详情",
            description = "返回管理员的账号信息、当前生效角色与汇总权限。需 ADMIN_MANAGE 权限。")
    public AdminAccountResponse getAdmin(
            @Parameter(description = "管理员账号唯一标识", example = "a_1234567890")
            @PathVariable String adminId,
            Authentication authentication) {
        require(authentication, "ADMIN_MANAGE");
        return governService.getAdmin(adminId);
    }

    @PostMapping("/{adminId}/status")
    @Operation(summary = "启用/停用管理员账号",
            description = "停用立即生效（认证逐请求查库，被停用者下一个请求即 401）。"
                    + "不能停用自己；停用后系统必须仍保有其他可用的最高管理员。需 ADMIN_GOVERN 权限。")
    public AdminAccountResponse setStatus(
            @Parameter(description = "管理员账号唯一标识", example = "a_1234567890")
            @PathVariable String adminId,
            @Valid @RequestBody AdminStatusRequest request,
            Authentication authentication) {
        AuthPrincipal actor = require(authentication, "ADMIN_GOVERN");
        return governService.setStatus(actor.id(), adminId, request);
    }

    @PostMapping("/{adminId}/roles")
    @Operation(summary = "授予角色",
            description = "为管理员授予一个角色，可带过期时间（空为永久）。同一管理员同一角色至多一条生效关系，"
                    + "撤销后再授予会复用原记录。授予 SUPER_ADMIN 仅限本身持有该角色的管理员。需 ADMIN_GOVERN 权限。")
    public AdminAccountResponse grantRole(
            @Parameter(description = "管理员账号唯一标识", example = "a_1234567890")
            @PathVariable String adminId,
            @Valid @RequestBody RoleGrantRequest request,
            Authentication authentication) {
        AuthPrincipal actor = require(authentication, "ADMIN_GOVERN");
        return governService.grantRole(actor, adminId, request);
    }

    @DeleteMapping("/{adminId}/roles/{roleCode}")
    @Operation(summary = "撤销角色",
            description = "撤销管理员的某个生效角色（保留审计记录）。撤销后系统必须仍保有其他可用的最高管理员。"
                    + "需 ADMIN_GOVERN 权限。")
    public AdminAccountResponse revokeRole(
            @Parameter(description = "管理员账号唯一标识", example = "a_1234567890")
            @PathVariable String adminId,
            @Parameter(description = "角色代码", example = "OPERATIONS")
            @PathVariable String roleCode,
            Authentication authentication) {
        require(authentication, "ADMIN_GOVERN");
        return governService.revokeRole(adminId, roleCode);
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
