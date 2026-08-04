package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("admin_role_permission")
public class AdminRolePermission {
    @TableId
    private String id;
    private String roleId;
    private String permissionId;
    private String status;
}
