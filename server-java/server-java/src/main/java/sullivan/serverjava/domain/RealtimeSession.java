package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实时会话：监护人与受护人之间基于长连接（WebSocket）的双向通道。
 * 语音通话与受护人视角实时共享为 [FULL] 扩展，session_type 当前仅 BASIC。
 */
@Data
@TableName("realtime_session")
public class RealtimeSession {
    @TableId
    private String id;
    private String status;
    private String initiatorUid;
    private String targetUid;
    private String sessionType;
    private LocalDateTime acceptedAt;
    private LocalDateTime endedAt;
    private String endedByUid;
    private String endReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
