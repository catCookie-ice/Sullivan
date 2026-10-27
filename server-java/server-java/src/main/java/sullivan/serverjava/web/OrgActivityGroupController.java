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
import sullivan.serverjava.service.ActivityGroupService;
import sullivan.serverjava.web.GroupDtos.GroupMessagePage;
import sullivan.serverjava.web.GroupDtos.GroupMessageResponse;
import sullivan.serverjava.web.GroupDtos.GroupResponse;
import sullivan.serverjava.web.GroupDtos.ReportDecisionRequest;
import sullivan.serverjava.web.GroupDtos.ReportResponse;
import sullivan.serverjava.web.GroupDtos.SendMessageRequest;

import java.util.List;

/**
 * 活动群聊（机构端）：本机构是群管理员——发言、撤回消息、处理举报。
 */
@RestController
@RequestMapping("/api/v1/org")
@Tag(name = "活动群聊（机构）", description = "群列表、消息、撤回与举报处理；权限=群管理员机构。")
@SecurityRequirement(name = "bearerAuth")
public class OrgActivityGroupController {
    private final ActivityGroupService groupService;

    public OrgActivityGroupController(ActivityGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/activity-groups")
    @Operation(summary = "本机构的群列表", description = "本机构主办活动的群，含状态与成员数。")
    public List<GroupResponse> myGroups(Authentication authentication) {
        return groupService.orgGroups(organization(authentication).id());
    }

    @GetMapping("/activity-groups/{groupId}")
    @Operation(summary = "群详情", description = "须为本机构的群。")
    public GroupResponse groupDetail(@PathVariable String groupId, Authentication authentication) {
        return groupService.groupDetailForOrganization(organization(authentication).id(), groupId);
    }

    @GetMapping("/activity-groups/{groupId}/messages")
    @Operation(summary = "消息历史", description = "游标分页口径同志愿者端。")
    public GroupMessagePage messages(
            @PathVariable String groupId,
            @Parameter(description = "游标：上一页最早一条消息的发送时间")
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        return groupService.listMessagesForOrganization(organization(authentication).id(), groupId, before, limit);
    }

    @PostMapping("/activity-groups/{groupId}/messages")
    @Operation(summary = "机构发言", description = "只允许文字；群封闭后拒绝。")
    public ResponseEntity<GroupMessageResponse> sendMessage(
            @PathVariable String groupId, @Valid @RequestBody SendMessageRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.sendMessageAsOrganization(organization(authentication).id(), groupId,
                        request.content()));
    }

    @PostMapping("/group-messages/{messageId}/withdraw")
    @Operation(summary = "撤回消息", description = "群管理员撤回本群任意成员消息；撤回后所有人不再可见。")
    public ResponseEntity<Void> withdraw(@PathVariable String messageId, Authentication authentication) {
        groupService.withdrawMessage(organization(authentication).id(), messageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/activity-groups/{groupId}/reports")
    @Operation(summary = "本群的举报列表",
            description = "status 过滤：PENDING / RESOLVED / DISMISSED；缺省全部。")
    public List<ReportResponse> reports(
            @PathVariable String groupId,
            @Parameter(description = "状态过滤", example = "PENDING")
            @RequestParam(required = false) String status,
            Authentication authentication) {
        groupService.groupDetailForOrganization(organization(authentication).id(), groupId);
        return groupService.listReports(organization(authentication).id(), groupId, status);
    }

    @PostMapping("/group-message-reports/{reportId}/decision")
    @Operation(summary = "处理举报",
            description = "withdrawMessage=true 受理并撤回被举报消息（RESOLVED）；false 驳回（DISMISSED）。")
    public ReportResponse decideReport(
            @PathVariable String reportId, @Valid @RequestBody ReportDecisionRequest request,
            Authentication authentication) {
        return groupService.resolveReport(organization(authentication).id(), reportId,
                Boolean.TRUE.equals(request.withdrawMessage()), request.note());
    }

    private AuthPrincipal organization(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"ORGANIZATION".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有公益机构可以管理活动群聊");
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
