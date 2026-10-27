package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
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
import sullivan.serverjava.service.ActivityEnrollmentService;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentResponse;

/**
 * 活动报名（志愿者端）：报名、撤回、我的报名。
 * 名额只统计 APPROVED；开启「需审核」的活动先进 PENDING。
 */
@RestController
@Tag(name = "活动报名（志愿者）", description = "报名、撤回与我的报名记录。")
@SecurityRequirement(name = "bearerAuth")
public class ActivityEnrollmentController {
    private final ActivityEnrollmentService enrollmentService;

    public ActivityEnrollmentController(ActivityEnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping("/api/v1/activities/{activityId}/enrollments")
    @Operation(summary = "报名活动",
            description = "须志愿者身份；活动须在报名窗口内。未开「需审核」时直接通过并入活动群；"
                    + "开启后进 PENDING，等机构审核。名额满返回 409。")
    public ResponseEntity<EnrollmentResponse> enroll(
            @PathVariable String activityId, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(enrollmentService.enroll(user(authentication).id(), activityId));
    }

    @GetMapping("/api/v1/enrollments/mine")
    @Operation(summary = "我的报名列表", description = "按报名时间倒序分页。")
    public IPage<EnrollmentResponse> myEnrollments(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        return enrollmentService.myEnrollments(user(authentication).id(), page, size);
    }

    @PostMapping("/api/v1/enrollments/{enrollmentId}/cancel")
    @Operation(summary = "撤回报名",
            description = "PENDING 直接撤回；APPROVED 撤回后退出活动群并释放名额。")
    public EnrollmentResponse cancel(@PathVariable String enrollmentId, Authentication authentication) {
        return enrollmentService.cancel(user(authentication).id(), enrollmentId);
    }

    private AuthPrincipal user(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有登录的自然人可以报名活动");
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
