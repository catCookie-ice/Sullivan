package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import sullivan.serverjava.web.AdminManagementDtos.AdminOrgResponse;
import sullivan.serverjava.web.AdminManagementDtos.AdminUserResponse;
import sullivan.serverjava.web.AdminManagementDtos.UserDeleteResult;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端用户与机构管理：列表查询、逻辑删除。
 *
 * <p>志愿者列表包含兼任志愿者的监护人（以 alsoVolunteer 标注）；监护人列表同样显示是否兼任志愿者。
 * 删除兼任志愿者仅撤销其 VOLUNTEER 角色，保留账号；其余删除均为逻辑删除（deleted_at）。</p>
 */
@Service
public class AdminManagementService {
    private static final Set<String> USER_ROLES = Set.of("GUARDIAN", "PROTECTEE", "VOLUNTEER");
    private static final int MAX_PAGE_SIZE = 200;

    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrganizationMapper organizationMapper;
    private final GuardianBindingMapper guardianBindingMapper;

    public AdminManagementService(UserAccountMapper userAccountMapper, UserProfileMapper userProfileMapper,
                                  UserRoleMapper userRoleMapper, OrganizationMapper organizationMapper,
                                  GuardianBindingMapper guardianBindingMapper) {
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.userRoleMapper = userRoleMapper;
        this.organizationMapper = organizationMapper;
        this.guardianBindingMapper = guardianBindingMapper;
    }

    /**
     * 按角色分页查询自然人。role=VOLUNTEER 时同时包含兼任志愿者的监护人；
     * 每行 roles 反映当前生效角色，alsoVolunteer 表示是否（作为监护人）兼任志愿者。
     */
    public IPage<AdminUserResponse> listUsers(String role, String keyword, long page, long size) {
        if (role != null && !USER_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role 取值非法，仅支持 GUARDIAN / PROTECTEE / VOLUNTEER");
        }
        LambdaQueryWrapper<UserAccount> wrapper = Wrappers.lambdaQuery();
        if (role != null) {
            String r = role;
            wrapper.and(w -> w.eq(UserAccount::getRoleCode, r)
                    .or().inSql(UserAccount::getId,
                            "SELECT user_id FROM user_role WHERE role_code='" + r + "' AND status='ACTIVE'"));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            List<String> byName = userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery()
                            .like(UserProfile::getDisplayName, kw)).stream()
                    .map(UserProfile::getUserId).toList();
            wrapper.and(w -> {
                w.like(UserAccount::getLoginName, kw);
                if (!byName.isEmpty()) {
                    w.or().in(UserAccount::getId, byName);
                }
            });
        }
        wrapper.orderByDesc(UserAccount::getCreatedAt);
        Page<UserAccount> result = userAccountMapper.selectPage(validatedPage(page, size), wrapper);
        return toUserPage(result);
    }

    /** 按状态分页查询机构。 */
    public IPage<AdminOrgResponse> listOrganizations(String status, long page, long size) {
        LambdaQueryWrapper<Organization> wrapper = Wrappers.lambdaQuery();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Organization::getStatus, status);
        }
        wrapper.orderByDesc(Organization::getCreatedAt);
        Page<Organization> result = organizationMapper.selectPage(validatedPage(page, size), wrapper);
        Page<AdminOrgResponse> out = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream()
                .map(o -> new AdminOrgResponse(o.getId(), o.getStatus(), o.getLoginName(),
                        o.getLegalName(), o.getPublicName(), o.getCreatedAt()))
                .toList());
        return out;
    }

    /**
     * 删除用户。兼任志愿者（同时具备 GUARDIAN 与 VOLUNTEER 角色）只取消其志愿者兼任；
     * 其余情况逻辑删除整个账号（含撤销全部角色、逻辑删除资料、终止监护绑定）。
     */
    @Transactional
    public UserDeleteResult deleteUser(String userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        Set<String> roles = effectiveRoles(account);
        boolean isVolunteer = roles.contains("VOLUNTEER");
        boolean isGuardian = roles.contains("GUARDIAN");
        if (isVolunteer && isGuardian) {
            revokeVolunteerRole(account);
            return new UserDeleteResult(false, "VOLUNTEER_ROLE_REVOKED",
                    "该用户是兼任志愿者，仅取消其志愿者角色，账号保留");
        }
        deleteAccount(account);
        return new UserDeleteResult(true, "ACCOUNT_DELETED", "用户已逻辑删除");
    }

    /** 逻辑删除机构。 */
    @Transactional
    public void deleteOrganization(String organizationId) {
        Organization organization = organizationMapper.selectById(organizationId);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "机构不存在");
        }
        organizationMapper.deleteById(organizationId);
    }

    private <T> Page<T> validatedPage(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new Page<>(safePage, safeSize);
    }

    private IPage<AdminUserResponse> toUserPage(Page<UserAccount> page) {
        List<String> ids = page.getRecords().stream().map(UserAccount::getId).toList();
        Map<String, UserProfile> profileMap = ids.isEmpty() ? Map.of()
                : userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery()
                        .in(UserProfile::getUserId, ids)).stream()
                        .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));
        Map<String, Set<String>> roleMap = ids.isEmpty() ? Map.of()
                : userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .in(UserRole::getUserId, ids)
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                        .collect(Collectors.groupingBy(UserRole::getUserId,
                                Collectors.mapping(UserRole::getRoleCode, Collectors.toSet())));

        Page<AdminUserResponse> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(page.getRecords().stream().map(account -> {
            Set<String> roles = new HashSet<>(roleMap.getOrDefault(account.getId(), Set.of()));
            roles.add(account.getRoleCode());
            boolean alsoVolunteer = roles.contains("GUARDIAN") && roles.contains("VOLUNTEER");
            UserProfile profile = profileMap.get(account.getId());
            return new AdminUserResponse(account.getId(), account.getStatus(), account.getLoginName(),
                    profile != null ? profile.getDisplayName() : null, account.getRoleCode(),
                    roles, alsoVolunteer, account.getCreatedAt());
        }).toList());
        return out;
    }

    private Set<String> effectiveRoles(UserAccount account) {
        Set<String> roles = new HashSet<>();
        roles.add(account.getRoleCode());
        userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getUserId, account.getId())
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getRoleCode)
                .forEach(roles::add);
        return roles;
    }

    private void revokeVolunteerRole(UserAccount account) {
        int revoked = userRoleMapper.update(null, Wrappers.<UserRole>lambdaUpdate()
                .set(UserRole::getStatus, "REVOKED")
                .eq(UserRole::getUserId, account.getId())
                .eq(UserRole::getRoleCode, "VOLUNTEER")
                .eq(UserRole::getStatus, "ACTIVE"));
        if (revoked == 0 && "VOLUNTEER".equals(account.getRoleCode())) {
            // 志愿者是主角色（没有 user_role 行），把主角色切回 GUARDIAN
            account.setRoleCode("GUARDIAN");
            userAccountMapper.updateById(account);
        }
    }

    private void deleteAccount(UserAccount account) {
        userAccountMapper.deleteById(account.getId());
        userRoleMapper.update(null, Wrappers.<UserRole>lambdaUpdate()
                .set(UserRole::getStatus, "REVOKED")
                .eq(UserRole::getUserId, account.getId())
                .eq(UserRole::getStatus, "ACTIVE"));
        userProfileMapper.delete(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, account.getId()));
        guardianBindingMapper.update(null, Wrappers.<GuardianBinding>lambdaUpdate()
                .set(GuardianBinding::getStatus, "TERMINATED")
                .in(GuardianBinding::getStatus, "PENDING", "ACTIVE")
                .and(w -> w.eq(GuardianBinding::getGuardianUid, account.getId())
                        .or().eq(GuardianBinding::getProtecteeUid, account.getId())));
    }
}
