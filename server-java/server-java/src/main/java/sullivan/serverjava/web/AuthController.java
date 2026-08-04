package sullivan.serverjava.web;

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
    public ResponseEntity<RegistrationResponse> registerUser(@Valid @RequestBody UserRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.registerUser(request));
    }

    @PostMapping("/register/organization")
    public ResponseEntity<RegistrationResponse> registerOrganization(
            @Valid @RequestBody OrganizationRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.registerOrganization(request));
    }

    @PostMapping("/register/admin")
    public ResponseEntity<RegistrationResponse> registerAdmin(@Valid @RequestBody AdminRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminRegistrationService.register(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
