package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报警。先落库再路由——报警本身就是最重要的证据，不能因为后续投递失败而丢失。
 *
 * <p>{@code autoSession*} 三列与 {@code status} 是**两条独立的进度线**：
 * {@code status} 描述报警本身的生命周期（创建→派发→确认→结束），
 * {@code autoSession*} 描述「5 秒后要不要替他呼监护人」这条副线。
 * 分开的原因是二者会交错：监护人可能在第 3 秒就主动回了电话（status 前进），
 * 而自动会话此时应当被取消而不是继续排期；反过来，报警已 RESOLVED 也不代表
 * 自动会话已经跑完。合成一个状态机会表达不出「已解决但会话还在响」。
 */
@Data
@TableName("alert")
public class Alert {
    @TableId
    private String id;
    private String status;
    private String protecteeUid;
    private String idempotencyKey;
    private String alertType;
    private String routeType;
    private String routeNote;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private LocalDateTime locationCapturedAt;
    /**
     * 报警位置的逆地理编码地名（服务端在报警创建时解析一次并定格）。
     * NULL = 解析失败或未配置高德 Web Key；客户端自行回落到坐标展示。
     */
    private String locationAddress;
    /**
     * true = 到达解析时限还没拿到地名，报警**没有等待**、直接转发出去了。
     * 接收方看到该标识应使用本端解析兜底（Android Key）。时限内成功、
     * 确定失败（无 Key 等）均为 false——后者的 address 为 null，客户端同样兜底。
     */
    private Boolean locationAddressPending;
    private LocalDateTime resolvedAt;

    /** 计划自动发起会话的时刻；{@code null} = 未安排。 */
    private LocalDateTime autoSessionDueAt;
    /** 实际开始依次发起的时刻。 */
    private LocalDateTime autoSessionStartedAt;
    /** NONE / SCHEDULED / RUNNING / SKIPPED / DONE。 */
    private String autoSessionState;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
