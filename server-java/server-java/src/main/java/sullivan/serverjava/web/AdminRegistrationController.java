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
import sullivan.serverjava.service.AdminRegistrationService;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeCreateRequest;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeResponse;

@RestController
@RequestMapping("/api/v1/admin/registration-codes")
public class AdminRegistrationController {
    private final AdminRegistrationService registrationService;

    public AdminRegistrationController(AdminRegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    public ResponseEntity<AdminCodeResponse> create(Authentication authentication,
                                                    @Valid @RequestBody AdminCodeCreateRequest request) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.createCode(principal, request));
    }
}
