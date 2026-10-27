package sullivan.serverjava.web;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AdminPermissionRequestService;
import sullivan.serverjava.web.AdminPermissionRequestDtos.CreateRequest;
import sullivan.serverjava.web.AdminPermissionRequestDtos.DecisionRequest;
import sullivan.serverjava.web.AdminPermissionRequestDtos.PermissionOption;
import sullivan.serverjava.web.AdminPermissionRequestDtos.PermissionRequestResponse;

import java.util.List;

/**
 * 权限申请：**发起与查看自己的申请不要求任何权限**——只要是管理员即可；
 * 申请语义是「我需要这项权限」（只填权限与理由）。
 * 列表全量与审批须 ADMIN_GOVERN；批准后系统把申请人加入持有该权限的承载角色（自动生成）。
 */
@RestController
@RequestMapping("/api/v1/admin/permission-requests")
@Tag(name = "权限申请", description = "任意管理员可申请给自己开通权限；ADMIN_GOVERN 审批后落地。")
@SecurityRequirement(name = "bearerAuth")
public class AdminPermissionRequestController {
    private final AdminPermissionRequestService requestService;

    public AdminPermissionRequestController(AdminPermissionRequestService requestService) {
        this.requestService = requestService;
    }

    @PostMapping
    @Operation(summary = "发起权限申请", description = "任意管理员可用；只填权限与理由——申请的是给自己的权限，不涉及角色。")
    public ResponseEntity<PermissionRequestResponse> create(@Valid @RequestBody CreateRequest request,
                                                            Authentication authentication) {
        AuthPrincipal admin = anyAdmin(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(requestService.create(admin.id(), request.permissionCode(), request.reason()));
    }

    @GetMapping("/mine")
    @Operation(summary = "我的申请", description = "含审批状态与意见。")
    public List<PermissionRequestResponse> mine(Authentication authentication) {
        return requestService.mine(anyAdmin(authentication).id());
    }

    @GetMapping("/catalog")
    @Operation(summary = "权限字典", description = "申请表单下拉用；任意管理员可读。")
    public List<PermissionOption> catalog(Authentication authentication) {
        anyAdmin(authentication);
        return requestService.catalog();
    }

    @GetMapping
    @Operation(summary = "申请列表（全量）", description = "需 ADMIN_GOVERN。")
    public List<PermissionRequestResponse> list(@RequestParam(required = false) String status,
                                                Authentication authentication) {
        governor(authentication);
        return requestService.list(status);
    }

    @PostMapping("/{requestId}/decision")
    @Operation(summary = "审批申请", description = "批准即给申请人开通该权限（自动挂到承载角色）；不能审批自己发起的。需 ADMIN_GOVERN。")
    public ResponseEntity<Void> decide(@PathVariable String requestId,
                                       @Valid @RequestBody DecisionRequest request,
                                       Authentication authentication) {
        requestService.decide(governor(authentication).id(), requestId, request.approve(), request.note());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** 发起/查看只要求「是管理员」，不要求任何具体权限。 */
    private AuthPrincipal anyAdmin(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ADMIN".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有管理员可以发起权限申请");
        }
        return principal;
    }

    private AuthPrincipal governor(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ADMIN".equals(principal.principalType()) || !principal.hasPermission("ADMIN_GOVERN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要管理员治理权限（ADMIN_GOVERN）");
        }
        return principal;
    }

    private AuthPrincipal principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
