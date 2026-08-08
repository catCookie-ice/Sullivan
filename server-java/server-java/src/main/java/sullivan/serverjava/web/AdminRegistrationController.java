package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AdminRegistrationService;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeCreateRequest;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeResponse;

@RestController
@RequestMapping("/api/v1/admin/registration-codes")
@Tag(name = "管理员", description = "管理员权限操作，需登录并具备对应角色/权限。")
@SecurityRequirement(name = "bearerAuth")
public class AdminRegistrationController {
    private final AdminRegistrationService registrationService;

    public AdminRegistrationController(AdminRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    @Operation(summary = "创建管理员一次性注册码",
            description = "仅最高管理员（SUPER_ADMIN）或 HR 管理员（HR_ADMIN）且持有 "
                    + "ADMIN_REGISTRATION_CODE_CREATE 权限可调用；签发 SUPER_ADMIN / HR_ADMIN 这类特权注册码"
                    + "仅限最高管理员。注册码明文只在此响应中返回一次，后端仅保存其 SHA-256 哈希。")
    public ResponseEntity<AdminCodeResponse> create(Authentication authentication,
                                                    @Valid @RequestBody AdminCodeCreateRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.createCode(principal, request));
    }
}
