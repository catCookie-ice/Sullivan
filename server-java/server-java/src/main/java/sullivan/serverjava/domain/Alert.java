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
