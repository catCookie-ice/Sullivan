package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.RealtimeSessionService;
import sullivan.serverjava.web.RealtimeDtos.RealtimeDecisionRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionCreateRequest;
import sullivan.serverjava.web.RealtimeDtos.RealtimeSessionResponse;

import java.util.List;

/**
 * 实时会话（长连接）REST 面：发起/接受/拒绝/结束。
 * 长连接本身在 WebSocket 端点 /ws/realtime，需携带 JWT 握手。
 */
@RestController
@RequestMapping("/api/v1/realtime/sessions")
@Tag(name = "实时会话", description = "需登录；仅自然人。会话创建与消息中继要求双方存在 ACTIVE 绑定。")
@SecurityRequirement(name = "bearerAuth")
public class RealtimeController {
    private final RealtimeSessionService sessionService;

    public RealtimeController(RealtimeSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @Operation(summary = "发起实时会话",
            description = "与已绑定受护人/监护人建立会话请求（REQUESTED）。会话类型当前仅 BASIC。")
    public ResponseEntity<RealtimeSessionResponse> create(
            Authentication authentication, @Valid @RequestBody RealtimeSessionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.create(principal(authentication).id(), request));
    }

    @GetMapping
    @Operation(summary = "我的会话列表", description = "作为发起方或接收方参与的全部会话。")
    public List<RealtimeSessionResponse> mySessions(Authentication authentication) {
        return sessionService.mySessions(principal(authentication).id());
    }

    @PostMapping("/{sessionId}/accept")
    @Operation(summary = "接收方接受会话", description = "仅接收方（target_uid）可接受，REQUESTED → ACTIVE。")
    public RealtimeSessionResponse accept(Authentication authentication,
                                          @Parameter(description = "会话唯一标识", example = "rs_1234567890")
                                          @PathVariable String sessionId) {
        return sessionService.accept(principal(authentication).id(), sessionId);
    }

    @PostMapping("/{sessionId}/reject")
    @Operation(summary = "接收方拒绝会话", description = "仅接收方可拒绝；原因可选。")
    public RealtimeSessionResponse reject(Authentication authentication,
                                          @Parameter(description = "会话唯一标识", example = "rs_1234567890")
                                          @PathVariable String sessionId,
                                          @Valid @RequestBody(required = false) RealtimeDecisionRequest request) {
        return sessionService.reject(principal(authentication).id(), sessionId);
    }

    @PostMapping("/{sessionId}/end")
    @Operation(summary = "结束会话", description = "任一方参与人可结束进行中或待接受的会话。")
    public RealtimeSessionResponse end(Authentication authentication,
                                       @Parameter(description = "会话唯一标识", example = "rs_1234567890")
                                       @PathVariable String sessionId) {
        return sessionService.end(principal(authentication).id(), sessionId);
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以建立实时会话");
        }
        return principal;
    }
}
