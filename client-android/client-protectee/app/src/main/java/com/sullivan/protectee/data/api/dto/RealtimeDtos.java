package com.sullivan.protectee.data.api.dto;

import com.google.gson.JsonElement;

/**
 * 实时会话 DTO，字段名与服务端 {@code web/RealtimeDtos.java} 与
 * {@code realtime/RealtimeEnvelope.java} 逐字对应。
 */
public final class RealtimeDtos {

    private RealtimeDtos() {
    }

    /**
     * 会话类型。**这里没有 EMERGENCY** —— 紧急会话只能由服务端在报警派发时创建，
     * 客户端传 EMERGENCY 会被服务端 403 拒绝（见 {@code RealtimeSessionService}）。
     * 它不是一个「更吓人的选项」，而是「这条会话来自一次真实的报警」的标记；
     * 让客户端能自己打上这个标记，它就失去了全部信息量。
     */
    public static final String TYPE_BASIC = "BASIC";
    public static final String TYPE_VOICE = "VOICE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_CAMERA = "CAMERA";

    /** 服务端派发出来的紧急会话。客户端只会**收到**，不会发出。 */
    public static final String TYPE_EMERGENCY = "EMERGENCY";

    /** {@code POST /api/v1/realtime/sessions} 请求体：发起会话。 */
    public static final class SessionCreateRequest {
        /**
         * 目标账号 ID（主键）。会话接口认的是 uid，不是登录名 ——
         * 界面上让人选/填的是登录名，提交前必须换成 uid（见 RealtimeActivity）。
         */
        public String targetUid;
        /** BASIC / VOICE / VIDEO / CAMERA 之一。 */
        public String sessionType;

        public SessionCreateRequest(String targetUid, String sessionType) {
            this.targetUid = targetUid;
            this.sessionType = sessionType;
        }
    }

    /**
     * 会话。{@code POST} / {@code GET} / accept / reject / end 共用。
     *
     * <p>状态取值：REQUESTED（待接受）/ ACTIVE（进行中）/ REJECTED（已拒绝）/
     * ENDED（已结束）。握手时服务端只放行 REQUESTED 与 ACTIVE。
     *
     * <p>{@code *LoginName} 与 {@code *Name} 是服务端在 2026-10-26 之后新带的字段，
     * 用来让界面显示人话而不是一串 uid。
     */
    public static final class SessionResponse {
        public String id;
        public String status;
        public String initiatorUid;
        public String targetUid;
        public String sessionType;
        public String createdAt;
        public String initiatorLoginName;
        public String targetLoginName;
        /** 显示名（profile.displayName），可能为 null。 */
        public String initiatorName;
        public String targetName;
    }

    /**
     * {@code GET /api/v1/realtime/ice-servers} 响应：WebRTC 的 ICE 配置。
     *
     * <p>配置了 TURN 时 {@code username}/{@code credential} 是**短时有效**的临时凭据
     * （用户名里带过期时间戳）。所以它不能被缓存到本地长期复用 ——
     * 过期之后再拿去建连接会直接失败，而失败原因看起来像「网络不好」。
     */
    public static final class IceServerResponse {
        public java.util.List<String> urls;
        public String username;
        public String credential;
        public int ttlSeconds;
    }

    /**
     * WebSocket 文本帧的信封，对应服务端 {@code realtime/RealtimeEnvelope}。
     *
     * <p>发送时只需要填 {@code type}（"relay" / "signal"）、{@code sessionId}、
     * {@code fromUid}、{@code toUid} 与 {@code payload} 四项 —— 服务端会逐个校验
     * fromUid 必须等于令牌主体、toUid 必须是会话另一方，对不上就回一条
     * {@code type="error"} 的错误帧。{@code messageId} 与 {@code ts} 由服务端转发时
     * 原样带过，客户端不生成也不依赖。
     *
     * <p><b>{@code payload} 是 {@link JsonElement} 而不是 String</b>：
     * 中继消息与信令的内容由客户端自己约定（text 用
     * {@code {"text": "..."}}，信令用 {@code {"kind":"offer", ...}}），
     * 服务端只做透传不解析。用 JsonElement 才能原样转发，用 String 会被 Gson
     * 序列化成带引号的字符串。
     */
    public static final class Envelope {
        /** relay / signal / ping / pong / leave / session.joined / error。 */
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

        /** WebRTC 信令帧。{@code payload} 必须是一个对象，且带 {@code kind} 字段。 */
        public static Envelope signal(String sessionId, String fromUid, String toUid,
                                      JsonElement payload) {
            Envelope envelope = new Envelope();
            envelope.type = "signal";
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
