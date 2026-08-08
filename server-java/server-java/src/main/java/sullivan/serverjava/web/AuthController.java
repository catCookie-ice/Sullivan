package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.service.AdminRegistrationService;
import sullivan.serverjava.service.AuthService;
import sullivan.serverjava.service.RegistrationService;
import sullivan.serverjava.web.RegistrationDtos.AdminRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.LoginRequest;
import sullivan.serverjava.web.RegistrationDtos.OrganizationRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;
import sullivan.serverjava.web.RegistrationDtos.TokenResponse;
import sullivan.serverjava.web.RegistrationDtos.UserRegisterRequest;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证与注册", description = "公开接口：自然人/机构/管理员注册与登录，无需携带令牌。")
public class AuthController {
    private final RegistrationService registrationService;
    private final AdminRegistrationService adminRegistrationService;
    private final AuthService authService;

    public AuthController(RegistrationService registrationService,
                          AdminRegistrationService adminRegistrationService,
                          AuthService authService) {
        this.registrationService = registrationService;
        this.adminRegistrationService = adminRegistrationService;
        this.authService = authService;
    }

    @PostMapping("/register/user")
    @Operation(summary = "注册自然人为监护人或志愿者",
            description = "公开注册自然人账号。role 只能为 GUARDIAN（监护人）或 VOLUNTEER（志愿者），"
                    + "登录名全局唯一。注册成功后账号状态为 ENABLED。")
    public ResponseEntity<RegistrationResponse> registerUser(@Valid @RequestBody UserRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.registerUser(request));
    }

    @PostMapping("/register/organization")
    @Operation(summary = "注册公益机构",
            description = "公开注册公益机构账号。注册成功后账号状态为 PENDING（待管理员审核），"
                    + "审核通过前无法登录使用业务功能。机构登录名全局唯一。")
    public ResponseEntity<RegistrationResponse> registerOrganization(
            @Valid @RequestBody OrganizationRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.registerOrganization(request));
    }

    @PostMapping("/register/admin")
    @Operation(summary = "使用一次性注册码注册管理员",
            description = "公开接口，但必须持有管理员签发的有效一次性注册码（registrationCode，形如 SV-...）。"
                    + "注册码只保存哈希、使用后立即核销；注册码携带的 initialRoleCode 决定新管理员的初始权限。")
    public ResponseEntity<RegistrationResponse> registerAdmin(@Valid @RequestBody AdminRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminRegistrationService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "登录并获取访问令牌",
            description = "按主体类型（USER/ORGANIZATION/ADMIN）校验登录名与密码，"
                    + "成功后返回 JWT 访问令牌。后续接口在请求头携带 "
                    + "`Authorization: Bearer <accessToken>` 即可通过认证。")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
