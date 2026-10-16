package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.QrBindCodeService;
import sullivan.serverjava.web.QrDtos.BindCodeResponse;
import sullivan.serverjava.web.QrDtos.BindScanRequest;
import sullivan.serverjava.web.QrDtos.BindScanResponse;

/**
 * 扫码绑定（功能 A）：受护人出示绑定码，监护人扫码即生效。
 *
 * <p>签发与查看绑定码必须是受护人**本人**且已登录（「绑定码仅登录后可展示」）；
 * 扫码端必须是监护人本人——监护人身份取自 Bearer 令牌，绝不从请求体取。</p>
 */
@RestController
@RequestMapping("/api/v1/qr/bind-codes")
@Tag(name = "扫码绑定", description = "受护人出示绑定码、监护人扫码建立绑定；需登录且携带 Bearer 令牌。")
@SecurityRequirement(name = "bearerAuth")
public class QrBindingController {
    private final QrBindCodeService bindCodeService;

    public QrBindingController(QrBindCodeService bindCodeService) {
        this.bindCodeService = bindCodeService;
    }

    @PostMapping
    @Operation(summary = "签发绑定码（受护人本人）",
            description = "作废该受护人此前所有绑定码后签发新码，保证同时只有一张有效码。")
    public ResponseEntity<BindCodeResponse> issueCode(Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bindCodeService.issueCode(principal(authentication).id()));
    }

    @GetMapping("/current")
    @Operation(summary = "查看当前绑定码（受护人本人）",
            description = "无有效绑定码时返回 204 No Content，客户端据此直接展示「刷新」按钮。")
    public ResponseEntity<BindCodeResponse> currentCode(Authentication authentication) {
        BindCodeResponse current = bindCodeService.currentCode(principal(authentication).id());
        return current == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(current);
    }

    @PostMapping("/scan")
    @Operation(summary = "监护人扫码绑定受护人",
            description = "扫码即时生效（记为「受护人发起 + 监护人扫码确认」）。同一监护人重复扫同一码是幂等的，"
                    + "返回 alreadyBound=true；若该对关系已存在由监护人发起的待确认申请则返回 409。")
    public BindScanResponse scan(Authentication authentication, @Valid @RequestBody BindScanRequest request) {
        return bindCodeService.scan(principal(authentication).id(), request.code());
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以使用扫码绑定接口");
        }
        return principal;
    }
}
