package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 公益活动（免费）。机构 APPROVED 后可发布；含报名窗口与高德经纬度。
 */
@Data
@TableName("volunteer_activity")
public class VolunteerActivity {
    @TableId
    private String id;
    private String status;
    private String organizationId;
    private String title;
    private String description;
    private String locationText;
    private BigDecimal locationLng;
    private BigDecimal locationLat;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private LocalDateTime registrationStartsAt;
    private LocalDateTime registrationEndsAt;
    /** 报名是否需机构审核；FALSE 时报名即通过并直接入群。 */
    private Boolean reviewRequired;
    private Integer maxVolunteers;
    private Integer approvedCount;
    private Boolean isFree;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
