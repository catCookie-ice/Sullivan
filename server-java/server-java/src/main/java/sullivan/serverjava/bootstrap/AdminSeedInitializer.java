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

@Component
@Order(1)
public class AdminSeedInitializer implements ApplicationRunner {
    private static final List<PermissionSeed> PERMISSIONS = List.of(
            new PermissionSeed("ADMIN_MANAGE", "管理员账号管理"),
            new PermissionSeed("ADMIN_REGISTRATION_CODE_CREATE", "创建管理员注册码"),
            new PermissionSeed("USER_READ", "查看用户资料"),
            new PermissionSeed("USER_GOVERN", "用户治理"),
            new PermissionSeed("USER_MANAGE", "用户管理（监护/受护/志愿者：查询与删除）"),
            new PermissionSeed("ORG_REVIEW", "机构审核"),
            new PermissionSeed("ORG_GOVERN", "机构治理"),
            new PermissionSeed("ORG_MANAGE", "机构管理（查询与删除）"),
            new PermissionSeed("BUSINESS_AUDIT", "业务审计"),
            new PermissionSeed("OPERATION_STATS", "运营统计"),
            new PermissionSeed("SYSTEM_NOTICE", "发布系统通知"));

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

        for (PermissionSeed seed : PERMISSIONS) {
            AdminPermission permission = ensurePermission(seed.code(), seed.name());
            ensureGrant(superAdmin.getId(), permission.getId());
            // HR 管理员：注册码创建 + 管理员账号查看（ADMIN_MANAGE，仅读）。
            if ("ADMIN_REGISTRATION_CODE_CREATE".equals(seed.code())
                    || "ADMIN_MANAGE".equals(seed.code())) {
                ensureGrant(hrAdmin.getId(), permission.getId());
            }
            // 运营管理员：运营统计 + 用户管理 + 机构管理。
            if ("OPERATION_STATS".equals(seed.code())
                    || "USER_MANAGE".equals(seed.code())
                    || "ORG_MANAGE".equals(seed.code())) {
                ensureGrant(operations.getId(), permission.getId());
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

    private void ensureGrant(String roleId, String permissionId) {
        Long count = rolePermissionMapper.selectCount(Wrappers.<AdminRolePermission>lambdaQuery()
                .eq(AdminRolePermission::getRoleId, roleId)
                .eq(AdminRolePermission::getPermissionId, permissionId)
                .eq(AdminRolePermission::getStatus, "ACTIVE"));
        if (count > 0) {
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
