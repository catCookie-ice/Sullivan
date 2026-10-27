package sullivan.serverjava.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.service.AdminAccessService;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理员权限的逐请求生效：过滤器不得信任 JWT 里的角色/权限声明，
 * 而是每请求用 AdminAccessService 从库中现算 —— 这决定了「授予/撤销立即生效」。
 */
class JwtAuthenticationFilterTests {
    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private JwtService jwtService;
    private AdminAccessService adminAccessService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 1800);
        adminAccessService = mock(AdminAccessService.class);
        filter = new JwtAuthenticationFilter(jwtService, adminAccessService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminPermissionsAreReResolvedFromDbOnEveryRequest() throws Exception {
        String token = jwtService.issue(new AuthPrincipal("a-1", "ADMIN",
                Set.of("STALE_ROLE"), Set.of("STALE_PERM")));
        // 库里的现状与 JWT 声明不同：角色已被调整。
        AdminAccessService.AdminAccessSnapshot snapshot = new AdminAccessService.AdminAccessSnapshot(
                enabledAccount(), Set.of("OPERATIONS"), Set.of("USER_MANAGE"));
        when(adminAccessService.resolveAdminAccess("a-1")).thenReturn(snapshot);

        runFilter(token);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        assertThat(principal.roles()).containsExactly("OPERATIONS");
        assertThat(principal.permissions()).containsExactly("USER_MANAGE");
        assertThat(principal.hasPermission("STALE_PERM")).isFalse();
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_OPERATIONS", "PERM_USER_MANAGE");
    }

    @Test
    void disabledAdminIsTreatedAsUnauthenticated() throws Exception {
        String token = jwtService.issue(new AuthPrincipal("a-1", "ADMIN",
                Set.of("SUPER_ADMIN"), Set.of("ADMIN_GOVERN")));
        // 账号已被停用 → 快照为 null。
        when(adminAccessService.resolveAdminAccess("a-1")).thenReturn(null);

        runFilter(token);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonAdminPrincipalsKeepJwtClaimsAndSkipDbResolution() throws Exception {
        String token = jwtService.issue(new AuthPrincipal("u-1", "USER",
                Set.of("GUARDIAN"), Set.of()));

        runFilter(token);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(((AuthPrincipal) authentication.getPrincipal()).roles()).containsExactly("GUARDIAN");
        verifyNoInteractions(adminAccessService);
    }

    private AdminAccount enabledAccount() {
        AdminAccount account = new AdminAccount();
        account.setId("a-1");
        account.setStatus("ENABLED");
        account.setLoginName("admin01");
        account.setDisplayName("管理员");
        return account;
    }

    private void runFilter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, mock(FilterChain.class));
    }
}
