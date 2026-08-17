package sullivan.serverjava.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存连接池实现：{@code sessionId -> uid -> 通道集合}。
 *
 * <p>多设备同账号可并行在线；send 向同会话内同账号的全部通道广播。
 * 发送失败（对端已断）时自动注销该通道。仅限单实例；多实例需换远程网关实现。</p>
 */
@Component
public class InMemoryConnectionPool implements ConnectionPool {
    private static final Logger log = LoggerFactory.getLogger(InMemoryConnectionPool.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, ConcurrentMap<String, Set<RealtimeChannel>>> bySession =
            new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();

    @Override
    public void register(String sessionId, String uid, RealtimeChannel channel) {
        ConcurrentMap<String, Set<RealtimeChannel>> byUid = bySession.computeIfAbsent(
                sessionId, ignored -> new ConcurrentHashMap<>());
        Set<RealtimeChannel> channels = byUid.computeIfAbsent(uid, ignored ->
                ConcurrentHashMap.newKeySet());
        if (channels.add(channel)) {
            active.incrementAndGet();
        }
        log.debug("realtime register session={} uid={} socket={}", sessionId, uid, channel.id());
    }

    @Override
    public void unregister(String sessionId, String uid, RealtimeChannel channel) {
        Map<String, Set<RealtimeChannel>> byUid = bySession.get(sessionId);
        if (byUid == null) {
            return;
        }
        Set<RealtimeChannel> channels = byUid.get(uid);
        if (channels != null && channels.remove(channel)) {
            active.decrementAndGet();
        }
        if (channels != null && channels.isEmpty()) {
            byUid.remove(uid);
        }
        if (byUid.isEmpty()) {
            bySession.remove(sessionId);
        }
        log.debug("realtime unregister session={} uid={} socket={}", sessionId, uid, channel.id());
    }

    @Override
    public List<RealtimeChannel> find(String sessionId, String uid) {
        Map<String, Set<RealtimeChannel>> byUid = bySession.get(sessionId);
        if (byUid == null) {
            return List.of();
        }
        Set<RealtimeChannel> channels = byUid.get(uid);
        return channels == null ? List.of() : new ArrayList<>(channels);
    }

    @Override
    public void send(String sessionId, String toUid, RealtimeEnvelope envelope) {
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (IOException exception) {
            log.warn("realtime serialize envelope failed: {}", exception.getMessage());
            return;
        }
        for (RealtimeChannel channel : find(sessionId, toUid)) {
            try {
                if (channel.isOpen()) {
                    channel.send(json);
                } else {
                    unregister(sessionId, toUid, channel);
                }
            } catch (IOException exception) {
                log.debug("realtime send failed, drop channel {}: {}", channel.id(), exception.getMessage());
                unregister(sessionId, toUid, channel);
            }
        }
    }

    @Override
    public int activeConnections() {
        return active.get();
    }
}
