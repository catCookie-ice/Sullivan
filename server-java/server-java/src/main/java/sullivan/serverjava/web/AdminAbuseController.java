package sullivan.serverjava.web;

import com.baomidou.mybatisplus.core.metadata.IPage;
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
import sullivan.serverjava.service.AbuseService;
import sullivan.serverjava.web.AbuseDtos.AdminReportResponse;
import sullivan.serverjava.web.AbuseDtos.AppealDecisionRequest;
import sullivan.serverjava.web.AbuseDtos.AppealResponse;
import sullivan.serverjava.web.AbuseDtos.PenaltyDecisionRequest;
import sullivan.serverjava.web.AbuseDtos.PresetApprovalRequest;
import sullivan.serverjava.web.AbuseDtos.PresetRequest;
import sullivan.serverjava.web.AbuseDtos.PresetResponse;
import sullivan.serverjava.web.AbuseDtos.TargetHistoryResponse;

import java.util.List;

/**
 * 举报治理工作台（管理员）：举报处置、目标档案、预设处罚审批、申诉处理。
 * 仅 {@code ABUSE_REVIEW} 权限的管理员可访问——这就是「举报治理管理员」角色的权限码。
 */
@RestController
@RequestMapping("/api/v1/admin/abuse")
@Tag(name = "举报治理（管理员）", description = "举报处置、预设处罚审批、申诉处理、目标档案。")
@SecurityRequirement(name = "bearerAuth")
public class AdminAbuseController {
    private final AbuseService abuseService;

    public AdminAbuseController(AbuseService abuseService) {
        this.abuseService = abuseService;
    }

    @GetMapping("/reports")
    @Operation(summary = "举报列表", description = "按状态/类别筛选，倒序分页。")
    public IPage<AdminReportResponse> reports(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              Authentication authentication) {
        reviewer(authentication);
        return abuseService.adminList(status, category, page, size);
    }

    @GetMapping("/reports/grouped")
    @Operation(summary = "举报列表（按对象合并）", description = "同一对象的举报合并为一条（含计数与最新状态），展开看每条举报。")
    public List<AbuseDtos.MergedReportGroup> reportsGrouped(@RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String category,
                                                            Authentication authentication) {
        reviewer(authentication);
        return abuseService.adminListGrouped(status, category);
    }

    @GetMapping("/targets/history")
    @Operation(summary = "目标档案", description = "被举报对象（活动/机构/群聊/个人）的历史被举报与违规记录。")
    public TargetHistoryResponse history(@RequestParam String category,
                                         @RequestParam String targetId,
                                         Authentication authentication) {
        reviewer(authentication);
        return abuseService.targetHistory(category, targetId);
    }

    @PostMapping("/reports/{reportId}/decision")
    @Operation(summary = "处置举报",
            description = "驳回（触发对举报人的阈值评估），或从 ACTIVE 预设处罚中选择一项施加。")
    public ResponseEntity<Void> decide(@PathVariable String reportId,
                                       @Valid @RequestBody PenaltyDecisionRequest request,
                                       Authentication authentication) {
        abuseService.decide(reviewer(authentication).id(), reportId, request.dismiss(),
                request.presetId(), request.note());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/presets")
    @Operation(summary = "预设处罚列表", description = "含待审批申请；处置时只能选用 ACTIVE 项。")
    public List<PresetResponse> presets(@RequestParam(required = false) String status,
                                        Authentication authentication) {
        reviewer(authentication);
        return abuseService.listPresets(status);
    }

    @PostMapping("/presets")
    @Operation(summary = "申请新增预设处罚", description = "进入待审批；不能自批，须另一名管理员批准后生效。")
    public ResponseEntity<PresetResponse> createPreset(@Valid @RequestBody PresetRequest request,
                                                       Authentication authentication) {
        PresetResponse response = abuseService.createPreset(reviewer(authentication).id(), request.name(),
                request.category(), request.action(), request.durationDays(), request.parts(),
                request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/presets/{presetId}/approval")
    @Operation(summary = "审批预设处罚", description = "批准 → ACTIVE；驳回 → REJECTED。不能审批自己申请的。")
    public ResponseEntity<Void> approvePreset(@PathVariable String presetId,
                                              @Valid @RequestBody PresetApprovalRequest request,
                                              Authentication authentication) {
        abuseService.approvePreset(reviewer(authentication).id(), presetId, request.approve());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/appeals")
    @Operation(summary = "申诉列表", description = "按状态筛选，倒序。")
    public List<AppealResponse> appeals(@RequestParam(required = false) String status,
                                        Authentication authentication) {
        reviewer(authentication);
        return abuseService.adminListAppeals(status);
    }

    @PostMapping("/appeals/{appealId}/decision")
    @Operation(summary = "处理申诉", description = "维持处罚，或撤销（联动解除限制、恢复被禁用账户）。")
    public ResponseEntity<Void> decideAppeal(@PathVariable String appealId,
                                             @Valid @RequestBody AppealDecisionRequest request,
                                             Authentication authentication) {
        abuseService.handleAppeal(reviewer(authentication).id(), appealId, request.uphold(), request.note());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private AuthPrincipal reviewer(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ADMIN".equals(principal.principalType()) || !principal.hasPermission("ABUSE_REVIEW")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "需要举报治理管理员权限（ABUSE_REVIEW）");
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
