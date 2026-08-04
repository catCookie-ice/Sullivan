package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("organization")
public class Organization {
    @TableId
    private String id;
    private String status;
    private String loginName;
    private String passwordHash;
    private String legalName;
    private String publicName;
}
