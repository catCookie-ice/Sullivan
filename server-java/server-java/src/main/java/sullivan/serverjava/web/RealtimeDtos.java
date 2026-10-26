package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 实时会话 DTO：监护人与已绑定受护人建立长连接。
 *
 * <p><b>用户可见标识一律是登录名</b>（00 §7 第 11 条）。响应里仍然保留 uid，
 * 因为客户端要靠它判断「这条会话里我是哪一方」；但 uid 只是内部主键，
 * 界面上要显示的人一律取 {@code *LoginName} / {@code *Name}。
 */
public final class RealtimeDtos {
    private RealtimeDtos() {
    }

    /** 客户端可达的会话类型。{@code EMERGENCY} 只能由服务端报警派发创建。 */
    public static final String TYPE_PATTERN = "BASIC|VOICE|VIDEO|CAMERA|EMERGENCY";

    public record RealtimeSessionCreateRequest(
            @Schema(description = "目标账号唯一标识（已绑定受护人或监护人）", example = "u_9876543210")
            @NotBlank String targetUid,
            @Schema(description = "会话类型：BASIC（纯消息）/ VOICE（语音）/ VIDEO（视频）/ "
                    + "CAMERA（监护人查看受护人摄像头，期间有语音）。"
                    + "EMERGENCY 只能由服务端报警派发创建，客户端传入返回 403。",
                    example = "VOICE",
                    allowableValues = {"BASIC", "VOICE", "VIDEO", "CAMERA", "EMERGENCY"})
            @NotBlank @Pattern(regexp = TYPE_PATTERN) String sessionType) {
    }

    public record RealtimeDecisionRequest(
            @Schema(description = "拒绝/结束原因（可选）", example = "暂时不方便", maxLength = 500)
            @Size(max = 500) String reason) {
    }

    public record RealtimeSessionResponse(
            @Schema(description = "会话唯一标识", example = "rs_1234567890") String id,
            @Schema(description = "状态：REQUESTED（待接受）/ ACTIVE（进行中）/ REJECTED（已拒绝）/ ENDED（已结束）",
                    example = "REQUESTED", allowableValues = {"REQUESTED", "ACTIVE", "REJECTED", "ENDED"}) String status,
            @Schema(description = "发起方账号 ID（内部主键，不作为界面显示）",
                    example = "u_1111111111") String initiatorUid,
            @Schema(description = "接收方账号 ID（内部主键，不作为界面显示）",
                    example = "u_9876543210") String targetUid,
            @Schema(description = "会话类型", example = "VOICE",
                    allowableValues = {"BASIC", "VOICE", "VIDEO", "CAMERA", "EMERGENCY"}) String sessionType,
            @Schema(description = "创建时间", example = "2026-08-17 10:00:00") LocalDateTime createdAt,
            @Schema(description = "发起方登录名（界面显示用）", example = "protectee_wang") String initiatorLoginName,
            @Schema(description = "接收方登录名（界面显示用）", example = "guardian_li") String targetLoginName,
            @Schema(description = "发起方显示名（优先于登录名用于界面）", example = "王小明") String initiatorName,
            @Schema(description = "接收方显示名（优先于登录名用于界面）", example = "李监护") String targetName) {
    }

    /**
     * WebRTC ICE 配置。{@code credential} 是**短时有效**的临时凭据（coturn REST 风格：
     * 用户名含过期时间戳，密码是 HMAC），不得把它当成长期密钥使用。
     */
    public record IceServerResponse(
            @Schema(description = "STUN/TURN 地址列表", example = "[\"stun:turn.example.com:3478\"]")
            java.util.List<String> urls,
            @Schema(description = "临时用户名（含过期时间戳）", example = "1793000000:u_1234567890")
            String username,
            @Schema(description = "临时密码", example = "Kx8v...") String credential,
            @Schema(description = "该凭据的有效期（秒）", example = "3600") int ttlSeconds) {
    }
}
