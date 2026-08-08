package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.AdminManagementDtos.AdminUserResponse;
import sullivan.serverjava.web.AdminManagementDtos.UserDeleteResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminManagementServiceTests {
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private UserRoleMapper userRoleMapper;
    private OrganizationMapper organizationMapper;
    private GuardianBindingMapper guardianBindingMapper;
    private AdminManagementService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper.set() 需要 MyBatis-Plus 表信息缓存，纯单测需手动初始化
        initTableInfo(UserAccount.class);
        initTableInfo(UserProfile.class);
        initTableInfo(UserRole.class);
        initTableInfo(Organization.class);
        initTableInfo(GuardianBinding.class);

        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        guardianBindingMapper = mock(GuardianBindingMapper.class);
        service = new AdminManagementService(userAccountMapper, userProfileMapper, userRoleMapper,
                organizationMapper, guardianBindingMapper);
    }

    private void initTableInfo(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    // ---------- 删除：兼任志愿者只取消兼任 ----------

    @Test
    void deleteGuardianWhoAlsoVolunteersOnlyRevokesVolunteerRole() {
        UserAccount account = account("u1", "GUARDIAN");
        when(userAccountMapper.selectById("u1")).thenReturn(account);
        // 兼任：user_role 中有 ACTIVE 的 VOLUNTEER
        when(userRoleMapper.selectList(any())).thenReturn(List.of(role("u1", "VOLUNTEER")));
        when(userRoleMapper.update(any(), any())).thenReturn(1);

        UserDeleteResult result = service.deleteUser("u1");

        assertThat(result.accountDeleted()).isFalse();
        assertThat(result.action()).isEqualTo("VOLUNTEER_ROLE_REVOKED");
        // 不应逻辑删除账号，也不撤销 GUARDIAN
        verify(userAccountMapper, never()).deleteById(anyString());
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        verify(userRoleMapper).update(any(), any());
    }

    @Test
    void deletePureVolunteerLogicallyDeletesAccount() {
        UserAccount account = account("u1", "VOLUNTEER");
        when(userAccountMapper.selectById("u1")).thenReturn(account);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserDeleteResult result = service.deleteUser("u1");

        assertThat(result.accountDeleted()).isTrue();
        assertThat(result.action()).isEqualTo("ACCOUNT_DELETED");
        verify(userAccountMapper).deleteById("u1");
        // 撤销角色 + 逻辑删除资料 + 终止绑定
        verify(userRoleMapper).update(any(), any());
        verify(userProfileMapper).delete(any());
        verify(guardianBindingMapper).update(any(), any());
    }

    @Test
    void deleteGuardianOnlyLogicallyDeletesAccount() {
        UserAccount account = account("u1", "GUARDIAN");
        when(userAccountMapper.selectById("u1")).thenReturn(account);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserDeleteResult result = service.deleteUser("u1");

        assertThat(result.accountDeleted()).isTrue();
        assertThat(result.action()).isEqualTo("ACCOUNT_DELETED");
        verify(userAccountMapper).deleteById("u1");
    }

    @Test
    void deleteUserMissingThrowsNotFound() {
        when(userAccountMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteUser("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    // ---------- 删除：机构 ----------

    @Test
    void deleteOrganizationLogicallyDeletes() {
        Organization org = new Organization();
        org.setId("o1");
        when(organizationMapper.selectById("o1")).thenReturn(org);

        service.deleteOrganization("o1");

        verify(organizationMapper).deleteById("o1");
    }

    @Test
    void deleteOrganizationMissingThrowsNotFound() {
        when(organizationMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteOrganization("missing"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(404));
    }

    // ---------- 列表：志愿者列表包含兼任监护人的监护人，并标注 alsoVolunteer ----------

    @Test
    void listVolunteersMarksAlsoVolunteerForGuardianDualRole() {
        UserAccount pureVolunteer = account("v1", "VOLUNTEER");
        UserAccount guardianAlsoVolunteer = account("g1", "GUARDIAN");
        Page<UserAccount> page = new Page<>(1, 20);
        page.setRecords(List.of(pureVolunteer, guardianAlsoVolunteer));
        page.setTotal(2);
        when(userAccountMapper.selectPage(any(), any())).thenAnswer(inv -> page);

        when(userProfileMapper.selectList(any())).thenReturn(List.of(
                profile("g1", "兼志愿者监护人"), profile("v1", "纯志愿者")));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                role("g1", "VOLUNTEER"))); // g1 兼任志愿者

        IPage<AdminUserResponse> result = service.listUsers("VOLUNTEER", null, 1, 20);

        assertThat(result.getTotal()).isEqualTo(2);
        AdminUserResponse pure = result.getRecords().get(0);
        AdminUserResponse dual = result.getRecords().get(1);
        assertThat(pure.alsoVolunteer()).isFalse();
        assertThat(pure.roles()).containsExactly("VOLUNTEER");
        assertThat(dual.alsoVolunteer()).isTrue();
        assertThat(dual.roles()).containsExactlyInAnyOrder("GUARDIAN", "VOLUNTEER");
    }

    @Test
    void listUsersRejectsInvalidRole() {
        assertThatThrownBy(() -> service.listUsers("BAD_ROLE", null, 1, 20))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
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
