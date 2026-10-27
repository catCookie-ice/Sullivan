package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.common.TimeUtil;
import sullivan.serverjava.domain.ActivityGroup;
import sullivan.serverjava.domain.ActivityGroupMember;
import sullivan.serverjava.domain.ActivityGroupMessage;
import sullivan.serverjava.domain.ActivityGroupReport;
import sullivan.serverjava.domain.BannedWord;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.ActivityGroupMapper;
import sullivan.serverjava.mapper.ActivityGroupMemberMapper;
import sullivan.serverjava.mapper.ActivityGroupMessageMapper;
import sullivan.serverjava.mapper.ActivityGroupReportMapper;
import sullivan.serverjava.mapper.BannedWordMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;
import sullivan.serverjava.web.GroupDtos.GroupMemberResponse;
import sullivan.serverjava.web.GroupDtos.GroupMessagePage;
import sullivan.serverjava.web.GroupDtos.GroupMessageResponse;
import sullivan.serverjava.web.GroupDtos.GroupResponse;
import sullivan.serverjava.web.GroupDtos.ReportResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动群聊：报名通过的志愿者共同入群，管理员即主办机构账户（不落成员行）。
 *
 * <p><b>三条硬规则</b>：只允许文字消息（消息表本就没有媒体字段，发送入口只收文字）；
 * 违禁词在发送时拦截（平台级 banned_word 表，包含匹配）；活动结束群自动封闭——
 * 定时任务每分钟扫描兜底，发送入口同时实时校验活动是否已到点，两道闸门缺一不可：
 * 只靠定时任务会有「到点与扫描之间」的窗口，只靠实时校验则群状态永远停在 OPEN。</p>
 *
 * <p><b>消息的读取是 REST + 轮询</b>，不复用 1:1 实时会话的 WebSocket（那是按
 * 「会话两个参与人」建模的握手，群广播要另建连接模型，属扩展点）；
 * 消息一律落库，离线成员拉历史不丢。</p>
 */
@Service
public class ActivityGroupService {

    private static final Logger log = LoggerFactory.getLogger(ActivityGroupService.class);

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String CLOSED_REASON_ACTIVITY_ENDED = "活动已结束，群聊自动封闭";

    /** 撤回时限（分钟）：机构撤回与志愿者自撤共用一条规则，超窗一律拒绝。 */
    @Value("${app.activity-group.message-withdraw-window-minutes:30}")
    private long messageWithdrawWindowMinutes;

    private final ActivityGroupMapper groupMapper;
    private final ActivityGroupMemberMapper memberMapper;
    private final ActivityGroupMessageMapper messageMapper;
    private final ActivityGroupReportMapper reportMapper;
    private final BannedWordMapper bannedWordMapper;
    private final VolunteerActivityMapper activityMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final OrganizationMapper organizationMapper;
    private final NotificationService notificationService;

    public ActivityGroupService(ActivityGroupMapper groupMapper, ActivityGroupMemberMapper memberMapper,
                                ActivityGroupMessageMapper messageMapper, ActivityGroupReportMapper reportMapper,
                                BannedWordMapper bannedWordMapper, VolunteerActivityMapper activityMapper,
                                UserAccountMapper userAccountMapper, UserProfileMapper userProfileMapper,
                                OrganizationMapper organizationMapper, NotificationService notificationService) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.messageMapper = messageMapper;
        this.reportMapper = reportMapper;
        this.bannedWordMapper = bannedWordMapper;
        this.activityMapper = activityMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.organizationMapper = organizationMapper;
        this.notificationService = notificationService;
    }

    // ---------- 群的创建与成员 ----------

    /**
     * 取活动群，没有则建（activity_id 唯一键兜底并发：撞键说明对方已建，重读即可）。
     * 惰性建群：首个报名通过的志愿者触发，避免每个活动都留一个空群。
     */
    @Transactional
    public ActivityGroup ensureGroup(VolunteerActivity activity) {
        ActivityGroup existing = groupByActivityId(activity.getId());
        if (existing != null) {
            return existing;
        }
        ActivityGroup group = new ActivityGroup();
        group.setId(IdGenerator.next());
        group.setStatus("OPEN");
        group.setActivityId(activity.getId());
        group.setOrganizationId(activity.getOrganizationId());
        try {
            groupMapper.insert(group);
        } catch (DuplicateKeyException exception) {
            ActivityGroup winner = groupByActivityId(activity.getId());
            if (winner == null) {
                throw exception;
            }
            return winner;
        }
        return group;
    }

    /** 报名通过后入群；幂等（已在群则什么都不做）。 */
    @Transactional
    public void joinActivityGroup(VolunteerActivity activity, String volunteerUid) {
        ActivityGroup group = ensureGroup(activity);
        Long active = memberMapper.selectCount(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getVolunteerUid, volunteerUid)
                .eq(ActivityGroupMember::getStatus, "ACTIVE"));
        if (active > 0) {
            return;
        }
        ActivityGroupMember member = new ActivityGroupMember();
        member.setId(IdGenerator.next());
        member.setStatus("ACTIVE");
        member.setGroupId(group.getId());
        member.setVolunteerUid(volunteerUid);
        member.setJoinedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException exception) {
            // 并发重复入群：唯一键兜底，幂等处理。
        }
    }

    /** 报名撤回/移出时出群；群尚不存在就是没人通过过报名，无事可做。 */    @Transactional
    public void removeFromActivityGroup(String activityId, String volunteerUid) {
        ActivityGroup group = groupByActivityId(activityId);
        if (group == null) {
            return;
        }
        memberMapper.update(null, Wrappers.<ActivityGroupMember>lambdaUpdate()
                .set(ActivityGroupMember::getStatus, "REMOVED")
                .setSql("version = version + 1")
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getVolunteerUid, volunteerUid)
                .eq(ActivityGroupMember::getStatus, "ACTIVE"));
    }

    /**
     * 按活动封闭群聊（条件更新，幂等）：活动被下架/取消时由治理链路调用。
     * 群不存在（从未通过过报名）或已封闭都是无事可做。
     */
    @Transactional
    public void closeGroupOfActivity(String activityId, String reason) {
        ActivityGroup group = groupByActivityId(activityId);
        if (group == null) {
            return;
        }
        groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                .set(ActivityGroup::getStatus, "CLOSED")
                .set(ActivityGroup::getClosedAt, LocalDateTime.now())
                .set(ActivityGroup::getClosedReason, reason)
                .setSql("version = version + 1")
                .eq(ActivityGroup::getId, group.getId())
                .in(ActivityGroup::getStatus, "OPEN", "SUSPENDED"));
    }

    /**
     * 按活动暂时封禁群聊（OPEN → SUSPENDED，带到期时间）：活动被暂时下架时由治理链路调用。
     * 已封闭的群不再变（永久优先于暂时）；已在暂时封禁中则刷新到期时间。
     */
    @Transactional
    public void suspendGroupOfActivity(String activityId, LocalDateTime until) {
        ActivityGroup group = groupByActivityId(activityId);
        if (group == null || "CLOSED".equals(group.getStatus())) {
            return;
        }
        groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                .set(ActivityGroup::getStatus, "SUSPENDED")
                .set(ActivityGroup::getSuspendedUntil, until)
                .setSql("version = version + 1")
                .eq(ActivityGroup::getId, group.getId())
                .in(ActivityGroup::getStatus, "OPEN", "SUSPENDED"));
    }

    /** 按群聊 id 暂时封禁（群聊处罚用），语义同上。 */
    @Transactional
    public void suspendGroupById(String groupId, LocalDateTime until) {
        groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                .set(ActivityGroup::getStatus, "SUSPENDED")
                .set(ActivityGroup::getSuspendedUntil, until)
                .setSql("version = version + 1")
                .eq(ActivityGroup::getId, groupId)
                .in(ActivityGroup::getStatus, "OPEN", "SUSPENDED"));
    }

    /** 按群聊 id 永久封闭（群聊处罚「永久封禁并下架活动」用）；已封闭/暂时封禁的群同样收敛为 CLOSED。 */
    @Transactional
    public void closeGroupById(String groupId, String reason) {
        groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                .set(ActivityGroup::getStatus, "CLOSED")
                .set(ActivityGroup::getClosedAt, LocalDateTime.now())
                .set(ActivityGroup::getClosedReason, reason)
                .setSql("version = version + 1")
                .eq(ActivityGroup::getId, groupId)
                .in(ActivityGroup::getStatus, "OPEN", "SUSPENDED"));
    }

    /**
     * 恢复一个暂时封禁的群聊：活动还在进行 → OPEN；活动已结束/取消 → 直接 CLOSED
     * （不必等下一轮生命周期扫描，恢复即终态）。幂等：非 SUSPENDED 不动。
     */
    @Transactional
    public void resumeGroupById(String groupId) {
        ActivityGroup group = groupMapper.selectById(groupId);
        if (group == null || !"SUSPENDED".equals(group.getStatus())) {
            return;
        }
        VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
        boolean activityAlive = activity != null
                && ("RECRUITING".equals(activity.getStatus()) || "IN_PROGRESS".equals(activity.getStatus())
                || "SUSPENDED".equals(activity.getStatus()))
                && activity.getEndsAt().isAfter(LocalDateTime.now());
        String next = activityAlive ? "OPEN" : "CLOSED";
        groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                .set(ActivityGroup::getStatus, next)
                .set(ActivityGroup::getSuspendedUntil, null)
                .setSql("version = version + 1")
                .eq(ActivityGroup::getId, groupId)
                .eq(ActivityGroup::getStatus, "SUSPENDED"));
    }

    // ---------- 消息 ----------

    /** 机构（群管理员）发消息。 */
    @Transactional
    public GroupMessageResponse sendMessageAsOrganization(String organizationId, String groupId, String content) {
        ActivityGroup group = loadGroup(groupId);
        if (!group.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有群管理员机构可以在群内发言");
        }
        return send(group, organizationId, "ORGANIZATION", content);
    }

    /** 志愿者成员发消息。 */
    @Transactional
    public GroupMessageResponse sendMessageAsVolunteer(String volunteerUid, String groupId, String content) {
        ActivityGroup group = loadGroup(groupId);
        requireMember(group, volunteerUid);
        return send(group, volunteerUid, "VOLUNTEER", content);
    }

    private GroupMessageResponse send(ActivityGroup group, String senderUid, String senderType, String content) {
        requireGroupOpen(group);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "消息不能超过 " + MAX_MESSAGE_LENGTH + " 字");
        }
        String hit = findBannedWord(text);
        if (hit != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "消息包含违禁词「" + hit + "」，已被拦截");
        }
        ActivityGroupMessage message = new ActivityGroupMessage();
        message.setId(IdGenerator.next());
        message.setStatus("NORMAL");
        message.setGroupId(group.getId());
        message.setSenderUid(senderUid);
        message.setSenderType(senderType);
        message.setContent(text);
        messageMapper.insert(message);
        return toMessageResponse(message);
    }

    /**
     * 违禁词拦截：平台级词表做包含匹配，命中即拒发（消息不落库）。
     * 词表当前规模很小，每次发送全量加载；词多了再上缓存。
     */
    private String findBannedWord(String content) {
        List<BannedWord> words = bannedWordMapper.selectList(Wrappers.<BannedWord>lambdaQuery()
                .eq(BannedWord::getStatus, "ENABLED"));
        for (BannedWord word : words) {
            if (word.getWord() != null && content.contains(word.getWord())) {
                return word.getWord();
            }
        }
        return null;
    }

    /**
     * 群可发言 = 群 OPEN 且活动未结束未取消且未到结束时刻。
     * 结束时刻用实时比较而非只看群状态：定时扫描有间隙，这里不给窗口留机会。
     */
    private void requireGroupOpen(ActivityGroup group) {
        if ("SUSPENDED".equals(group.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "群聊已被暂时封禁，暂停发言");
        }
        if (!"OPEN".equals(group.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "群聊已封闭，不能再发言");
        }
        VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
        if (activity == null || "ENDED".equals(activity.getStatus()) || "CANCELLED".equals(activity.getStatus())
                || !activity.getEndsAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已结束，群聊已封闭");
        }
    }

    /**
     * 消息历史（游标分页）：{@code before} 为页内最早一条的发送时间，为空时从最新开始。
     * 返回按时间升序；主键是随机 UUID 不能当时间游标，按 created_at 走
     * （同一毫秒内的兄弟消息在翻页边界可能被跳过，对聊天场景可接受）。
     * 已撤回的消息以「墓碑」形式下发（withdrawn=true、content 为空串），前端显示
     * 「xx 撤回了一条消息」——聊天里凭空消失一条比显示墓碑更让人困惑。
     */
    public GroupMessagePage listMessages(String groupId, String before, int limit) {
        ActivityGroup group = loadGroup(groupId);
        LocalDateTime beforeAt = before == null || before.isBlank() ? null : TimeUtil.parseDateTimeLenient(before);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<ActivityGroupMessage> rows = messageMapper.selectList(Wrappers.<ActivityGroupMessage>lambdaQuery()
                .eq(ActivityGroupMessage::getGroupId, group.getId())
                .lt(beforeAt != null, ActivityGroupMessage::getCreatedAt, beforeAt)
                .orderByDesc(ActivityGroupMessage::getCreatedAt)
                .last("LIMIT " + (safeLimit + 1)));
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        List<ActivityGroupMessage> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        return new GroupMessagePage(ordered.stream().map(this::toMessageResponse).toList(), hasMore);
    }

    /** 志愿者读消息：须为群成员。 */
    public GroupMessagePage listMessagesForVolunteer(String volunteerUid, String groupId, String before, int limit) {
        requireMember(loadGroup(groupId), volunteerUid);
        return listMessages(groupId, before, limit);
    }

    /** 机构读消息：须为群管理员。 */
    public GroupMessagePage listMessagesForOrganization(String organizationId, String groupId, String before, int limit) {
        requireOwner(loadGroup(groupId), organizationId);
        return listMessages(groupId, before, limit);
    }

    // ---------- 成员与群列表 ----------

    /** 群成员列表（仅志愿者；机构管理员不落行，由 organizationPublicName 体现）。 */
    public List<GroupMemberResponse> listMembers(String groupId) {
        ActivityGroup group = loadGroup(groupId);
        List<ActivityGroupMember> members = memberMapper.selectList(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getStatus, "ACTIVE")
                .orderByAsc(ActivityGroupMember::getJoinedAt));
        Set<String> uids = new HashSet<>();
        members.forEach(member -> uids.add(member.getVolunteerUid()));
        Map<String, UserAccount> accounts = accountsById(uids);
        Map<String, String> names = displayNamesById(uids);
        return members.stream().map(member -> new GroupMemberResponse(member.getId(), member.getVolunteerUid(),
                accountLoginName(accounts, member.getVolunteerUid()),
                names.getOrDefault(member.getVolunteerUid(),
                        accountLoginName(accounts, member.getVolunteerUid())),
                member.getJoinedAt())).toList();
    }

    /** 志愿者可见的群详情（须为成员）。 */
    public GroupResponse groupDetailForVolunteer(String volunteerUid, String groupId) {
        ActivityGroup group = loadGroup(groupId);
        requireMember(group, volunteerUid);
        return toGroupResponse(group);
    }

    /** 机构可见的群详情（须为本机构群）。 */
    public GroupResponse groupDetailForOrganization(String organizationId, String groupId) {
        ActivityGroup group = loadGroup(groupId);
        requireOwner(group, organizationId);
        return toGroupResponse(group);
    }

    /** 我的群列表（报名通过的活动的群）。 */
    public List<GroupResponse> myGroups(String volunteerUid) {
        List<ActivityGroupMember> memberships = memberMapper.selectList(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getVolunteerUid, volunteerUid)
                .eq(ActivityGroupMember::getStatus, "ACTIVE")
                .orderByDesc(ActivityGroupMember::getJoinedAt));
        return memberships.stream()
                .map(membership -> groupMapper.selectById(membership.getGroupId()))
                .filter(group -> group != null && isParticipant(group, volunteerUid))
                .map(this::toGroupResponse)
                .toList();
    }

    /** 机构的群列表（本机构主办活动的群）。 */
    public List<GroupResponse> orgGroups(String organizationId) {
        return groupMapper.selectList(Wrappers.<ActivityGroup>lambdaQuery()
                .eq(ActivityGroup::getOrganizationId, organizationId)
                .orderByDesc(ActivityGroup::getCreatedAt))
                .stream().map(this::toGroupResponse).toList();
    }

    /** 群资格复查：成员行的群必须仍然把此人记为 ACTIVE（防止读到脏的旧行）。 */
    private boolean isParticipant(ActivityGroup group, String volunteerUid) {
        return memberMapper.selectCount(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getVolunteerUid, volunteerUid)
                .eq(ActivityGroupMember::getStatus, "ACTIVE")) > 0;
    }

    // ---------- 撤回与举报 ----------

    /**
     * 撤回时限：机构撤回与志愿者自撤共用——消息发出后 {@code messageWithdrawWindowMinutes}
     * 分钟内可撤，超窗一律 409。没有时限的撤回等于可以悄悄改写群聊历史。
     */
    private void requireWithinWithdrawWindow(ActivityGroupMessage message) {
        if (message.getCreatedAt() == null) {
            return;
        }
        LocalDateTime deadline = message.getCreatedAt().plusMinutes(messageWithdrawWindowMinutes);
        if (LocalDateTime.now().isAfter(deadline)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "已超过可撤回时间（消息发出后 " + messageWithdrawWindowMinutes + " 分钟内可撤回）");
        }
    }

    /**
     * 群管理员（机构）撤回任意成员消息：WITHDRAWN 后正文不再对任何人下发（只留墓碑），
     * 并给群内志愿者发一条「机构撤回了一条消息」的站内通知。
     * 同样受撤回时限约束；处理举报时撤回超窗消息会 409，机构只能改判驳回。
     */
    @Transactional
    public void withdrawMessage(String organizationId, String messageId) {
        ActivityGroupMessage message = loadMessage(messageId);
        ActivityGroup group = loadGroup(message.getGroupId());
        requireOwner(group, organizationId);
        requireWithinWithdrawWindow(message);
        int updated = messageMapper.update(null, Wrappers.<ActivityGroupMessage>lambdaUpdate()
                .set(ActivityGroupMessage::getStatus, "WITHDRAWN")
                .set(ActivityGroupMessage::getWithdrawnAt, LocalDateTime.now())
                .set(ActivityGroupMessage::getWithdrawnBy, organizationId)
                .setSql("version = version + 1")
                .eq(ActivityGroupMessage::getId, messageId)
                .eq(ActivityGroupMessage::getStatus, "NORMAL"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "消息已撤回");
        }
        notifyMembersOfWithdrawal(group);
    }

    /**
     * 志愿者撤回自己发送的消息：与机构撤回同一时限、同一墓碑展示，但不发通知
     * （自己操作的，群成员刷新即见墓碑）。
     */
    @Transactional
    public void withdrawOwnMessage(String volunteerUid, String messageId) {
        ActivityGroupMessage message = loadMessage(messageId);
        if (!"VOLUNTEER".equals(message.getSenderType()) || !volunteerUid.equals(message.getSenderUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能撤回自己发送的消息");
        }
        requireWithinWithdrawWindow(message);
        int updated = messageMapper.update(null, Wrappers.<ActivityGroupMessage>lambdaUpdate()
                .set(ActivityGroupMessage::getStatus, "WITHDRAWN")
                .set(ActivityGroupMessage::getWithdrawnAt, LocalDateTime.now())
                .set(ActivityGroupMessage::getWithdrawnBy, volunteerUid)
                .setSql("version = version + 1")
                .eq(ActivityGroupMessage::getId, messageId)
                .eq(ActivityGroupMessage::getStatus, "NORMAL"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "消息已撤回");
        }
    }

    /** 机构撤回消息后通知群内全部在群志愿者（尽力而为，失败不影响撤回本身）。 */
    private void notifyMembersOfWithdrawal(ActivityGroup group) {
        VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
        String title = activity == null ? "活动群聊" : activity.getTitle();
        List<ActivityGroupMember> members = memberMapper.selectList(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getStatus, "ACTIVE"));
        for (ActivityGroupMember member : members) {
            notificationService.notify(member.getVolunteerUid(), "USER",
                    NotificationService.TYPE_GROUP_MESSAGE_WITHDRAWN,
                    "机构撤回了一条消息",
                    "群「" + title + "」中，机构撤回了一条消息",
                    "/volunteer/group-chat?activityId=" + group.getActivityId());
        }
    }

    /** 成员举报消息（同一成员对同一消息同时只允许一条 PENDING，唯一键兜底）。 */
    @Transactional
    public ReportResponse reportMessage(String reporterUid, String messageId, String reason) {
        ActivityGroupMessage message = loadMessage(messageId);
        if (!"NORMAL".equals(message.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该消息已撤回，无需举报");
        }
        ActivityGroup group = loadGroup(message.getGroupId());
        requireMember(group, reporterUid);
        Long duplicate = reportMapper.selectCount(Wrappers.<ActivityGroupReport>lambdaQuery()
                .eq(ActivityGroupReport::getMessageId, messageId)
                .eq(ActivityGroupReport::getReporterUid, reporterUid)
                .eq(ActivityGroupReport::getStatus, "PENDING"));
        if (duplicate > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "已举报过该消息，请等待处理");
        }
        ActivityGroupReport report = new ActivityGroupReport();
        report.setId(IdGenerator.next());
        report.setStatus("PENDING");
        report.setGroupId(group.getId());
        report.setMessageId(messageId);
        report.setReporterUid(reporterUid);
        report.setReason(reason == null ? "" : reason.strip());
        reportMapper.insert(report);
        return toReportResponse(reportMapper.selectById(report.getId()));
    }

    /** 机构查看本群收到的举报。 */
    public List<ReportResponse> listReports(String organizationId, String groupId, String status) {
        requireOwner(loadGroup(groupId), organizationId);
        return reportMapper.selectList(Wrappers.<ActivityGroupReport>lambdaQuery()
                .eq(ActivityGroupReport::getGroupId, groupId)
                .eq(status != null && !status.isBlank(), ActivityGroupReport::getStatus, status)
                .orderByDesc(ActivityGroupReport::getCreatedAt))
                .stream().map(this::toReportResponse).toList();
    }

    /** 机构处理举报：受理（撤回消息）或驳回。 */
    @Transactional
    public ReportResponse resolveReport(String organizationId, String reportId, boolean withdrawMessage, String note) {
        ActivityGroupReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "举报不存在");
        }
        requireOwner(loadGroup(report.getGroupId()), organizationId);
        if (!"PENDING".equals(report.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该举报已处理");
        }
        if (withdrawMessage) {
            withdrawMessage(organizationId, report.getMessageId());
        }
        int updated = reportMapper.update(null, Wrappers.<ActivityGroupReport>lambdaUpdate()
                .set(ActivityGroupReport::getStatus, withdrawMessage ? "RESOLVED" : "DISMISSED")
                .set(ActivityGroupReport::getHandledAt, LocalDateTime.now())
                .set(ActivityGroupReport::getHandledBy, organizationId)
                .set(ActivityGroupReport::getDecisionNote, note)
                .setSql("version = version + 1")
                .eq(ActivityGroupReport::getId, reportId)
                .eq(ActivityGroupReport::getStatus, "PENDING"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该举报已处理");
        }
        return toReportResponse(reportMapper.selectById(reportId));
    }

    // ---------- 定时任务：活动状态推进 + 群自动封闭 ----------

    /**
     * 每分钟扫描：开始到点的活动 RECRUITING→IN_PROGRESS；结束到点的活动→ENDED；
     * 活动已结束/取消（或已到点）的 OPEN 群逐个封闭（条件更新，重启可重入）。
     * 封闭后消息不可再发，历史仍可读——「封闭」关的是新消息，不是记忆。
     */
    @Scheduled(cron = "0 * * * * *")
    public void scheduledSweep() {
        sweepActivityLifecycle();
    }

    /** 扫描本体（暴露成普通方法便于单测直接调用）。 */
    public void sweepActivityLifecycle() {
        LocalDateTime now = LocalDateTime.now();
        activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .set(VolunteerActivity::getStatus, "IN_PROGRESS")
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getStatus, "RECRUITING")
                .le(VolunteerActivity::getStartsAt, now)
                .gt(VolunteerActivity::getEndsAt, now));
        int ended = activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .set(VolunteerActivity::getStatus, "ENDED")
                .setSql("version = version + 1")
                .in(VolunteerActivity::getStatus, "RECRUITING", "IN_PROGRESS")
                .le(VolunteerActivity::getEndsAt, now));
        int closed = 0;
        List<ActivityGroup> openGroups = groupMapper.selectList(Wrappers.<ActivityGroup>lambdaQuery()
                .eq(ActivityGroup::getStatus, "OPEN"));
        for (ActivityGroup group : openGroups) {
            VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
            boolean shouldClose = activity == null || "ENDED".equals(activity.getStatus())
                    || "CANCELLED".equals(activity.getStatus())
                    || !activity.getEndsAt().isAfter(now);
            if (!shouldClose) {
                continue;
            }
            closed += groupMapper.update(null, Wrappers.<ActivityGroup>lambdaUpdate()
                    .set(ActivityGroup::getStatus, "CLOSED")
                    .set(ActivityGroup::getClosedAt, now)
                    .set(ActivityGroup::getClosedReason, CLOSED_REASON_ACTIVITY_ENDED)
                    .setSql("version = version + 1")
                    .eq(ActivityGroup::getId, group.getId())
                    .eq(ActivityGroup::getStatus, "OPEN"));
        }
        if (ended > 0 || closed > 0) {
            log.info("activity lifecycle sweep: ended={} closedGroups={}", ended, closed);
        }
    }

    // ---------- 内部工具 ----------

    private ActivityGroup loadGroup(String groupId) {
        ActivityGroup group = groupId == null ? null : groupMapper.selectById(groupId);
        if (group == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "群不存在");
        }
        return group;
    }

    private ActivityGroup groupByActivityId(String activityId) {
        return groupMapper.selectOne(Wrappers.<ActivityGroup>lambdaQuery()
                .eq(ActivityGroup::getActivityId, activityId));
    }

    private ActivityGroupMessage loadMessage(String messageId) {
        ActivityGroupMessage message = messageId == null ? null : messageMapper.selectById(messageId);
        if (message == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "消息不存在");
        }
        return message;
    }

    private void requireMember(ActivityGroup group, String volunteerUid) {
        if (!isParticipant(group, volunteerUid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有群成员可以访问群聊");
        }
    }

    private void requireOwner(ActivityGroup group, String organizationId) {
        if (!group.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有群管理员机构可以执行该操作");
        }
    }

    private GroupResponse toGroupResponse(ActivityGroup group) {
        VolunteerActivity activity = activityMapper.selectById(group.getActivityId());
        Organization organization = organizationMapper.selectById(group.getOrganizationId());
        long memberCount = memberMapper.selectCount(Wrappers.<ActivityGroupMember>lambdaQuery()
                .eq(ActivityGroupMember::getGroupId, group.getId())
                .eq(ActivityGroupMember::getStatus, "ACTIVE"));
        return new GroupResponse(group.getId(), group.getActivityId(),
                activity == null ? null : activity.getTitle(),
                group.getOrganizationId(),
                organization == null ? null : organization.getPublicName(),
                group.getStatus(), activity == null ? null : activity.getStartsAt(),
                activity == null ? null : activity.getEndsAt(),
                memberCount, group.getCreatedAt());
    }

    private GroupMessageResponse toMessageResponse(ActivityGroupMessage message) {
        boolean withdrawn = "WITHDRAWN".equals(message.getStatus());
        // 已撤回不下发原文：撤回的意义就是「没人再看得到内容」。
        return new GroupMessageResponse(message.getId(), message.getGroupId(), message.getSenderUid(),
                message.getSenderType(), senderName(message), withdrawn ? "" : message.getContent(),
                message.getCreatedAt(), withdrawn);
    }

    /** 发送者展示名：志愿者取昵称（缺省登录名），机构取展示名。 */
    private String senderName(ActivityGroupMessage message) {
        if ("ORGANIZATION".equals(message.getSenderType())) {
            Organization organization = organizationMapper.selectById(message.getSenderUid());
            return organization == null ? message.getSenderUid() : organization.getPublicName();
        }
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, message.getSenderUid()));
        if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        UserAccount account = userAccountMapper.selectById(message.getSenderUid());
        return account == null ? message.getSenderUid() : account.getLoginName();
    }

    private ReportResponse toReportResponse(ActivityGroupReport report) {
        ActivityGroupMessage message = messageMapper.selectById(report.getMessageId());
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, report.getReporterUid()));
        String reporterName = profile != null && profile.getDisplayName() != null
                && !profile.getDisplayName().isBlank() ? profile.getDisplayName() : report.getReporterUid();
        return new ReportResponse(report.getId(), report.getMessageId(), report.getGroupId(), report.getReporterUid(),
                reporterName, message == null ? null : message.getContent(), report.getReason(),
                report.getStatus(), report.getDecisionNote(), report.getHandledAt(), report.getCreatedAt());
    }

    private Map<String, UserAccount> accountsById(Set<String> uids) {
        if (uids.isEmpty()) {
            return Map.of();
        }
        Map<String, UserAccount> result = new HashMap<>();
        userAccountMapper.selectBatchIds(uids).forEach(account -> result.put(account.getId(), account));
        return result;
    }

    private Map<String, String> displayNamesById(Set<String> uids) {
        if (uids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery().in(UserProfile::getUserId, uids))
                .forEach(profile -> {
                    if (profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
                        result.put(profile.getUserId(), profile.getDisplayName());
                    }
                });
        return result;
    }

    private String accountLoginName(Map<String, UserAccount> accounts, String uid) {
        UserAccount account = accounts.get(uid);
        return account == null ? uid : account.getLoginName();
    }
}
