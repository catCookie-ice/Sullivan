package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台管理员账号。
 *
 * <p>与 user_account 同构：createdAt 仅用于展示排序（插入时为 null，由 DB 默认值落库）；
 * deletedAt 挂全局逻辑删除配置，软删后查询自动过滤、selectById 返回 null，
 * 从而让 {@code AdminAccessService.resolveAdminAccess} 把已删管理员当作未认证。</p>
 */
@Data
@TableName("admin_account")
public class AdminAccount {
    @TableId
    private String id;
    private String status;
    private String loginName;
    private String passwordHash;
    private String displayName;
    private LocalDateTime createdAt;
    @TableLogic
    private LocalDateTime deletedAt;
}
