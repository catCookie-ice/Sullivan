package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminPermission;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.domain.AdminRolePermission;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminAccessService {
    private final AdminAccountMapper accountMapper;
    private final AdminAccountRoleMapper accountRoleMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminPermissionMapper permissionMapper;

    public AdminAccessService(AdminAccountMapper accountMapper, AdminAccountRoleMapper accountRoleMapper,
                              AdminRoleMapper roleMapper, AdminRolePermissionMapper rolePermissionMapper,
                              AdminPermissionMapper permissionMapper) {
        this.accountMapper = accountMapper;
        this.accountRoleMapper = accountRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    /**
     * 管理员的实时访问快照：账号状态 + 生效角色 + 汇总权限。
     *
     * @param account     管理员账号行（已确认 ENABLED）
     * @param roles       当前生效角色代码（ACTIVE 角色关系 + ACTIVE 角色 + 未过期）
     * @param permissions 上述角色汇总到的 ACTIVE 权限代码
     */
    public record AdminAccessSnapshot(AdminAccount account, Set<String> roles, Set<String> permissions) {
    }

    /**
     * 每次调用都从库中现算管理员的可访问性，供认证过滤器逐请求使用。
     *
     * <p>文档 03 §5.1 要求「权限变更对新请求立即生效，已签发 JWT 不得长期固化可撤销权限」；
     * 以前角色/权限在登录时写进 JWT（TTL 30 分钟），调整角色或撤销授权后旧令牌仍按旧权限放行，
     * 这正是「权限分配改了却不生效」的根源。改为逐请求查库后，禁用账号、停用角色、
     * 撤销角色关系、撤销角色权限四类变更都在下一个请求立即生效。</p>
     *
     * @return null 表示账号不存在 / 已逻辑删除 / 已禁用 —— 调用方应视为未认证；
     *         角色或权限为空集不属于 null（空权限的管理员仍可登录，只是所有业务接口 403）。
     */
    public AdminAccessSnapshot resolveAdminAccess(String adminId) {
        AdminAccount account = adminId == null ? null : accountMapper.selectById(adminId);
        if (account == null || !"ENABLED".equals(account.getStatus())) {
            return null;
        }
        return new AdminAccessSnapshot(account, roles(adminId), permissions(adminId));
    }

    public Set<String> permissions(String adminId) {
        List<AdminAccountRole> accountRoles = activeAccountRoles(List.of(adminId));
        return permissionsOfRoles(roleIdsOf(accountRoles));
    }

    /** 批量解析多名管理员的生效角色代码：adminId → 角色代码集合（缺省空集）。 */
    public Map<String, Set<String>> roleCodesByAdminIds(Collection<String> adminIds) {
        if (adminIds.isEmpty()) {
            return Map.of();
        }
        List<AdminAccountRole> links = activeAccountRoles(adminIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<String, String> roleCodeById = activeRolesByIds(roleIdsOf(links));
        Map<String, Set<String>> result = new HashMap<>();
        for (AdminAccountRole link : links) {
            String roleCode = roleCodeById.get(link.getRoleId());
            if (roleCode != null) {
                result.computeIfAbsent(link.getAdminId(), key -> new HashSet<>()).add(roleCode);
            }
        }
        return result;
    }

    /** 批量解析角色的汇总权限代码：roleId → 权限代码集合（缺省空集）。 */
    public Map<String, Set<String>> permissionCodesByRoleIds(Collection<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        List<AdminRolePermission> links = rolePermissionMapper.selectList(
                Wrappers.<AdminRolePermission>lambdaQuery()
                        .in(AdminRolePermission::getRoleId, roleIds)
                        .eq(AdminRolePermission::getStatus, "ACTIVE"));
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<String, String> permissionCodeById = permissionMapper.selectBatchIds(
                        links.stream().map(AdminRolePermission::getPermissionId).distinct().toList()).stream()
                .filter(permission -> "ACTIVE".equals(permission.getStatus()))
                .collect(Collectors.toMap(AdminPermission::getId, AdminPermission::getPermissionCode));
        Map<String, Set<String>> result = new HashMap<>();
        for (AdminRolePermission link : links) {
            String permissionCode = permissionCodeById.get(link.getPermissionId());
            if (permissionCode != null) {
                result.computeIfAbsent(link.getRoleId(), key -> new HashSet<>()).add(permissionCode);
            }
        }
        return result;
    }

    /** 生效的角色关系行：ACTIVE 且未过期（expires_at 为空表示永久）。 */
    private List<AdminAccountRole> activeAccountRoles(Collection<String> adminIds) {
        if (adminIds.isEmpty()) {
            return List.of();
        }
        return accountRoleMapper.selectList(Wrappers.<AdminAccountRole>lambdaQuery()
                .in(AdminAccountRole::getAdminId, adminIds)
                .eq(AdminAccountRole::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(AdminAccountRole::getExpiresAt)
                        .or().gt(AdminAccountRole::getExpiresAt, LocalDateTime.now())));
    }

    private Set<String> roleIdsOf(List<AdminAccountRole> links) {
        return links.stream().map(AdminAccountRole::getRoleId).collect(Collectors.toSet());
    }

    /** roleId → 角色代码，仅保留 ACTIVE 角色。 */
    private Map<String, String> activeRolesByIds(Set<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .collect(Collectors.toMap(AdminRole::getId, AdminRole::getRoleCode));
    }

    private Set<String> permissionsOfRoles(Set<String> roleIds) {
        Map<String, Set<String>> byRole = permissionCodesByRoleIds(roleIds);
        return byRole.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> roles(String adminId) {
        List<AdminAccountRole> links = activeAccountRoles(List.of(adminId));
        if (links.isEmpty()) {
            return Set.of();
        }
        Map<String, String> roleCodeById = activeRolesByIds(roleIdsOf(links));
        return links.stream()
                .map(link -> roleCodeById.get(link.getRoleId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
