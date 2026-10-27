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
import sullivan.serverjava.domain.VoiceMessage;
import sullivan.serverjava.mapper.NotificationMapper;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.VoiceMessageMapper;
import sullivan.serverjava.service.media.MediaStore;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageCreateRequest;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceMessageServiceTests {
    private static final String SENDER = "protectee-1";
    private static final String RECEIVER = "guardian-1";
    private static final String OUTSIDER = "outsider-1";

    private VoiceMessageMapper voiceMessageMapper;
    private NotificationMapper notificationMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private RealtimeSessionMapper realtimeSessionMapper;
    private BindingService bindingService;
    private RecordingMediaStore mediaStore;
    private VoiceMessageService service;

    /** 记录写入/删除的对象键，同时让 get 返回一段可读内容。 */
    private static final class RecordingMediaStore implements MediaStore {
        String lastPutKey;
        String lastPutContentType;
        long lastPutSize;
        String deletedKey;
        boolean throwNotFound;

        @Override
        public void put(String objectKey, InputStream stream, long size, String contentType) {
            lastPutKey = objectKey;
            lastPutSize = size;
            lastPutContentType = contentType;
        }

        @Override
        public InputStream get(String objectKey) {
            if (throwNotFound) {
                throw new MediaNotFoundException("不存在: " + objectKey);
            }
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        }

        @Override
        public void delete(String objectKey) {
            deletedKey = objectKey;
        }
    }

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                VoiceMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserProfile.class);
    }

    @BeforeEach
    void setUp() {
        voiceMessageMapper = mock(VoiceMessageMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        realtimeSessionMapper = mock(RealtimeSessionMapper.class);
        bindingService = mock(BindingService.class);
        mediaStore = new RecordingMediaStore();
        service = new VoiceMessageService(voiceMessageMapper, notificationMapper, userAccountMapper,
                userProfileMapper, realtimeSessionMapper, bindingService, mediaStore, 60_000, 1_048_576L, 50);

        when(userAccountMapper.selectById(RECEIVER)).thenReturn(account(RECEIVER, "guardian_li"));
        when(userAccountMapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(userProfileMapper.selectList(any())).thenReturn(List.of());
        when(voiceMessageMapper.selectCount(any())).thenReturn(0L);
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    @Test
    void createStoresAudioAndMetadata() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        VoiceMessageResponse result = service.create(SENDER, request(RECEIVER, 12_000, 96_000L, "audio/mp4"),
                audio());

        ArgumentCaptor<VoiceMessage> captor = ArgumentCaptor.forClass(VoiceMessage.class);
        verify(voiceMessageMapper).insert(captor.capture());
        VoiceMessage saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("SENT");
        assertThat(saved.getSenderUid()).isEqualTo(SENDER);
        assertThat(saved.getReceiverUid()).isEqualTo(RECEIVER);
        assertThat(saved.getDurationMs()).isEqualTo(12_000);
        assertThat(saved.getObjectKey()).isEqualTo(mediaStore.lastPutKey);
        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.myRole()).isEqualTo("SENDER");
    }

    @Test
    void createRejectsBogusSourceSession() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);
        // mock 默认 selectById 返回 null：不存在的会话必须在落库前被拦下（404），
        // 而不是撞上 fk_voice_message_session 变成 500。
        assertThatThrownBy(() -> service.create(SENDER,
                requestWithSession(RECEIVER, "no-such-session", 12_000, 96_000L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(voiceMessageMapper, never()).insert(any(VoiceMessage.class));
    }

    @Test
    void createAcceptsSessionBetweenSamePair() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);
        when(realtimeSessionMapper.selectById("session-1")).thenReturn(session("session-1"));

        VoiceMessageResponse result = service.create(SENDER,
                requestWithSession(RECEIVER, "session-1", 12_000, 96_000L, "audio/mp4"), audio());

        ArgumentCaptor<VoiceMessage> captor = ArgumentCaptor.forClass(VoiceMessage.class);
        verify(voiceMessageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(result.status()).isEqualTo("SENT");
    }

    /**
     * 投递响应必须带时间。
     *
     * <p>{@code createdAt} 由数据库填，insert 之后内存对象里还是 null。
     * 早先这里直接把内存对象拿去拼响应，客户端收到 {@code createdAt=null}，
     * 表现为「刚发的留言没有时间」，而列表接口（走 selectList）却有时间 ——
     * 同一个字段在两个接口里表现不一致。单元测试当时没抓到，因为它只断言 status。
     */
    @Test
    void createResponseCarriesPersistedTimestamp() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);
        VoiceMessage persisted = message("vm-new", "SENT", SENDER, RECEIVER);
        persisted.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 26, 18, 43, 35));
        when(voiceMessageMapper.selectById(any())).thenReturn(persisted);

        VoiceMessageResponse result = service.create(SENDER,
                request(RECEIVER, 1_000, 100L, "audio/mp4"), audio());

        assertThat(result.createdAt()).isEqualTo(persisted.getCreatedAt());
    }

    /** 对象键里不能出现发送方或接收方的 uid：它会出现在存储日志与目录列表里。 */
    @Test
    void objectKeyDoesNotLeakParticipantIds() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        service.create(SENDER, request(RECEIVER, 1_000, 100L, "audio/mp4"), audio());

        assertThat(mediaStore.lastPutKey).doesNotContain(SENDER).doesNotContain(RECEIVER);
        assertThat(mediaStore.lastPutKey).startsWith("voice/");
    }

    @Test
    void createRequiresActiveBinding() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(false);

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 1_000, 100L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        // 校验没过就不该写对象存储：否则每次越权尝试都会在存储里留下一段没人引用的音频。
        assertThat(mediaStore.lastPutKey).isNull();
    }

    @Test
    void createRejectsSelfMessaging() {
        assertThatThrownBy(() -> service.create(SENDER,
                request(SENDER, 1_000, 100L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsOverlongDuration() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 60_001, 100L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsOversizedAudio() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 1_000, 1_048_577L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsUnsupportedContentType() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 1_000, 100L, "audio/x-wav"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createRejectsWhenDailyQuotaExhausted() {
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);
        when(voiceMessageMapper.selectCount(any())).thenReturn(50L);

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 1_000, 100L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void createRejectsDisabledReceiver() {
        when(userAccountMapper.selectById(RECEIVER)).thenReturn(account(RECEIVER, "guardian_li", "DISABLED"));

        assertThatThrownBy(() -> service.create(SENDER,
                request(RECEIVER, 1_000, 100L, "audio/mp4"), audio()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 收件箱 / 已听 / 删除 / 取回
    // ------------------------------------------------------------------

    @Test
    void inboxReturnsReceivedAndSentWithRoles() {
        VoiceMessage received = incoming("vm1");
        VoiceMessage sent = outgoing("vm2");
        when(voiceMessageMapper.selectList(any())).thenReturn(List.of(received), List.of(sent));

        List<VoiceMessageResponse> inbox = service.inbox(SENDER);

        assertThat(inbox).hasSize(2);
        assertThat(inbox).extracting(VoiceMessageResponse::myRole)
                .containsExactlyInAnyOrder("RECEIVER", "SENDER");
    }

    @Test
    void inboxEmptyWhenNoMessages() {
        when(voiceMessageMapper.selectList(any())).thenReturn(List.of(), List.of());

        assertThat(service.inbox(SENDER)).isEmpty();
    }

    @Test
    void onlyReceiverCanMarkListened() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));

        assertThatThrownBy(() -> service.markListened(SENDER, "vm1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        VoiceMessageResponse result = service.markListened(RECEIVER, "vm1");
        assertThat(result.status()).isEqualTo("LISTENED");
    }

    @Test
    void markListenedIsIdempotent() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(message("vm1", "LISTENED", SENDER, RECEIVER));

        VoiceMessageResponse result = service.markListened(RECEIVER, "vm1");

        assertThat(result.status()).isEqualTo("LISTENED");
        // 已经是 LISTENED 就不再发 UPDATE：重复标记不该刷新 listened_at。
        verify(voiceMessageMapper, never()).update(any(), any());
    }

    @Test
    void deleteRemovesAudioObjectToo() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));

        service.delete(RECEIVER, "vm1");

        assertThat(mediaStore.deletedKey).isEqualTo("voice/2026/10/26/vm1.m4a");
    }

    @Test
    void outsiderCannotDeleteOrFetchAudio() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));

        assertThatThrownBy(() -> service.delete(OUTSIDER, "vm1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> service.audio(OUTSIDER, "vm1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * 绑定解除后历史留言不再可取回音频。
     *
     * <p>解绑要收回的是**能力**，不只是「不再新增」。否则「解除绑定」只断了一条路，
     * 而不是收回权限 —— 对方仍能听过去留下的全部录音。
     */
    @Test
    void audioBlockedAfterBindingRemoved() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(false);

        assertThatThrownBy(() -> service.audio(RECEIVER, "vm1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void audioReturnsStreamWithContentType() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);

        var audio = service.audio(RECEIVER, "vm1");

        assertThat(audio.contentType()).isEqualTo("audio/mp4");
        assertThat(audio.sizeBytes()).isEqualTo(96_000L);
        assertThat(audio.stream()).isNotNull();
    }

    @Test
    void audioMissingObjectBecomesNotFound() {
        when(voiceMessageMapper.selectById("vm1")).thenReturn(outgoing("vm1"));
        when(bindingService.hasActiveBinding(SENDER, RECEIVER)).thenReturn(true);
        mediaStore.throwNotFound = true;

        assertThatThrownBy(() -> service.audio(RECEIVER, "vm1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingMessageIsNotFound() {
        when(voiceMessageMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.markListened(RECEIVER, "nope"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------

    private VoiceMessageCreateRequest request(String receiver, int durationMs, long sizeBytes,
                                              String contentType) {
        return new VoiceMessageCreateRequest(receiver, null, durationMs, sizeBytes, contentType);
    }

    private VoiceMessageCreateRequest requestWithSession(String receiver, String sessionId,
                                                         int durationMs, long sizeBytes, String contentType) {
        return new VoiceMessageCreateRequest(receiver, sessionId, durationMs, sizeBytes, contentType);
    }

    /** 来源会话：双方正是留言双方（方向不限）。 */
    private RealtimeSession session(String id) {
        RealtimeSession session = new RealtimeSession();
        session.setId(id);
        session.setInitiatorUid(RECEIVER);
        session.setTargetUid(SENDER);
        session.setStatus("ENDED");
        return session;
    }

    private InputStream audio() {
        return new ByteArrayInputStream(new byte[]{9, 9, 9});
    }

    /** 我（SENDER）收到的留言：对方发给我。 */
    private VoiceMessage incoming(String id) {
        return message(id, "SENT", RECEIVER, SENDER);
    }

    /** 我（SENDER）发出的留言：我发给对方。 */
    private VoiceMessage outgoing(String id) {
        return message(id, "SENT", SENDER, RECEIVER);
    }

    private VoiceMessage message(String id, String status, String sender, String receiver) {
        VoiceMessage message = new VoiceMessage();
        message.setId(id);
        message.setStatus(status);
        message.setSenderUid(sender);
        message.setReceiverUid(receiver);
        message.setObjectKey("voice/2026/10/26/" + id + ".m4a");
        message.setDurationMs(12_000);
        message.setSizeBytes(96_000L);
        message.setContentType("audio/mp4");
        return message;
    }

    private UserAccount account(String id, String loginName) {
        return account(id, loginName, "ENABLED");
    }

    private UserAccount account(String id, String loginName, String status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus(status);
        account.setLoginName(loginName);
        account.setRoleCode("GUARDIAN");
        return account;
    }
}
