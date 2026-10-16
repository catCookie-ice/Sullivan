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
import sullivan.serverjava.service.QrLoginService;
import sullivan.serverjava.web.QrDtos.LoginTicketConfirmRequest;
import sullivan.serverjava.web.QrDtos.LoginTicketCreateRequest;
import sullivan.serverjava.web.QrDtos.LoginTicketCreateResponse;
import sullivan.serverjava.web.QrDtos.LoginTicketExchangeRequest;
import sullivan.serverjava.web.QrDtos.LoginTicketScanResponse;
import sullivan.serverjava.web.QrDtos.LoginTicketStatusResponse;
import sullivan.serverjava.web.RegistrationDtos.TokenResponse;

/**
 * 扫码授权登录（功能 B）：受护人未登录时出示登录码，监护人扫码并选定自己名下的受护人，
 * 出示码的那台设备即登录为该受护人。
 *
 * <p>create / status / exchange 三个接口必须匿名可用——出示设备此时尚未登录；它们的安全边界是
 * 票据本身的不透明性（128 bit）+ 设备本地 clientSecret + 一次性核销，而不是认证。
 * scan / confirm 需要监护人已登录，走全局认证拦截。</p>
 */
@RestController
@RequestMapping("/api/v1/qr/login-tickets")
@Tag(name = "扫码授权登录", description = "create/status/exchange 匿名可用；scan/confirm 需监护人登录。")
public class QrLoginController {
    private final QrLoginService loginService;

    public QrLoginController(QrLoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping
    @Operation(summary = "创建登录票据（公开）",
            description = "出示设备本地生成 clientSecret，只上传其 SHA-256；明文留在设备上用于兑换令牌。")
    public ResponseEntity<LoginTicketCreateResponse> create(
            @Valid @RequestBody LoginTicketCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(loginService.create(request.secretHash()));
    }

    @GetMapping("/{ticket}")
    @Operation(summary = "轮询登录票据状态（公开）",
            description = "只返回状态。CONFIRMED 时附带 exchangeCode 与被授权受护人；"
                    + "**响应中永远不含 accessToken**。已消费的票据返回 404。")
    public LoginTicketStatusResponse status(
            @Parameter(description = "登录票据", example = "Zm9vYmFyYmF6cXV4MTIzNDU2") @PathVariable String ticket) {
        return loginService.status(ticket);
    }

    @PostMapping("/{ticket}/exchange")
    @Operation(summary = "兑换访问令牌（公开，一次性）",
            description = "出示 clientSecret 与轮询拿到的 exchangeCode，核销票据并换回受护人的令牌。"
                    + "服务端会重新校验监护关系仍 ACTIVE、受护人仍 ENABLED。")
    public TokenResponse exchange(
            @Parameter(description = "登录票据", example = "Zm9vYmFyYmF6cXV4MTIzNDU2") @PathVariable String ticket,
            @Valid @RequestBody LoginTicketExchangeRequest request) {
        return loginService.exchange(ticket, request.clientSecret(), request.exchangeCode());
    }

    @PostMapping("/{ticket}/scan")
    @Operation(summary = "监护人扫描登录码",
            description = "标记票据为 SCANNED（他人抢扫同一票据返回 409），并返回该监护人 ACTIVE 绑定的"
                    + "受护人候选列表。")
    @SecurityRequirement(name = "bearerAuth")
    public LoginTicketScanResponse scan(Authentication authentication,
                                        @Parameter(description = "登录票据", example = "Zm9vYmFyYmF6cXV4MTIzNDU2")
                                        @PathVariable String ticket) {
        return loginService.scan(principal(authentication).id(), ticket);
    }

    @PostMapping("/{ticket}/confirm")
    @Operation(summary = "监护人确认授权",
            description = "选定的受护人必须与该监护人存在 ACTIVE 绑定，否则 403；确认后票据转为 CONFIRMED。")
    @SecurityRequirement(name = "bearerAuth")
    public LoginTicketStatusResponse confirm(Authentication authentication,
                                             @Parameter(description = "登录票据", example = "Zm9vYmFyYmF6cXV4MTIzNDU2")
                                             @PathVariable String ticket,
                                             @Valid @RequestBody LoginTicketConfirmRequest request) {
        return loginService.confirm(principal(authentication).id(), ticket, request.protecteeId());
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || !"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有自然人可以使用扫码授权登录接口");
        }
        return principal;
    }
}
