package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.NotificationService;
import sullivan.serverjava.web.NotificationDtos.NotificationPage;
import sullivan.serverjava.web.NotificationDtos.UnreadCountResponse;

/**
 * 站内通知（导航栏铃铛）：未读数、列表、单条已读、全部已读。
 * 志愿者（USER）与机构（ORGANIZATION）共用同一组端点，按令牌的 principalType 区分收件箱；
 * 管理员当前没有通知来源，接口对其返回空列表。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "站内通知", description = "铃铛角标、通知列表与已读。")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读通知数", description = "铃铛角标；上限 99+ 由前端展示层处理。")
    public UnreadCountResponse unreadCount(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        return notificationService.unreadCount(principal.id(), principal.principalType());
    }

    @GetMapping
    @Operation(summary = "通知列表", description = "游标分页，最新在前；before=上一页最早一条的创建时间。")
    public NotificationPage list(
            @Parameter(description = "游标：上一页最早一条的创建时间")
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        return notificationService.list(principal.id(), principal.principalType(), before, limit);
    }

    @PostMapping("/{notificationId}/read")
    @Operation(summary = "标记已读", description = "只能标记自己的通知；重复标记幂等成功。")
    public ResponseEntity<Void> markRead(@PathVariable String notificationId, Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        notificationService.markRead(principal.id(), principal.principalType(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "全部已读", description = "把本人所有未读通知置为已读。")
    public ResponseEntity<Void> markAllRead(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        notificationService.markAllRead(principal.id(), principal.principalType());
        return ResponseEntity.noContent().build();
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
