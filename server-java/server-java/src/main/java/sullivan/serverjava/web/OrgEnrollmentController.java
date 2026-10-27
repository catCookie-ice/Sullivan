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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.ActivityEnrollmentService;
import sullivan.serverjava.web.EnrollmentDtos.DecisionRequest;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentPage;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentResponse;

/**
 * 活动报名（机构端）：查看报名与人数统计、审核。
 * 群管理员即本机构，审核通过的志愿者进入活动群。
 */
@RestController
@RequestMapping("/api/v1/org")
@Tag(name = "活动报名（机构）", description = "报名审核与人数统计。")
@SecurityRequirement(name = "bearerAuth")
public class OrgEnrollmentController {
    private final ActivityEnrollmentService enrollmentService;

    public OrgEnrollmentController(ActivityEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @GetMapping("/activities/{activityId}/enrollments")
    @Operation(summary = "活动的报名列表",
            description = "含各状态统计：total 为总报名人数（含待审/已拒/已撤），approved 即当前占用名额。")
    public EnrollmentPage listEnrollments(
            @PathVariable String activityId,
            @Parameter(description = "状态过滤：PENDING / APPROVED / REJECTED / CANCELLED", example = "PENDING")
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        return enrollmentService.listForActivity(organization(authentication).id(), activityId, status, page, size);
    }

    @PostMapping("/activity-enrollments/{enrollmentId}/decision")
    @Operation(summary = "审核报名",
            description = "通过（approve=true）占用名额并把志愿者拉入活动群；满员返回 409，报名退回待审。")
    public EnrollmentResponse decide(
            @PathVariable String enrollmentId, @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        return enrollmentService.decide(organization(authentication).id(), enrollmentId,
                Boolean.TRUE.equals(request.approve()), request.reason());
    }

    private AuthPrincipal organization(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ORGANIZATION".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有公益机构可以审核报名");
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
