package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知：导航栏铃铛的数据源。接收者为多态引用
 * （志愿者=USER/user_account.id，机构=ORGANIZATION/organization.id），
 * 与群消息 sender 一样不建外键、由服务端校验归属；点击按 link 跳转对应前端页面。
 */
@Data
@TableName("user_notification")
public class UserNotification {
    @TableId
    private String id;
    /** UNREAD 未读 / READ 已读。 */
    private String status;
    private String recipientUid;
    /** USER 志愿者 / ORGANIZATION 机构。 */
    private String recipientType;
    /** ENROLLMENT_PENDING / ENROLLMENT_APPROVED / ENROLLMENT_REJECTED / GROUP_MESSAGE_WITHDRAWN。 */
    private String type;
    private String title;
    private String content;
    /** 点击跳转的前端路由（按接收端拼接，后端只存路径）。 */
    private String link;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
