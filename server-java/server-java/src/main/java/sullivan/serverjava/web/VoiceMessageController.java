package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.VoiceMessageService;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageCreateRequest;
import sullivan.serverjava.web.VoiceMessageDtos.VoiceMessageResponse;

import java.io.IOException;
import java.util.List;

/**
 * 语音留言：投递 / 收件箱 / 标记已听 / 删除 / 取回音频。
 *
 * <p>投递用 {@code multipart/form-data}：音频是二进制，塞进 JSON 的 base64 会让体积
 * 涨三分之一，还会让「内容长度」与「声明的 sizeBytes」两处对不上。
 *
 * <p>取回音频走 {@code GET /voice-messages/{id}/audio}，每次重新授权
 * （见 {@code VoiceMessageService.audio}），不签发预签名地址。
 */
@RestController
@RequestMapping("/api/v1/voice-messages")
@Tag(name = "语音留言", description = "需登录；仅自然人。投递要求双方存在 ACTIVE 绑定。")
@SecurityRequirement(name = "bearerAuth")
public class VoiceMessageController {
    private final VoiceMessageService service;

    public VoiceMessageController(VoiceMessageService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "投递语音留言",
            description = "接收方须与当前用户存在 ACTIVE 绑定。音频经 form-data 的 file 字段上传，"
                    + "时长/大小/格式均受服务端限制，24 小时内条数超限返回 429。")
    public ResponseEntity<VoiceMessageResponse> create(
            Authentication authentication,
            @RequestParam("receiverUid") String receiverUid,
            @RequestParam("durationMs") int durationMs,
            @RequestParam("sizeBytes") long sizeBytes,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少音频内容");
        }
        String declared = file.getContentType() == null ? contentType : file.getContentType();
        var request = new VoiceMessageCreateRequest(receiverUid, sessionId, durationMs, sizeBytes, declared);
        try (var stream = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(service.create(principal(authentication).id(), request, stream));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "读取上传内容失败");
        }
    }

    @GetMapping
    @Operation(summary = "我的留言箱",
            description = "返回「我收到的」与「我发出的」两类，按时间倒序；myRole 区分当前用户是哪一方。"
                    + "响应不含 uid 与对象键，界面用 senderName/receiverName 显示。")
    public List<VoiceMessageResponse> inbox(Authentication authentication) {
        return service.inbox(principal(authentication).id());
    }

    @PostMapping("/{messageId}/listened")
    @Operation(summary = "标记已听", description = "只有接收方本人可以标记；重复标记幂等。")
    public VoiceMessageResponse markListened(
            Authentication authentication,
            @Parameter(description = "留言唯一标识", example = "vm_1234567890") @PathVariable String messageId) {
        return service.markListened(principal(authentication).id(), messageId);
    }

    @DeleteMapping("/{messageId}")
    @Operation(summary = "删除留言", description = "投递双方任一方可删除；音频本体一并删除。")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @Parameter(description = "留言唯一标识", example = "vm_1234567890") @PathVariable String messageId) {
        service.delete(principal(authentication).id(), messageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{messageId}/audio")
    @Operation(summary = "取回留言音频",
            description = "仅投递双方可取回；每次取回都会重新校验双方仍存在 ACTIVE 绑定。"
                    + "不返回预签名地址，音频经本端点流式转发。")
    public ResponseEntity<InputStreamResource> audio(
            Authentication authentication,
            @Parameter(description = "留言唯一标识", example = "vm_1234567890") @PathVariable String messageId) {
        var audio = service.audio(principal(authentication).id(), messageId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.contentType()))
                // 音频是私密内容：禁止任何中间层缓存，否则代理/浏览器会把「别人的录音」
                // 留在共享缓存里，让鉴权形同虚设。
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private");
        if (audio.sizeBytes() != null) {
            builder.contentLength(audio.sizeBytes());
        }
        return builder.body(new InputStreamResource(audio.stream()));
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以使用语音留言");
        }
        return principal;
    }
}
