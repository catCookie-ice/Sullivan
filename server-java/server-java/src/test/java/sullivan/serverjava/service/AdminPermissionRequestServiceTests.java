package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminPermission;
import sullivan.serverjava.domain.AdminPermissionRequest;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.domain.AdminRolePermission;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminPermissionRequestMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 权限申请：发起零门槛（只填权限+理由）、审批 ADMIN_GOVERN、批准给本人开通（承载角色自动生成）、自批拒绝。 */
class AdminPermissionRequestServiceTests {
    private static final String REQUESTER = "adm-req";
    private static final String GOVERNOR = "adm-gov";

    private AdminPermissionRequestMapper requestMapper;
    private AdminPermissionMapper permissionMapper;
    private AdminRoleMapper roleMapper;
    private AdminRolePermissionMapper rolePermissionMapper;
    private AdminAccountRoleMapper accountRoleMapper;
    private AdminAccessService adminAccessService;
    private AdminPermissionRequestService service;

    @BeforeEach
    void setUp() {
        requestMapper = mock(AdminPermissionRequestMapper.class);
        permissionMapper = mock(AdminPermissionMapper.class);
        roleMapper = mock(AdminRoleMapper.class);
        rolePermissionMapper = mock(AdminRolePermissionMapper.class);
        accountRoleMapper = mock(AdminAccountRoleMapper.class);
        adminAccessService = mock(AdminAccessService.class);
        service = new AdminPermissionRequestService(requestMapper, permissionMapper, roleMapper,
                rolePermissionMapper, accountRoleMapper, adminAccessService);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        List.of(AdminPermissionRequest.class, AdminPermission.class, AdminRole.class,
                        AdminRolePermission.class, AdminAccountRole.class)
                .forEach(type -> TableInfoHelper.initTableInfo(assistant, type));
    }

    private AdminPermission abuseReviewPermission() {
        AdminPermission permission = new AdminPermission();
        permission.setId("perm-1");
        permission.setStatus("ACTIVE");
        permission.setPermissionCode("ABUSE_REVIEW");
        permission.setPermissionName("举报治理");
        return permission;
    }

    private AdminPermissionRequest pendingRequest() {
        AdminPermissionRequest request = new AdminPermissionRequest();
        request.setId("req-1");
        request.setStatus("PENDING");
        request.setPermissionCode("ABUSE_REVIEW");
        request.setReason("需要处理举报");
        request.setRequestedBy(REQUESTER);
        return request;
    }

    @Test
    void anyAdminCanCreateWithJustPermissionAndReason() {
        when(permissionMapper.selectOne(any())).thenReturn(abuseReviewPermission());
        when(adminAccessService.permissions(REQUESTER)).thenReturn(Set.of());
        when(requestMapper.selectCount(any())).thenReturn(0L);

        var response = service.create(REQUESTER, "ABUSE_REVIEW", "需要处理举报");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.permissionCode()).isEqualTo("ABUSE_REVIEW");
        assertThat(response.permissionName()).isEqualTo("举报治理");
    }

    @Test
    void unknownPermissionRejected() {
        when(permissionMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.create(REQUESTER, "NOPE", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void alreadyHavingPermissionConflicts() {
        when(permissionMapper.selectOne(any())).thenReturn(abuseReviewPermission());
        when(adminAccessService.permissions(REQUESTER)).thenReturn(Set.of("ABUSE_REVIEW"));
        assertThatThrownBy(() -> service.create(REQUESTER, "ABUSE_REVIEW", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void duplicatePendingConflicts() {
        when(permissionMapper.selectOne(any())).thenReturn(abuseReviewPermission());
        when(adminAccessService.permissions(REQUESTER)).thenReturn(Set.of());
        when(requestMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(REQUESTER, "ABUSE_REVIEW", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void cannotApproveOwnRequest() {
        AdminPermissionRequest request = pendingRequest();
        when(requestMapper.selectById("req-1")).thenReturn(request);
        assertThatThrownBy(() -> service.decide(REQUESTER, "req-1", true, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(403));
    }

    @Test
    void decideTwiceConflicts() {
        AdminPermissionRequest request = pendingRequest();
        request.setStatus("APPROVED");
        when(requestMapper.selectById("req-1")).thenReturn(request);
        assertThatThrownBy(() -> service.decide(GOVERNOR, "req-1", true, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void approvalCreatesCarrierRoleGrantAndMembership() {
        AdminPermissionRequest request = pendingRequest();
        request.setHandledBy(GOVERNOR);
        when(requestMapper.selectById("req-1")).thenReturn(request);
        when(permissionMapper.selectOne(any())).thenReturn(abuseReviewPermission());
        when(roleMapper.selectOne(any())).thenReturn(null); // 承载角色不存在
        when(rolePermissionMapper.selectOne(any())).thenReturn(null);
        when(accountRoleMapper.selectOne(any())).thenReturn(null);

        service.decide(GOVERNOR, "req-1", true, "同意");

        assertThat(request.getStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<AdminRole> role = ArgumentCaptor.forClass(AdminRole.class);
        verify(roleMapper).insert(role.capture());
        // 承载角色按权限自动生成，申请人无需接触角色概念。
        assertThat(role.getValue().getRoleCode()).isEqualTo("PERM_ABUSE_REVIEW");
        assertThat(role.getValue().getRoleName()).isEqualTo("举报治理");
        assertThat(role.getValue().getIsSystem()).isFalse();
        verify(rolePermissionMapper).insert(any(AdminRolePermission.class));
        ArgumentCaptor<AdminAccountRole> membership = ArgumentCaptor.forClass(AdminAccountRole.class);
        verify(accountRoleMapper).insert(membership.capture());
        assertThat(membership.getValue().getAdminId()).isEqualTo(REQUESTER);
        assertThat(membership.getValue().getGrantedByAdminId()).isEqualTo(GOVERNOR);
    }

    @Test
    void approvalReusesExistingCarrierRoleAndRevokedMembership() {
        AdminPermissionRequest request = pendingRequest();
        when(requestMapper.selectById("req-1")).thenReturn(request);
        when(permissionMapper.selectOne(any())).thenReturn(abuseReviewPermission());
        AdminRole carrier = new AdminRole();
        carrier.setId("role-1");
        carrier.setRoleCode("PERM_ABUSE_REVIEW");
        carrier.setStatus("ACTIVE");
        when(roleMapper.selectOne(any())).thenReturn(carrier);
        AdminRolePermission grant = new AdminRolePermission();
        grant.setId("link-1");
        grant.setStatus("ACTIVE");
        when(rolePermissionMapper.selectOne(any())).thenReturn(grant);
        AdminAccountRole revokedMembership = new AdminAccountRole();
        revokedMembership.setId("member-1");
        revokedMembership.setAdminId(REQUESTER);
        revokedMembership.setRoleId("role-1");
        revokedMembership.setStatus("REVOKED");
        when(accountRoleMapper.selectOne(any())).thenReturn(revokedMembership);

        service.decide(GOVERNOR, "req-1", true, null);

        // 唯一键旧行复用：成员关系置回 ACTIVE 而不是 insert（撞 uq_admin_account_role）。
        assertThat(revokedMembership.getStatus()).isEqualTo("ACTIVE");
        assertThat(revokedMembership.getGrantedByAdminId()).isEqualTo(GOVERNOR);
        verify(accountRoleMapper).updateById(revokedMembership);
        verify(accountRoleMapper, never()).insert(any(AdminAccountRole.class));
        verify(roleMapper, never()).insert(any(AdminRole.class));
        verify(rolePermissionMapper, never()).insert(any(AdminRolePermission.class));
    }

    @Test
    void rejectionDoesNotTouchRoles() {
        AdminPermissionRequest request = pendingRequest();
        when(requestMapper.selectById("req-1")).thenReturn(request);

        service.decide(GOVERNOR, "req-1", false, "理由不充分");

        assertThat(request.getStatus()).isEqualTo("REJECTED");
        assertThat(request.getDecisionNote()).isEqualTo("理由不充分");
        verify(roleMapper, never()).insert(any(AdminRole.class));
        verify(accountRoleMapper, never()).insert(any(AdminAccountRole.class));
    }
}
