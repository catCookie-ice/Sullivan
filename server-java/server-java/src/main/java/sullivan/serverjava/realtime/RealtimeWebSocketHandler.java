package sullivan.serverjava.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.BindingService;
import sullivan.serverjava.service.RealtimeSessionService;

import java.util.Objects;

/**
 * 实时 WebSocket 处理器：JOIN 注册连接池、RELAY 中继消息、SIGNAL 中继 WebRTC 信令、
 * PING/PONG 心跳、LEAVE 注销。
 *
 * <p>中继授权：fromUid 必须等于令牌主体；双方必须是会话参与人；每次转发实时复查
 * 双方仍存在 ACTIVE 绑定（绑定解除后立即拒绝转发）。</p>
 *
 * <p><b>信令（{@code signal}）与消息（{@code relay}）为什么分开两个 type</b>：
 * relay 的 payload 是「一句话」，signal 的 payload 是 SDP 与 ICE 候选。后者在
 * ACTIVE 之前毫无意义 —— 会话还停在 REQUESTED 时交换候选只会让两端各建一条
 * 连不通的 PeerConnection。所以 signal 额外要求会话已 ACTIVE；
 * relay 保持原样（REQUESTED 时也能发，用来「先问一句方不方便」）。
 *
 * <p><b>服务端不解析 SDP。</b>它只检查结构（有 type、有 payload）然后原样转发。
 * 解析 SDP 意味着服务端要知道每种浏览器/手机的组合细节，而这份知识在每个
 * 通话版本里都会变；更重要的是，SDP 里带着候选 IP 与网络拓扑，落库或记日志
 * 等于把用户的网络环境写成了永久记录。
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /** 合法的信令子类型。其余值一律拒绝，避免变成「任意 JSON 透传通道」。 */
    private static final java.util.Set<String> SIGNAL_KINDS =
            java.util.Set.of("offer", "answer", "candidate", "hangup", "renegotiate");

    private final ConnectionPool connectionPool;
    private final BindingService bindingService;
    private final RealtimeSessionMapper sessionMapper;
    private final RealtimeSessionService sessionService;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(ConnectionPool connectionPool, BindingService bindingService,
                                    RealtimeSessionMapper sessionMapper, RealtimeSessionService sessionService,
                                    ObjectMapper objectMapper) {
        this.connectionPool = connectionPool;
        this.bindingService = bindingService;
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        AuthPrincipal principal = principal(session);
        String sessionId = attr(session, "sessionId");
        String initiatorUid = attr(session, "initiatorUid");
        String targetUid = attr(session, "targetUid");
        RealtimeChannel channel = new WebSocketRealtimeChannel(session.getId(), principal.id(), session);
        connectionPool.register(sessionId, principal.id(), channel);

        String otherUid = Objects.equals(principal.id(), initiatorUid) ? targetUid : initiatorUid;
        connectionPool.send(sessionId, otherUid,
                RealtimeEnvelope.control("session.joined", sessionId, principal.id(), otherUid, null));
        log.debug("realtime connected session={} uid={}", sessionId, principal.id());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AuthPrincipal principal = principal(session);
        String sessionId = attr(session, "sessionId");
        String initiatorUid = attr(session, "initiatorUid");
        String targetUid = attr(session, "targetUid");

        RealtimeEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), RealtimeEnvelope.class);
        } catch (Exception exception) {
            sendError(sessionId, principal.id(), "非法消息格式");
            return;
        }
        if (envelope.type() == null) {
            sendError(sessionId, principal.id(), "缺少消息类型");
            return;
        }
        switch (envelope.type()) {
            case "relay" -> relay(sessionId, principal.id(), initiatorUid, targetUid, envelope);
            case "signal" -> signal(sessionId, principal.id(), initiatorUid, targetUid, envelope);
            case "ping" -> connectionPool.send(sessionId, principal.id(),
                    RealtimeEnvelope.control("pong", sessionId, principal.id(), principal.id(), null));
            case "leave" -> connectionPool.unregister(sessionId, principal.id(),
                    new WebSocketRealtimeChannel(session.getId(), principal.id(), session));
            default -> sendError(sessionId, principal.id(), "不支持的消息类型: " + envelope.type());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AuthPrincipal principal = principal(session);
        String sessionId = attr(session, "sessionId");
        if (sessionId != null) {
            connectionPool.unregister(sessionId, principal.id(),
                    new WebSocketRealtimeChannel(session.getId(), principal.id(), session));
        }
        log.debug("realtime disconnected session={} uid={}", sessionId, principal.id());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("realtime transport error session={}: {}", attr(session, "sessionId"), exception.getMessage());
    }

    private void relay(String sessionId, String fromUid, String initiatorUid, String targetUid,
                       RealtimeEnvelope envelope) {
        String toUid = resolvePeer(sessionId, fromUid, initiatorUid, targetUid, envelope);
        if (toUid == null) {
            return;
        }
        // 每次转发实时复查双方仍存在 ACTIVE 绑定；绑定解除后立即拒绝。
        if (!bindingService.hasActiveBinding(fromUid, toUid)) {
            sendError(sessionId, fromUid, "双方不再存在生效绑定，消息未转发");
            return;
        }
        connectionPool.send(sessionId, toUid, envelope);
    }

    /**
     * WebRTC 信令中继。
     *
     * <p>比 relay 多两道检查：会话必须 ACTIVE，且信号子类型必须是已知的几种。
     * 前者是因为在 REQUESTED 阶段交换 SDP 没有意义；后者是为了不让这条通道
     * 退化成「任意 JSON 都能透传」——那样它就成了一个绕过业务校验的旁路。
     */
    private void signal(String sessionId, String fromUid, String initiatorUid, String targetUid,
                        RealtimeEnvelope envelope) {
        String toUid = resolvePeer(sessionId, fromUid, initiatorUid, targetUid, envelope);
        if (toUid == null) {
            return;
        }
        RealtimeSession session = sessionMapper.selectById(sessionId);
        if (!sessionService.signalingAllowed(session)) {
            // 会话未开始、已结束，或绑定已解除。三种情况共用一句提示：
            // 对端不需要知道具体是哪一种，而调用方本来也改变不了结果。
            sendError(sessionId, fromUid, "会话不在可通话状态，信令未转发");
            return;
        }
        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            sendError(sessionId, fromUid, "信令负载格式不正确");
            return;
        }
        String kind = payload.path("kind").asText("");
        if (!SIGNAL_KINDS.contains(kind)) {
            sendError(sessionId, fromUid, "不支持的信令类型: " + kind);
            return;
        }
        connectionPool.send(sessionId, toUid, envelope);
    }

    /**
     * 校验发送方身份与目标，返回应当转发给谁；不合法时已回错误帧并返回 {@code null}。
     */
    private String resolvePeer(String sessionId, String fromUid, String initiatorUid, String targetUid,
                               RealtimeEnvelope envelope) {
        boolean senderIsInitiator = Objects.equals(fromUid, initiatorUid);
        boolean senderIsTarget = Objects.equals(fromUid, targetUid);
        String toUid = senderIsInitiator ? targetUid : senderIsTarget ? initiatorUid : null;
        if (toUid == null || !Objects.equals(fromUid, envelope.fromUid())) {
            sendError(sessionId, fromUid, "中继方不是会话参与人");
            return null;
        }
        if (!Objects.equals(toUid, envelope.toUid())) {
            sendError(sessionId, fromUid, "目标不是会话参与人");
            return null;
        }
        return toUid;
    }

    private void sendError(String sessionId, String uid, String message) {
        connectionPool.send(sessionId, uid,
                RealtimeEnvelope.control("error", sessionId, uid, uid,
                        objectMapper.getNodeFactory().textNode(message)));
    }

    private AuthPrincipal principal(WebSocketSession session) {
        return (AuthPrincipal) session.getAttributes().get("principal");
    }

    private String attr(WebSocketSession session, String name) {
        return (String) session.getAttributes().get(name);
    }
}
