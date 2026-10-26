package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 语音留言 DTO。
 *
 * <p><b>响应里没有 objectKey，也没有任何可下载地址</b>：音频只能经
 * {@code GET /voice-messages/{id}/audio} 取回，那个端点每次重新做授权。
 * 让人拿到一个「自带授权」的地址，等于把撤销能力交出去。
 *
 * <p><b>没有 uid 字段</b>（00 §7 第 11 条）：界面要显示的是「谁给我留的」，
 * 那是登录名与显示名；uid 只在服务端内部用于归属判断。
 */
public final class VoiceMessageDtos {
    private VoiceMessageDtos() {
    }

    public record VoiceMessageCreateRequest(
            @Schema(description = "接收方账号唯一标识（须与当前用户存在 ACTIVE 绑定）",
                    example = "u_9876543210") String receiverUid,
            @Schema(description = "来源会话（可选）", example = "rs_1234567890") String sessionId,
            @Schema(description = "时长（毫秒），上限由服务端配置控制", example = "12000",
                    minimum = "1") int durationMs,
            @Schema(description = "音频大小（字节），用于与实际上传内容比对", example = "96000",
                    minimum = "1") long sizeBytes,
            @Schema(description = "MIME 类型", example = "audio/mp4",
                    allowableValues = {"audio/mp4", "audio/aac", "audio/3gpp", "audio/amr", "audio/ogg"})
            String contentType) {
    }

    public record VoiceMessageResponse(
            @Schema(description = "留言唯一标识", example = "vm_1234567890") String id,
            @Schema(description = "状态：SENT（未听）/ LISTENED（已听）/ DELETED（已删除）",
                    example = "SENT", allowableValues = {"SENT", "LISTENED", "DELETED"}) String status,
            @Schema(description = "发送方登录名", example = "protectee_wang") String senderLoginName,
            @Schema(description = "发送方显示名（优先用于界面）", example = "王小明") String senderName,
            @Schema(description = "接收方登录名", example = "guardian_li") String receiverLoginName,
            @Schema(description = "接收方显示名", example = "李监护") String receiverName,
            @Schema(description = "我在这条留言里是发送方还是接收方",
                    example = "RECEIVER", allowableValues = {"SENDER", "RECEIVER"}) String myRole,
            @Schema(description = "时长（毫秒）", example = "12000") Integer durationMs,
            @Schema(description = "大小（字节）", example = "96000") Long sizeBytes,
            @Schema(description = "MIME 类型", example = "audio/mp4") String contentType,
            @Schema(description = "创建时间", example = "2026-10-26 10:00:00") LocalDateTime createdAt,
            @Schema(description = "收听时间（未听为 null）", example = "2026-10-26 10:05:00")
            LocalDateTime listenedAt) {
    }
}
