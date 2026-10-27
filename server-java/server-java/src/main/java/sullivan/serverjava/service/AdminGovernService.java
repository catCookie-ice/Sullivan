package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminPermission;
import sullivan.serverjava.domain.AdminRegistrationCode;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.domain.AdminRolePermission;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminRegistrationCodeMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.web.AdminGovernDtos.AdminAccountResponse;
import sullivan.serverjava.web.AdminGovernDtos.AdminStatusRequest;
import sullivan.serverjava.web.AdminGovernDtos.PermissionResponse;
import sullivan.serverjava.web.AdminGovernDtos.RoleCreateRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleGrantRequest;
import sullivan.serverjava.web.AdminGovernDtos.RolePermissionsRequest;
import sullivan.serverjava.web.AdminGovernDtos.RoleResponse;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理员治理：管理员账号的查看/启停/角色授予撤销，以及角色（权限集）的管理。
 *
 * <p>以前权限只能在「签发注册码 → 新管理员注册」那一刻随初始角色固定下来，运行期既看不到
 * 谁有什么权限，也没有任何授予/撤销入口 —— VOLUNTEER_REVIEW 这类「需要显式授予」的权限
 * 实际上永远授不出去。本服务补上这块：权限集打包成角色（HR_ADMIN、OPERATIONS 即为例子，
 * 也可新建），角色授予管理员，变更经 {@link AdminAccessService} 逐请求生效。</p>
 *
 * <p>防呆底线（全部在写路径强制）：不能停用自己；任何操作后系统必须仍保有一名
 * ENABLED 的 SUPER_ADMIN；SUPER_ADMIN 角色的权限集固定全量不可改；系统内置角色不可删除
 * （停用代替删除，因为角色行被注册码/授权关系外键引用，且 admin_role 的状态机本就提供
 * DISABLED）。</p>
 */
@Service
public class AdminGovernService {
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final int MAX_PAGE_SIZE = 200;

    private final AdminAccountMapper accountMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminAccountRoleMapper accountRoleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminRegistrationCodeMapper registrationCodeMapper;
    private final AdminAccessService adminAccessService;

    public AdminGovernService(AdminAccountMapper accountMapper, AdminRoleMapper roleMapper,
                              AdminPermissionMapper permissionMapper, AdminAccountRoleMapper accountRoleMapper,
                              AdminRolePermissionMapper rolePermissionMapper,
                              AdminRegistrationCodeMapper registrationCodeMapper,
                              AdminAccessService adminAccessService) {
        this.accountMapper = accountMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.accountRoleMapper = accountRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.registrationCodeMapper = registrationCodeMapper;
        this.adminAccessService = adminAccessService;
    }

    // ------------------------------------------------------------------
    // 管理员账号
    // ------------------------------------------------------------------

    /** 分页列出管理员（含实时角色与权限汇总）；keyword 匹配登录名或显示名。 */
    public IPage<AdminAccountResponse> listAdmins(String keyword, long page, long size) {
        LambdaQueryWrapper<AdminAccount> wrapper = Wrappers.lambdaQuery();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(AdminAccount::getLoginName, kw).or().like(AdminAccount::getDisplayName, kw));
        }
        wrapper.orderByDesc(AdminAccount::getCreatedAt);
        Page<AdminAccount> result = accountMapper.selectPage(validatedPage(page, size), wrapper);
        List<String> ids = result.getRecords().stream().map(AdminAccount::getId).toList();
        Map<String, Set<String>> rolesByAdmin = adminAccessService.roleCodesByAdminIds(ids);
        Map<String, Set<String>> permissionsByAdmin = permissionsByAdminIds(rolesByAdmin);
        Page<AdminAccountResponse> out = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream()
                .map(account -> toResponse(account,
                        rolesByAdmin.getOrDefault(account.getId(), Set.of()),
                        permissionsByAdmin.getOrDefault(account.getId(), Set.of())))
                .toList());
        return out;
    }

    /** 管理员详情（实时角色与权限汇总）。 */
    public AdminAccountResponse getAdmin(String adminId) {
        return toResponse(requireAccount(adminId),
                adminAccessService.roles(adminId), permissionsFor(adminAccessService.roles(adminId)));
    }

    /**
     * 启用/停用管理员账号。停用立即生效：认证过滤器逐请求查库，
     * 被停用者的下一个请求即按未认证处理（无需等 JWT 过期）。
     */
    @Transactional
    public AdminAccountResponse setStatus(String actorId, String adminId, AdminStatusRequest request) {
        AdminAccount account = requireAccount(adminId);
        if (actorId.equals(adminId) && "DISABLED".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "不能停用当前登录的管理员自己");
        }
        if ("DISABLED".equals(request.status())) {
            // 先守卫后落库：停用后必须仍有其他可用的最高管理员（启用是放宽操作，不需要该守卫）。
            requireSurvivingSuperAdmin(adminId);
        }
        account.setStatus(request.status());
        accountMapper.updateById(account);
        return getAdmin(adminId);
    }

    /**
     * 授予角色（带可选过期时间）。同管理员同角色至多一行：撤销后再授予 = 复用旧行置回 ACTIVE。
     * SUPER_ADMIN 只能由本身持有该角色的管理员亲自授予（注册码之外的第二条受控通道）。
     */
    @Transactional
    public AdminAccountResponse grantRole(AuthPrincipal actor, String adminId, RoleGrantRequest request) {
        requireAccount(adminId);
        AdminRole role = requireRoleByCode(request.roleCode());
        if (SUPER_ADMIN.equals(role.getRoleCode()) && !actor.hasRole(SUPER_ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有最高管理员可以授予 SUPER_ADMIN 角色");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "过期时间必须是未来时间");
        }
        upsertAccountRole(adminId, role.getId(), actor.id(), request.expiresAt());
        return getAdmin(adminId);
    }

    /** 撤销角色的生效关系（行置 REVOKED，保留审计痕迹）。 */
    @Transactional
    public AdminAccountResponse revokeRole(String adminId, String roleCode) {
        requireAccount(adminId);
        AdminRole role = requireRoleByCode(roleCode);
        AdminAccountRole link = accountRoleMapper.selectOne(
                Wrappers.<AdminAccountRole>lambdaQuery()
                        .eq(AdminAccountRole::getAdminId, adminId)
                        .eq(AdminAccountRole::getRoleId, role.getId())
                        .eq(AdminAccountRole::getStatus, "ACTIVE"));
        if (link == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "该管理员没有生效的 " + roleCode + " 角色");
        }
        // 先守卫后落库：撤销后必须仍有别的可用最高管理员（含不能撤销自己仅有的 SUPER_ADMIN）。
        requireSurvivingSuperAdmin(adminId);
        link.setStatus("REVOKED");
        accountRoleMapper.updateById(link);
        return getAdmin(adminId);
    }

    // ------------------------------------------------------------------
    // 角色与权限集
    // ------------------------------------------------------------------

    /** 角色列表（仅 ACTIVE），带各自权限集与生效成员数。 */
    public List<RoleResponse> listRoles() {
        List<AdminRole> roles = roleMapper.selectList(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getStatus, "ACTIVE")
                .orderByDesc(AdminRole::getIsSystem)
                .orderByAsc(AdminRole::getRoleCode));
        if (roles.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> permissionsByRole = adminAccessService.permissionCodesByRoleIds(
                roles.stream().map(AdminRole::getId).toList());
        Map<String, Long> memberCountByRole = activeMemberCounts(roles.stream().map(AdminRole::getId).toList());
        return roles.stream()
                .map(role -> new RoleResponse(role.getId(), role.getRoleCode(), role.getRoleName(),
                        Boolean.TRUE.equals(role.getIsSystem()),
                        permissionsByRole.getOrDefault(role.getId(), Set.of()),
                        memberCountByRole.getOrDefault(role.getId(), 0L)))
                .toList();
    }

    /** 权限代码字典。 */
    public List<PermissionResponse> listPermissions() {
        return permissionMapper.selectList(Wrappers.<AdminPermission>lambdaQuery()
                        .eq(AdminPermission::getStatus, "ACTIVE")
                        .orderByAsc(AdminPermission::getPermissionCode)).stream()
                .map(permission -> new PermissionResponse(
                        permission.getPermissionCode(), permission.getPermissionName()))
                .toList();
    }

    /** 新建自定义角色（权限集打包）。系统角色由种子播种，不经此入口。 */
    @Transactional
    public RoleResponse createRole(RoleCreateRequest request) {
        if (roleMapper.selectCount(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, request.roleCode())) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "角色代码已存在");
        }
        if (request.permissionCodes() != null) {
            requireAllPermissionsExist(request.permissionCodes());
        }
        AdminRole role = new AdminRole();
        role.setId(IdGenerator.next());
        role.setStatus("ACTIVE");
        role.setRoleCode(request.roleCode());
        role.setRoleName(request.roleName().trim());
        role.setIsSystem(false);
        roleMapper.insert(role);
        if (request.permissionCodes() != null) {
            setRoleGrants(role, request.permissionCodes());
        }
        return findRoleResponse(role.getId());
    }

    /**
     * 全量覆盖式调整角色权限集：缺少的授予（复用 REVOKED 旧行），多余的撤销。
     * SUPER_ADMIN 角色固定全量，不可调整 —— 它是防锁定底线。
     */
    @Transactional
    public RoleResponse updateRolePermissions(String roleCode, RolePermissionsRequest request) {
        AdminRole role = requireRoleByCode(roleCode);
        if (SUPER_ADMIN.equals(role.getRoleCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "最高管理员的权限集固定为全量，不可调整");
        }
        requireAllPermissionsExist(request.permissionCodes());
        setRoleGrants(role, request.permissionCodes());
        return findRoleResponse(role.getId());
    }

    /**
     * 停用角色（不是物理删除：角色行被注册码与授权关系外键引用，且状态机提供 DISABLED）。
     * 停用立即生效：角色关系解析时过滤非 ACTIVE 角色，持有者下一请求即失去其权限。
     */
    @Transactional
    public void disableRole(String roleCode) {
        AdminRole role = requireRoleByCode(roleCode);
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new ApiException(HttpStatus.CONFLICT, "系统内置角色不可删除，如需回收权限请调整其权限集");
        }
        long members = activeMemberCounts(List.of(role.getId())).getOrDefault(role.getId(), 0L);
        if (members > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "仍有 " + members + " 名管理员持有该角色，请先撤销其角色");
        }
        Long activeCodes = registrationCodeMapper.selectCount(Wrappers.<AdminRegistrationCode>lambdaQuery()
                .eq(AdminRegistrationCode::getRoleId, role.getId())
                .eq(AdminRegistrationCode::getStatus, "ACTIVE"));
        if (activeCodes > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "仍有 " + activeCodes + " 张可用注册码绑定该角色，请让其过期或作废后再停用");
        }
        role.setStatus("DISABLED");
        roleMapper.updateById(role);
    }

    // ------------------------------------------------------------------
    // 内部装配与守卫
    // ------------------------------------------------------------------

    private AdminAccountResponse toResponse(AdminAccount account, Set<String> roles, Set<String> permissions) {
        return new AdminAccountResponse(account.getId(), account.getStatus(), account.getLoginName(),
                account.getDisplayName(), roles, permissions);
    }

    private Set<String> permissionsFor(Set<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        List<AdminRole> roles = roleMapper.selectList(Wrappers.<AdminRole>lambdaQuery()
                .in(AdminRole::getRoleCode, roleCodes)
                .eq(AdminRole::getStatus, "ACTIVE"));
        Map<String, Set<String>> byRole = adminAccessService.permissionCodesByRoleIds(
                roles.stream().map(AdminRole::getId).toList());
        return byRole.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }

    /** 管理员 ids → 汇总权限（按其生效角色聚合）。 */
    private Map<String, Set<String>> permissionsByAdminIds(Map<String, Set<String>> rolesByAdmin) {
        Set<String> allRoleCodes = rolesByAdmin.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        if (allRoleCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> roleIdByCode = roleMapper.selectList(Wrappers.<AdminRole>lambdaQuery()
                        .in(AdminRole::getRoleCode, allRoleCodes)
                        .eq(AdminRole::getStatus, "ACTIVE")).stream()
                .collect(Collectors.toMap(AdminRole::getRoleCode, AdminRole::getId));
        Map<String, Set<String>> permissionsByRoleId =
                adminAccessService.permissionCodesByRoleIds(roleIdByCode.values());
        Map<String, Set<String>> result = new HashMap<>();
        rolesByAdmin.forEach((adminId, roleCodes) -> {
            Set<String> merged = new HashSet<>();
            for (String roleCode : roleCodes) {
                String roleId = roleIdByCode.get(roleCode);
                if (roleId != null) {
                    merged.addAll(permissionsByRoleId.getOrDefault(roleId, Set.of()));
                }
            }
            result.put(adminId, merged);
        });
        return result;
    }

    /** roleId → 生效成员数（ACTIVE 且未过期的角色关系）。 */
    private Map<String, Long> activeMemberCounts(Collection<String> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return accountRoleMapper.selectList(Wrappers.<AdminAccountRole>lambdaQuery()
                        .in(AdminAccountRole::getRoleId, roleIds)
                        .eq(AdminAccountRole::getStatus, "ACTIVE")
                        .and(wrapper -> wrapper.isNull(AdminAccountRole::getExpiresAt)
                                .or().gt(AdminAccountRole::getExpiresAt, LocalDateTime.now()))).stream()
                .collect(Collectors.groupingBy(AdminAccountRole::getRoleId, Collectors.counting()));
    }

    /**
     * 防锁定底线：任何变更后，除 candidateAdminId 之外必须仍存在至少一名
     * 持有 SUPER_ADMIN 且账号 ENABLED 的管理员（candidate 自身被停用/被撤销也不破坏底线）。
     */
    private void requireSurvivingSuperAdmin(String candidateAdminId) {
        AdminRole superRole = roleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, SUPER_ADMIN)
                .eq(AdminRole::getStatus, "ACTIVE"));
        if (superRole == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SUPER_ADMIN 角色缺失，请检查种子数据");
        }
        List<AdminAccountRole> links = accountRoleMapper.selectList(Wrappers.<AdminAccountRole>lambdaQuery()
                .eq(AdminAccountRole::getRoleId, superRole.getId())
                .eq(AdminAccountRole::getStatus, "ACTIVE")
                .and(wrapper -> wrapper.isNull(AdminAccountRole::getExpiresAt)
                        .or().gt(AdminAccountRole::getExpiresAt, LocalDateTime.now())));
        List<String> holderIds = links.stream()
                .map(AdminAccountRole::getAdminId)
                .filter(id -> !id.equals(candidateAdminId))
                .distinct()
                .toList();
        if (holderIds.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "系统必须保留至少一名其他可用的最高管理员（SUPER_ADMIN）");
        }
        Long enabled = accountMapper.selectCount(Wrappers.<AdminAccount>lambdaQuery()
                .in(AdminAccount::getId, holderIds)
                .eq(AdminAccount::getStatus, "ENABLED"));
        if (enabled == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "系统必须保留至少一名其他可用的最高管理员（SUPER_ADMIN）");
        }
    }

    /**
     * 授予（upsert）：同管理员同角色唯一，存在 REVOKED 旧行则复用置回 ACTIVE 并刷新授予人/过期时间，
     * 否则插入新行。
     */
    private void upsertAccountRole(String adminId, String roleId, String grantedBy, LocalDateTime expiresAt) {
        AdminAccountRole existing = accountRoleMapper.selectOne(Wrappers.<AdminAccountRole>lambdaQuery()
                .eq(AdminAccountRole::getAdminId, adminId)
                .eq(AdminAccountRole::getRoleId, roleId));
        if (existing != null) {
            existing.setStatus("ACTIVE");
            existing.setGrantedByAdminId(grantedBy);
            existing.setExpiresAt(expiresAt);
            accountRoleMapper.updateById(existing);
            return;
        }
        AdminAccountRole link = new AdminAccountRole();
        link.setId(IdGenerator.next());
        link.setAdminId(adminId);
        link.setRoleId(roleId);
        link.setStatus("ACTIVE");
        link.setGrantedByAdminId(grantedBy);
        link.setExpiresAt(expiresAt);
        accountRoleMapper.insert(link);
    }

    /** 全量覆盖式设置角色的权限授权：缺的授予（复用 REVOKED 旧行），多的撤销。 */
    private void setRoleGrants(AdminRole role, Set<String> permissionCodes) {
        Set<String> desiredPermissionIds = new LinkedHashSet<>();
        for (String code : new LinkedHashSet<>(permissionCodes)) {
            AdminPermission permission = permissionMapper.selectOne(Wrappers.<AdminPermission>lambdaQuery()
                    .eq(AdminPermission::getPermissionCode, code));
            if (permission != null && "ACTIVE".equals(permission.getStatus())) {
                desiredPermissionIds.add(permission.getId());
            }
        }
        Map<String, AdminRolePermission> existingByPermissionId = rolePermissionMapper.selectList(
                        Wrappers.<AdminRolePermission>lambdaQuery()
                                .eq(AdminRolePermission::getRoleId, role.getId())).stream()
                .collect(Collectors.toMap(AdminRolePermission::getPermissionId, Function.identity()));

        for (String permissionId : desiredPermissionIds) {
            AdminRolePermission existing = existingByPermissionId.get(permissionId);
            if (existing != null) {
                if (!"ACTIVE".equals(existing.getStatus())) {
                    existing.setStatus("ACTIVE");
                    rolePermissionMapper.updateById(existing);
                }
                continue;
            }
            AdminRolePermission link = new AdminRolePermission();
            link.setId(IdGenerator.next());
            link.setRoleId(role.getId());
            link.setPermissionId(permissionId);
            link.setStatus("ACTIVE");
            rolePermissionMapper.insert(link);
        }
        for (AdminRolePermission existing : existingByPermissionId.values()) {
            if ("ACTIVE".equals(existing.getStatus()) && !desiredPermissionIds.contains(existing.getPermissionId())) {
                existing.setStatus("REVOKED");
                rolePermissionMapper.updateById(existing);
            }
        }
    }

    private void requireAllPermissionsExist(Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return; // 空集合法（= 清空角色权限）；同时避免 IN () 空集合 SQL。
        }
        List<String> known = permissionMapper.selectList(Wrappers.<AdminPermission>lambdaQuery()
                        .in(AdminPermission::getPermissionCode, permissionCodes)
                        .eq(AdminPermission::getStatus, "ACTIVE")).stream()
                .map(AdminPermission::getPermissionCode)
                .toList();
        List<String> unknown = permissionCodes.stream().filter(code -> !known.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未知或已停用的权限代码：" + String.join("、", unknown));
        }
    }

    private AdminAccount requireAccount(String adminId) {
        AdminAccount account = adminId == null ? null : accountMapper.selectById(adminId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "管理员不存在");
        }
        return account;
    }

    private AdminRole requireRoleByCode(String roleCode) {
        AdminRole role = roleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, roleCode));
        if (role == null || !"ACTIVE".equals(role.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "角色不存在或已停用");
        }
        return role;
    }

    private RoleResponse findRoleResponse(String roleId) {
        return listRoles().stream()
                .filter(role -> role.id().equals(roleId))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "角色不存在或已停用"));
    }

    private Page<AdminAccount> validatedPage(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new Page<>(safePage, safeSize);
    }
}
