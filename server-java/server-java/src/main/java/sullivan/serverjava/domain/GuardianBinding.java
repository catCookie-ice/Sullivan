package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
    /** 发起方账号 ID（监护人或受护人）；方向由与 guardian_uid/protectee_uid 比较得出。 */
    private String requestedBy;
    private LocalDateTime requestedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime expiresAt;
    private String decisionNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
