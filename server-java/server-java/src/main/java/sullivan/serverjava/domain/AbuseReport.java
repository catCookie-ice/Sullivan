package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 统一举报：活动/机构/群聊/个人 + 反向举报（恶意举报）；由举报治理管理员处置。 */
@Data
@TableName("abuse_report")
public class AbuseReport {
    @TableId
    private String id;
    /** PENDING / RESOLVED（处罚）/ DISMISSED（驳回） */
    private String status;
    /** ACTIVITY / ORGANIZATION / GROUP / USER / REPORT_ABUSE */
    private String category;
    private String reporterUid;
    private String reason;
    private String targetActivityId;
    private String targetOrgId;
    private String targetGroupId;
    private String targetUserId;
    /** 反向举报指向的原举报 */
    private String sourceReportId;
    private String handledBy;
    private LocalDateTime handledAt;
    private String decisionNote;
    private String penaltyId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
