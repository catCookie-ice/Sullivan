package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.Notification;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.VoiceMessage;
import sullivan.serverjava.mapper.NotificationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.VoiceMessageMapper;
import sullivan.serverjava.service.media.MediaStore;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageCreateRequest;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageResponse;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 语音留言：投递、收件箱、标记已听、删除、取回音频。
 *
 * <p><b>留言不是通话的替代品，而是通话接不通时的兜底。</b>受护人按下求助，
 * 监护人可能正在开会、手机静音；这时能留一句话，比什么都留不下要好。
 * 所以留言的生命周期刻意长于会话：会话结束、对方从没接听，留言都还在。
 *
 * <p><b>投递要求双方存在 ACTIVE 绑定</b>，与报警、位置、会话一致。
 * 绑定解除后：不再接受新的留言，历史留言也不能再取回音频
 * （{@link #audio} 会重新校验绑定）—— 否则「解绑」就只是断了一条路，
 * 而不是收回权限。
 */
@Service
public class VoiceMessageService {
    private static final Logger log = LoggerFactory.getLogger(VoiceMessageService.class);

    /** 允许的音频容器。限定白名单而不是黑名单：未来要加格式就显式加一行。 */
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("audio/mp4", "audio/aac", "audio/3gpp", "audio/amr", "audio/ogg");

    private final VoiceMessageMapper voiceMessageMapper;
    private final NotificationMapper notificationMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final BindingService bindingService;
    private final MediaStore mediaStore;

    private final int maxDurationMs;
    private final long maxSizeBytes;
    private final int maxPerDay;

    public VoiceMessageService(VoiceMessageMapper voiceMessageMapper,
                               NotificationMapper notificationMapper,
                               UserAccountMapper userAccountMapper,
                               UserProfileMapper userProfileMapper,
                               BindingService bindingService,
                               MediaStore mediaStore,
                               @Value("${app.voice-message.max-duration-ms:60000}") int maxDurationMs,
                               @Value("${app.voice-message.max-size-bytes:1048576}") long maxSizeBytes,
                               @Value("${app.voice-message.max-per-day:50}") int maxPerDay) {
        this.voiceMessageMapper = voiceMessageMapper;
        this.notificationMapper = notificationMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.bindingService = bindingService;
        this.mediaStore = mediaStore;
        this.maxDurationMs = maxDurationMs;
        this.maxSizeBytes = maxSizeBytes;
        this.maxPerDay = maxPerDay;
    }

    /**
     * 投递一条语音留言。
     *
     * <p>顺序是「先校验、再存音频、最后落库」：音频先落地而落库失败会留下一个
     * 无人引用的对象（由后续清理脚本回收），但反过来「落库成功而音频没存上」
     * 会让用户看到一条点开就报错的留言 —— 两者里前者只是浪费存储，后者是坏体验。
     */
    @Transactional
    public VoiceMessageResponse create(String senderUid, VoiceMessageCreateRequest request,
                                       InputStream audio) {
        String receiverUid = request.receiverUid();
        if (receiverUid == null || receiverUid.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少接收方");
        }
        if (Objects.equals(receiverUid, senderUid)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能给自己留言");
        }
        UserAccount receiver = userAccountMapper.selectById(receiverUid);
        if (receiver == null || !"ENABLED".equals(receiver.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "接收方账号不存在或不可用");
        }
        if (!bindingService.hasActiveBinding(senderUid, receiverUid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "双方不存在生效绑定，不能留言");
        }
        if (request.durationMs() <= 0 || request.durationMs() > maxDurationMs) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "留言时长需在 1 毫秒到 " + maxDurationMs + " 毫秒之间");
        }
        if (request.sizeBytes() <= 0 || request.sizeBytes() > maxSizeBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "留言大小需在 1 字节到 " + maxSizeBytes + " 字节之间");
        }
        String contentType = request.contentType() == null ? "audio/mp4" : request.contentType().trim();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的音频格式: " + contentType);
        }
        enforceDailyLimit(senderUid);

        String id = IdGenerator.next();
        String objectKey = objectKeyFor(id, contentType);
        mediaStore.put(objectKey, audio, request.sizeBytes(), contentType);

        VoiceMessage message = new VoiceMessage();
        message.setId(id);
        message.setStatus("SENT");
        message.setSenderUid(senderUid);
        message.setReceiverUid(receiverUid);
        message.setSessionId(blankToNull(request.sessionId()));
        message.setObjectKey(objectKey);
        message.setDurationMs(request.durationMs());
        message.setSizeBytes(request.sizeBytes());
        message.setContentType(contentType);
        voiceMessageMapper.insert(message);

        notifyReceiver(message, senderUid);
        return toResponse(message, senderUid, Map.of(), Map.of());
    }

    /**
     * 我的留言箱：**我收到的** + **我发出的**，按时间倒序。
     *
     * <p>两半都返回是有意的：发出去的人需要知道对方听没听（`LISTENED`），
     * 否则「他到底收到没有」永远是个悬着的问题。用 {@code myRole} 区分，
     * 界面据此分组，不必猜。
     */
    public List<VoiceMessageResponse> inbox(String callerUid) {
        List<VoiceMessage> messages = new ArrayList<>();
        messages.addAll(voiceMessageMapper.selectList(Wrappers.<VoiceMessage>lambdaQuery()
                .eq(VoiceMessage::getReceiverUid, callerUid)
                .ne(VoiceMessage::getStatus, "DELETED")
                .orderByDesc(VoiceMessage::getCreatedAt)));
        messages.addAll(voiceMessageMapper.selectList(Wrappers.<VoiceMessage>lambdaQuery()
                .eq(VoiceMessage::getSenderUid, callerUid)
                .ne(VoiceMessage::getStatus, "DELETED")
                .orderByDesc(VoiceMessage::getCreatedAt)));
        if (messages.isEmpty()) {
            return List.of();
        }
        messages.sort((left, right) -> {
            if (left.getCreatedAt() == null || right.getCreatedAt() == null) {
                return 0;
            }
            return right.getCreatedAt().compareTo(left.getCreatedAt());
        });
        Map<String, UserAccount> accounts = accountsById(messages);
        Map<String, String> names = displayNamesById(messages);
        return messages.stream().map(message -> toResponse(message, callerUid, accounts, names)).toList();
    }

    /** 标记已听。只有接收方本人能标记；重复标记幂等（02 §9.6）。 */
    @Transactional
    public VoiceMessageResponse markListened(String callerUid, String messageId) {
        VoiceMessage message = load(messageId);
        if (!Objects.equals(callerUid, message.getReceiverUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有接收方可以标记已听");
        }
        if ("LISTENED".equals(message.getStatus())) {
            return toResponse(message, callerUid);
        }
        var update = Wrappers.<VoiceMessage>lambdaUpdate()
                .set(VoiceMessage::getStatus, "LISTENED")
                .set(VoiceMessage::getListenedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(VoiceMessage::getId, messageId)
                .ne(VoiceMessage::getStatus, "LISTENED");
        voiceMessageMapper.update(null, update);
        message.setStatus("LISTENED");
        message.setListenedAt(LocalDateTime.now());
        return toResponse(message, callerUid);
    }

    /** 删除留言（软删除）。投递双方任一方都可删除自己这一侧的记录。 */
    @Transactional
    public void delete(String callerUid, String messageId) {
        VoiceMessage message = load(messageId);
        if (!Objects.equals(callerUid, message.getSenderUid())
                && !Objects.equals(callerUid, message.getReceiverUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有投递双方可以删除留言");
        }
        voiceMessageMapper.update(null, Wrappers.<VoiceMessage>lambdaUpdate()
                .set(VoiceMessage::getStatus, "DELETED")
                .set(VoiceMessage::getDeletedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(VoiceMessage::getId, messageId));
        // 音频一并删掉：软删除的留言不会再被取回，留着对象只是占用空间，
        // 而且它仍是「一段真实存在的录音」，没有保留的理由。
        mediaStore.delete(message.getObjectKey());
    }

    /**
     * 取回音频。**每次都要重新校验绑定**，而不是信任留言创建时的那一次。
     *
     * <p>绑定解除后历史留言不再可取回音频：解绑应当收回的是**能力**，
     * 而不只是「不再新增」。这条与位置、会话的口径一致。
     */
    public AudioStream audio(String callerUid, String messageId) {
        VoiceMessage message = load(messageId);
        if (!Objects.equals(callerUid, message.getSenderUid())
                && !Objects.equals(callerUid, message.getReceiverUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有投递双方可以取回音频");
        }
        if ("DELETED".equals(message.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "留言已删除");
        }
        if (!bindingService.hasActiveBinding(message.getSenderUid(), message.getReceiverUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "双方绑定已解除，不能再取回该留言");
        }
        try {
            return new AudioStream(mediaStore.get(message.getObjectKey()),
                    message.getContentType(), message.getSizeBytes());
        } catch (MediaStore.MediaNotFoundException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "留言音频已不可用");
        }
    }

    /** 音频流与它的元信息；controller 据此设置响应头。 */
    public record AudioStream(InputStream stream, String contentType, Long sizeBytes) {
    }

    // ------------------------------------------------------------------

    private void enforceDailyLimit(String senderUid) {
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        Long today = voiceMessageMapper.selectCount(Wrappers.<VoiceMessage>lambdaQuery()
                .eq(VoiceMessage::getSenderUid, senderUid)
                .ge(VoiceMessage::getCreatedAt, since));
        if (today != null && today >= maxPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "24 小时内留言已达上限（" + maxPerDay + " 条）");
        }
    }

    private void notifyReceiver(VoiceMessage message, String senderUid) {
        Notification notification = new Notification();
        notification.setId(IdGenerator.next());
        notification.setStatus("UNREAD");
        notification.setReceiverPrincipalType("USER");
        notification.setReceiverPrincipalId(message.getReceiverUid());
        notification.setNotificationType("VOICE_MESSAGE");
        notification.setTitle("收到一条语音留言");
        notification.setBody(displayName(senderUid) + " 给你留了一条语音留言");
        // 通知里**只有这句提示**：不放音频、不放对象键、不放可下载地址。
        // 与推送口径一致（21 §2.4）—— 通知只送「有事发生」，不送内容本体。
        notification.setSourceType("VOICE_MESSAGE");
        notification.setSourceId(message.getId());
        notificationMapper.insert(notification);
    }

    private VoiceMessage load(String messageId) {
        VoiceMessage message = voiceMessageMapper.selectById(messageId);
        if (message == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "留言不存在");
        }
        return message;
    }

    private VoiceMessageResponse toResponse(VoiceMessage message, String callerUid) {
        return toResponse(message, callerUid, accountsById(List.of(message)),
                displayNamesById(List.of(message)));
    }

    private VoiceMessageResponse toResponse(VoiceMessage message, String callerUid,
                                            Map<String, UserAccount> accounts,
                                            Map<String, String> names) {
        UserAccount sender = accounts.get(message.getSenderUid());
        UserAccount receiver = accounts.get(message.getReceiverUid());
        String myRole = Objects.equals(callerUid, message.getSenderUid()) ? "SENDER" : "RECEIVER";
        return new VoiceMessageResponse(message.getId(), message.getStatus(),
                sender == null ? null : sender.getLoginName(), names.get(message.getSenderUid()),
                receiver == null ? null : receiver.getLoginName(), names.get(message.getReceiverUid()),
                myRole, message.getDurationMs(), message.getSizeBytes(), message.getContentType(),
                message.getCreatedAt(), message.getListenedAt());
    }

    private Map<String, UserAccount> accountsById(List<VoiceMessage> messages) {
        Set<String> ids = new java.util.HashSet<>();
        for (VoiceMessage message : messages) {
            ids.add(message.getSenderUid());
            ids.add(message.getReceiverUid());
        }
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UserAccount> result = new HashMap<>();
        userAccountMapper.selectBatchIds(ids).forEach(account -> result.put(account.getId(), account));
        return result;
    }

    private Map<String, String> displayNamesById(List<VoiceMessage> messages) {
        Set<String> ids = new java.util.HashSet<>();
        for (VoiceMessage message : messages) {
            ids.add(message.getSenderUid());
            ids.add(message.getReceiverUid());
        }
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery().in(UserProfile::getUserId, ids))
                .forEach(profile -> {
                    if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                        result.put(profile.getUserId(), profile.getDisplayName());
                    }
                });
        return result;
    }

    private String displayName(String uid) {
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, uid).last("LIMIT 1"));
        if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        UserAccount account = userAccountMapper.selectById(uid);
        return account == null || account.getLoginName() == null ? "对方" : account.getLoginName();
    }

    /**
     * 对象键：按日期分目录，扩展名跟着 MIME 走。
     *
     * <p>键里**不含任何用户标识**。对象键会出现在 MinIO 的目录列表与服务端日志里，
     * 把 uid 编进去等于让「谁给谁留言」这件事从存储层泄露出去。
     */
    private static String objectKeyFor(String id, String contentType) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        return "voice/" + stamp + "/" + id + extensionFor(contentType);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "audio/aac" -> ".aac";
            case "audio/3gpp" -> ".3gp";
            case "audio/amr" -> ".amr";
            case "audio/ogg" -> ".ogg";
            default -> ".m4a";
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
