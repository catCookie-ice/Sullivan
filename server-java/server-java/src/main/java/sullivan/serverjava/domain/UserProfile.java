package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_profile")
public class UserProfile {
    @TableId
    private String id;
    private String status;
    private String userId;
    private String displayName;
    private String emergencyNote;
}
