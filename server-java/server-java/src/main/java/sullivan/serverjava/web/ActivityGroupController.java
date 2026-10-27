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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.security.AuthPrincipal;
import sullivan.serverjava.service.ActivityGroupService;
import sullivan.serverjava.web.GroupDtos.GroupMemberResponse;
import sullivan.serverjava.web.GroupDtos.GroupMessagePage;
import sullivan.serverjava.web.GroupDtos.GroupMessageResponse;
import sullivan.serverjava.web.GroupDtos.GroupResponse;
import sullivan.serverjava.web.GroupDtos.ReportRequest;
import sullivan.serverjava.web.GroupDtos.ReportResponse;
import sullivan.serverjava.web.GroupDtos.SendMessageRequest;

import java.util.List;

/**
 * 活动群聊（志愿者端）：我的群、消息（只允许文字）、成员与举报。
 * 机构是群管理员，走 /api/v1/org/activity-groups。
 */
@RestController
@Tag(name = "活动群聊（志愿者）", description = "群列表、消息收发（仅文字）、举报。")
@SecurityRequirement(name = "bearerAuth")
public class ActivityGroupController {
    private final ActivityGroupService groupService;

    public ActivityGroupController(ActivityGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping("/api/v1/activity-groups/mine")
    @Operation(summary = "我的活动群", description = "报名通过的活动的群，含群状态（活动结束自动封闭）。")
    public List<GroupResponse> myGroups(Authentication authentication) {
        return groupService.myGroups(user(authentication).id());
    }

    @GetMapping("/api/v1/activity-groups/{groupId}")
    @Operation(summary = "群详情", description = "须为群成员。")
    public GroupResponse groupDetail(@PathVariable String groupId, Authentication authentication) {
        return groupService.groupDetailForVolunteer(user(authentication).id(), groupId);
    }

    @GetMapping("/api/v1/activity-groups/{groupId}/messages")
    @Operation(summary = "消息历史",
            description = "须为群成员。游标分页：before 传上一页最早一条的发送时间，返回该时刻之前"
                    + "（不含）的消息，按时间升序；before 为空从最新开始。已撤回的消息不下发。")
    public GroupMessagePage messages(
            @PathVariable String groupId,
            @Parameter(description = "游标：上一页最早一条消息的发送时间（yyyy-MM-dd HH:mm:ss 或 ISO）")
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        return groupService.listMessagesForVolunteer(user(authentication).id(), groupId, before, limit);
    }

    @PostMapping("/api/v1/activity-groups/{groupId}/messages")
    @Operation(summary = "发送消息",
            description = "只允许文字（1-2000 字）；命中违禁词直接拦截（400），消息不落库。群封闭后拒绝。")
    public ResponseEntity<GroupMessageResponse> sendMessage(
            @PathVariable String groupId, @Valid @RequestBody SendMessageRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groupService.sendMessageAsVolunteer(user(authentication).id(), groupId, request.content()));
    }

    @GetMapping("/api/v1/activity-groups/{groupId}/members")
    @Operation(summary = "群成员", description = "须为群成员；机构管理员不落成员行。")
    public List<GroupMemberResponse> members(@PathVariable String groupId, Authentication authentication) {
        groupService.groupDetailForVolunteer(user(authentication).id(), groupId);
        return groupService.listMembers(groupId);
    }

    @PostMapping("/api/v1/group-messages/{messageId}/report")
    @Operation(summary = "举报消息", description = "群成员可举报同群消息，由群管理员机构处理；同一消息同时只允许一条待处理举报。")
    public ReportResponse report(
            @PathVariable String messageId, @Valid @RequestBody ReportRequest request,
            Authentication authentication) {
        return groupService.reportMessage(user(authentication).id(), messageId, request.reason());
    }

    @PostMapping("/api/v1/group-messages/{messageId}/withdraw")
    @Operation(summary = "撤回自己的消息",
            description = "只能撤回自己发送的消息，且在发出后的撤回时限内（默认 30 分钟）；撤回后群里显示「xx 撤回了一条消息」。")
    public ResponseEntity<Void> withdrawOwn(@PathVariable String messageId, Authentication authentication) {
        groupService.withdrawOwnMessage(user(authentication).id(), messageId);
        return ResponseEntity.noContent().build();
    }

    private AuthPrincipal user(Authentication authentication) {
        AuthPrincipal principal = principal(authentication);
        if (!"USER".equals(principal.principalType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有登录的自然人可以访问活动群聊");
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
