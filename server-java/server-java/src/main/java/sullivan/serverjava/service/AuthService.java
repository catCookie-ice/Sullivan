package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.security.JwtService;
import sullivan.serverjava.web.RegistrationDtos.LoginRequest;
import sullivan.serverjava.web.RegistrationDtos.TokenResponse;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrganizationMapper organizationMapper;
    private final AdminAccountMapper adminAccountMapper;
    private final AdminAccessService adminAccessService;
    private final RecoveryService recoveryService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserAccountMapper userAccountMapper, UserRoleMapper userRoleMapper,
                       OrganizationMapper organizationMapper, AdminAccountMapper adminAccountMapper,
                       AdminAccessService adminAccessService, RecoveryService recoveryService,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
        this.organizationMapper = organizationMapper;
        this.adminAccountMapper = adminAccountMapper;
        this.adminAccessService = adminAccessService;
        this.recoveryService = recoveryService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public TokenResponse login(LoginRequest request) {
        return switch (request.principalType()) {
            case "USER" -> loginUser(request);
            case "ORGANIZATION" -> loginOrganization(request);
            case "ADMIN" -> loginAdmin(request);
            default -> throw unauthorized();
        };
    }

    private TokenResponse loginUser(LoginRequest request) {
        UserAccount account = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getLoginName, request.loginName()));
        if (account == null || !"ENABLED".equals(account.getStatus()) ||
                !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw unauthorized();
        }
        Set<String> roles = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getUserId, account.getId()).eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getRoleCode).collect(Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) {
            roles = Set.of(account.getRoleCode());
        }
        return token(new AuthPrincipal(account.getId(), "USER", roles, Set.of()),
                recoveryService.reminderRequired("USER", account.getId()));
    }

    private TokenResponse loginOrganization(LoginRequest request) {
        Organization organization = organizationMapper.selectOne(Wrappers.<Organization>lambdaQuery()
                .eq(Organization::getLoginName, request.loginName()));
        if (organization == null || "DISABLED".equals(organization.getStatus()) ||
                !passwordEncoder.matches(request.password(), organization.getPasswordHash())) {
            throw unauthorized();
        }
        return token(new AuthPrincipal(organization.getId(), "ORGANIZATION", Set.of("ORGANIZATION"), Set.of()),
                recoveryService.reminderRequired("ORGANIZATION", organization.getId()));
    }

    private TokenResponse loginAdmin(LoginRequest request) {
        AdminAccount account = adminAccountMapper.selectOne(Wrappers.<AdminAccount>lambdaQuery()
                .eq(AdminAccount::getLoginName, request.loginName()));
        if (account == null || !"ENABLED".equals(account.getStatus()) ||
                !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw unauthorized();
        }
        return token(new AuthPrincipal(account.getId(), "ADMIN",
                adminAccessService.roles(account.getId()), adminAccessService.permissions(account.getId())),
                false);
    }

    private TokenResponse token(AuthPrincipal principal, boolean recoveryReminder) {
        return new TokenResponse(jwtService.issue(principal), principal.id(), principal.principalType(),
                principal.roles(), principal.permissions(), recoveryReminder);
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
    }
}
