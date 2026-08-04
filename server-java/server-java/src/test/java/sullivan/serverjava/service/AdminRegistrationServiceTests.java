package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sullivan.serverjava.domain.AdminAccountRole;
import sullivan.serverjava.domain.AdminRegistrationCode;
import sullivan.serverjava.domain.AdminRole;
import sullivan.serverjava.mapper.AdminAccountMapper;
import sullivan.serverjava.mapper.AdminAccountRoleMapper;
import sullivan.serverjava.mapper.AdminRegistrationCodeMapper;
import sullivan.serverjava.mapper.AdminRoleMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.web.RegistrationDtos.AdminCodeCreateRequest;
import sullivan.serverjava.web.RegistrationDtos.AdminRegisterRequest;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRegistrationServiceTests {
    private AdminAccountMapper accountMapper;
    private AdminRoleMapper roleMapper;
    private AdminAccountRoleMapper accountRoleMapper;
    private AdminRegistrationCodeMapper codeMapper;
    private AdminRegistrationService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AdminAccountMapper.class);
        roleMapper = mock(AdminRoleMapper.class);
        accountRoleMapper = mock(AdminAccountRoleMapper.class);
        codeMapper = mock(AdminRegistrationCodeMapper.class);
        service = new AdminRegistrationService(accountMapper, roleMapper, accountRoleMapper, codeMapper,
                new BCryptPasswordEncoder());
    }

    @Test
    void hrAdminCanCreateOperationsRegistrationCode() {
        AdminRole operations = role("role-operations", "OPERATIONS");
        when(roleMapper.selectOne(any())).thenReturn(operations);
        AuthPrincipal actor = new AuthPrincipal("hr-1", "ADMIN", Set.of("HR_ADMIN"),
                Set.of("ADMIN_REGISTRATION_CODE_CREATE"));

        var result = service.createCode(actor,
                new AdminCodeCreateRequest("OPERATIONS", LocalDateTime.now().plusDays(1)));

        assertThat(result.code()).startsWith("SV-");
        assertThat(result.initialRoleCode()).isEqualTo("OPERATIONS");
        verify(codeMapper).insert(any(AdminRegistrationCode.class));
    }

    @Test
    void registrationConsumesCodeAndAssignsItsRole() {
        AdminRegistrationCode code = new AdminRegistrationCode();
        code.setId("code-1");
        code.setRoleId("role-operations");
        code.setStatus("ACTIVE");
        code.setVersion(0L);
        code.setCreatedByAdminId("hr-1");
        code.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(accountMapper.selectCount(any())).thenReturn(0L);
        when(codeMapper.selectOne(any())).thenReturn(code);
        when(roleMapper.selectById("role-operations")).thenReturn(role("role-operations", "OPERATIONS"));
        when(codeMapper.update(isNull(), any())).thenReturn(1);

        var result = service.register(new AdminRegisterRequest(
                "operator01", "password123", "运营员", "SV-test"));

        ArgumentCaptor<AdminAccountRole> captor = ArgumentCaptor.forClass(AdminAccountRole.class);
        verify(accountRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleId()).isEqualTo("role-operations");
        assertThat(captor.getValue().getGrantedByAdminId()).isEqualTo("hr-1");
        assertThat(result.roles()).containsExactly("OPERATIONS");
    }

    private AdminRole role(String id, String code) {
        AdminRole role = new AdminRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus("ACTIVE");
        return role;
    }
}
