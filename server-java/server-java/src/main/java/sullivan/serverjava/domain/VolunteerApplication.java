package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 志愿者兼任申请：监护人提交，具备 VOLUNTEER_REVIEW 权限的管理员审核。
 */
@Data
@TableName("volunteer_application")
public class VolunteerApplication {
    @TableId
    private String id;
    private String status;
    private String applicantUid;
    private String reason;
    private String decidedByAdminId;
    private LocalDateTime decidedAt;
    private String decisionNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
