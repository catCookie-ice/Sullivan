package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 预设处罚字典：处罚只能从 ACTIVE 预设中选择；新增须另一名管理员批准。 */
@Data
@TableName("preset_penalty")
public class PresetPenalty {
    @TableId
    private String id;
    /** PENDING / ACTIVE / REJECTED */
    private String status;
    /** ACTIVITY / ORGANIZATION / USER */
    private String category;
    /** DELIST / DELIST_AND_BAN_ORG / BAN_ORG / BAN_USER */
    private String action;
    /** 封禁天数；null=永久（DELIST 类为 null） */
    private Integer durationDays;
    private String name;
    private String description;
    private String createdBy;
    private String approvedBy;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
