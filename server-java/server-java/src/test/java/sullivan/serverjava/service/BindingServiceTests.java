package sullivan.serverjava.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.GuardianBinding;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.GuardianBindingMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.web.BindingDtos.BindingRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BindingServiceTests {
    private static final String GUARDIAN = "guardian-1";
    private static final String PROTECTEE = "protectee-1";
    private static final String OTHER = "outsider-1";

    private GuardianBindingMapper bindingMapper;
    private UserAccountMapper userAccountMapper;
    private UserRoleMapper userRoleMapper;
    private UserProfileMapper userProfileMapper;
    private BindingService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(GuardianBindingMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        service = new BindingService(bindingMapper, userAccountMapper, userRoleMapper, userProfileMapper);
        // 角色校验默认通过（GUARDIAN / PROTECTEE 均视为 ACTIVE）
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(userAccountMapper.selectById(any())).thenReturn(account(PROTECTEE, "PROTECTEE"));
    }

    @Test
    void guardianRequestsBindingCreatesPendingWithRequestedBy() {
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        BindingResponse result = service.requestBinding(GUARDIAN, new BindingRequest(PROTECTEE, "PROTECTEE"));

        ArgumentCaptor<GuardianBinding> captor = ArgumentCaptor.forClass(GuardianBinding.class);
        verify(bindingMapper).insert(captor.capture());
        GuardianBinding binding = captor.getValue();
        assertThat(binding.getStatus()).isEqualTo("PENDING");
        assertThat(binding.getRequestedBy()).isEqualTo(GUARDIAN);
        assertThat(binding.getGuardianUid()).isEqualTo(GUARDIAN);
        assertThat(binding.getProtecteeUid()).isEqualTo(PROTECTEE);
        assertThat(binding.getExpiresAt()).isNotNull();
        assertThat(result.requestedBy()).isEqualTo(GUARDIAN);
    }

    @Test
    void protecteeRequestsBindingToGuardianCreatesPending() {
        when(bindingMapper.selectCount(any())).thenReturn(0L);
        when(userAccountMapper.selectById(any())).thenReturn(account(GUARDIAN, "GUARDIAN"));

        BindingResponse result = service.requestBinding(PROTECTEE, new BindingRequest(GUARDIAN, "GUARDIAN"));

        ArgumentCaptor<GuardianBinding> captor = ArgumentCaptor.forClass(GuardianBinding.class);
        verify(bindingMapper).insert(captor.capture());
        GuardianBinding binding = captor.getValue();
        assertThat(binding.getRequestedBy()).isEqualTo(PROTECTEE);
        assertThat(binding.getGuardianUid()).isEqualTo(GUARDIAN);
        assertThat(binding.getProtecteeUid()).isEqualTo(PROTECTEE);
        assertThat(result.requestedBy()).isEqualTo(PROTECTEE);
    }

    @Test
    void duplicateActiveBindingRejectedWithConflict() {
        when(bindingMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.requestBinding(GUARDIAN, new BindingRequest(PROTECTEE, "PROTECTEE")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void receiverAcceptsPendingBindingAndBecomesPrimary() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);
        when(bindingMapper.selectCount(any())).thenReturn(0L); // 无主绑定
        when(bindingMapper.update(any(), any())).thenReturn(1);

        BindingResponse result = service.acceptBinding(PROTECTEE, "b1");

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.primary()).isTrue();
    }

    @Test
    void initiatorCannotAcceptOwnRequest() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);

        assertThatThrownBy(() -> service.acceptBinding(GUARDIAN, "b1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void outsiderCannotAccept() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);

        assertThatThrownBy(() -> service.acceptBinding(OTHER, "b1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void receiverRejectsPendingBinding() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);
        when(bindingMapper.update(any(), any())).thenReturn(1);

        BindingResponse result = service.rejectBinding(PROTECTEE, "b1", "暂不接受");

        assertThat(result.status()).isEqualTo("REJECTED");
    }

    @Test
    void initiatorCancelsOwnPending() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);
        when(bindingMapper.update(any(), any())).thenReturn(1);

        BindingResponse result = service.cancelBinding(GUARDIAN, "b1");

        assertThat(result.status()).isEqualTo("CANCELLED");
    }

    @Test
    void nonInitiatorCannotCancel() {
        GuardianBinding binding = pendingBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        when(bindingMapper.selectById("b1")).thenReturn(binding);

        assertThatThrownBy(() -> service.cancelBinding(PROTECTEE, "b1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void permanentBindingCannotBeTerminated() {
        GuardianBinding binding = activeBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        binding.setPermanentFlag(true);
        binding.setIsPrimary(true);
        when(bindingMapper.selectById("b1")).thenReturn(binding);

        assertThatThrownBy(() -> service.terminateBinding(GUARDIAN, "b1", null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void terminatingPrimaryPromotesNextActiveBinding() {
        GuardianBinding primary = activeBinding(GUARDIAN, PROTECTEE, GUARDIAN);
        primary.setIsPrimary(true);
        GuardianBinding next = activeBinding("guardian-2", PROTECTEE, "guardian-2");
        when(bindingMapper.selectById("b1")).thenReturn(primary);
        when(bindingMapper.update(any(), any())).thenReturn(1);
        when(bindingMapper.selectList(any())).thenReturn(List.of(next));

        BindingResponse result = service.terminateBinding(PROTECTEE, "b1", null);

        assertThat(result.status()).isEqualTo("TERMINATED");
        ArgumentCaptor<GuardianBinding> promoted = ArgumentCaptor.forClass(GuardianBinding.class);
        verify(bindingMapper).updateById(promoted.capture());
        assertThat(promoted.getValue().getIsPrimary()).isTrue();
    }

    @Test
    void hasActiveBindingChecksEitherDirection() {
        when(bindingMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.hasActiveBinding(GUARDIAN, PROTECTEE)).isTrue();
        assertThat(service.hasActiveBinding(PROTECTEE, GUARDIAN)).isTrue();
    }

    @Test
    void expireExpiredBindingsRunsUpdate() {
        when(bindingMapper.update(any(), any())).thenReturn(1);
        service.expireExpiredBindings();
        verify(bindingMapper).update(any(), any());
    }

    private GuardianBinding pendingBinding(String guardian, String protectee, String requestedBy) {
        GuardianBinding binding = new GuardianBinding();
        binding.setId("b1");
        binding.setStatus("PENDING");
        binding.setGuardianUid(guardian);
        binding.setProtecteeUid(protectee);
        binding.setRequestedBy(requestedBy);
        binding.setIsPrimary(false);
        binding.setPermanentFlag(false);
        return binding;
    }

    private GuardianBinding activeBinding(String guardian, String protectee, String requestedBy) {
        GuardianBinding binding = pendingBinding(guardian, protectee, requestedBy);
        binding.setStatus("ACTIVE");
        return binding;
    }

    private UserAccount account(String id, String roleCode) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus("ENABLED");
        account.setRoleCode(roleCode);
        return account;
    }
}
