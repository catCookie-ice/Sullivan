package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.LookupService;
import sullivan.serverjava.web.LookupDtos.AdminLookupResponse;
import sullivan.serverjava.web.LookupDtos.OrganizationLookupResponse;
import sullivan.serverjava.web.LookupDtos.UserLookupResponse;

/**
 * 按唯一标识（ID）或登录名查找各业务对象。
 *
 * <p>志愿者、监护人、受护人均为自然人账号，可通过 {@code GET /users/{id}} 按 ID 或
 * {@code GET /users/by-login-name/{loginName}} 按登录名查找，以返回的 roles 区分当前角色；
 * 机构与管理员各有独立接口。</p>
 *
 * <p><b>面向用户的查找一律用登录名</b>（00 §7 第 11 条）：账号 ID 是 26 位随机串，
 * 既记不住也抄不对，只适合作为内部主键。按 ID 的端点保留，供已有客户端与调试使用，
 * 但不得再作为界面上唯一的入口。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "对象查找", description = "按登录名或唯一标识查找业务对象。需登录；返回字段不含密码哈希等敏感信息。")
@SecurityRequirement(name = "bearerAuth")
public class LookupController {
    private final LookupService lookupService;

    public LookupController(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    @GetMapping("/users/by-login-name/{loginName}")
    @Operation(summary = "按登录名查找自然人（志愿者/监护人/受护人）",
            description = "精确匹配登录名。志愿者、监护人、受护人共用本接口，以 roles 区分。"
                    + "不存在时返回 404，提示语与「无权查看」一致，避免被用来枚举账号。"
                    + "这是界面应当使用的查找方式。")
    public UserLookupResponse findUserByLoginName(
            @Parameter(description = "自然人登录名（全局唯一，精确匹配）", example = "protectee_wang")
            @PathVariable String loginName) {
        return lookupService.findUserByLoginName(loginName);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "按唯一标识查找自然人（志愿者/监护人/受护人）",
            description = "任何已登录用户可按账号 ID 查找自然人，返回其状态、显示名与当前生效角色集合。"
                    + "志愿者、监护人、受护人共用本接口，以 roles 区分。"
                    + "该端点保留给内部与调试使用；界面应改用 by-login-name。")
    public UserLookupResponse findUser(
            @Parameter(description = "自然人账号唯一标识", example = "u_1234567890")
            @PathVariable String userId) {
        return lookupService.findUser(userId);
    }

    @GetMapping("/organizations/{organizationId}")
    @Operation(summary = "按唯一标识查找公益机构",
            description = "任何已登录用户可按机构 ID 查找机构，返回其状态、登录名与注册/展示名称。")
    public OrganizationLookupResponse findOrganization(
            @Parameter(description = "机构唯一标识", example = "o_1234567890")
            @PathVariable String organizationId) {
        return lookupService.findOrganization(organizationId);
    }

    @GetMapping("/admins/{adminId}")
    @Operation(summary = "按唯一标识查找管理员（需 ADMIN_MANAGE 权限）",
            description = "仅最高管理员与 HR 管理员（持有 ADMIN_MANAGE 权限）可按管理员 ID 查找，"
                    + "返回其状态、登录名、显示名与当前角色集合；其他主体返回 403。")
    public AdminLookupResponse findAdmin(
            @Parameter(description = "管理员账号唯一标识", example = "a_1234567890")
            @PathVariable String adminId,
            Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!"ADMIN".equals(principal.principalType()) || !principal.hasPermission("ADMIN_MANAGE")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要 ADMIN_MANAGE 权限");
        }
        return lookupService.findAdmin(adminId);
    }
}
