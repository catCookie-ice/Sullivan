package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处罚执行组件：预设组件的实例化记录；暂时类组件到期由扫描恢复（活动/群聊）。 */
@Data
@TableName("penalty_part")
public class PenaltyPart {
    @TableId
    private String id;
    private String penaltyId;
    /** ACTIVITY / ORGANIZATION / GROUP */
    private String targetKind;
    /** 基础惩罚 */
    private String action;
    private String targetActivityId;
    private String targetOrgId;
    private String targetGroupId;
    /** 解析后的生效时长；NULL=永久 */
    private Integer durationDays;
    /** 暂时类组件到期时刻 */
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
