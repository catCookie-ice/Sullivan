package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.RegistrationService;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;

@RestController
@RequestMapping("/api/v1/users/me/roles")
@Tag(name = "账号与角色", description = "登录用户对自己的账号角色进行操作。")
@SecurityRequirement(name = "bearerAuth")
public class UserRoleController {
    private final RegistrationService registrationService;

    public UserRoleController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/volunteer")
    @Operation(summary = "监护人兼任志愿者",
            description = "当前登录账号必须是持有 GUARDIAN 角色的自然人。为其追加 VOLUNTEER 角色，"
                    + "幂等：已存在志愿者角色时不会重复创建；角色此前被停用则重新启用。"
                    + "返回的 roles 中同时包含 GUARDIAN 和 VOLUNTEER。")
    public RegistrationResponse addVolunteerRole(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal) ||
                !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有监护人可以兼任志愿者");
        }
        return registrationService.addVolunteerRole(principal.id());
    }
}
