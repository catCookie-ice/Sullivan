package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class RecoveryDtos {
    private RecoveryDtos() {
    }

    public record RecoveryMethod(
            @Schema(description = "找回方式：PHONE（手机号）/ EMAIL（邮箱）",
                    example = "PHONE", allowableValues = {"PHONE", "EMAIL"}) String type,
            @Schema(description = "掩码后的不加密段，用于用户识别是哪条联系方式",
                    example = "****8000") String maskedPrefix) {
    }

    public record PasswordRecoveryStartRequest(
            @Schema(description = "主体类型：USER（自然人）/ ORGANIZATION（机构）",
                    example = "USER", allowableValues = {"USER", "ORGANIZATION"})
            @Pattern(regexp = "USER|ORGANIZATION") String principalType,
            @Schema(description = "登录名", example = "zhangsan")
            @NotBlank @Size(max = 64) String loginName) {
    }

    public record PasswordRecoveryStartResponse(
            @Schema(description = "该账号已设置的可找回方式（含掩码前缀）；未找到账号或无方式时为空列表",
                    example = "[{\"type\":\"PHONE\",\"maskedPrefix\":\"****8000\"}]")
            List<RecoveryMethod> methods) {
    }

    public record PasswordRecoveryVerifyRequest(
            @Schema(description = "主体类型：USER / ORGANIZATION",
                    example = "USER", allowableValues = {"USER", "ORGANIZATION"})
            @Pattern(regexp = "USER|ORGANIZATION") String principalType,
            @Schema(description = "登录名", example = "zhangsan")
            @NotBlank @Size(max = 64) String loginName,
            @Schema(description = "所选找回方式：PHONE / EMAIL",
                    example = "PHONE", allowableValues = {"PHONE", "EMAIL"})
            @Pattern(regexp = "PHONE|EMAIL") String recoveryType,
            @Schema(description = "用户补全后的完整手机号或邮箱", example = "13800138000")
            @NotBlank @Size(max = 128) String fullContact) {
    }

    public record PasswordRecoveryVerifyResponse(
            @Schema(description = "验证是否通过", example = "true") boolean verified) {
    }

    public record SetRecoveryRequest(
            @Schema(description = "找回手机号（可选；与找回邮箱至少填一个）", example = "13800138000",
                    maxLength = 16)
            @Size(max = 16) String recoveryPhone,
            @Schema(description = "找回邮箱（可选；与找回手机号至少填一个）", example = "zhangsan@example.com",
                    maxLength = 128)
            @Size(max = 128) String recoveryEmail) {
    }

    public record RecoveryStatusResponse(
            @Schema(description = "是否已设置至少一种找回联系方式", example = "false") boolean hasRecovery,
            @Schema(description = "本次登录后是否需要提醒设置找回联系方式", example = "true") boolean remindRequired,
            @Schema(description = "已设置的找回方式（含掩码前缀）") List<RecoveryMethod> methods) {
    }

    public record RecoveryActionResponse(
            @Schema(description = "操作是否成功", example = "true") boolean ok) {
    }
}
