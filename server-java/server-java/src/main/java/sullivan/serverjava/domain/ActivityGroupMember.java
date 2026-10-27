package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动群成员：只有志愿者落行（报名通过即入群，撤回/移出置 REMOVED）；
 * 机构管理员的权限由 {@link ActivityGroup#getOrganizationId()} 推导，不落行。
 */
@Data
@TableName("activity_group_member")
public class ActivityGroupMember {
    @TableId
    private String id;
    private String status;
    private String groupId;
    private String volunteerUid;
    private LocalDateTime joinedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
