package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处罚申诉：OVERTURNED 联动解除限制与账户禁用。 */
@Data
@TableName("appeal")
public class Appeal {
    @TableId
    private String id;
    /** PENDING / UPHELD（维持）/ OVERTURNED（撤销） */
    private String status;
    private String penaltyId;
    /** USER / ORGANIZATION */
    private String appellantType;
    private String appellantUid;
    private String reason;
    private String handledBy;
    private LocalDateTime handledAt;
    private String decisionNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
