package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 受护人出示的扫码绑定码；每位受护人同时最多一张 ACTIVE（由生成列唯一键保证）。 */
@Data
@TableName("protectee_bind_code")
public class ProtecteeBindCode {
    @TableId
    private String code;
    private String protecteeUid;
    /** ACTIVE（待扫）/ REVOKED（被新码替换）/ EXPIRED（超时）。 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long version;
}
