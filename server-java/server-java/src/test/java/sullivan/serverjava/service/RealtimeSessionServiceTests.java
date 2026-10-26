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
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionCreateRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSessionServiceTests {
    private static final String GUARDIAN = "guardian-1";
    private static final String PROTECTEE = "protectee-1";
    private static final String OTHER = "outsider-1";

    private RealtimeSessionMapper sessionMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private BindingService bindingService;
    private sullivan.serverjava.realtime.ConnectionPool connectionPool;
    private RealtimeSessionService service;

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RealtimeSession.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserProfile.class);
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(RealtimeSessionMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        bindingService = mock(BindingService.class);
        connectionPool = mock(sullivan.serverjava.realtime.ConnectionPool.class);
        service = new RealtimeSessionService(sessionMapper, userAccountMapper, userProfileMapper,
                bindingService, connectionPool);
        when(userAccountMapper.selectById(PROTECTEE)).thenReturn(account(PROTECTEE));
        when(userAccountMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(userProfileMapper.selectList(any())).thenReturn(List.of());
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

    // ------------------------------------------------------------------
    // 会话类型（00 §8.1 基线变更后的四条新增可达值）
    // ------------------------------------------------------------------

    @Test
    void voiceVideoAndCameraTypesAccepted() {
        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        for (String type : List.of("VOICE", "VIDEO", "CAMERA")) {
            service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, type));
        }

        ArgumentCaptor<RealtimeSession> captor = ArgumentCaptor.forClass(RealtimeSession.class);
        verify(sessionMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(RealtimeSession::getSessionType)
                .containsExactly("VOICE", "VIDEO", "CAMERA");
    }

    /**
     * 客户端不能自己发起「紧急会话」。
     *
     * <p>这一条是语义防线而不是权限防线：EMERGENCY 意味着「这条会话是报警触发的」，
     * 若客户端能自行设置，它就退化成「一个让呼叫看起来更急的标签」。
     */
    @Test
    void clientCannotCreateEmergencySession() {
        assertThatThrownBy(() -> service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, "EMERGENCY")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownSessionTypeRejectedAsBadRequest() {
        assertThatThrownBy(() -> service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, "LOCATION")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sessionTypeIsNormalisedToUpperCase() {
        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(true);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        service.create(GUARDIAN, new RealtimeSessionCreateRequest(PROTECTEE, "voice"));

        ArgumentCaptor<RealtimeSession> captor = ArgumentCaptor.forClass(RealtimeSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionType()).isEqualTo("VOICE");
    }

    /** 信令只在「会话 ACTIVE 且绑定仍生效」时放行；两条路径任一断了都不行。 */
    @Test
    void signalingRequiresBothActiveSessionAndActiveBinding() {
        RealtimeSession active = requestedSession();
        active.setStatus("ACTIVE");

        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(true);
        assertThat(service.signalingAllowed(active)).isTrue();

        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(false);
        assertThat(service.signalingAllowed(active)).isFalse();

        active.setStatus("ENDED");
        when(bindingService.hasActiveBinding(GUARDIAN, PROTECTEE)).thenReturn(true);
        assertThat(service.signalingAllowed(active)).isFalse();
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

    // ------------------------------------------------------------------
    // 状态推送（「对点点一次确认即可通话」的那一半）
    // ------------------------------------------------------------------

    /**
     * 接受会话必须把 {@code session.accepted} 推给**发起方**。
     *
     * <p>不推的话，发起方只能靠轮询列表发现「对方接了」，
     * 「点一次确认就能通话」这条需求在发起方那一侧就不成立。
     */
    @Test
    void acceptPushesAcceptedToInitiator() {
        RealtimeSession session = requestedSession();
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        service.accept(PROTECTEE, "rs1");

        ArgumentCaptor<sullivan.serverjava.realtime.RealtimeEnvelope> captor =
                ArgumentCaptor.forClass(sullivan.serverjava.realtime.RealtimeEnvelope.class);
        verify(connectionPool).send(eq("rs1"), eq(GUARDIAN), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("session.accepted");
        assertThat(captor.getValue().toUid()).isEqualTo(GUARDIAN);
    }

    /** 结束会话要通知**另一方**，而不是发给自己。 */
    @Test
    void endPushesEndedToTheOtherSideOnly() {
        RealtimeSession session = requestedSession();
        session.setStatus("ACTIVE");
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);

        service.end(GUARDIAN, "rs1");

        ArgumentCaptor<sullivan.serverjava.realtime.RealtimeEnvelope> captor =
                ArgumentCaptor.forClass(sullivan.serverjava.realtime.RealtimeEnvelope.class);
        verify(connectionPool).send(eq("rs1"), eq(PROTECTEE), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("session.ended");
    }

    /** 推送通道抛异常不能把「状态已经改了」这件事回滚掉。 */
    @Test
    void pushFailureDoesNotBreakAccept() {
        RealtimeSession session = requestedSession();
        when(sessionMapper.selectById("rs1")).thenReturn(session);
        when(sessionMapper.update(any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("socket gone"))
                .when(connectionPool).send(anyString(), anyString(), any());

        RealtimeSessionResponse result = service.accept(PROTECTEE, "rs1");

        assertThat(result.status()).isEqualTo("ACTIVE");
    }

}
