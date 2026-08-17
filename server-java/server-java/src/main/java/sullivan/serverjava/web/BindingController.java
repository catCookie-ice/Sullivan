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
import sullivan.serverjava.service.BindingService;
import sullivan.serverjava.web.BindingDtos.BindingDecisionRequest;
import sullivan.serverjava.web.BindingDtos.BindingRequest;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;

import java.util.List;

/**
 * 绑定双向流程（规范接口）：监护人与受护人均可发起，接收方确认/拒绝，发起方取消，任一方解除。
 */
@RestController
@RequestMapping("/api/v1/bindings")
@Tag(name = "监护绑定（双向）", description = "需登录且携带 Bearer 令牌；只允许自然人操作。")
@SecurityRequirement(name = "bearerAuth")
public class BindingController {
    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    @Operation(summary = "我的绑定与请求列表", description = "返回当前用户作为监护人或受护人参与的全部关系。")
    public List<BindingResponse> listMyBindings(Authentication authentication) {
        return bindingService.listMyBindings(principal(authentication).id());
    }

    @PostMapping
    @Operation(summary = "发起绑定",
            description = "targetRole=PROTECTEE 时当前用户须为监护人（绑定目标受护人）；"
                    + "targetRole=GUARDIAN 时当前用户须为受护人（绑定目标监护人）。"
                    + "请求为 PENDING，需由接收方在 accept 接口确认后变为 ACTIVE。")
    public ResponseEntity<BindingResponse> requestBinding(
            Authentication authentication, @Valid @RequestBody BindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bindingService.requestBinding(principal(authentication).id(), request));
    }

    @PostMapping("/{bindingId}/accept")
    @Operation(summary = "接收方确认绑定", description = "只有发起方的另一方（接收方）可以确认。")
    public BindingResponse acceptBinding(Authentication authentication,
                                         @Parameter(description = "绑定关系唯一标识", example = "b_1234567890")
                                         @PathVariable String bindingId) {
        return bindingService.acceptBinding(principal(authentication).id(), bindingId);
    }

    @PostMapping("/{bindingId}/reject")
    @Operation(summary = "接收方拒绝绑定", description = "只有接收方可以拒绝；原因可选。")
    public BindingResponse rejectBinding(Authentication authentication,
                                         @Parameter(description = "绑定关系唯一标识", example = "b_1234567890")
                                         @PathVariable String bindingId,
                                         @Valid @RequestBody(required = false) BindingDecisionRequest request) {
        return bindingService.rejectBinding(principal(authentication).id(), bindingId,
                request == null ? null : request.reason());
    }

    @PostMapping("/{bindingId}/cancel")
    @Operation(summary = "发起方取消绑定请求", description = "只有发起方可在确认前取消 PENDING 请求。")
    public BindingResponse cancelBinding(Authentication authentication,
                                         @Parameter(description = "绑定关系唯一标识", example = "b_1234567890")
                                         @PathVariable String bindingId) {
        return bindingService.cancelBinding(principal(authentication).id(), bindingId);
    }

    @PostMapping("/{bindingId}/terminate")
    @Operation(summary = "解除绑定",
            description = "生效关系中的任一方可解除；永久初始绑定（permanent=true）不可解除，返回 403。")
    public BindingResponse terminateBinding(Authentication authentication,
                                            @Parameter(description = "绑定关系唯一标识", example = "b_1234567890")
                                            @PathVariable String bindingId,
                                            @Valid @RequestBody(required = false) BindingDecisionRequest request) {
        return bindingService.terminateBinding(principal(authentication).id(), bindingId,
                request == null ? null : request.reason());
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以使用绑定接口");
        }
        return principal;
    }
}
