package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.VolunteerApplicationService;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationAdminResponse;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationRequest;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationResponse;

/**
 * 管理员·志愿者兼任申请审核。需 VOLUNTEER_REVIEW 权限（由 SUPER_ADMIN 显式授予）。
 */
@RestController
@RequestMapping("/api/v1/admin/volunteer-applications")
@Tag(name = "管理员·志愿者兼任审核", description = "需 ADMIN 登录并具备 VOLUNTEER_REVIEW 权限。")
@SecurityRequirement(name = "bearerAuth")
public class AdminVolunteerController {
    private final VolunteerApplicationService volunteerApplicationService;

    public AdminVolunteerController(VolunteerApplicationService volunteerApplicationService) {
        this.volunteerApplicationService = volunteerApplicationService;
    }

    @GetMapping
    @Operation(summary = "志愿者兼任申请列表",
            description = "按状态分页列出申请，含申请人登录名/显示名/注册时间（用于注册时长审核）。")
    public IPage<VolunteerApplicationAdminResponse> listApplications(
            @Parameter(description = "状态过滤：PENDING / APPROVED / REJECTED / CANCELLED", example = "PENDING")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        require(authentication);
        return volunteerApplicationService.listForAdmin(status, page, size);
    }

    @PostMapping("/{applicationId}/approve")
    @Operation(summary = "审核通过", description = "事务内校验注册时长并追加 VOLUNTEER 角色后置为 APPROVED。")
    public VolunteerApplicationResponse approve(
            @Parameter(description = "申请唯一标识", example = "va_1234567890")
            @PathVariable String applicationId,
            Authentication authentication) {
        return volunteerApplicationService.approve(require(authentication).id(), applicationId);
    }

    @PostMapping("/{applicationId}/reject")
    @Operation(summary = "审核拒绝", description = "拒绝原因必填。")
    public VolunteerApplicationResponse reject(
            @Parameter(description = "申请唯一标识", example = "va_1234567890")
            @PathVariable String applicationId,
            @Valid @RequestBody VolunteerApplicationRequest request,
            Authentication authentication) {
        return volunteerApplicationService.reject(require(authentication).id(), applicationId, request.reason());
    }

    private AuthPrincipal require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!"ADMIN".equals(principal.principalType()) || !principal.hasPermission("VOLUNTEER_REVIEW")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要 VOLUNTEER_REVIEW 权限");
        }
        return principal;
    }
}
