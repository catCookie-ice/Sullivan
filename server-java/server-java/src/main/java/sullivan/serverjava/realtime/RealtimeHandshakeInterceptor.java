package sullivan.serverjava.realtime;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.security.JwtService;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * WebSocket 握手鉴权：从 query 参数 {@code token} 或 {@code Sec-WebSocket-Protocol}
 * 子协议（{@code sullivan.v1.<jwt>}）解析 JWT，校验会话参与人。
 *
 * <p>仅当会话为 REQUESTED/ACTIVE 且当前用户是发起方或接收方时允许建立连接。
 * 成功后将 principal、会话参与人与 sessionId 放入会话 attributes 供 handler 使用。</p>
 */
@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {
    private static final String SUBPROTOCOL_PREFIX = "sullivan.v1.";

    private final JwtService jwtService;
    private final RealtimeSessionMapper sessionMapper;

    public RealtimeHandshakeInterceptor(JwtService jwtService, RealtimeSessionMapper sessionMapper) {
        this.jwtService = jwtService;
        this.sessionMapper = sessionMapper;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = queryToken(request);
        if (token == null) {
            token = subprotocolToken(request);
        }
        if (token == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        AuthPrincipal principal;
        try {
            principal = jwtService.parse(token);
        } catch (RuntimeException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String sessionId = queryParam(request, "sessionId");
        RealtimeSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null || !Set.of("REQUESTED", "ACTIVE").contains(session.getStatus())
                || (!Objects.equals(principal.id(), session.getInitiatorUid())
                && !Objects.equals(principal.id(), session.getTargetUid()))) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put("principal", principal);
        attributes.put("sessionId", session.getId());
        attributes.put("initiatorUid", session.getInitiatorUid());
        attributes.put("targetUid", session.getTargetUid());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String queryToken(ServerHttpRequest request) {
        return queryParam(request, "token");
    }

    private String queryParam(ServerHttpRequest request, String name) {
        var params = request.getURI().getQuery() == null ? null
                : parseQuery(request.getURI().getQuery());
        return params == null ? null : params.get(name);
    }

    private Map<String, String> parseQuery(String query) {
        return java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .filter(pair -> pair.length == 2 && !pair[1].isBlank())
                .collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair -> pair[1], (a, b) -> a));
    }

    private String subprotocolToken(ServerHttpRequest request) {
        String protocol = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(protocol.split(","))
                .map(String::trim)
                .filter(p -> p.startsWith(SUBPROTOCOL_PREFIX))
                .map(p -> p.substring(SUBPROTOCOL_PREFIX.length()))
                .findFirst()
                .orElse(null);
    }
}
