package sullivan.serverjava.web;

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
public class UserRoleController {
    private final RegistrationService registrationService;

    public UserRoleController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/volunteer")
    public RegistrationResponse addVolunteerRole(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal) ||
                !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有监护人可以兼任志愿者");
        }
        return registrationService.addVolunteerRole(principal.id());
    }
}
