package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
import sullivan.serverjava.web.LookupDtos.AdminLookupResponse;
import sullivan.serverjava.web.LookupDtos.OrganizationLookupResponse;
import sullivan.serverjava.web.LookupDtos.UserLookupResponse;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按唯一标识（ID）查找各业务对象。查找接口不返回密码哈希等敏感字段。
 */
@Service
public class LookupService {
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrganizationMapper organizationMapper;
    private final AdminAccountMapper adminAccountMapper;
    private final AdminAccessService adminAccessService;

    public LookupService(UserAccountMapper userAccountMapper, UserProfileMapper userProfileMapper,
                         UserRoleMapper userRoleMapper, OrganizationMapper organizationMapper,
                         AdminAccountMapper adminAccountMapper, AdminAccessService adminAccessService) {
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.userRoleMapper = userRoleMapper;
        this.organizationMapper = organizationMapper;
        this.adminAccountMapper = adminAccountMapper;
        this.adminAccessService = adminAccessService;
    }

    /**
     * 按唯一标识查找自然人（志愿者/监护人/受护人共用此接口，以 roles 区分当前角色）。
     */
    public UserLookupResponse findUser(String userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "自然人不存在");
        }
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, userId));
        Set<String> roles = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getStatus, "ACTIVE")).stream()
                .map(UserRole::getRoleCode)
                .collect(Collectors.toSet());
        if (roles.isEmpty()) {
            roles = Set.of(account.getRoleCode());
        } else {
            roles = new java.util.HashSet<>(roles);
            roles.add(account.getRoleCode());
        }
        boolean alsoVolunteer = roles.contains("GUARDIAN") && roles.contains("VOLUNTEER");
        return new UserLookupResponse(account.getId(), account.getStatus(), account.getLoginName(),
                profile != null ? profile.getDisplayName() : null, account.getRoleCode(), roles, alsoVolunteer);
    }

    /**
     * 按唯一标识查找公益机构。
     */
    public OrganizationLookupResponse findOrganization(String organizationId) {
        Organization organization = organizationMapper.selectById(organizationId);
        if (organization == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "机构不存在");
        }
        return new OrganizationLookupResponse(organization.getId(), organization.getStatus(),
                organization.getLoginName(), organization.getLegalName(), organization.getPublicName());
    }

    /**
     * 按唯一标识查找管理员。调用方须具备 ADMIN_MANAGE 权限（见 LookupController）。
     */
    public AdminLookupResponse findAdmin(String adminId) {
        AdminAccount account = adminAccountMapper.selectById(adminId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "管理员不存在");
        }
        return new AdminLookupResponse(account.getId(), account.getStatus(), account.getLoginName(),
                account.getDisplayName(), adminAccessService.roles(account.getId()));
    }
}
