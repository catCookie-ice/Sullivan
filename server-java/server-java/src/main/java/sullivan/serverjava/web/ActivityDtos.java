package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 公益活动 DTO：机构发布活动（含报名窗口与高德经纬度）。
 */
public final class ActivityDtos {
    private ActivityDtos() {
    }

    public record ActivityCreateRequest(
            @Schema(description = "活动名", example = "朝阳区视障人士徒步陪伴", maxLength = 160)
            @NotBlank @Size(max = 160) String title,
            @Schema(description = "活动说明（志愿者要求等）", example = "陪伴视障人士完成2公里徒步")
            @NotBlank String description,
            @Schema(description = "活动地点文本", example = "朝阳公园南门", maxLength = 300)
            @NotBlank @Size(max = 300) String locationText,
            @Schema(description = "高德经度（可选，与纬度同空或同非空）", example = "116.493729")
            BigDecimal locationLng,
            @Schema(description = "高德纬度（可选，与经度同空或同非空）", example = "39.935524")
            BigDecimal locationLat,
            @Schema(description = "活动开始时间", example = "2026-09-01T09:00:00")
            @NotNull LocalDateTime startsAt,
            @Schema(description = "活动结束时间", example = "2026-09-01T12:00:00")
            @NotNull LocalDateTime endsAt,
            @Schema(description = "报名开始时间", example = "2026-08-20T00:00:00")
            @NotNull LocalDateTime registrationStartsAt,
            @Schema(description = "报名结束时间（不得晚于活动开始时间）", example = "2026-08-30T23:59:59")
            @NotNull LocalDateTime registrationEndsAt,
            @Schema(description = "需要的志愿者人数", example = "20", minimum = "1")
            @NotNull @Min(1) Integer maxVolunteers,
            @Schema(description = "报名是否需机构审核；缺省 false（报名即通过并直接入群）", example = "true")
            Boolean reviewRequired) {
    }

    public record ActivityResponse(
            @Schema(description = "活动唯一标识", example = "act_1234567890") String id,
            @Schema(description = "状态：RECRUITING（招募中）/ IN_PROGRESS（进行中）/ ENDED（已结束）/ CANCELLED（已取消）",
                    example = "RECRUITING") String status,
            @Schema(description = "组织机构唯一标识", example = "o_1234567890") String organizationId,
            @Schema(description = "机构展示名称", example = "朝阳盲协") String organizationPublicName,
            @Schema(description = "活动名", example = "朝阳区视障人士徒步陪伴") String title,
            @Schema(description = "活动说明", example = "陪伴视障人士完成2公里徒步") String description,
            @Schema(description = "活动地点文本", example = "朝阳公园南门") String locationText,
            @Schema(description = "高德经度", example = "116.493729") BigDecimal locationLng,
            @Schema(description = "高德纬度", example = "39.935524") BigDecimal locationLat,
            @Schema(description = "活动开始时间", example = "2026-09-01T09:00:00") LocalDateTime startsAt,
            @Schema(description = "活动结束时间", example = "2026-09-01T12:00:00") LocalDateTime endsAt,
            @Schema(description = "报名开始时间", example = "2026-08-20T00:00:00") LocalDateTime registrationStartsAt,
            @Schema(description = "报名结束时间", example = "2026-08-30T23:59:59") LocalDateTime registrationEndsAt,
            @Schema(description = "需要的志愿者人数", example = "20") Integer maxVolunteers,
            @Schema(description = "报名是否需机构审核", example = "true") Boolean reviewRequired,
            @Schema(description = "已批准报名人数（参与志愿者数）", example = "0") Integer approvedCount,
            @Schema(description = "创建时间", example = "2026-08-17T10:00:00") LocalDateTime createdAt) {
    }
}
