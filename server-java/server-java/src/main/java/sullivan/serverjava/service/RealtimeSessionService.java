package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionCreateRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 实时会话生命周期：发起/接受/拒绝/结束。创建与消息中继都要求双方存在 ACTIVE 绑定
 * （中继校验见 {@link sullivan.serverjava.realtime.RealtimeWebSocketHandler}）。
 */
@Service
public class RealtimeSessionService {
    private final RealtimeSessionMapper sessionMapper;
    private final UserAccountMapper userAccountMapper;
    private final BindingService bindingService;

    public RealtimeSessionService(RealtimeSessionMapper sessionMapper, UserAccountMapper userAccountMapper,
                                  BindingService bindingService) {
        this.sessionMapper = sessionMapper;
        this.userAccountMapper = userAccountMapper;
        this.bindingService = bindingService;
    }

    /** 发起实时会话：双方须存在 ACTIVE 绑定；同一对账号只允许一条 REQUESTED/ACTIVE 会话。 */
    @Transactional
    public RealtimeSessionResponse create(String callerId, RealtimeSessionCreateRequest request) {
        if (!"BASIC".equals(request.sessionType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "会话类型仅支持 BASIC");
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
        session.setSessionType(request.sessionType());
        sessionMapper.insert(session);
        return toResponse(session);
    }

    /** 我的会话列表（发起或接收）。 */
    public List<RealtimeSessionResponse> mySessions(String callerId) {
        return sessionMapper.selectList(Wrappers.<RealtimeSession>lambdaQuery()
                        .and(w -> w.eq(RealtimeSession::getInitiatorUid, callerId)
                                .or().eq(RealtimeSession::getTargetUid, callerId))
                        .orderByDesc(RealtimeSession::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /** 接收方接受会话。 */
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
        return toResponse(session);
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

    private RealtimeSessionResponse toResponse(RealtimeSession session) {
        return new RealtimeSessionResponse(session.getId(), session.getStatus(), session.getInitiatorUid(),
                session.getTargetUid(), session.getSessionType(), session.getCreatedAt());
    }
}
