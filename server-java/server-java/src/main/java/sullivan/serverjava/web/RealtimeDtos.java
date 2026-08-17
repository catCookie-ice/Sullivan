package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 实时会话 DTO：监护人与已绑定受护人建立长连接。
 */
public final class RealtimeDtos {
    private RealtimeDtos() {
    }

    public record RealtimeSessionCreateRequest(
            @Schema(description = "目标账号唯一标识（已绑定受护人或监护人）", example = "u_9876543210")
            @NotBlank String targetUid,
            @Schema(description = "会话类型：BASIC（基础消息中继，MINI 唯一可选项）", example = "BASIC",
                    allowableValues = {"BASIC"})
            @NotBlank @Pattern(regexp = "BASIC") String sessionType) {
    }

    public record RealtimeDecisionRequest(
            @Schema(description = "拒绝/结束原因（可选）", example = "暂时不方便", maxLength = 500)
            @Size(max = 500) String reason) {
    }

    public record RealtimeSessionResponse(
            @Schema(description = "会话唯一标识", example = "rs_1234567890") String id,
            @Schema(description = "状态：REQUESTED（待接受）/ ACTIVE（进行中）/ REJECTED（已拒绝）/ ENDED（已结束）",
                    example = "REQUESTED", allowableValues = {"REQUESTED", "ACTIVE", "REJECTED", "ENDED"}) String status,
            @Schema(description = "发起方账号 ID", example = "u_1111111111") String initiatorUid,
            @Schema(description = "接收方账号 ID", example = "u_9876543210") String targetUid,
            @Schema(description = "会话类型", example = "BASIC") String sessionType,
            @Schema(description = "创建时间", example = "2026-08-17T10:00:00") LocalDateTime createdAt) {
    }
}
