package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 语音留言：会话参与人留给他人的一段音频。
 *
 * <p><b>只存元数据。</b>音频本体在 {@link sullivan.serverjava.service.media.MediaStore}
 * 里，本表的 {@code objectKey} 是唯一入口，而它从不下发给客户端。
 *
 * <p><b>与 {@link Notification} 分开</b>（16 §2 N-04）：通知回答「用户看不看得到」，
 * 留言回答「用户留了一段音频」。通知读到就结束，留言要留在收件箱、要标记已听、
 * 要能删除 —— 合表会让通知长出一个它本不该有的状态机。
 */
@Data
@TableName("voice_message")
public class VoiceMessage {
    @TableId
    private String id;
    /** SENT / LISTENED / DELETED，见 02 §9.6。 */
    private String status;
    private String senderUid;
    private String receiverUid;
    private String sessionId;
    private String objectKey;
    private Integer durationMs;
    private Long sizeBytes;
    private String contentType;
    private LocalDateTime listenedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
