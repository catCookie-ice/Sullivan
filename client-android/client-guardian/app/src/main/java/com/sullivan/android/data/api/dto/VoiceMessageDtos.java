package com.sullivan.android.data.api.dto;

/**
 * 语音留言 DTO，字段名与服务端 {@code web/VoiceMessageDtos.java} 逐字对应。
 *
 * <p><b>没有 uid，也没有对象键</b>：服务端刻意不返回这两样。uid 是内部标识，
 * 对象键则等于告诉客户端「音频存在哪」—— 一旦泄露，拿到它的人就能绕过
 * 「重新校验绑定」那道关口。所以音频只能经带令牌的音频端点取回。
 */
public final class VoiceMessageDtos {

    private VoiceMessageDtos() {
    }

    /** 状态取值：SENT（已投递）/ LISTENED（已听）/ DELETED（已删除）。 */
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_LISTENED = "LISTENED";

    /** 我在这条留言里的身份。 */
    public static final String ROLE_SENDER = "SENDER";
    public static final String ROLE_RECEIVER = "RECEIVER";

    /**
     * {@code GET /api/v1/voice-messages} 的列表元素。
     *
     * <p>时间字段是服务端统一的 {@code yyyy-MM-dd HH:mm:ss}（空格分隔）字符串，
     * 与项目里其它接口一致，不要按 ISO 的 {@code T} 去解析。
     */
    public static final class VoiceMessageResponse {
        public String id;
        public String status;
        public String senderLoginName;
        public String senderName;
        public String receiverLoginName;
        public String receiverName;
        /** SENDER 或 RECEIVER。 */
        public String myRole;
        public int durationMs;
        public long sizeBytes;
        public String contentType;
        public String createdAt;
        /** 未听过时是 null。 */
        public String listenedAt;
    }
}
