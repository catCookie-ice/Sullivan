package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 二维码相关 DTO：受护人出示的绑定码（扫码绑定）与登录票据（扫码授权登录）。
 *
 * <p>二维码里只放不透明随机串，不放任何身份信息；监护人身份一律取自 Bearer 令牌，
 * 绝不从二维码 payload 或请求体取。</p>
 */
public final class QrDtos {
    private QrDtos() {
    }

    // ---------- 扫码绑定（受护人出示绑定码） ----------

    public record BindCodeResponse(
            @Schema(description = "绑定码明文；客户端把它放进二维码，同时供受护人朗读/监护人手输",
                    example = "K7M2XQ9TB4ND")
            String code,
            @Schema(description = "受护人账号唯一标识", example = "u_1234567890") String protecteeId,
            @Schema(description = "受护人显示名，供监护人在扫码结果里确认身份", example = "王小明")
            String protecteeName,
            @Schema(description = "绑定码过期时间，格式 yyyy-MM-dd HH:mm:ss", example = "2026-10-16 15:04:05")
            LocalDateTime expiresAt,
            @Schema(description = "距离过期的剩余秒数，客户端倒计时直接用这个值", example = "120")
            long ttlSeconds) {
    }

    public record BindScanRequest(
            @Schema(description = "扫描到的绑定码（二维码内容或受护人念出的短码）", example = "K7M2XQ9TB4ND")
            @NotBlank String code) {
    }

    public record BindScanResponse(
            @Schema(description = "绑定关系唯一标识", example = "a1b2c3d4e5f6g7h8i9j0k1l2m3") String bindingId,
            @Schema(description = "绑定状态，扫码即时生效故为 ACTIVE", example = "ACTIVE") String status,
            @Schema(description = "监护人账号 ID（取自令牌）", example = "u_1111111111") String guardianId,
            @Schema(description = "受护人账号 ID", example = "u_1234567890") String protecteeId,
            @Schema(description = "受护人显示名", example = "王小明") String protecteeName,
            @Schema(description = "本次扫码前该关系是否已生效（重复扫为 true，属正常幂等）",
                    example = "false") boolean alreadyBound,
            @Schema(description = "面向用户的结果说明", example = "已绑定 王小明") String message) {
    }

    // ---------- 扫码登录（受护人出示登录票据） ----------

    public record LoginTicketCreateRequest(
            @Schema(description = "出示设备本地生成的 clientSecret 的 SHA-256（64 位十六进制小写）；"
                    + "明文只留在设备上，服务端不保存，兑换令牌时用它做第二次校验",
                    example = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "secretHash 必须是 64 位十六进制小写")
            String secretHash) {
    }

    public record LoginTicketCreateResponse(
            @Schema(description = "登录票据；放进二维码，监护人扫到后据此发起确认",
                    example = "Zm9vYmFyYmF6cXV4MTIzNDU2") String ticket,
            @Schema(description = "票据过期时间，格式 yyyy-MM-dd HH:mm:ss", example = "2026-10-16 15:04:05")
            LocalDateTime expiresAt,
            @Schema(description = "距离过期的剩余秒数", example = "120") long ttlSeconds) {
    }

    public record LoginTicketStatusResponse(
            @Schema(description = "票据状态：PENDING（待扫）/ SCANNED（已扫待选）/ CONFIRMED（已授权）/"
                    + " EXPIRED（已过期）",
                    example = "CONFIRMED",
                    allowableValues = {"PENDING", "SCANNED", "CONFIRMED", "EXPIRED"}) String status,
            @Schema(description = "仅在 CONFIRMED 时非空：兑换令牌时必须一并回传的凭据",
                    example = "Qk9ndXNPcGFxdWVDb2RlMDEyMzQ1Ng") String exchangeCode,
            @Schema(description = "仅在 CONFIRMED 时非空：被授权登录的受护人显示名", example = "王小明")
            String protecteeName,
            @Schema(description = "仅在 CONFIRMED 时非空：被授权登录的受护人账号 ID", example = "u_1234567890")
            String protecteeId,
            @Schema(description = "距离过期的剩余秒数；已过期或已确认时为 0", example = "87") long ttlSeconds) {
    }

    public record ProtecteeCandidate(
            @Schema(description = "受护人账号唯一标识", example = "u_1234567890") String protecteeId,
            @Schema(description = "受护人显示名", example = "王小明") String displayName,
            @Schema(description = "受护人登录名，同名时用于区分", example = "protectee_wang") String loginName) {
    }

    public record LoginTicketScanResponse(
            @Schema(description = "票据状态：本次扫描成功后为 SCANNED", example = "SCANNED") String status,
            @Schema(description = "当前监护人可授权登录的受护人候选（仅 ACTIVE 绑定）")
            List<ProtecteeCandidate> candidates) {
    }

    public record LoginTicketConfirmRequest(
            @Schema(description = "监护人选定的受护人账号 ID；服务端会重新校验二者存在 ACTIVE 绑定",
                    example = "u_1234567890")
            @NotBlank String protecteeId) {
    }

    public record LoginTicketExchangeRequest(
            @Schema(description = "出示设备本地保存的 clientSecret 明文", example = "3f2a1b0c9d8e7f60")
            @NotBlank @Size(max = 128) String clientSecret,
            @Schema(description = "轮询 CONFIRMED 时拿到的 exchangeCode", example = "Qk9ndXNPcGFxdWVDb2RlMDEyMzQ1Ng")
            @NotBlank @Size(max = 64) String exchangeCode) {
    }
}
