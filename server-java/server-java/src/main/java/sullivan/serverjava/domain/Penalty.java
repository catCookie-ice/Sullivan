package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处罚执行记录：从预设实例化；申诉推翻后 LIFTED 并联动解除限制。 */
@Data
@TableName("penalty")
public class Penalty {
    @TableId
    private String id;
    /** ACTIVE / LIFTED / EXPIRED */
    private String status;
    private String presetId;
    private String reportId;
    /** ACTIVITY / ORGANIZATION / USER */
    private String category;
    private String action;
    private Integer durationDays;
    private String targetActivityId;
    private String targetOrgId;
    private String targetGroupId;
    private String targetUserId;
    private String reason;
    private String appliedBy;
    private LocalDateTime appliedAt;
    private LocalDateTime expiresAt;
    /** 处罚是否连带禁用了账户（申诉推翻时据此恢复） */
    private Boolean accountDisabled;
    private String liftedBy;
    private LocalDateTime liftedAt;
    private String liftNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
