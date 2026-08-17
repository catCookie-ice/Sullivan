package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动报名：志愿者报名，机构审核。
 */
@Data
@TableName("activity_enrollment")
public class ActivityEnrollment {
    @TableId
    private String id;
    private String status;
    private String activityId;
    private String volunteerUid;
    private String decisionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
