package sullivan.serverjava.web;

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
import sullivan.serverjava.service.RegistrationService;
import sullivan.serverjava.web.RegistrationDtos.ProtecteeRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;
import sullivan.serverjava.web.RegistrationDtos.RegistrationResponse;

@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianRegistrationController {
    private final RegistrationService registrationService;

    public GuardianRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/protectees")
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
    public BindingResponse acceptBinding(Authentication authentication,
                                         @org.springframework.web.bind.annotation.PathVariable String bindingId) {
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
