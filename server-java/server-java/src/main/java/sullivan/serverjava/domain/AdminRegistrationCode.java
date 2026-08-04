package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_registration_code")
public class AdminRegistrationCode {
    @TableId
    private String id;
    private String codeHash;
    private String roleId;
    private String status;
    private LocalDateTime expiresAt;
    private String createdByAdminId;
    private String usedByAdminId;
    private LocalDateTime usedAt;
    private Long version;
}
