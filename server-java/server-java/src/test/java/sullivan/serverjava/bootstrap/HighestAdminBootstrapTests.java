package sullivan.serverjava.bootstrap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sullivan.serverjava.domain.AdminAccount;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HighestAdminBootstrapTests {
    @Test
    void createsConfiguredHighestAdminWhenDatabaseHasNone() throws Exception {
        AdminAccountMapper accountMapper = mock(AdminAccountMapper.class);
        AdminRoleMapper roleMapper = mock(AdminRoleMapper.class);
        AdminAccountRoleMapper accountRoleMapper = mock(AdminAccountRoleMapper.class);
        AdminRole role = new AdminRole();
        role.setId("super-role");
        role.setRoleCode("SUPER_ADMIN");
        role.setStatus("ACTIVE");
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(accountRoleMapper.selectList(any())).thenReturn(List.of());
        when(accountMapper.selectCount(any())).thenReturn(0L);

        HighestAdminBootstrap bootstrap = new HighestAdminBootstrap(accountMapper, roleMapper, accountRoleMapper,
                new BCryptPasswordEncoder(), "root-admin", "StrongPassword123", "最高管理员");
        bootstrap.run(new DefaultApplicationArguments(new String[0]));

        ArgumentCaptor<AdminAccount> accountCaptor = ArgumentCaptor.forClass(AdminAccount.class);
        verify(accountMapper).insert(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getLoginName()).isEqualTo("root-admin");
        assertThat(accountCaptor.getValue().getPasswordHash()).startsWith("$2");

        ArgumentCaptor<AdminAccountRole> linkCaptor = ArgumentCaptor.forClass(AdminAccountRole.class);
        verify(accountRoleMapper).insert(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getRoleId()).isEqualTo("super-role");
    }
}
