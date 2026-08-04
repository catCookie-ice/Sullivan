package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_account")
public class UserAccount {
    @TableId
    private String id;
    private String status;
    private String loginName;
    private String passwordHash;
    private String roleCode;
}
