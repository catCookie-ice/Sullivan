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
     * 按**登录名**查找自然人。这是给用户用的查找方式（00 §7 第 11 条、01 §5 第 7 条）。
     *
     * <p><b>为什么需要它。</b>此前只有 {@code GET /users/{userId}}，而 uid 是
     * 26 位随机串 —— 想绑定某人或给他发起会话，用户必须先拿到那一长串字符。
     * 这不是「不够方便」，是**根本做不到**：没有任何界面把对方的 uid 展示给他，
     * 于是所有依赖 uid 的入口都成了死路。登录名才是人记得住、说得出的标识。
     *
     * <p><b>精确匹配，不做模糊。</b>模糊匹配等于把「谁能被搜到」做成了一个功能：
     * 输入 {@code zhang} 就能枚举出全部姓张的账号，配合显示名可以拼出用户名录。
     * 精确匹配下，搜不到就是搜不到，攻击者还得先知道完整登录名。
     *
     * <p><b>不存在与无权查看返回同一个 404</b>，且提示语一致，避免被用来探测账号是否存在。
     */
    public UserLookupResponse findUserByLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少登录名");
        }
        UserAccount account = userAccountMapper.selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getLoginName, loginName.trim())
                .last("LIMIT 1"));
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, USER_NOT_FOUND_MESSAGE);
        }
        return buildUserResponse(account);
    }

    /**
     * 按唯一标识查找自然人（志愿者/监护人/受护人共用此接口，以 roles 区分当前角色）。
     */
    public UserLookupResponse findUser(String userId) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, USER_NOT_FOUND_MESSAGE);
        }
        return buildUserResponse(account);
    }

    /** 账号不存在时统一的提示语：不区分「没这个人」与「你查不到」。 */
    private static final String USER_NOT_FOUND_MESSAGE = "没有找到该登录名对应的账号";

    private UserLookupResponse buildUserResponse(UserAccount account) {
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, account.getId()));
        Set<String> roles = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getUserId, account.getId())
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
