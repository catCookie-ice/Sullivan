package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 找回联系方式提醒状态：记录"下次再说"（lastRemindedAt）与"不再提醒"（remindNever）。
 * 每个主体一行，仅在未设置任何找回联系方式时有意义。
 */
@Data
@TableName("account_recovery_reminder")
public class AccountRecoveryReminder {
    @TableId
    private String id;
    private String status;
    private String principalType;
    private String principalId;
    private Boolean remindNever;
    private LocalDateTime lastRemindedAt;
    private LocalDateTime createdAt;
    @TableLogic
    private LocalDateTime deletedAt;
}
