package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 群聊消息举报：群成员举报消息，群管理员（机构）处理——
 * 受理（RESOLVED，撤回被举报消息）或驳回（DISMISSED）。
 */
@Data
@TableName("activity_group_report")
public class ActivityGroupReport {
    @TableId
    private String id;
    private String status;
    private String groupId;
    private String messageId;
    private String reporterUid;
    private String reason;
    private LocalDateTime handledAt;
    private String handledBy;
    private String decisionNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
