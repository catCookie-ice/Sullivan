package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private AdminRegistrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        accountMapper = mock(AdminAccountMapper.class);
        roleMapper = mock(AdminRoleMapper.class);
        accountRoleMapper = mock(AdminAccountRoleMapper.class);
        codeMapper = mock(AdminRegistrationCodeMapper.class);
        // 默认 Redis 无此 key（返回 null）→ register 回退到 DB 乐观锁路径，便于断言 DB 行为。
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any(String.class))).thenReturn(null);
        service = new AdminRegistrationService(accountMapper, roleMapper, accountRoleMapper, codeMapper,
                redisTemplate, new BCryptPasswordEncoder());
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

    @Test
    void registrationViaRedisPathResolvesRoleById() {
        // Redis 命中：value 中 roleId 是角色 ID，register 应按 ID 解析并原子核销。
        when(accountMapper.selectCount(any())).thenReturn(0L);
        when(valueOps.get(any(String.class)))
                .thenReturn("{\"roleId\":\"role-operations\",\"expiresAt\":\"2026-09-01T00:00:00\",\"createdByAdminId\":\"hr-1\"}");
        when(roleMapper.selectById("role-operations")).thenReturn(role("role-operations", "OPERATIONS"));
        when(redisTemplate.delete(any(String.class))).thenReturn(true);

        var result = service.register(new AdminRegisterRequest(
                "operator02", "password123", "运营员", "SV-redis-test"));

        ArgumentCaptor<AdminAccountRole> captor = ArgumentCaptor.forClass(AdminAccountRole.class);
        verify(accountRoleMapper).insert(captor.capture());
        assertThat(captor.getValue().getRoleId()).isEqualTo("role-operations");
        assertThat(captor.getValue().getGrantedByAdminId()).isEqualTo("hr-1");
        assertThat(result.roles()).containsExactly("OPERATIONS");
        // Redis 路径核销后应回填 DB 审计字段（先置 USED，再回填 used_by_admin_id）。
        verify(codeMapper, org.mockito.Mockito.times(2)).update(isNull(), any());
    }

    private AdminRole role(String id, String code) {
        AdminRole role = new AdminRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus("ACTIVE");
        return role;
    }
}
