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
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.BindingService;

import java.util.Objects;

/**
 * 实时 WebSocket 处理器：JOIN 注册连接池、RELAY 中继消息、PING/PONG 心跳、LEAVE 注销。
 *
 * <p>中继授权：fromUid 必须等于令牌主体；双方必须是会话参与人；每次转发实时复查
 * 双方仍存在 ACTIVE 绑定（绑定解除后立即拒绝转发）。</p>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final ConnectionPool connectionPool;
    private final BindingService bindingService;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(ConnectionPool connectionPool, BindingService bindingService,
                                    ObjectMapper objectMapper) {
        this.connectionPool = connectionPool;
        this.bindingService = bindingService;
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
        switch (envelope.type()) {
            case "relay" -> relay(sessionId, principal.id(), initiatorUid, targetUid, envelope);
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
        boolean senderIsInitiator = Objects.equals(fromUid, initiatorUid);
        boolean senderIsTarget = Objects.equals(fromUid, targetUid);
        String toUid = senderIsInitiator ? targetUid : senderIsTarget ? initiatorUid : null;
        if (toUid == null || !Objects.equals(fromUid, envelope.fromUid())) {
            sendError(sessionId, fromUid, "中继方不是会话参与人");
            return;
        }
        if (!Objects.equals(toUid, envelope.toUid())) {
            sendError(sessionId, fromUid, "目标不是会话参与人");
            return;
        }
        // 每次转发实时复查双方仍存在 ACTIVE 绑定；绑定解除后立即拒绝。
        if (!bindingService.hasActiveBinding(fromUid, toUid)) {
            sendError(sessionId, fromUid, "双方不再存在生效绑定，消息未转发");
            return;
        }
        connectionPool.send(sessionId, toUid, envelope);
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
