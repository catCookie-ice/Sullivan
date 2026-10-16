package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.RegistrationDtos.ProtecteeRegisterRequest;
import sullivan.serverjava.web.RegistrationDtos.UserRegisterRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTests {
    private UserAccountMapper userAccountMapper;
    private UserRoleMapper userRoleMapper;
    private GuardianBindingMapper bindingMapper;
    private RecoveryService recoveryService;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        userAccountMapper = mock(UserAccountMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        bindingMapper = mock(GuardianBindingMapper.class);
        recoveryService = mock(RecoveryService.class);
        service = new RegistrationService(userAccountMapper, mock(UserProfileMapper.class), userRoleMapper,
                bindingMapper, mock(OrganizationMapper.class), recoveryService, new BCryptPasswordEncoder());
    }

    @Test
    void userRegistrationPersistsRecoveryContacts() {
        when(userAccountMapper.selectCount(any())).thenReturn(0L);

        var result = service.registerUser(new UserRegisterRequest("zhangsan", "password123", "张三",
                "GUARDIAN", "13800138000", "zhangsan@example.com"));

        // 账号 ID 由 IdGenerator 生成，取出后校验 setContacts 使用的是同一 ID
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountMapper).insert(accountCaptor.capture());
        String accountId = accountCaptor.getValue().getId();
        assertThat(result.id()).isEqualTo(accountId);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(recoveryService).setContacts(type.capture(), id.capture(), phone.capture(), email.capture());
        assertThat(type.getValue()).isEqualTo("USER");
        assertThat(id.getValue()).isEqualTo(accountId);
        assertThat(phone.getValue()).isEqualTo("13800138000");
        assertThat(email.getValue()).isEqualTo("zhangsan@example.com");
    }

    /**
     * 省略 role 必须是 400 参数错，而不是 500 内部错误。
     *
     * <p>这里刻意直接调服务而不是走 MockMvc：DTO 上 role 只标了 {@code @Pattern}，
     * 该注解**不校验 null**，所以请求校验放行之后，第一个碰到 null 的就是服务这一行。
     * 曾经写成 {@code Set.of("GUARDIAN","VOLUNTEER").contains(role)}，而
     * {@code Set.of(...).contains(null)} 会抛 NPE，于是公开接口上省略 role 返回 500。
     */
    @Test
    void missingRoleIsABadRequestNotAnInternalError() {
        assertThatThrownBy(() -> service.registerUser(new UserRegisterRequest("zhangsan",
                "password123", "张三", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("自然人只能自主注册为监护人或志愿者")
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        // 未通过校验就不该落任何数据（BaseMapper.insert 有单条与批量两个重载，参数要带类型）。
        verify(userAccountMapper, never()).insert(any(UserAccount.class));
    }

    /** 角色不在白名单里也要是 400，而不是被 Set.of 的异常吞掉。 */
    @Test
    void unknownRoleIsRejectedAsBadRequest() {
        assertThatThrownBy(() -> service.registerUser(new UserRegisterRequest("zhangsan",
                "password123", "张三", "PROTECTEE", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void guardianCreatesProtecteeWithPermanentActiveBinding() {
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(userAccountMapper.selectCount(any())).thenReturn(0L);

        var result = service.registerProtectee("guardian-1",
                new ProtecteeRegisterRequest("protectee01", "password123", "小明", null));

        ArgumentCaptor<GuardianBinding> captor = ArgumentCaptor.forClass(GuardianBinding.class);
        verify(bindingMapper).insert(captor.capture());
        GuardianBinding binding = captor.getValue();
        assertThat(binding.getGuardianUid()).isEqualTo("guardian-1");
        assertThat(binding.getProtecteeUid()).isEqualTo(result.id());
        assertThat(binding.getStatus()).isEqualTo("ACTIVE");
        assertThat(binding.getPermanentFlag()).isTrue();
        assertThat(binding.getIsPrimary()).isTrue();
        assertThat(binding.getRequestedBy()).isEqualTo("guardian-1");
        assertThat(result.roles()).containsExactly("PROTECTEE");
    }
}
