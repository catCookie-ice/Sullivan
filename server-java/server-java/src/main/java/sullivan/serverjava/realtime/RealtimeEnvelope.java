package sullivan.serverjava.realtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 实时会话消息信封（JSON 文本帧）。该协议与 {@link ConnectionPool} 接口一起构成
 * C++ 重构的预留缝：C++ 网关可承接同一信封协议，Java 侧仅依赖 ConnectionPool 接口。
 *
 * <p>type 取值：session.request/accept/reject/end/joined（控制）、relay（双方消息中继）、
 * ping/pong（心跳）、error（服务端错误）。语音/视频信令与摄像头帧元数据走 future
 * 的 signal 类型（payload 不透传原始帧，见 docs/final/16 N-03）。</p>
 */
public record RealtimeEnvelope(
        String type,
        String sessionId,
        String fromUid,
        String toUid,
        String messageId,
        long ts,
        JsonNode payload) {

    public static RealtimeEnvelope control(String type, String sessionId, String fromUid, String toUid,
                                           JsonNode payload) {
        return new RealtimeEnvelope(type, sessionId, fromUid, toUid, null, System.currentTimeMillis(), payload);
    }
}
