package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_profile")
public class UserProfile {
    @TableId
    private String id;
    private String status;
    private String userId;
    private String displayName;
    private String emergencyNote;
    @TableLogic
    private LocalDateTime deletedAt;
}
