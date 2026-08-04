package sullivan.serverjava.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;

import java.util.List;
import java.time.LocalDateTime;

@Component
@Order(2)
public class HighestAdminBootstrap implements ApplicationRunner {
    private final AdminAccountMapper accountMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminAccountRoleMapper accountRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final String login;
    private final String password;
    private final String displayName;

    public HighestAdminBootstrap(AdminAccountMapper accountMapper, AdminRoleMapper roleMapper,
                                 AdminAccountRoleMapper accountRoleMapper, PasswordEncoder passwordEncoder,
                                 @Value("${app.bootstrap.admin.login:}") String login,
                                 @Value("${app.bootstrap.admin.password:}") String password,
                                 @Value("${app.bootstrap.admin.display-name:初始最高管理员}") String displayName) {
        this.accountMapper = accountMapper;
        this.roleMapper = roleMapper;
        this.accountRoleMapper = accountRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.login = login;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AdminRole superRole = roleMapper.selectOne(Wrappers.<AdminRole>lambdaQuery()
                .eq(AdminRole::getRoleCode, "SUPER_ADMIN")
                .eq(AdminRole::getStatus, "ACTIVE"));
        if (superRole == null) {
            throw new IllegalStateException("SUPER_ADMIN role initialization failed");
        }
        List<AdminAccountRole> superLinks = accountRoleMapper.selectList(
                Wrappers.<AdminAccountRole>lambdaQuery()
                        .eq(AdminAccountRole::getRoleId, superRole.getId())
                        .eq(AdminAccountRole::getStatus, "ACTIVE"));
        boolean exists = superLinks.stream()
                .filter(link -> link.getExpiresAt() == null || link.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(AdminAccountRole::getAdminId)
                .map(accountMapper::selectById)
                .anyMatch(account -> account != null && "ENABLED".equals(account.getStatus()));
        if (exists) {
            return;
        }
        if (!StringUtils.hasText(login) || !StringUtils.hasText(password)) {
            throw new IllegalStateException(
                    "No enabled SUPER_ADMIN exists. Configure app.bootstrap.admin.login and app.bootstrap.admin.password");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("app.bootstrap.admin.password must contain at least 12 characters");
        }
        if (accountMapper.selectCount(Wrappers.<AdminAccount>lambdaQuery().eq(AdminAccount::getLoginName, login)) > 0) {
            throw new IllegalStateException("Bootstrap admin login already belongs to a non-SUPER_ADMIN account");
        }

        AdminAccount admin = new AdminAccount();
        admin.setId(IdGenerator.next());
        admin.setStatus("ENABLED");
        admin.setLoginName(login);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setDisplayName(displayName);
        accountMapper.insert(admin);

        AdminAccountRole link = new AdminAccountRole();
        link.setId(IdGenerator.next());
        link.setAdminId(admin.getId());
        link.setRoleId(superRole.getId());
        link.setStatus("ACTIVE");
        accountRoleMapper.insert(link);
    }
}
