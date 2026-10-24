package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知。
 *
 * <p>与 {@link AlertDelivery} 分开：投递回答「有没有送到」，通知回答「用户看不看得到」。
 * 一条报警可能投递成功而用户没点开（UNREAD），也可能投递失败但后来补发了通知。
 * 记录「送到哪一步」与「读没读」混在一张表里，两边的状态都会变得说不清。
 */
@Data
@TableName("notification")
public class Notification {
    @TableId
    private String id;
    private String status;
    private String receiverPrincipalType;
    private String receiverPrincipalId;
    private String notificationType;
    private String title;
    private String body;
    private String payloadJson;
    private LocalDateTime readAt;
    private String sourceType;
    private String sourceId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
