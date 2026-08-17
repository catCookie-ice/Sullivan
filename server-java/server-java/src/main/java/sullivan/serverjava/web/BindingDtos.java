package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 绑定双向流程 DTO：监护人发起绑定受护人、受护人发起绑定监护人，均由接收方确认。
 */
public final class BindingDtos {
    private BindingDtos() {
    }

    public record BindingRequest(
            @Schema(description = "目标账号唯一标识（targetRole=PROTECTEE 时为目标受护人，"
                    + "targetRole=GUARDIAN 时为目标监护人）", example = "u_9876543210")
            @NotBlank String targetId,
            @Schema(description = "目标角色：PROTECTEE（发起方为监护人）/ GUARDIAN（发起方为受护人）",
                    example = "PROTECTEE", allowableValues = {"GUARDIAN", "PROTECTEE"})
            @NotBlank @Pattern(regexp = "GUARDIAN|PROTECTEE") String targetRole) {
    }

    public record BindingDecisionRequest(
            @Schema(description = "拒绝/解除原因（可选）", example = "暂不接受绑定", maxLength = 500)
            @Size(max = 500) String reason) {
    }

    /** 旧接口 /guardians/bindings 的兼容请求体（仅含目标受护人 ID）。 */
    public record LegacyBindingRequest(
            @Schema(description = "目标受护人账号唯一标识", example = "u_9876543210")
            @NotBlank String protecteeId) {
    }
}
