package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.realtime.ConnectionPool;
import sullivan.serverjava.realtime.RealtimeEnvelope;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionCreateRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionResponse;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 实时会话生命周期：发起/接受/拒绝/结束。创建与消息中继都要求双方存在 ACTIVE 绑定
 * （中继校验见 {@link sullivan.serverjava.realtime.RealtimeWebSocketHandler}）。
 *
 * <p><b>会话类型</b>（02 §8.2）：{@code BASIC} 纯消息、{@code VOICE} 语音、
 * {@code VIDEO} 视频、{@code CAMERA} 监护人看受护人摄像头（有语音）、
 * {@code EMERGENCY} 紧急视频会话。媒体流走 WebRTC，本服务只管会话状态与信令授权。
 *
 * <p><b>{@code EMERGENCY} 只能由服务端报警派发创建</b>（{@link RealtimeAlertDispatcher}）。
 * 客户端若能自行把类型设成 EMERGENCY，就绕过了「紧急」二字的语义 ——
 * 它本应意味着「这条会话是报警触发的」，而不是「我想让它看起来更急」。
 */
@Service
public class RealtimeSessionService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionService.class);

    /** 客户端可自行发起的类型。EMERGENCY 不在此列，理由见类注释。 */
    private static final Set<String> CLIENT_TYPES = Set.of("BASIC", "VOICE", "VIDEO", "CAMERA");

    private final RealtimeSessionMapper sessionMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final BindingService bindingService;
    private final ConnectionPool connectionPool;

    public RealtimeSessionService(RealtimeSessionMapper sessionMapper, UserAccountMapper userAccountMapper,
                                  UserProfileMapper userProfileMapper, BindingService bindingService,
                                  ConnectionPool connectionPool) {
        this.sessionMapper = sessionMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.bindingService = bindingService;
        this.connectionPool = connectionPool;
    }

    /** 发起实时会话：双方须存在 ACTIVE 绑定；同一对账号只允许一条 REQUESTED/ACTIVE 会话。 */
    @Transactional
    public RealtimeSessionResponse create(String callerId, RealtimeSessionCreateRequest request) {
        String type = request.sessionType() == null ? "BASIC" : request.sessionType().trim().toUpperCase();
        if ("EMERGENCY".equals(type)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "紧急会话只能由报警自动发起");
        }
        if (!CLIENT_TYPES.contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "会话类型仅支持 BASIC / VOICE / VIDEO / CAMERA");
        }
        UserAccount target = userAccountMapper.selectById(request.targetUid());
        if (target == null || !"ENABLED".equals(target.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "目标账号不存在或不可用");
        }
        if (!bindingService.hasActiveBinding(callerId, request.targetUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "双方不存在生效绑定，不能建立实时会话");
        }
        Long duplicate = sessionMapper.selectCount(Wrappers.<RealtimeSession>lambdaQuery()
                .and(w -> w.and(x -> x.eq(RealtimeSession::getInitiatorUid, callerId)
                                .eq(RealtimeSession::getTargetUid, request.targetUid()))
                        .or().and(x -> x.eq(RealtimeSession::getInitiatorUid, request.targetUid())
                                .eq(RealtimeSession::getTargetUid, callerId)))
                .in(RealtimeSession::getStatus, "REQUESTED", "ACTIVE"));
        if (duplicate > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "与目标已存在进行中或待接受的会话");
        }
        RealtimeSession session = new RealtimeSession();
        session.setId(IdGenerator.next());
        session.setStatus("REQUESTED");
        session.setInitiatorUid(callerId);
        session.setTargetUid(request.targetUid());
        session.setSessionType(type);
        sessionMapper.insert(session);
        // 重新读一次：createdAt 由数据库默认值填，insert 之后内存对象里还是 null。
        // 不重读的话，创建响应的 createdAt 是 null，而列表接口有值 ——
        // 客户端会看到「刚发起的会话没有时间」，同一个字段两个接口两种表现。
        RealtimeSession stored = sessionMapper.selectById(session.getId());
        return toResponse(stored == null ? session : stored);
    }

    /** 我的会话列表（发起或接收）。 */
    public List<RealtimeSessionResponse> mySessions(String callerId) {
        List<RealtimeSession> sessions = sessionMapper.selectList(Wrappers.<RealtimeSession>lambdaQuery()
                .and(w -> w.eq(RealtimeSession::getInitiatorUid, callerId)
                        .or().eq(RealtimeSession::getTargetUid, callerId))
                .orderByDesc(RealtimeSession::getCreatedAt));
        // 一次查出全部对端名字，而不是每行查两次。会话列表本来就只有个位数，
        // 但 N+1 的写法会在「某人绑了十几个监护人」时变成几十次查询。
        Map<String, UserAccount> accounts = accountsById(sessions);
        Map<String, String> names = displayNamesById(sessions);
        return sessions.stream().map(session -> toResponse(session, accounts, names)).toList();
    }

    /**
     * 接收方接受会话。
     *
     * <p><b>一次点击即进入会话</b>（00 §8.1 的简化）：接受后状态变 ACTIVE，
     * 双方各自连上长连接就能开始通话，发起方不需要再点第二次，
     * 也不存在「已接受但还没连上」的中间状态。
     */
    @Transactional
    public RealtimeSessionResponse accept(String callerId, String sessionId) {
        RealtimeSession session = load(sessionId);
        if (!Objects.equals(callerId, session.getTargetUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有接收方可以接受会话");
        }
        int updated = conditionalUpdate(session, "ACTIVE", null);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "会话已处理");
        }
        session.setStatus("ACTIVE");
        session.setAcceptedAt(LocalDateTime.now());
        // 主动推给发起方。不推的话，发起方只能靠轮询列表才知道对方已经接了 ——
        // 而「对方点一次确认就能通话」这条需求，正卡在这一步上。
        push(session, "session.accepted", session.getTargetUid());
        return toResponse(session);
    }

    /** 接收方拒绝会话。 */
    @Transactional
    public RealtimeSessionResponse reject(String callerId, String sessionId) {
        RealtimeSession session = load(sessionId);
        if (!Objects.equals(callerId, session.getTargetUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有接收方可以拒绝会话");
        }
        int updated = conditionalUpdate(session, "REJECTED", null);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "会话已处理");
        }
        session.setStatus("REJECTED");
        push(session, "session.rejected", session.getTargetUid());
        return toResponse(session);
    }

    /** 任一方结束进行中（或待接受）的会话。 */
    @Transactional
    public RealtimeSessionResponse end(String callerId, String sessionId) {
        RealtimeSession session = load(sessionId);
        if (!Objects.equals(callerId, session.getInitiatorUid())
                && !Objects.equals(callerId, session.getTargetUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有会话参与人可以结束会话");
        }
        int updated = conditionalUpdate(session, "ENDED", callerId);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "会话已结束");
        }
        session.setStatus("ENDED");
        session.setEndedAt(LocalDateTime.now());
        session.setEndedByUid(callerId);
        push(session, "session.ended", callerId);
        return toResponse(session);
    }

    /**
     * 把会话状态变化推给另一方。
     *
     * <p><b>为什么必须推</b>：接受方点一次「接听」就该直接进会话，
     * 发起方不再需要任何动作。不推的话发起方只能靠轮询列表
     * 发现「对方已经接了」—— 那不是一次确认就能通话，而是
     * 「一次确认 + 对方偶然刷新到」。
     *
     * <p>只发给另一方（{@code actorUid} 是动作发起者，他自己已经知道结果）。
     * 对方没连长连接时 {@link ConnectionPool#send} 什么都不做，
     * 这是正确的降级：推送是加快，不是唯一通道。
     *
     * <p><b>它不能把调用方的事务搞崩</b>：调用点都在 {@code @Transactional} 方法里，
     * 而状态已经落库。推送只是尽力而为，序列化或写 socket 出错都不该让
     * 「会话已经结束」这件事回滚 —— 那会让状态与用户看到的结果对不上。
     */
    private void push(RealtimeSession session, String type, String actorUid) {
        String peerUid = Objects.equals(actorUid, session.getInitiatorUid())
                ? session.getTargetUid() : session.getInitiatorUid();
        if (peerUid == null) {
            return;
        }
        try {
            connectionPool.send(session.getId(), peerUid,
                    RealtimeEnvelope.control(type, session.getId(), actorUid, peerUid, null));
        } catch (RuntimeException exception) {
            log.warn("realtime push {} failed for session={}: {}", type, session.getId(),
                    exception.getMessage());
        }
    }

    /**
     * 会话是否为「可传信令」的状态：ACTIVE 且双方仍有 ACTIVE 绑定。
     *
     * <p>信令中继每次转发都调用它。会话结束与绑定解除是**两条独立的失效路径**，
     * 只判其中一个都会留下「绑定已经解除、通话还在继续」的窗口。
     */
    public boolean signalingAllowed(RealtimeSession session) {
        if (session == null || !"ACTIVE".equals(session.getStatus())) {
            return false;
        }
        return bindingService.hasActiveBinding(session.getInitiatorUid(), session.getTargetUid());
    }

    private int conditionalUpdate(RealtimeSession session, String status, String endedByUid) {
        var update = Wrappers.<RealtimeSession>lambdaUpdate()
                .set(RealtimeSession::getStatus, status)
                .setSql("version = version + 1")
                .eq(RealtimeSession::getId, session.getId())
                .eq(RealtimeSession::getStatus, session.getStatus());
        if ("ACTIVE".equals(status)) {
            update.set(RealtimeSession::getAcceptedAt, LocalDateTime.now());
        } else if ("ENDED".equals(status)) {
            update.set(RealtimeSession::getEndedAt, LocalDateTime.now());
            update.set(RealtimeSession::getEndedByUid, endedByUid);
        }
        return sessionMapper.update(null, update);
    }

    private RealtimeSession load(String sessionId) {
        RealtimeSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private Map<String, UserAccount> accountsById(List<RealtimeSession> sessions) {
        Set<String> ids = new java.util.HashSet<>();
        for (RealtimeSession session : sessions) {
            ids.add(session.getInitiatorUid());
            ids.add(session.getTargetUid());
        }
        ids.remove(null);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UserAccount> result = new HashMap<>();
        userAccountMapper.selectBatchIds(ids).forEach(account -> result.put(account.getId(), account));
        return result;
    }

    private Map<String, String> displayNamesById(List<RealtimeSession> sessions) {
        Set<String> ids = new java.util.HashSet<>();
        for (RealtimeSession session : sessions) {
            ids.add(session.getInitiatorUid());
            ids.add(session.getTargetUid());
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

    private RealtimeSessionResponse toResponse(RealtimeSession session) {
        Map<String, UserAccount> accounts = accountsById(List.of(session));
        return toResponse(session, accounts, displayNamesById(List.of(session)));
    }

    private RealtimeSessionResponse toResponse(RealtimeSession session, Map<String, UserAccount> accounts,
                                               Map<String, String> names) {
        UserAccount initiator = accounts.get(session.getInitiatorUid());
        UserAccount target = accounts.get(session.getTargetUid());
        return new RealtimeSessionResponse(session.getId(), session.getStatus(), session.getInitiatorUid(),
                session.getTargetUid(), session.getSessionType(), session.getCreatedAt(),
                initiator == null ? null : initiator.getLoginName(),
                target == null ? null : target.getLoginName(),
                names.get(session.getInitiatorUid()), names.get(session.getTargetUid()));
    }
}
