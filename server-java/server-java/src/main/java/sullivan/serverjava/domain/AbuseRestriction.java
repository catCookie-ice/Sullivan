package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生效中的限制：举报锁定/禁参加活动/账户封禁（用户侧）与机构封禁（机构侧）。
 * 阈值自动升级与处罚执行共用这张表；申诉推翻/到期前置为 LIFTED。
 */
@Data
@TableName("abuse_restriction")
public class AbuseRestriction {
    @TableId
    private String id;
    /** ACTIVE / LIFTED */
    private String status;
    /** REPORT_LOCKED / ACTIVITY_BANNED / ACCOUNT_BANNED / ORG_BANNED */
    private String kind;
    private String userId;
    private String orgId;
    private String penaltyId;
    private String reason;
    /** 到期时间；null=永久 */
    private LocalDateTime expiresAt;
    /** 创建者；阈值自动升级为 null */
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
