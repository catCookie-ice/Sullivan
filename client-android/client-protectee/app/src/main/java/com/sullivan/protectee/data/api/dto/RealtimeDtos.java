package com.sullivan.protectee.data.api.dto;

import com.google.gson.JsonElement;

/**
 * 实时会话 DTO，字段名与服务端 {@code web/RealtimeDtos.java} 与
 * {@code realtime/RealtimeEnvelope.java} 逐字对应。
 */
public final class RealtimeDtos {

    private RealtimeDtos() {
    }

    /** {@code POST /api/v1/realtime/sessions} 请求体：发起会话。 */
    public static final class SessionCreateRequest {
        /** 目标账号 ID（须与当前用户存在 ACTIVE 绑定，服务端会校验）。 */
        public String targetUid;
        /** 目前只有 BASIC 一种（基础消息中继）。 */
        public String sessionType = "BASIC";

        public SessionCreateRequest(String targetUid) {
            this.targetUid = targetUid;
        }
    }

    /**
     * 会话。{@code POST} / {@code GET} / accept / reject / end 共用。
     *
     * <p>状态取值：REQUESTED（待接受）/ ACTIVE（进行中）/ REJECTED（已拒绝）/
     * ENDED（已结束）。握手时服务端只放行 REQUESTED 与 ACTIVE。
     */
    public static final class SessionResponse {
        public String id;
        public String status;
        public String initiatorUid;
        public String targetUid;
        public String sessionType;
        public String createdAt;
    }

    /**
     * WebSocket 文本帧的信封，对应服务端 {@code realtime/RealtimeEnvelope}。
     *
     * <p>发送时只需要填 {@code type}（"relay"）、{@code sessionId}、{@code fromUid}、
     * {@code toUid} 与 {@code payload} 四项 —— 服务端会逐个校验
     * fromUid 必须等于令牌主体、toUid 必须是会话另一方，对不上就回一条
     * {@code type="error"} 的错误帧。{@code messageId} 与 {@code ts} 由服务端转发时
     * 原样带过，客户端不生成也不依赖。
     *
     * <p><b>{@code payload} 是 {@link JsonElement} 而不是 String</b>：
     * 中继消息的内容由客户端自己约定（当前发的是
     * {@code {"text": "..."}}），服务端只做透传不解析。
     * 用 JsonElement 才能原样转发，用 String 会被 Gson 序列化成带引号的字符串。
     */
    public static final class Envelope {
        /** relay / ping / pong / leave / session.joined / error。 */
        public String type;
        public String sessionId;
        public String fromUid;
        public String toUid;
        public String messageId;
        public long ts;
        public JsonElement payload;

        public static Envelope relay(String sessionId, String fromUid, String toUid,
                                     JsonElement payload) {
            Envelope envelope = new Envelope();
            envelope.type = "relay";
            envelope.sessionId = sessionId;
            envelope.fromUid = fromUid;
            envelope.toUid = toUid;
            envelope.payload = payload;
            return envelope;
        }

        public static Envelope control(String type) {
            Envelope envelope = new Envelope();
            envelope.type = type;
            return envelope;
        }
    }
}
