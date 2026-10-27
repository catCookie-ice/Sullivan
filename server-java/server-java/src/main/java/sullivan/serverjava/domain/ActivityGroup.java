package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动志愿者群聊：每个活动至多一个群，管理员即主办机构账户（organization_id），
 * 机构不落成员行。活动结束由定时任务封闭（OPEN → CLOSED）。
 */
@Data
@TableName("activity_group")
public class ActivityGroup {
    @TableId
    private String id;
    private String status;
    private String activityId;
    private String organizationId;
    private LocalDateTime closedAt;
    private String closedReason;
    /** 暂时封禁到期时间；SUSPENDED 必填 */
    private LocalDateTime suspendedUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
