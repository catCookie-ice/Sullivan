package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.Hashing;
import sullivan.serverjava.domain.QrLoginTicket;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.QrLoginTicketMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.web.QrDtos.LoginTicketScanResponse;
import sullivan.serverjava.web.QrDtos.LoginTicketStatusResponse;
import sullivan.serverjava.web.RegistrationDtos.TokenResponse;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QrLoginServiceTests {
    private static final String PROTECTEE = "protectee-1";
    private static final String OTHER_PROTECTEE = "protectee-2";
    private static final String GUARDIAN = "guardian-1";
    private static final String OTHER_GUARDIAN = "guardian-2";
    private static final String TICKET = "TICKET-abc";
    private static final String CLIENT_SECRET = "s3cret-value";
    private static final String EXCHANGE_CODE = "EXCHANGE-code";

    private QrLoginTicketMapper ticketMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private BindingService bindingService;
    private AuthService authService;
    private RoleGuard roleGuard;
    private QrLoginService service;

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存（同 RealtimeSessionServiceTests）。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, QrLoginTicket.class);
        TableInfoHelper.initTableInfo(assistant, UserProfile.class);
    }

    @BeforeEach
    void setUp() {
        ticketMapper = mock(QrLoginTicketMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        bindingService = mock(BindingService.class);
        authService = mock(AuthService.class);
        roleGuard = mock(RoleGuard.class);
        service = new QrLoginService(ticketMapper, userAccountMapper, userProfileMapper,
                bindingService, authService, roleGuard, 120);
        when(userAccountMapper.selectById(anyString()))
                .thenAnswer(call -> account(call.<String>getArgument(0)));
        when(userProfileMapper.selectOne(any())).thenReturn(profile("王小明"));
    }

    @Test
    void pollingResponseNeverCarriesAToken() {
        // 契约钉死：轮询接口的 DTO 里不许出现任何令牌字段。
        // 一旦有人「图省事」把 accessToken 塞进这个响应，GET 带凭据出网就会进各级日志。
        List<String> components = Arrays.stream(LoginTicketStatusResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).doesNotContain("accessToken", "token", "access_token");
        assertThat(components).containsExactlyInAnyOrder(
                "status", "exchangeCode", "protecteeName", "protecteeId", "ttlSeconds");
    }

    @Test
    void pendingTicketReportsStatusWithoutExchangeCode() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("PENDING", null, null));

        LoginTicketStatusResponse response = service.status(TICKET);

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.exchangeCode()).isNull();
        assertThat(response.protecteeId()).isNull();
        assertThat(response.ttlSeconds()).isGreaterThan(0);
    }

    @Test
    void expiredTicketReportsExpiredWithoutWritingToTheDatabase() {
        QrLoginTicket row = ticket("PENDING", null, null);
        row.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(ticketMapper.selectById(TICKET)).thenReturn(row);

        assertThat(service.status(TICKET).status()).isEqualTo("EXPIRED");
        // 读路径不写库：否则「标记过期后抛异常」会把标记一起回滚。
        verify(ticketMapper, never()).update(any(), any());
    }

    @Test
    void consumedTicketLooksGoneAndCannotBePolled() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("CONSUMED", PROTECTEE, EXCHANGE_CODE));

        assertThatThrownBy(() -> service.status(TICKET))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void confirmedTicketHandsBackExchangeCodeAndProtectee() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("CONFIRMED", PROTECTEE, EXCHANGE_CODE));

        LoginTicketStatusResponse response = service.status(TICKET);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.exchangeCode()).isEqualTo(EXCHANGE_CODE);
        assertThat(response.protecteeId()).isEqualTo(PROTECTEE);
        assertThat(response.protecteeName()).isEqualTo("王小明");
    }

    @Test
    void guardianScanMarksTicketScannedAndListsOwnProtectees() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("PENDING", null, null));
        when(ticketMapper.update(any(), any())).thenReturn(1);
        when(bindingService.activeProtecteeIds(GUARDIAN)).thenReturn(List.of(PROTECTEE));

        LoginTicketScanResponse response = service.scan(GUARDIAN, TICKET);

        assertThat(response.status()).isEqualTo("SCANNED");
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).protecteeId()).isEqualTo(PROTECTEE);
        assertThat(response.candidates().get(0).displayName()).isEqualTo("王小明");
    }

    @Test
    void secondGuardianScanningSameTicketGetsConflict() {
        QrLoginTicket row = ticket("SCANNED", null, null);
        row.setGuardianUid(GUARDIAN);
        when(ticketMapper.selectById(TICKET)).thenReturn(row);

        assertThatThrownBy(() -> service.scan(OTHER_GUARDIAN, TICKET))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rescanningBySameGuardianIsIdempotent() {
        QrLoginTicket row = ticket("SCANNED", null, null);
        row.setGuardianUid(GUARDIAN);
        when(ticketMapper.selectById(TICKET)).thenReturn(row);
        when(bindingService.activeProtecteeIds(GUARDIAN)).thenReturn(List.of(PROTECTEE, OTHER_PROTECTEE));

        assertThat(service.scan(GUARDIAN, TICKET).candidates()).hasSize(2);
    }

    @Test
    void confirmRejectsProtecteeTheGuardianDoesNotGuard() {
        QrLoginTicket row = ticket("SCANNED", null, null);
        row.setGuardianUid(GUARDIAN);
        when(ticketMapper.selectById(TICKET)).thenReturn(row);
        when(bindingService.hasActiveGuardianship(GUARDIAN, OTHER_PROTECTEE)).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(GUARDIAN, TICKET, OTHER_PROTECTEE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(ticketMapper, never()).update(any(), any());
    }

    @Test
    void confirmRequiresTheTicketToBeScannedFirst() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("PENDING", null, null));

        assertThatThrownBy(() -> service.confirm(GUARDIAN, TICKET, PROTECTEE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void confirmByAnotherGuardianIsForbidden() {
        QrLoginTicket row = ticket("SCANNED", null, null);
        row.setGuardianUid(GUARDIAN);
        when(ticketMapper.selectById(TICKET)).thenReturn(row);

        assertThatThrownBy(() -> service.confirm(OTHER_GUARDIAN, TICKET, PROTECTEE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void confirmIssuesExchangeCodeForBoundProtectee() {
        QrLoginTicket row = ticket("SCANNED", null, null);
        row.setGuardianUid(GUARDIAN);
        when(ticketMapper.selectById(TICKET)).thenReturn(row);
        when(bindingService.hasActiveGuardianship(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(ticketMapper.update(any(), any())).thenReturn(1);

        LoginTicketStatusResponse response = service.confirm(GUARDIAN, TICKET, PROTECTEE);

        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.exchangeCode()).isNotBlank();
        assertThat(response.protecteeId()).isEqualTo(PROTECTEE);
    }

    @Test
    void exchangeRejectsWrongClientSecret() {
        when(ticketMapper.selectById(TICKET)).thenReturn(confirmedTicket());

        assertThatThrownBy(() -> service.exchange(TICKET, "not-the-secret", EXCHANGE_CODE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(ticketMapper, never()).update(any(), any());
    }

    @Test
    void exchangeRejectsWrongExchangeCode() {
        when(ticketMapper.selectById(TICKET)).thenReturn(confirmedTicket());

        assertThatThrownBy(() -> service.exchange(TICKET, CLIENT_SECRET, "wrong-code"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void exchangeRejectedWhenBindingWasTerminatedAfterConfirm() {
        when(ticketMapper.selectById(TICKET)).thenReturn(confirmedTicket());
        when(bindingService.hasActiveGuardianship(GUARDIAN, PROTECTEE)).thenReturn(false);

        assertThatThrownBy(() -> service.exchange(TICKET, CLIENT_SECRET, EXCHANGE_CODE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void exchangeConsumesTicketAndReturnsProtecteeToken() {
        when(ticketMapper.selectById(TICKET)).thenReturn(confirmedTicket());
        when(bindingService.hasActiveGuardianship(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(ticketMapper.update(any(), any())).thenReturn(1);
        TokenResponse token = new TokenResponse("jwt", PROTECTEE, "USER", Set.of("PROTECTEE"), Set.of(), false);
        when(authService.userToken(any())).thenReturn(token);

        TokenResponse response = service.exchange(TICKET, CLIENT_SECRET, EXCHANGE_CODE);

        assertThat(response.accessToken()).isEqualTo("jwt");
        assertThat(response.principalId()).isEqualTo(PROTECTEE);
        verify(authService).userToken(any());
    }

    @Test
    void exchangeOnAlreadyConsumedTicketIsNotFound() {
        when(ticketMapper.selectById(TICKET)).thenReturn(ticket("CONSUMED", PROTECTEE, EXCHANGE_CODE));

        assertThatThrownBy(() -> service.exchange(TICKET, CLIENT_SECRET, EXCHANGE_CODE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exchangeLosesRaceWhenAtomicConsumeAffectsNoRow() {
        when(ticketMapper.selectById(TICKET)).thenReturn(confirmedTicket());
        when(bindingService.hasActiveGuardianship(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(ticketMapper.update(any(), any())).thenReturn(0); // 并发下被其他请求核销

        assertThatThrownBy(() -> service.exchange(TICKET, CLIENT_SECRET, EXCHANGE_CODE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(authService, never()).userToken(any());
    }

    private QrLoginTicket confirmedTicket() {
        QrLoginTicket row = ticket("CONFIRMED", PROTECTEE, EXCHANGE_CODE);
        row.setGuardianUid(GUARDIAN);
        row.setSecretHash(Hashing.sha256(CLIENT_SECRET));
        return row;
    }

    private QrLoginTicket ticket(String status, String protecteeUid, String exchangeCode) {
        QrLoginTicket row = new QrLoginTicket();
        row.setTicket(TICKET);
        row.setStatus(status);
        row.setProtecteeUid(protecteeUid);
        row.setExchangeCode(exchangeCode);
        row.setSecretHash(Hashing.sha256(CLIENT_SECRET));
        row.setCreatedAt(LocalDateTime.now());
        row.setExpiresAt(LocalDateTime.now().plusSeconds(90));
        row.setVersion(0L);
        return row;
    }

    private UserProfile profile(String displayName) {
        UserProfile profile = new UserProfile();
        profile.setDisplayName(displayName);
        return profile;
    }

    private UserAccount account(String id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus("ENABLED");
        account.setRoleCode("PROTECTEE");
        account.setLoginName(id);
        return account;
    }
}
