package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_account_role")
public class AdminAccountRole {
    @TableId
    private String id;
    private String adminId;
    private String roleId;
    private String status;
    private String grantedByAdminId;
    private LocalDateTime expiresAt;
}
