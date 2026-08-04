package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.RegistrationDtos.ProtecteeRegisterRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTests {
    private UserAccountMapper userAccountMapper;
    private UserRoleMapper userRoleMapper;
    private GuardianBindingMapper bindingMapper;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        userAccountMapper = mock(UserAccountMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        bindingMapper = mock(GuardianBindingMapper.class);
        service = new RegistrationService(userAccountMapper, mock(UserProfileMapper.class), userRoleMapper,
                bindingMapper, mock(OrganizationMapper.class), new BCryptPasswordEncoder());
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
        assertThat(result.roles()).containsExactly("PROTECTEE");
    }
}
