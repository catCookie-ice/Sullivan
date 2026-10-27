package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminRegistrationCodeMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.web.AdminGovernDtos.AdminAccountResponse;
import sullivan.serverjava.web.AdminGovernDtos.AdminStatusRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleCreateRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleGrantRequest;
import sullivan.serverjava.web.AdminGovernDtos.RolePermissionsRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminGovernServiceTests {
    private AdminAccountMapper accountMapper;
    private AdminRoleMapper roleMapper;
    private AdminPermissionMapper permissionMapper;
    private AdminAccountRoleMapper accountRoleMapper;
    private AdminRolePermissionMapper rolePermissionMapper;
    private AdminRegistrationCodeMapper registrationCodeMapper;
    private AdminAccessService adminAccessService;
    private AdminGovernService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AdminAccountMapper.class);
        roleMapper = mock(AdminRoleMapper.class);
        permissionMapper = mock(AdminPermissionMapper.class);
        accountRoleMapper = mock(AdminAccountRoleMapper.class);
        rolePermissionMapper = mock(AdminRolePermissionMapper.class);
        registrationCodeMapper = mock(AdminRegistrationCodeMapper.class);
        adminAccessService = mock(AdminAccessService.class);
        service = new AdminGovernService(accountMapper, roleMapper, permissionMapper, accountRoleMapper,
                rolePermissionMapper, registrationCodeMapper, adminAccessService);
    }

    // ------------------------------------------------------------------
    // 角色授予（upsert 语义）
    // ------------------------------------------------------------------

    @Test
    void grantRoleReactivatesRevokedRowInsteadOfInserting() {
        stubAccount("a-1");
        AdminRole operations = role("role-ops", "OPERATIONS", false);
        AdminRole superRole = role("role-super", "SUPER_ADMIN", true);
        // 第一次 selectOne：requireRoleByCode(OPERATIONS)；第二次：requireSurvivingSuperAdmin(SUPER_ADMIN)。
        when(roleMapper.selectOne(any())).thenReturn(operations, superRole);
        AdminAccountRole revoked = accountRoleLink("link-1", "a-1", "role-ops", "REVOKED");
        when(accountRoleMapper.selectOne(any())).thenReturn(revoked);
        // 底线检查：除候选人外还有别的可用最高管理员。
        when(accountRoleMapper.selectList(any()))
                .thenReturn(List.of(accountRoleLink("link-s", "super-1", "role-super", "ACTIVE")));
        when(accountMapper.selectCount(any())).thenReturn(1L);
        stubResponseAssembly("a-1", Set.of("OPERATIONS"));

        AuthPrincipal actor = new AuthPrincipal("super-1", "ADMIN", Set.of("SUPER_ADMIN"),
                Set.of("ADMIN_GOVERN"));
        AdminAccountResponse response = service.grantRole(actor, "a-1",
                new RoleGrantRequest("OPERATIONS", LocalDateTime.now().plusDays(30)));

        ArgumentCaptor<AdminAccountRole> captor = ArgumentCaptor.forClass(AdminAccountRole.class);
        verify(accountRoleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().getGrantedByAdminId()).isEqualTo("super-1");
        // 唯一键 uq_admin_account_role(admin_id, role_id)：复用旧行，绝不能 insert 撞键。
        verify(accountRoleMapper, never()).insert(any(AdminAccountRole.class));
        assertThat(response.roles()).containsExactly("OPERATIONS");
    }

    @Test
    void grantRoleRejectsPastExpiration() {
        stubAccount("a-1");
        when(roleMapper.selectOne(any())).thenReturn(role("role-ops", "OPERATIONS", false));

        AuthPrincipal actor = new AuthPrincipal("super-1", "ADMIN", Set.of("SUPER_ADMIN"),
                Set.of("ADMIN_GOVERN"));
        assertThatThrownBy(() -> service.grantRole(actor, "a-1",
                new RoleGrantRequest("OPERATIONS", LocalDateTime.now().minusSeconds(1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void grantSuperAdminRoleRequiresActorToHoldIt() {
        stubAccount("a-1");
        when(roleMapper.selectOne(any())).thenReturn(role("role-super", "SUPER_ADMIN", true));

        // 操作者只有 ADMIN_GOVERN 而没有 SUPER_ADMIN 角色：不能授予 SUPER_ADMIN。
        AuthPrincipal delegated = new AuthPrincipal("ops-1", "ADMIN", Set.of("OPERATIONS"),
                Set.of("ADMIN_GOVERN"));
        assertThatThrownBy(() -> service.grantRole(delegated, "a-1",
                new RoleGrantRequest("SUPER_ADMIN", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ------------------------------------------------------------------
    // 账号启停与角色撤销的防锁定底线
    // ------------------------------------------------------------------

    @Test
    void cannotDisableSelf() {
        stubAccount("a-1");
        assertThatThrownBy(() -> service.setStatus("a-1", "a-1", new AdminStatusRequest("DISABLED")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void cannotDisableTheOnlyOtherEnabledSuperAdmin() {
        stubAccount("a-1");
        AdminRole superRole = role("role-super", "SUPER_ADMIN", true);
        // 操作者是持有 ADMIN_GOVERN 的运营管理员，目标账号是唯一的最高管理员。
        when(roleMapper.selectOne(any())).thenReturn(superRole);
        when(accountRoleMapper.selectList(any())).thenReturn(List.of()); // 除候选人外无人持有 SUPER_ADMIN
        assertThatThrownBy(() -> service.setStatus("ops-1", "a-1", new AdminStatusRequest("DISABLED")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("至少一名");
        // 守卫先行：账号状态不得落库。
        verify(accountMapper, never()).updateById(any(AdminAccount.class));
    }

    @Test
    void revokeSuperAdminFailsWhenNoOtherEnabledHolderRemains() {
        stubAccount("a-1");
        AdminRole superRole = role("role-super", "SUPER_ADMIN", true);
        when(roleMapper.selectOne(any())).thenReturn(superRole);
        when(accountRoleMapper.selectOne(any()))
                .thenReturn(accountRoleLink("link-1", "a-1", "role-super", "ACTIVE"));
        // 候选人被排除后无人持有 SUPER_ADMIN（自己撤自己的唯一 SUPER_ADMIN 也在此被拦）。
        when(accountRoleMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.revokeRole("a-1", "SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // 撤销被守住：状态不得落库。
        verify(accountRoleMapper, never()).updateById(any(AdminAccountRole.class));
    }

    @Test
    void revokeRoleWithoutActiveLinkReturns404() {
        stubAccount("a-1");
        when(roleMapper.selectOne(any())).thenReturn(role("role-ops", "OPERATIONS", false));
        when(accountRoleMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.revokeRole("a-1", "OPERATIONS"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ------------------------------------------------------------------
    // 角色（权限集）管理
    // ------------------------------------------------------------------

    @Test
    void superAdminPermissionSetIsImmutable() {
        when(roleMapper.selectOne(any())).thenReturn(role("role-super", "SUPER_ADMIN", true));
        assertThatThrownBy(() -> service.updateRolePermissions("SUPER_ADMIN",
                new RolePermissionsRequest(Set.of("USER_MANAGE"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void createRoleRejectsUnknownPermissionCode() {
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(permissionMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.createRole(
                new RoleCreateRequest("AUDITOR", "审计员", Set.of("NOT_A_PERMISSION"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void disableSystemRoleIsRejected() {
        when(roleMapper.selectOne(any())).thenReturn(role("role-super", "SUPER_ADMIN", true));
        assertThatThrownBy(() -> service.disableRole("SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void disableRoleWithActiveMembersIsRejected() {
        when(roleMapper.selectOne(any())).thenReturn(role("role-audit", "AUDITOR", false));
        when(accountRoleMapper.selectList(any()))
                .thenReturn(List.of(accountRoleLink("link-1", "a-1", "role-audit", "ACTIVE")));
        assertThatThrownBy(() -> service.disableRole("AUDITOR"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("1 名管理员");
    }

    // ------------------------------------------------------------------
    // 桩与工厂
    // ------------------------------------------------------------------

    private void stubAccount(String adminId) {
        AdminAccount account = new AdminAccount();
        account.setId(adminId);
        account.setStatus("ENABLED");
        account.setLoginName(adminId + "-login");
        account.setDisplayName("显示名");
        when(accountMapper.selectById(anyString())).thenReturn(account);
    }

    /** 变更方法末尾都会走 getAdmin 装配响应，这里给足空/最小桩避免 NPE。 */
    private void stubResponseAssembly(String adminId, Set<String> roles) {
        when(adminAccessService.roles(adminId)).thenReturn(roles);
        when(adminAccessService.permissionCodesByRoleIds(any())).thenReturn(Map.of());
        when(roleMapper.selectList(any())).thenReturn(List.of());
    }

    private AdminRole role(String id, String code, boolean system) {
        AdminRole role = new AdminRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setRoleName(code);
        role.setStatus("ACTIVE");
        role.setIsSystem(system);
        return role;
    }

    private AdminAccountRole accountRoleLink(String id, String adminId, String roleId, String status) {
        AdminAccountRole link = new AdminAccountRole();
        link.setId(id);
        link.setAdminId(adminId);
        link.setRoleId(roleId);
        link.setStatus(status);
        return link;
    }
}
