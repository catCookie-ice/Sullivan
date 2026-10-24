package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 受护人的报警偏好。
 *
 * <p>**缺行按默认处理**（自动会话开启、延迟 5 秒）：服务层读不到就取默认值，
 * 绝不因为「没有配置行」而静默关掉求助功能。反过来，「关掉」必须是一条显式的行，
 * 这样「用户选了关闭」和「我们从没配置过」在数据上就分得开。
 */
@Data
@TableName("protectee_alert_setting")
public class ProtecteeAlertSetting {
    @TableId
    private String id;
    private String status;
    private String protecteeUid;
    /** 报警后是否自动向监护人依次发起会话；null 视为 true。 */
    private Boolean autoSessionEnabled;
    /** 报警到发起会话的延迟秒数；null 视为 5。 */
    private Integer autoSessionDelaySeconds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
