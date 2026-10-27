package sullivan.serverjava.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台级违禁词：活动群聊发送内容做包含匹配，命中即拦截。
 * 种子词由迁移写入，词表的增删管理留作扩展点（本版不做管理界面）。
 */
@Data
@TableName("banned_word")
public class BannedWord {
    @TableId
    private String id;
    private String status;
    private String word;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    @TableLogic
    private LocalDateTime deletedAt;
}
