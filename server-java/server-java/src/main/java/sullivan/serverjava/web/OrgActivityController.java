package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.VolunteerActivityService;
import sullivan.serverjava.web.ActivityDtos.ActivityCreateRequest;
import sullivan.serverjava.web.ActivityDtos.ActivityResponse;

/**
 * 公益活动：机构发布（APPROVED）、我的活动、公开活动列表。
 */
@RestController
@RequestMapping("/api/v1/activities")
@Tag(name = "公益活动", description = "创建/我的活动需机构登录；公开列表任何已登录用户可读。")
@SecurityRequirement(name = "bearerAuth")
public class OrgActivityController {
    private final VolunteerActivityService activityService;

    public OrgActivityController(VolunteerActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping
    @Operation(summary = "机构创建活动",
            description = "机构须为 APPROVED。提供活动名、说明（志愿者要求）、地点（文本+高德经纬度）、"
                    + "活动开始/结束时间、报名开始/结束时间、需要人数。")
    public ResponseEntity<ActivityResponse> createActivity(
            Authentication authentication, @Valid @RequestBody ActivityCreateRequest request) {
        AuthPrincipal principal = organization(authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(activityService.create(principal.id(), request));
    }

    @GetMapping("/mine")
    @Operation(summary = "我的活动列表", description = "按状态分页列出所属机构的活动，归属强制为令牌主体。")
    public IPage<ActivityResponse> listMyActivities(
            @Parameter(description = "状态过滤：RECRUITING / IN_PROGRESS / ENDED / CANCELLED", example = "RECRUITING")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        return activityService.listMy(organization(authentication).id(), status, page, size);
    }

    @GetMapping
    @Operation(summary = "公开活动列表",
            description = "任何已登录用户可读；默认只返回 RECRUITING（招募中）活动，不含报名者清单。")
    public IPage<ActivityResponse> listPublicActivities(
            @Parameter(description = "状态过滤（默认 RECRUITING）", example = "RECRUITING")
            @RequestParam(required = false) String status,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数（最大 200）", example = "20")
            @RequestParam(defaultValue = "20") long size,
            Authentication authentication) {
        requireLoggedIn(authentication);
        return activityService.listPublic(status, page, size);
    }

    private AuthPrincipal organization(Authentication authentication) {
        AuthPrincipal principal = requireLoggedIn(authentication);
        if (!"ORGANIZATION".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有公益机构可以创建和管理活动");
        }
        return principal;
    }

    private AuthPrincipal requireLoggedIn(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return principal;
    }
}
