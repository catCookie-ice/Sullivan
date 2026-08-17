package sullivan.serverjava.realtime;

import java.util.List;

/**
 * 实时连接池（C++ 重构预留缝）：按 {@code sessionId -> uid -> 通道集合} 组织在线连接。
 * 实现只需面向 {@link RealtimeChannel} 与 {@link RealtimeEnvelope}，不依赖 WebSocket 细节；
 * 未来可替换为基于 Redis 发布订阅或 gRPC 的远程网关实现（见 docs/final/16）。
 */
public interface ConnectionPool {

    void register(String sessionId, String uid, RealtimeChannel channel);

    void unregister(String sessionId, String uid, RealtimeChannel channel);

    /** 某会话中某账号当前在线的全部通道（多设备）。 */
    List<RealtimeChannel> find(String sessionId, String uid);

    /** 向会话中对端账号广播信封（序列化为 JSON 文本帧后发送）。 */
    void send(String sessionId, String toUid, RealtimeEnvelope envelope);

    /** 当前在线连接总数（监控用）。 */
    int activeConnections();
}
