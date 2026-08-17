package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.VolunteerApplicationService;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationRequest;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationResponse;

import java.util.List;

/**
 * 监护人兼任志愿者：由"立即授角色"改为"提交申请 → 管理员审核"。
 * 审核通过后追加 VOLUNTEER 角色，申请人须重新登录刷新 JWT 角色。
 */
@RestController
@RequestMapping("/api/v1/users/me/roles")
@Tag(name = "账号与角色", description = "登录用户对自己的账号角色进行操作。")
@SecurityRequirement(name = "bearerAuth")
public class UserRoleController {
    private final VolunteerApplicationService volunteerApplicationService;

    public UserRoleController(VolunteerApplicationService volunteerApplicationService) {
        this.volunteerApplicationService = volunteerApplicationService;
    }

    @PostMapping("/volunteer")
    @Operation(summary = "提交志愿者兼任申请",
            description = "当前登录账号必须是持有 GUARDIAN 角色的自然人。提交后状态为 PENDING，"
                    + "由具备 VOLUNTEER_REVIEW 权限的管理员审核；通过后追加 VOLUNTEER 角色。"
                    + "注册时间须满配置天数（默认 90 天）。")
    public ResponseEntity<VolunteerApplicationResponse> applyVolunteer(
            Authentication authentication, @Valid @RequestBody(required = false) VolunteerApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(volunteerApplicationService.submit(principal(authentication).id(),
                        request == null ? null : request.reason()));
    }

    @GetMapping("/volunteer")
    @Operation(summary = "我的志愿者兼任申请列表")
    public List<VolunteerApplicationResponse> myApplications(Authentication authentication) {
        return volunteerApplicationService.myApplications(principal(authentication).id());
    }

    @PostMapping("/volunteer/cancel/{applicationId}")
    @Operation(summary = "撤销我的 PENDING 申请", description = "只有申请人本人可撤销待审核申请。")
    public VolunteerApplicationResponse cancelApplication(
            Authentication authentication,
            @Parameter(description = "申请唯一标识", example = "va_1234567890")
            @PathVariable String applicationId) {
        return volunteerApplicationService.cancel(principal(authentication).id(), applicationId);
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以申请志愿者兼任");
        }
        return principal;
    }
}
