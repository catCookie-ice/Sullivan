package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 报警与位置 DTO。
 *
 * <p>坐标一律用 {@link BigDecimal} 而不是 double：经纬度要原样落进 DECIMAL(9,6)，
 * 用 double 会在序列化时引入二进制浮点的尾数，虽然对本场景无害，但会让
 * 「客户端上报的字符串」与「库里存的值」对不上，排查位置问题时多一层噪声。
 */
public final class AlertDtos {
    private AlertDtos() {
    }

    /**
     * 上报最新位置。
     *
     * <p>没有单独的「位置共享开关」参数：是否允许上报由系统定位权限与本接口的调用
     * 共同决定，服务端只负责记录「用户确实给了这个位置」这一事实。
     */
    public record LocationReportRequest(
            @Schema(description = "纬度", example = "39.935524", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
            @Schema(description = "经度", example = "116.493729", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
            @Schema(description = "定位精度（米）；设备给不出时为 null", example = "12.5")
            @DecimalMin("0.0") BigDecimal accuracyM,
            @Schema(description = "设备采集该位置的时刻（ISO-8601）；缺省时按服务端收到时刻计",
                    example = "2026-10-24T14:00:00")
            LocalDateTime capturedAt) {
    }

    /**
     * 创建报警。
     *
     * <p>{@code idempotencyKey} 是**必填**：受护人端在弱网下重试、或用户连点两次，
     * 只应产生一条报警、一次呼叫。由客户端生成并把同一次报警的多次提交复用同一个键。
     */
    public record AlertCreateRequest(
            @Schema(description = "报警类型：MANUAL（用户主动）/ VOICE_HELP（语音求助）",
                    example = "MANUAL", allowableValues = {"MANUAL", "VOICE_HELP", "COLLISION"})
            @NotBlank @Pattern(regexp = "MANUAL|VOICE_HELP|COLLISION") String alertType,
            @Schema(description = "幂等键，同一次报警的多次提交必须复用", example = "a1b2c3d4e5f6",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 100) String idempotencyKey,
            @Schema(description = "报警时用一句话说明情况（可选）", example = "我在路口摔倒了", maxLength = 300)
            @Size(max = 300) String note,
            @Schema(description = """
                    报警**当下**刚取到的位置（可选）。
                    传了就以它为准，回落到 1 秒内收到的上报缓存。
                    """)
            @Valid AlertCaptureRequest location) {
    }

    /**
     * 报警携带的「此刻位置」。
     *
     * <p><b>为什么报警要自带位置，而不是让服务端去读缓存。</b>
     * 服务端原来只读 {@code latest_location}——那是受护人端每 10 秒上报一次的缓存行。
     * 实测出现过两种失真：
     * <ul>
     *   <li>报警时刻的缓存已经是 8 分钟前的（用户在室内一直没拿到新定位）；</li>
     *   <li>客户端「先刷新位置、再发报警」是两次独立请求，报警往往先到，
     *       位置后到，于是报警里根本没位置。</li>
     * </ul>
     * 报警是要拿去找人的，几公里外的旧坐标比没有坐标更糟 ——
     * 会让人往错的方向赶。所以位置必须由**同一次请求**带上来，
     * 由客户端在发报警前主动取一次实时定位。
     */
    public record AlertCaptureRequest(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
            @Schema(description = "定位精度（米）；未知时不传", example = "12.5")
            @DecimalMin("0.0") BigDecimal accuracyM,
            @Schema(description = "采集时刻；不传则取服务端当前时间",
                    example = "2026-10-24 17:02:15")
            LocalDateTime capturedAt) {
    }

    /** 位置快照，附在报警详情与会话问候里。 */
    public record AlertLocation(
            @Schema(description = "纬度", example = "39.935524") BigDecimal latitude,
            @Schema(description = "经度", example = "116.493729") BigDecimal longitude,
            @Schema(description = "定位精度（米）", example = "12.5") BigDecimal accuracyM,
            @Schema(description = "采集时刻", example = "2026-10-24T14:00:00") LocalDateTime capturedAt,
            @Schema(description = "距今秒数；越大越不可信", example = "3") Long ageSeconds,
            @Schema(description = "逆地理编码地名（服务端在报警创建时解析）；解析失败或未配置高德 Web Key 时为 null",
                    example = "浙江省杭州市西湖区文三路 100 号") String address,
            @Schema(description = "true = 服务端在时限内没完成解析，报警未等待直接转发；接收方应使用本端解析兜底",
                    example = "false") Boolean addressPending) {
    }

    /** 一个接收人的派发结果。 */
    public record AlertDeliveryView(
            @Schema(description = "接收人账号 ID", example = "u_9876543210") String receiverUid,
            @Schema(description = "接收顺序，从 1 开始", example = "1") Integer dispatchOrder,
            @Schema(description = "投递状态：PENDING / SENT / DELIVERED / ACKNOWLEDGED / FAILED",
                    example = "SENT") String status,
            @Schema(description = "为该投递创建的实时会话 ID；未创建时为 null",
                    example = "rs_1234567890") String sessionId,
            @Schema(description = "失败原因；成功时为 null") String failureReason) {
    }

    /** 报警详情。 */
    public record AlertResponse(
            @Schema(description = "报警唯一标识", example = "al_1234567890") String id,
            @Schema(description = "状态：CREATED / DISPATCHING / DISPATCHED / ACKNOWLEDGED / RESOLVED / NO_RECIPIENT / DISPATCH_FAILED",
                    example = "DISPATCHED") String status,
            @Schema(description = "报警类型", example = "MANUAL") String alertType,
            @Schema(description = "路由类型：ALL_GUARDIANS / NEARBY_VOLUNTEERS / NONE", example = "ALL_GUARDIANS")
            String routeType,
            @Schema(description = "路由说明", example = "已通知全部 2 名生效监护人") String routeNote,
            @Schema(description = "受护人账号 ID") String protecteeUid,
            @Schema(description = "报警时的位置快照；未上报位置时为 null") AlertLocation location,
            @Schema(description = "创建时间") LocalDateTime createdAt,
            @Schema(description = "解决时间") LocalDateTime resolvedAt,
            @Schema(description = "自动发起会话的状态：NONE / SCHEDULED / RUNNING / SKIPPED / DONE",
                    example = "SCHEDULED") String autoSessionState,
            @Schema(description = "计划自动发起会话的时刻") LocalDateTime autoSessionDueAt,
            @Schema(description = "各接收人的派发结果") List<AlertDeliveryView> deliveries) {
    }

    /** 报警偏好。 */
    public record AlertSettingResponse(
            @Schema(description = "报警后是否自动向监护人依次发起会话", example = "true")
            boolean autoSessionEnabled,
            @Schema(description = "报警到发起会话的延迟秒数", example = "5") int delaySeconds) {
    }

    public record AlertSettingUpdateRequest(
            @Schema(description = "报警后是否自动向监护人依次发起会话", example = "false",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Boolean autoSessionEnabled) {
    }

    /** 受护人看到的一条联系人（用于「会通知谁」的预览）。 */
    public record AlertGuardianView(
            @Schema(description = "监护人账号 ID", example = "u_9876543210") String guardianUid,
            @Schema(description = "昵称", example = "小李") String displayName,
            @Schema(description = "顺序，从 1 开始", example = "1") int order) {
    }
}
