package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LookupServiceTests {
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private UserRoleMapper userRoleMapper;
    private OrganizationMapper organizationMapper;
    private AdminAccountMapper adminAccountMapper;
    private AdminAccessService adminAccessService;
    private LookupService service;

    @BeforeEach
    void setUp() {
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        adminAccountMapper = mock(AdminAccountMapper.class);
        adminAccessService = mock(AdminAccessService.class);
        service = new LookupService(userAccountMapper, userProfileMapper, userRoleMapper,
                organizationMapper, adminAccountMapper, adminAccessService);
    }

    @Test
    void findUserReturnsRolesFromRoleTable() {
        UserAccount account = account("u1", "GUARDIAN");
        when(userAccountMapper.selectById("u1")).thenReturn(account);
        when(userProfileMapper.selectOne(any())).thenReturn(profile("u1", "张三"));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                role("u1", "GUARDIAN"), role("u1", "VOLUNTEER")));

        var result = service.findUser("u1");

        assertThat(result.id()).isEqualTo("u1");
        assertThat(result.displayName()).isEqualTo("张三");
        assertThat(result.roleCode()).isEqualTo("GUARDIAN");
        assertThat(result.roles()).containsExactlyInAnyOrder("GUARDIAN", "VOLUNTEER");
        assertThat(result.alsoVolunteer()).isTrue();
    }

    @Test
    void findUserFallsBackToPrimaryRoleWhenNoRoleRows() {
        UserAccount account = account("u2", "PROTECTEE");
        when(userAccountMapper.selectById("u2")).thenReturn(account);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        var result = service.findUser("u2");

        assertThat(result.roles()).containsExactly("PROTECTEE");
        assertThat(result.alsoVolunteer()).isFalse();
    }

    @Test
    void findUserMissingThrowsNotFound() {
        when(userAccountMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.findUser("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 按登录名查找（00 §7 第 11 条：界面用登录名，不用 uid）
    // ------------------------------------------------------------------

    @Test
    void findByLoginNameReturnsSameShapeAsById() {
        UserAccount account = account("u1", "GUARDIAN");
        account.setLoginName("guardian_li");
        when(userAccountMapper.selectOne(any())).thenReturn(account);
        when(userProfileMapper.selectOne(any())).thenReturn(profile("u1", "李监护"));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("u1", "GUARDIAN")));

        var result = service.findUserByLoginName("guardian_li");

        assertThat(result.id()).isEqualTo("u1");
        assertThat(result.loginName()).isEqualTo("guardian_li");
        assertThat(result.displayName()).isEqualTo("李监护");
    }

    @Test
    void findByLoginNameTrimsSurroundingWhitespace() {
        UserAccount account = account("u1", "GUARDIAN");
        account.setLoginName("guardian_li");
        when(userAccountMapper.selectOne(any())).thenReturn(account);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findUserByLoginName("  guardian_li  ").loginName()).isEqualTo("guardian_li");
    }

    /**
     * 不存在与无权查看必须是**同一个** 404 + 同一句话。
     *
     * <p>若前者说「没有这个人」、后者说「你无权查看」，两句提示的差别本身
     * 就足以用来枚举账号 —— 攻击者不需要拿到数据，只要看提示变没变。
     */
    @Test
    void findByLoginNameMissingIsIndistinguishableNotFound() {
        when(userAccountMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.findUserByLoginName("nobody"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status().value()).isEqualTo(404);
                    assertThat(api.getMessage()).isEqualTo("没有找到该登录名对应的账号");
                });
    }

    @Test
    void findByLoginNameBlankRejectedAsBadRequest() {
        assertThatThrownBy(() -> service.findUserByLoginName("   "))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void findOrganizationReturnsPublicFields() {
        Organization organization = new Organization();
        organization.setId("o1");
        organization.setStatus("APPROVED");
        organization.setLoginName("blindsociety");
        organization.setLegalName("北京市朝阳区盲人协会");
        organization.setPublicName("朝阳盲协");
        when(organizationMapper.selectById("o1")).thenReturn(organization);

        var result = service.findOrganization("o1");

        assertThat(result.id()).isEqualTo("o1");
        assertThat(result.publicName()).isEqualTo("朝阳盲协");
        assertThat(result.status()).isEqualTo("APPROVED");
    }

    @Test
    void findOrganizationMissingThrowsNotFound() {
        when(organizationMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.findOrganization("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void findAdminReturnsRoles() {
        AdminAccount admin = new AdminAccount();
        admin.setId("a1");
        admin.setStatus("ENABLED");
        admin.setLoginName("admin01");
        admin.setDisplayName("张管理");
        when(adminAccountMapper.selectById("a1")).thenReturn(admin);
        when(adminAccessService.roles("a1")).thenReturn(Set.of("HR_ADMIN"));

        var result = service.findAdmin("a1");

        assertThat(result.loginName()).isEqualTo("admin01");
        assertThat(result.roles()).containsExactly("HR_ADMIN");
    }

    @Test
    void findAdminMissingThrowsNotFound() {
        when(adminAccountMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.findAdmin("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    private UserAccount account(String id, String roleCode) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus("ENABLED");
        account.setLoginName("user-" + id);
        account.setRoleCode(roleCode);
        return account;
    }

    private UserProfile profile(String userId, String displayName) {
        UserProfile profile = new UserProfile();
        profile.setId("p-" + userId);
        profile.setUserId(userId);
        profile.setDisplayName(displayName);
        return profile;
    }

    private UserRole role(String userId, String roleCode) {
        UserRole role = new UserRole();
        role.setId("r-" + userId + "-" + roleCode);
        role.setUserId(userId);
        role.setRoleCode(roleCode);
        role.setStatus("ACTIVE");
        return role;
    }
}
