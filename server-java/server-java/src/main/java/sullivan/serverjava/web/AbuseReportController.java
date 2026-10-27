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
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.AbuseService;
import sullivan.serverjava.web.AbuseDtos.AppealCreateRequest;
import sullivan.serverjava.web.AbuseDtos.AppealResponse;
import sullivan.serverjava.web.AbuseDtos.CounterReportRequest;
import sullivan.serverjava.web.AbuseDtos.CreateReportRequest;
import sullivan.serverjava.web.AbuseDtos.PenaltyResponse;
import sullivan.serverjava.web.AbuseDtos.ReceivedReportResponse;
import sullivan.serverjava.web.AbuseDtos.ReportResponse;

import java.util.List;

/**
 * 志愿者侧举报治理：发起举报（活动/机构/群聊/个人）、匿名反诉、
 * 查看针对自己的举报（举报人匿名）与对自己的处罚、申诉。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "举报治理（志愿者）", description = "举报、反诉、我的处罚与申诉。")
@SecurityRequirement(name = "bearerAuth")
public class AbuseReportController {
    private final AbuseService abuseService;

    public AbuseReportController(AbuseService abuseService) {
        this.abuseService = abuseService;
    }

    @PostMapping("/abuse-reports")
    @Operation(summary = "发起举报", description = "类别：ACTIVITY / ORGANIZATION / GROUP / USER；同一对象同时只有一条待处理举报。")
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(abuseService.createReport(user(authentication).id(), request.category(),
                        request.targetId(), request.reason()));
    }

    @PostMapping("/abuse-reports/{sourceReportId}/counter")
    @Operation(summary = "反向举报（匿名反诉）",
            description = "针对自己的举报被驳回后可反诉恶意举报；系统以你名义生成，对方不会知道你是谁。")
    public ResponseEntity<ReportResponse> counter(@PathVariable String sourceReportId,
                                                  @Valid @RequestBody CounterReportRequest request,
                                                  Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(abuseService.counterReport(user(authentication).id(), sourceReportId, request.reason()));
    }

    @GetMapping("/abuse-reports/mine")
    @Operation(summary = "我发起的举报")
    public List<ReportResponse> mine(Authentication authentication) {
        return abuseService.myReports(user(authentication).id());
    }

    @GetMapping("/abuse-reports/received")
    @Operation(summary = "针对我的举报（仅被驳回的）",
            description = "只返回被驳回的举报，供反诉；举报人匿名，不下发处置意见。")
    public List<ReceivedReportResponse> received(Authentication authentication) {
        return abuseService.receivedReports(user(authentication).id());
    }

    @GetMapping("/penalties/mine")
    @Operation(summary = "我的处罚", description = "生效中的处罚可申诉（每条处罚一次）。")
    public List<PenaltyResponse> myPenalties(Authentication authentication) {
        return abuseService.penaltiesFor("USER", user(authentication).id());
    }

    @PostMapping("/penalties/{penaltyId}/appeal")
    @Operation(summary = "申诉处罚", description = "被处罚方可申诉一次；撤销后限制同步解除。")
    public ResponseEntity<AppealResponse> appeal(@PathVariable String penaltyId,
                                                 @Valid @RequestBody AppealCreateRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(abuseService.createAppeal(user(authentication).id(), "USER", penaltyId, request.reason()));
    }

    private AuthPrincipal user(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有登录的自然人可以发起举报与申诉");
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
