package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.RecoveryService;
import sullivan.serverjava.web.RecoveryDtos.PasswordRecoveryStartRequest;
import sullivan.serverjava.web.RecoveryDtos.PasswordRecoveryStartResponse;
import sullivan.serverjava.web.RecoveryDtos.PasswordRecoveryVerifyRequest;
import sullivan.serverjava.web.RecoveryDtos.PasswordRecoveryVerifyResponse;
import sullivan.serverjava.web.RecoveryDtos.RecoveryActionResponse;
import sullivan.serverjava.web.RecoveryDtos.RecoveryStatusResponse;
import sullivan.serverjava.web.RecoveryDtos.SetRecoveryRequest;

import java.util.Set;

/**
 * 找回联系方式（半加密存储）与找回密码验证、登录提醒。
 * <p>找回密码 start/verify 为公开接口；设置/查看/提醒操作为登录接口（仅 USER / ORGANIZATION）。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "找回联系方式与找回密码", description = "找回手机号/邮箱的设置、提醒与验证。"
        + "找回密码流程目前仅实现联系方式验证，发送验证码与后续步骤留空。")
public class RecoveryController {
    private static final Set<String> SUPPORTED_PRINCIPALS = Set.of("USER", "ORGANIZATION");

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/password-recovery/start")
    @Operation(summary = "开始找回密码：返回可用找回方式与不加密前缀",
            description = "公开接口。按登录名查找账号，返回已设置的找回方式（含掩码后的不加密段，"
                    + "便于用户识别）。账号不存在或未设置方式时返回空列表。")
    public PasswordRecoveryStartResponse startRecovery(
            @Valid @RequestBody PasswordRecoveryStartRequest request) {
        return new PasswordRecoveryStartResponse(
                recoveryService.startRecovery(request.principalType(), request.loginName()));
    }

    @PostMapping("/password-recovery/verify")
    @Operation(summary = "补全联系方式并验证",
            description = "公开接口。用户根据掩码前缀补全完整手机号或邮箱后，按同规则重算比对。"
                    + "验证通过即完成当前阶段的找回；发送验证码与重置密码后续实现留空。")
    public PasswordRecoveryVerifyResponse verifyRecovery(
            @Valid @RequestBody PasswordRecoveryVerifyRequest request) {
        boolean verified = recoveryService.verifyRecovery(request.principalType(), request.loginName(),
                request.recoveryType(), request.fullContact());
        return new PasswordRecoveryVerifyResponse(verified);
    }

    @GetMapping("/me/recovery")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "查看我的找回联系方式状态",
            description = "登录接口。返回是否已设置找回联系方式、是否需要提醒，以及已设置方式的掩码前缀。")
    public RecoveryStatusResponse myRecovery(Authentication authentication) {
        AuthPrincipal principal = requirePrincipal(authentication);
        return status(principal.principalType(), principal.id());
    }

    @PutMapping("/me/recovery")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "设置或更新我的找回联系方式",
            description = "登录接口。recoveryPhone 与 recoveryEmail 至少填一个；两者均可不限次数更新。"
                    + "联系方式采用半加密两段存储：手机号后四位 / 邮箱 local 前 1/4 段明文，其余单向加密。")
    public RecoveryStatusResponse setRecovery(Authentication authentication,
                                              @Valid @RequestBody SetRecoveryRequest request) {
        AuthPrincipal principal = requirePrincipal(authentication);
        if ((request.recoveryPhone() == null || request.recoveryPhone().isBlank())
                && (request.recoveryEmail() == null || request.recoveryEmail().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请至少填写找回手机号或找回邮箱");
        }
        recoveryService.setContacts(principal.principalType(), principal.id(),
                request.recoveryPhone(), request.recoveryEmail());
        return status(principal.principalType(), principal.id());
    }

    @PostMapping("/me/recovery/remind-later")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "提醒“下次再说”",
            description = "登录接口。记录本次提醒时间，一周后（若仍未设置找回联系方式）登录时再次提醒。")
    public RecoveryActionResponse remindLater(Authentication authentication) {
        AuthPrincipal principal = requirePrincipal(authentication);
        recoveryService.remindLater(principal.principalType(), principal.id());
        return new RecoveryActionResponse(true);
    }

    @PostMapping("/me/recovery/dismiss")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "不再提醒",
            description = "登录接口。永久关闭找回联系方式设置提醒，之后登录不再提示。")
    public RecoveryActionResponse dismissReminder(Authentication authentication) {
        AuthPrincipal principal = requirePrincipal(authentication);
        recoveryService.dismissReminder(principal.principalType(), principal.id());
        return new RecoveryActionResponse(true);
    }

    private RecoveryStatusResponse status(String principalType, String principalId) {
        return new RecoveryStatusResponse(
                recoveryService.hasRecovery(principalType, principalId),
                recoveryService.reminderRequired(principalType, principalId),
                recoveryService.methods(principalType, principalId));
    }

    private AuthPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (!SUPPORTED_PRINCIPALS.contains(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号类型不支持找回联系方式设置");
        }
        return principal;
    }
}
