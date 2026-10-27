package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动群消息：只允许文字（content），无任何媒体字段。
 * sender_uid 是多态引用（志愿者=user_account.id，机构=organization.id），
 * 由 sender_type 区分，服务端校验发送者与群的归属关系。
 */
@Data
@TableName("activity_group_message")
public class ActivityGroupMessage {
    @TableId
    private String id;
    private String status;
    private String groupId;
    private String senderUid;
    private String senderType;
    private String content;
    private LocalDateTime withdrawnAt;
    private String withdrawnBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
