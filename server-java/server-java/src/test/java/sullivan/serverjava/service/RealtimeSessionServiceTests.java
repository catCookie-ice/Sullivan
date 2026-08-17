package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionCreateRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSessionServiceTests {
    private static final String GUARDIAN = "guardian-1";
    private static final String PROTECTEE = "protectee-1";
    private static final String OTHER = "outsider-1";

    private RealtimeSessionMapper sessionMapper;
    private UserAccountMapper userAccountMapper;
    private BindingService bindingService;
    private RealtimeSessionService service;

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RealtimeSession.class);
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(RealtimeSessionMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        bindingService = mock(BindingService.class);
        service = new RealtimeSessionService(sessionMapper, userAccountMapper, bindingService);
        when(userAccountMapper.selectById(PROTECTEE)).thenReturn(account(PROTECTEE));
    }

    @Test
    void createWithoutActiveBindingRejected() {
        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(false);

        assertThatThrownBy(() -> service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, "BASIC")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createWithActiveBindingCreatesRequestedSession() {
        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        RealtimeSessionResponse result = service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, "BASIC"));

        ArgumentCaptor<RealtimeSession> captor = ArgumentCaptor.forClass(RealtimeSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REQUESTED");
        assertThat(captor.getValue().getInitiatorUid()).isEqualTo(GUARDIAN);
        assertThat(captor.getValue().getTargetUid()).isEqualTo(PROTECTEE);
        assertThat(result.status()).isEqualTo("REQUESTED");
    }

    @Test
    void onlyTargetCanAccept() {
        RealtimeSession session = requestedSession();
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        RealtimeSessionResponse result = service.accept(PROTECTEE, "rs1");
        assertThat(result.status()).isEqualTo("ACTIVE");

        assertThatThrownBy(() -> service.accept(GUARDIAN, "rs1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void onlyTargetCanReject() {
        RealtimeSession session = requestedSession();
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        RealtimeSessionResponse result = service.reject(PROTECTEE, "rs1");
        assertThat(result.status()).isEqualTo("REJECTED");
    }

    @Test
    void participantCanEndSession() {
        RealtimeSession session = requestedSession();
        session.setStatus("ACTIVE");
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        RealtimeSessionResponse result = service.end(GUARDIAN, "rs1");
        assertThat(result.status()).isEqualTo("ENDED");
    }

    @Test
    void outsiderCannotEndSession() {
        RealtimeSession session = requestedSession();
        session.setStatus("ACTIVE");
        when(sessionMapper.selectById("rs1")).thenReturn(session);

        assertThatThrownBy(() -> service.end(OTHER, "rs1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    private RealtimeSession requestedSession() {
        RealtimeSession session = new RealtimeSession();
        session.setId("rs1");
        session.setStatus("REQUESTED");
        session.setInitiatorUid(GUARDIAN);
        session.setTargetUid(PROTECTEE);
        session.setSessionType("BASIC");
        return session;
    }

    private UserAccount account(String id) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus("ENABLED");
        return account;
    }
}
