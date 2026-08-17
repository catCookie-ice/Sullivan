package sullivan.serverjava.realtime;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

/**
 * 基于 Spring WebSocketSession 的通道实现，适配 {@link RealtimeChannel} 抽象。
 */
public record WebSocketRealtimeChannel(String id, String principalId, WebSocketSession socket)
        implements RealtimeChannel {

    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    @Override
    public void send(String text) throws IOException {
        synchronized (socket) {
            socket.sendMessage(new TextMessage(text));
        }
    }
}
