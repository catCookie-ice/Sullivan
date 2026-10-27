package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 预设处罚的附带组件：跨对象（ACTIVITY/ORGANIZATION/GROUP），取目标对象的基础惩罚集；时长留空跟随主处罚。 */
@Data
@TableName("preset_penalty_part")
public class PresetPenaltyPart {
    @TableId
    private String id;
    /** 附带对象类型：ACTIVITY / ORGANIZATION / GROUP */
    private String targetKind;
    /** 该对象的基础惩罚 */
    private String action;
    /** 附带时长；NULL=跟随主处罚时长 */
    private Integer durationDays;
    private String presetId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
