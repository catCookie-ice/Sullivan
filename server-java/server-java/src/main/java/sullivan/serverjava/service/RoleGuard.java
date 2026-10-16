package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserRoleMapper;

/**
 * 自然人角色与账号状态的联合校验（扫码绑定与扫码登录共用）。
 *
 * <p>存在的意义是把一处已经踩过的坑固化下来：{@code BindingService.requireActiveRole} 在
 * {@code user_role} 有 ACTIVE 行时直接返回，**从不过问 {@code user_account.status}**，
 * 而 {@code BindingService.requestBinding} 里那个 ENABLED 检查针对的是被绑定的 target
 * 而不是 caller。因此凡是「某个自然人能不能做这件事」的判断，都必须
 * **角色关系 + 账号状态**两件事一起看，不能只看其中之一。</p>
 */
@Component
public class RoleGuard {
    private final UserAccountMapper userAccountMapper;
    private final UserRoleMapper userRoleMapper;

    public RoleGuard(UserAccountMapper userAccountMapper, UserRoleMapper userRoleMapper) {
        this.userAccountMapper = userAccountMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** 账号存在、ENABLED、且具备 PROTECTEE 角色。 */
    public void requireEnabledProtectee(String userId) {
        UserAccount account = requireEnabledAccount(userId, "受护人");
        if (!hasActiveRole(userId, "PROTECTEE", account)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该账号不是受护人");
        }
    }

    /** 账号存在、ENABLED、且具备 GUARDIAN 角色。 */
    public void requireEnabledGuardian(String userId) {
        UserAccount account = requireEnabledAccount(userId, "监护人");
        if (!hasActiveRole(userId, "GUARDIAN", account)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该账号不是监护人");
        }
    }

    /** 角色关系生效即可（user_role 为 ACTIVE，或账号主角色就是它）；不看账号状态。 */
    public boolean hasActiveRole(String userId, String roleCode) {
        return hasActiveRole(userId, roleCode, userAccountMapper.selectById(userId));
    }

    private boolean hasActiveRole(String userId, String roleCode, UserAccount account) {
        Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleCode, roleCode)
                .eq(UserRole::getStatus, "ACTIVE"));
        return count > 0 || (account != null && roleCode.equals(account.getRoleCode()));
    }

    private UserAccount requireEnabledAccount(String userId, String label) {
        UserAccount account = userAccountMapper.selectById(userId);
        if (account == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, label + "账号不存在");
        }
        if (!"ENABLED".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, label + "账号已停用");
        }
        return account;
    }
}
