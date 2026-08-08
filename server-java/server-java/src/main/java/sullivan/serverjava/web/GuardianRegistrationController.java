package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.RegistrationService;
import sullivan.serverjava.web.RegistrationDtos.ProtecteeRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;

@RestController
@RequestMapping("/api/v1/guardians")
@Tag(name = "监护绑定", description = "需登录且携带 Bearer 令牌；角色校验失败返回 403。")
@SecurityRequirement(name = "bearerAuth")
public class GuardianRegistrationController {
    private final RegistrationService registrationService;

    public GuardianRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/protectees")
    @Operation(summary = "监护人创建受护人",
            description = "当前登录账号必须是监护人（GUARDIAN）。创建受护人账号的同时建立一条永久、"
                    + "主要的监护绑定关系，绑定状态直接为 ACTIVE。返回受护人账号信息。")
    public ResponseEntity<RegistrationResponse> registerProtectee(
            Authentication authentication, @Valid @RequestBody ProtecteeRegisterRequest request) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有监护人可以创建受护人");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registrationService.registerProtectee(principal.id(), request));
    }

    @PostMapping("/bindings")
    @Operation(summary = "监护人发起绑定请求",
            description = "当前登录账号必须是自然人且持有 GUARDIAN 角色。为目标受护人发起一条绑定申请，"
                    + "初始状态为 PENDING，需由受护人在 accept 接口确认后变为 ACTIVE。"
                    + "同一监护人对同一受护人只能存在一条 PENDING 或 ACTIVE 绑定。")
    public ResponseEntity<BindingResponse> requestBinding(
            Authentication authentication, @Valid @RequestBody BindingRequest request) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以发起绑定");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registrationService.requestBinding(principal.id(), request));
    }

    @PostMapping("/bindings/{bindingId}/accept")
    @Operation(summary = "受护人确认绑定",
            description = "当前登录账号必须是受护人（PROTECTEE），且只能确认绑定对象为自己的请求。"
                    + "确认后绑定状态变为 ACTIVE；若受护人此前还没有主绑定，则该绑定自动成为主绑定。")
    public BindingResponse acceptBinding(Authentication authentication,
                                         @Parameter(description = "绑定关系唯一标识", example = "b_1234567890")
                                         @PathVariable String bindingId) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有受护人可以确认绑定");
        }
        return registrationService.acceptBinding(principal.id(), bindingId);
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
