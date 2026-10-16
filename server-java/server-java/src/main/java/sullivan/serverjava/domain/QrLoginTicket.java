package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 扫码登录票据；状态机 PENDING → SCANNED → CONFIRMED → CONSUMED（任一步可 EXPIRED）。 */
@Data
@TableName("qr_login_ticket")
public class QrLoginTicket {
    @TableId
    private String ticket;
    private String status;
    /** 出示设备本地生成的 clientSecret 的 SHA-256；明文不落库。 */
    private String secretHash;
    private String guardianUid;
    private String protecteeUid;
    /** CONFIRMED 后下发给轮询方的换令牌凭据。 */
    private String exchangeCode;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime scannedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime consumedAt;
    private Long version;
}
