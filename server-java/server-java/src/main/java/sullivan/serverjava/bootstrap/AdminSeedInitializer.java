package sullivan.serverjava.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.AdminPermission;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.domain.AdminRolePermission;
import sullivan.serverjava.mapper.AdminPermissionMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.mapper.AdminRolePermissionMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理员 RBAC 种子：权限字典、系统角色及其固定权限集，启动时幂等播种。
 *
 * <p>SUPER_ADMIN 固定全量；HR_ADMIN / OPERATIONS / REPORT_ADMIN 的固定集只是「出厂默认」，
 * 运行期可由持 ADMIN_GOVERN 权限的管理员在管理端调整（SUPER_ADMIN 角色除外），
 * 详见 AdminGovernService。</p>
 */
@Component
@Order(1)
public class AdminSeedInitializer implements ApplicationRunner {
    private static final List<PermissionSeed> PERMISSIONS = List.of(
            new PermissionSeed("ADMIN_MANAGE", "管理员账号查看（列表 / 详情 / 按 ID 查询）"),
            new PermissionSeed("ADMIN_GOVERN", "管理员治理：启停管理员账号、授予/撤销角色、调整角色权限集"),
            new PermissionSeed("ADMIN_REGISTRATION_CODE_CREATE", "创建管理员注册码"),
            new PermissionSeed("USER_READ", "查看用户资料"),
            new PermissionSeed("USER_GOVERN", "用户治理"),
            new PermissionSeed("USER_MANAGE", "用户管理（监护/受护/志愿者：查询与删除）"),
            new PermissionSeed("ORG_REVIEW", "机构审核"),
            new PermissionSeed("ORG_GOVERN", "机构治理"),
            new PermissionSeed("ORG_MANAGE", "机构管理（查询与删除）"),
            new PermissionSeed("BUSINESS_AUDIT", "业务审计"),
            new PermissionSeed("OPERATION_STATS", "运营统计"),
            new PermissionSeed("VOLUNTEER_REVIEW", "志愿者兼任审核"),
            new PermissionSeed("ABUSE_REVIEW", "举报治理：举报处置、预设处罚审批、申诉处理、目标档案"),
            new PermissionSeed("SYSTEM_NOTICE", "发布系统通知"));

    /** 系统角色的出厂权限集（SUPER_ADMIN 之外的都可在运行期调整）。 */
    private static final Map<String, Set<String>> ROLE_GRANTS = Map.of(
            // HR 管理员：注册码创建 + 管理员账号查看（ADMIN_MANAGE，仅读）。
            "HR_ADMIN", Set.of("ADMIN_REGISTRATION_CODE_CREATE", "ADMIN_MANAGE"),
            // 运营管理员：运营统计 + 用户管理 + 机构管理 + 机构审核。
            "OPERATIONS", Set.of("OPERATION_STATS", "USER_MANAGE", "ORG_MANAGE", "ORG_REVIEW"),
            // 举报治理管理员：举报处置工作台（admin-web「举报治理」页）。
            "REPORT_ADMIN", Set.of("ABUSE_REVIEW"));

    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;

    public AdminSeedInitializer(AdminRoleMapper roleMapper, AdminPermissionMapper permissionMapper,
                                AdminRolePermissionMapper rolePermissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AdminRole superAdmin = ensureRole("SUPER_ADMIN", "最高管理员");
        AdminRole hrAdmin = ensureRole("HR_ADMIN", "HR管理员");
        AdminRole operations = ensureRole("OPERATIONS", "运营管理员");
        AdminRole reportAdmin = ensureRole("REPORT_ADMIN", "举报治理管理员");

        // SUPER_ADMIN 固定全量；其余角色按出厂映射表授予（保证种子幂等：已有 ACTIVE 行则跳过）。
        for (PermissionSeed seed : PERMISSIONS) {
            ensureGrant(superAdmin.getId(), ensurePermission(seed.code(), seed.name()).getId());
        }
        grantByRoleCode(hrAdmin, ROLE_GRANTS.get("HR_ADMIN"));
        grantByRoleCode(operations, ROLE_GRANTS.get("OPERATIONS"));
        grantByRoleCode(reportAdmin, ROLE_GRANTS.get("REPORT_ADMIN"));
    }

    private void grantByRoleCode(AdminRole role, Set<String> permissionCodes) {
        if (permissionCodes == null) {
            return;
        }
        for (String code : permissionCodes) {
            AdminPermission permission = permissionMapper.selectOne(
                    Wrappers.<AdminPermission>lambdaQuery().eq(AdminPermission::getPermissionCode, code));
            if (permission != null) {
                ensureGrant(role.getId(), permission.getId());
            }
        }
    }

    private AdminRole ensureRole(String code, String name) {
        AdminRole role = roleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery().eq(AdminRole::getRoleCode, code));
        if (role != null) {
            return role;
        }
        role = new AdminRole();
        role.setId(IdGenerator.next());
        role.setStatus("ACTIVE");
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setIsSystem(true);
        roleMapper.insert(role);
        return role;
    }

    private AdminPermission ensurePermission(String code, String name) {
        AdminPermission permission = permissionMapper.selectOne(
                Wrappers.<AdminPermission>lambdaQuery().eq(AdminPermission::getPermissionCode, code));
        if (permission != null) {
            return permission;
        }
        permission = new AdminPermission();
        permission.setId(IdGenerator.next());
        permission.setStatus("ACTIVE");
        permission.setPermissionCode(code);
        permission.setPermissionName(name);
        permissionMapper.insert(permission);
        return permission;
    }

    /**
     * 授予角色一条权限。「同角色同权限至多一行」（uq_admin_role_permission），
     * 因此撤销后再授予必须复用旧行置回 ACTIVE，不能盲目 insert（会撞唯一键）。
     */
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

    private record PermissionSeed(String code, String name) {
    }
}
