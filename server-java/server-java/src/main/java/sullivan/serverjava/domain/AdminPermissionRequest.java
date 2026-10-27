package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限申请（V5）：申请语义是「我需要这项权限」——任意管理员可发起（无需任何权限），
 * 只填权限与理由，不涉及角色；ADMIN_GOVERN 审批、自批拒绝；
 * 批准后由系统把申请人加入按权限自动生成的承载角色（PERM_&lt;权限码&gt;）。
 */
@Data
@TableName("admin_permission_request")
public class AdminPermissionRequest {
    @TableId
    private String id;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private String permissionCode;
    private String reason;
    /** 申请人（受益人就是本人）；审批人不能与其相同（服务端校验） */
    private String requestedBy;
    private String handledBy;
    private LocalDateTime handledAt;
    private String decisionNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
