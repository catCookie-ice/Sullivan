package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("guardian_binding")
public class GuardianBinding {
    @TableId
    private String id;
    private String status;
    private String guardianUid;
    private String protecteeUid;
    private Boolean isPrimary;
    private Boolean permanentFlag;
    private Long version;
}
