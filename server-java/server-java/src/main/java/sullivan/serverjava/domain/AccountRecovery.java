package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 找回联系方式（半加密两段存储）。
 * prefix 为明文不加密段，cipherHash 为剩余段单向加密（HMAC-SHA256，key=prefix）。
 */
@Data
@TableName("account_recovery")
public class AccountRecovery {
    @TableId
    private String id;
    private String status;
    private String principalType;
    private String principalId;
    private String recoveryType;
    private String prefix;
    private String cipherHash;
    private LocalDateTime createdAt;
    @TableLogic
    private LocalDateTime deletedAt;
}
