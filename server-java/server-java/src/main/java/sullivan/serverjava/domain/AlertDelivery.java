package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条报警对一个接收人。
 *
 * <p>{@code dispatchOrder} 是「依次发起会话」的**顺序来源**。必须落库而不是靠内存里的
 * 列表：定时任务可能被重试、可能跨实例，靠内存顺序会让第二次执行变成「乱序重发」。
 * 从 1 开始，按它升序逐个发起。
 */
@Data
@TableName("alert_delivery")
public class AlertDelivery {
    @TableId
    private String id;
    private String status;
    private String alertId;
    private String receiverPrincipalType;
    private String receiverPrincipalId;
    private String routeType;
    private LocalDateTime acknowledgedAt;
    private String failureReason;
    private Integer retryCount;
    /** 依次发起时的序号，从 1 开始；{@code null} 表示尚未排入序列。 */
    private Integer dispatchOrder;
    /** 为该投递创建的实时会话 ID。 */
    private String sessionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
