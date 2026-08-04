package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("admin_role")
public class AdminRole {
    @TableId
    private String id;
    private String status;
    private String roleCode;
    private String roleName;
    private Boolean isSystem;
}
