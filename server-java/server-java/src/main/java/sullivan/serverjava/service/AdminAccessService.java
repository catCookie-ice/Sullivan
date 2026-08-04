package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminPermission;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.domain.AdminRolePermission;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminAccessService {
    private final AdminAccountRoleMapper accountRoleMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminPermissionMapper permissionMapper;

    public AdminAccessService(AdminAccountRoleMapper accountRoleMapper, AdminRoleMapper roleMapper,
                              AdminRolePermissionMapper rolePermissionMapper,
                              AdminPermissionMapper permissionMapper) {
        this.accountRoleMapper = accountRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    public Set<String> roles(String adminId) {
        List<AdminAccountRole> links = accountRoleMapper.selectList(Wrappers.<AdminAccountRole>lambdaQuery()
                .eq(AdminAccountRole::getAdminId, adminId)
                .eq(AdminAccountRole::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(AdminAccountRole::getExpiresAt)
                        .or().gt(AdminAccountRole::getExpiresAt, LocalDateTime.now())));
        if (links.isEmpty()) {
            return Set.of();
        }
        return roleMapper.selectBatchIds(links.stream().map(AdminAccountRole::getRoleId).toList()).stream()
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .map(AdminRole::getRoleCode)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> permissions(String adminId) {
        List<AdminAccountRole> accountRoles = accountRoleMapper.selectList(Wrappers.<AdminAccountRole>lambdaQuery()
                .eq(AdminAccountRole::getAdminId, adminId)
                .eq(AdminAccountRole::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(AdminAccountRole::getExpiresAt)
                        .or().gt(AdminAccountRole::getExpiresAt, LocalDateTime.now())));
        if (accountRoles.isEmpty()) {
            return Set.of();
        }
        Set<String> roleIds = accountRoles.stream().map(AdminAccountRole::getRoleId).collect(Collectors.toSet());
        List<AdminRolePermission> links = rolePermissionMapper.selectList(
                Wrappers.<AdminRolePermission>lambdaQuery()
                        .in(AdminRolePermission::getRoleId, roleIds)
                        .eq(AdminRolePermission::getStatus, "ACTIVE"));
        if (links.isEmpty()) {
            return Set.of();
        }
        return permissionMapper.selectBatchIds(links.stream().map(AdminRolePermission::getPermissionId).toList()).stream()
                .filter(permission -> "ACTIVE".equals(permission.getStatus()))
                .map(AdminPermission::getPermissionCode)
                .collect(Collectors.toUnmodifiableSet());
    }
}
