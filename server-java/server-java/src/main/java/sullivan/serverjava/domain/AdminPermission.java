package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("admin_permission")
public class AdminPermission {
    @TableId
    private String id;
    private String status;
    private String permissionCode;
    private String permissionName;
}
