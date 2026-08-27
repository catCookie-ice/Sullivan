package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员一次性注册码（明文存储）。
 *
 * <p>按业务要求，注册码以明文存储且即为主键：不再使用自增/雪花 id，也不使用 code_hash 哈希。
 * 使用后置为 USED 并回填 used_by_admin_id / used_at；过期由 service 标记为 EXPIRED。</p>
 */
@Data
@TableName("admin_registration_code")
public class AdminRegistrationCode {
    @TableId
    private String code;
    private String roleId;
    private String status;
    private LocalDateTime expiresAt;
    private String createdByAdminId;
    private String usedByAdminId;
    private LocalDateTime usedAt;
    private Long version;
}
