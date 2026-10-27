package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
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
import sullivan.serverjava.web.AdminPermissionRequestDtos.PermissionOption;
import sullivan.serverjava.web.AdminPermissionRequestDtos.PermissionRequestResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限申请：申请功能本身**不需要任何权限**——只要是管理员（principalType=ADMIN）即可发起。
 * 申请语义是「**我**需要这项权限」：表单只有权限与理由，不涉及角色；
 * 审批（ADMIN_GOVERN）批准后，系统把申请人加入持有该权限的承载角色
 * （按权限自动生成，如 {@code PERM_ABUSE_REVIEW}；不存在则创建）——
 * 权限仍经角色解析（RBAC 不动），申请人全程不接触角色概念。
 * 申请人与审批人不能是同一人——与预设处罚审批同一条分立原则。
 */
@Service
public class AdminPermissionRequestService {

    private static final String CARRIER_ROLE_PREFIX = "PERM_";

    private final AdminPermissionRequestMapper requestMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminAccountRoleMapper accountRoleMapper;
    private final AdminAccessService adminAccessService;

    public AdminPermissionRequestService(AdminPermissionRequestMapper requestMapper,
                                         AdminPermissionMapper permissionMapper,
                                         AdminRoleMapper roleMapper,
                                         AdminRolePermissionMapper rolePermissionMapper,
                                         AdminAccountRoleMapper accountRoleMapper,
                                         AdminAccessService adminAccessService) {
        this.requestMapper = requestMapper;
        this.permissionMapper = permissionMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.accountRoleMapper = accountRoleMapper;
        this.adminAccessService = adminAccessService;
    }

    /** 发起申请：任意管理员，只填权限与理由。已拥有该权限、或同权限待审中都会被拒。 */
    @Transactional
    public PermissionRequestResponse create(String requesterId, String permissionCode, String reason) {
        AdminPermission permission = requirePermission(permissionCode);
        if (adminAccessService.permissions(requesterId).contains(permissionCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "你已拥有该权限，无需申请");
        }
        Long duplicated = requestMapper.selectCount(Wrappers.<AdminPermissionRequest>lambdaQuery()
                .eq(AdminPermissionRequest::getRequestedBy, requesterId)
                .eq(AdminPermissionRequest::getPermissionCode, permissionCode)
                .eq(AdminPermissionRequest::getStatus, "PENDING"));
        if (duplicated != null && duplicated > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "你已有一条该权限的申请在待审中");
        }
        AdminPermissionRequest request = new AdminPermissionRequest();
        request.setId(IdGenerator.next());
        request.setStatus("PENDING");
        request.setPermissionCode(permissionCode);
        request.setReason(reason.strip());
        request.setRequestedBy(requesterId);
        requestMapper.insert(request);
        return toResponse(request, permission.getPermissionName());
    }

    /** 我的申请（申请人视角，含审批结果与意见）。 */
    public List<PermissionRequestResponse> mine(String requesterId) {
        return requestMapper.selectList(Wrappers.<AdminPermissionRequest>lambdaQuery()
                .eq(AdminPermissionRequest::getRequestedBy, requesterId)
                .orderByDesc(AdminPermissionRequest::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /** 全部申请（审批人视角）。 */
    public List<PermissionRequestResponse> list(String status) {
        return requestMapper.selectList(Wrappers.<AdminPermissionRequest>lambdaQuery()
                .eq(status != null && !status.isBlank(), AdminPermissionRequest::getStatus, status)
                .orderByDesc(AdminPermissionRequest::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /** 权限字典（申请表单下拉；任意管理员可读——码表不是秘密，审批权才是闸门）。 */
    public List<PermissionOption> catalog() {
        return permissionMapper.selectList(Wrappers.<AdminPermission>lambdaQuery()
                .eq(AdminPermission::getStatus, "ACTIVE")
                .orderByAsc(AdminPermission::getPermissionCode)).stream()
                .map(permission -> new PermissionOption(permission.getPermissionCode(), permission.getPermissionName()))
                .toList();
    }

    /** 审批：批准即给申请人开通权限（挂到承载角色），驳回仅记录。自批拒绝。 */
    @Transactional
    public void decide(String actorId, String requestId, boolean approve, String note) {
        AdminPermissionRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(request.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该申请已处理");
        }
        if (actorId.equals(request.getRequestedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "不能审批自己发起的申请");
        }
        request.setStatus(approve ? "APPROVED" : "REJECTED");
        request.setHandledBy(actorId);
        request.setHandledAt(LocalDateTime.now());
        request.setDecisionNote(note == null ? null : note.strip());
        requestMapper.updateById(request);
        if (approve) {
            materialize(request);
        }
    }

    /** 落地：把申请人加入持有该权限的承载角色（PERM_前缀，不存在则创建）。 */
    private void materialize(AdminPermissionRequest request) {
        AdminPermission permission = requirePermission(request.getPermissionCode());
        String carrierCode = CARRIER_ROLE_PREFIX + permission.getPermissionCode();
        AdminRole role = roleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, carrierCode));
        if (role == null) {
            role = new AdminRole();
            role.setId(IdGenerator.next());
            role.setStatus("ACTIVE");
            role.setRoleCode(carrierCode);
            role.setRoleName(permission.getPermissionName());
            role.setIsSystem(false);
            roleMapper.insert(role);
        } else if (!"ACTIVE".equals(role.getStatus())) {
            role.setStatus("ACTIVE");
            roleMapper.updateById(role);
        }
        ensureGrant(role.getId(), permission.getId());
        addMembership(request.getRequestedBy(), role.getId(), request.getHandledBy());
    }

    /** 「同角色同权限至多一行」（uq_admin_role_permission）：撤销后重授必须复用旧行置回 ACTIVE。 */
    private void ensureGrant(String roleId, String permissionId) {
        AdminRolePermission existing = rolePermissionMapper.selectOne(
                Wrappers.<AdminRolePermission>lambdaQuery()
                        .eq(AdminRolePermission::getRoleId, roleId)
                        .eq(AdminRolePermission::getPermissionId, permissionId));
        if (existing != null) {
            if (!"ACTIVE".equals(existing.getStatus())) {
                existing.setStatus("ACTIVE");
                rolePermissionMapper.updateById(existing);
            }
            return;
        }
        AdminRolePermission link = new AdminRolePermission();
        link.setId(IdGenerator.next());
        link.setRoleId(roleId);
        link.setPermissionId(permissionId);
        link.setStatus("ACTIVE");
        rolePermissionMapper.insert(link);
    }

    /** 「同管理员同角色至多一行」（uq_admin_account_role）：成员关系同样复用旧行；永久有效。 */
    private void addMembership(String adminId, String roleId, String grantedBy) {
        AdminAccountRole existing = accountRoleMapper.selectOne(Wrappers.<AdminAccountRole>lambdaQuery()
                .eq(AdminAccountRole::getAdminId, adminId)
                .eq(AdminAccountRole::getRoleId, roleId));
        if (existing != null) {
            if (!"ACTIVE".equals(existing.getStatus())) {
                existing.setStatus("ACTIVE");
                existing.setGrantedByAdminId(grantedBy);
                existing.setExpiresAt(null);
                accountRoleMapper.updateById(existing);
            }
            return;
        }
        AdminAccountRole link = new AdminAccountRole();
        link.setId(IdGenerator.next());
        link.setAdminId(adminId);
        link.setRoleId(roleId);
        link.setStatus("ACTIVE");
        link.setGrantedByAdminId(grantedBy);
        accountRoleMapper.insert(link);
    }

    private AdminPermission requirePermission(String permissionCode) {
        AdminPermission permission = permissionMapper.selectOne(Wrappers.<AdminPermission>lambdaQuery()
                .eq(AdminPermission::getPermissionCode, permissionCode));
        if (permission == null || !"ACTIVE".equals(permission.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "权限代码不存在");
        }
        return permission;
    }

    private PermissionRequestResponse toResponse(AdminPermissionRequest request) {
        AdminPermission permission = permissionMapper.selectOne(Wrappers.<AdminPermission>lambdaQuery()
                .eq(AdminPermission::getPermissionCode, request.getPermissionCode()));
        return toResponse(request, permission == null ? null : permission.getPermissionName());
    }

    private PermissionRequestResponse toResponse(AdminPermissionRequest request, String permissionName) {
        return new PermissionRequestResponse(request.getId(), request.getPermissionCode(), permissionName,
                request.getStatus(), request.getReason(), request.getDecisionNote(), request.getRequestedBy(),
                request.getHandledBy(), request.getHandledAt(), request.getCreatedAt());
    }
}
