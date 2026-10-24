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
        // principalType 缺失时必须给 401 而不是 500。
        // 之前是 `switch (request.principalType())`：Java 的 switch 在 null 上直接 NPE，
        // 于是"忘了传 principalType"这个纯粹的前端疏忽会变成一个 500，
        // 前端看到的是"服务器坏了"，排查方向从一开始就是错的。
        // DTO 上已加 @NotNull 让正常路径走到 400，这里再兜一层，
        // 保证即使校验被绕过（内部调用、将来换校验实现）也不会抛 NPE。
        String principalType = request.principalType();
        if (principalType == null) {
            throw unauthorized();
        }
        return switch (principalType) {
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
        return userToken(account);
    }

    /**
     * 为一个已经用别的方式验证过身份的受护人签发令牌。
     *
     * <p>目前唯一的调用方是二维码扫码授权登录（{@code QrLoginService.exchange}）：身份由监护人确认，
     * 而不是由受护人的密码证明。之所以要把角色解析抽到这里，是因为扫码路径必须有和账密登录
     * **完全一致**的角色集合——另写一份一定会漂移。</p>
     *
     * <p>注意这条路径绕过了「受护人知道自己的密码」这个唯一的持有证明，是一次显式的授权决策，
     * 代价记录在 docs/final/17-开发日记-2026-10-16.md。</p>
     */
    public TokenResponse userToken(UserAccount account) {
        if (account == null || !"ENABLED".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号不可用");
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
        if (organization == null || !"APPROVED".equals(organization.getStatus()) ||
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
