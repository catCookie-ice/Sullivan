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
import sullivan.serverjava.web.AbuseDtos.PenaltyResponse;

import java.util.List;

/** 机构侧举报治理：查看对自己的处罚并申诉。 */
@RestController
@RequestMapping("/api/v1/org")
@Tag(name = "举报治理（机构）", description = "机构处罚与申诉。")
@SecurityRequirement(name = "bearerAuth")
public class OrgAbuseController {
    private final AbuseService abuseService;

    public OrgAbuseController(AbuseService abuseService) {
        this.abuseService = abuseService;
    }

    @GetMapping("/penalties/mine")
    @Operation(summary = "机构的处罚", description = "生效中的处罚可申诉（每条处罚一次）。")
    public List<PenaltyResponse> myPenalties(Authentication authentication) {
        return abuseService.penaltiesFor("ORGANIZATION", organization(authentication).id());
    }

    @PostMapping("/penalties/{penaltyId}/appeal")
    @Operation(summary = "申诉处罚", description = "被处罚方可申诉一次；撤销后限制同步解除。")
    public ResponseEntity<AppealResponse> appeal(@PathVariable String penaltyId,
                                                 @Valid @RequestBody AppealCreateRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(abuseService.createAppeal(organization(authentication).id(), "ORGANIZATION",
                        penaltyId, request.reason()));
    }

    private AuthPrincipal organization(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ORGANIZATION".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有公益机构可以访问");
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
