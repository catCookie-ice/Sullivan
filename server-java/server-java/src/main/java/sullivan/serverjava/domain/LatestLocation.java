package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户的最新位置（一行一个用户，不带轨迹）。
 *
 * <p>只保留最新一条是刻意的：本版只需要回答「报警那一刻他在哪」。
 * 存轨迹会引入保留期、导出与删除等一整套义务，超出 [MINI] 范围。
 */
@Data
@TableName("latest_location")
public class LatestLocation {
    @TableId
    private String id;
    private String status;
    private String userId;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal accuracyM;
    /** 设备采集该位置的时刻（来自客户端），用于计算新鲜度。 */
    private LocalDateTime capturedAt;
    /** 服务端收到的时刻；与 {@code capturedAt} 的差值反映网络/离线补报延迟。 */
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
