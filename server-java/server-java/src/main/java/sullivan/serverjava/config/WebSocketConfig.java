package sullivan.serverjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import sullivan.serverjava.realtime.RealtimeHandshakeInterceptor;
import sullivan.serverjava.realtime.RealtimeWebSocketHandler;

/**
 * 注册实时长连接端点 /ws/realtime。握手鉴权由 {@link RealtimeHandshakeInterceptor}
 * 完成（JWT + 会话参与人校验），Spring Security 仅放行 /ws/**。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler realtimeHandler;
    private final RealtimeHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(RealtimeWebSocketHandler realtimeHandler,
                           RealtimeHandshakeInterceptor handshakeInterceptor) {
        this.realtimeHandler = realtimeHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeHandler, "/ws/realtime")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
