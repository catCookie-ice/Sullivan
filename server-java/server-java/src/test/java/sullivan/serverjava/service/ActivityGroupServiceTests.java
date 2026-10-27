package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.ActivityGroup;
import sullivan.serverjava.domain.ActivityGroupMember;
import sullivan.serverjava.domain.ActivityGroupMessage;
import sullivan.serverjava.domain.ActivityGroupReport;
import sullivan.serverjava.domain.BannedWord;
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
import sullivan.serverjava.web.GroupDtos.GroupMessagePage;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityGroupServiceTests {
    private static final String VOLUNTEER = "vol-1";
    private static final String OTHER_VOLUNTEER = "vol-2";
    private static final String ORG = "org-1";
    private static final String ACTIVITY_ID = "act-1";
    private static final String GROUP_ID = "grp-1";
    private static final String MESSAGE_ID = "msg-1";

    private ActivityGroupMapper groupMapper;
    private ActivityGroupMemberMapper memberMapper;
    private ActivityGroupMessageMapper messageMapper;
    private ActivityGroupReportMapper reportMapper;
    private BannedWordMapper bannedWordMapper;
    private VolunteerActivityMapper activityMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private OrganizationMapper organizationMapper;
    private NotificationService notificationService;
    private ActivityGroupService service;

    @BeforeEach
    void setUp() {
        groupMapper = mock(ActivityGroupMapper.class);
        memberMapper = mock(ActivityGroupMemberMapper.class);
        messageMapper = mock(ActivityGroupMessageMapper.class);
        reportMapper = mock(ActivityGroupReportMapper.class);
        bannedWordMapper = mock(BannedWordMapper.class);
        activityMapper = mock(VolunteerActivityMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        notificationService = mock(NotificationService.class);
        service = new ActivityGroupService(groupMapper, memberMapper, messageMapper, reportMapper,
                bannedWordMapper, activityMapper, userAccountMapper, userProfileMapper, organizationMapper,
                notificationService);
        // 撤回时限走 @Value 注入，单测里手工置为默认值 30 分钟。
        ReflectionTestUtils.setField(service, "messageWithdrawWindowMinutes", 30L);
        // Lambda wrapper 需要实体的 TableInfo 缓存；mock mapper 不会注册它，测试里手工初始化。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ActivityGroup.class);
        TableInfoHelper.initTableInfo(assistant, ActivityGroupMember.class);
        TableInfoHelper.initTableInfo(assistant, ActivityGroupMessage.class);
        TableInfoHelper.initTableInfo(assistant, ActivityGroupReport.class);
        TableInfoHelper.initTableInfo(assistant, BannedWord.class);
        TableInfoHelper.initTableInfo(assistant, VolunteerActivity.class);
        TableInfoHelper.initTableInfo(assistant, sullivan.serverjava.domain.UserProfile.class);
    }

    // ---------- 群与成员 ----------

    @Test
    void ensureGroupCreatesOnceAndReuses() {
        when(groupMapper.selectOne(any())).thenReturn(null, openGroup());
        VolunteerActivity activity = activity();

        ActivityGroup created = service.ensureGroup(activity);
        ActivityGroup reused = service.ensureGroup(activity);

        assertThat(created.getOrganizationId()).isEqualTo(ORG);
        assertThat(reused.getId()).isEqualTo(GROUP_ID);
        verify(groupMapper, atLeastOnce()).insert(any(ActivityGroup.class));
    }

    @Test
    void joinActivityGroupIsIdempotent() {
        when(groupMapper.selectOne(any())).thenReturn(openGroup());
        when(memberMapper.selectCount(any())).thenReturn(0L, 1L);

        service.joinActivityGroup(activity(), VOLUNTEER);
        service.joinActivityGroup(activity(), VOLUNTEER);

        ArgumentCaptor<ActivityGroupMember> captor = ArgumentCaptor.forClass(ActivityGroupMember.class);
        verify(memberMapper, org.mockito.Mockito.times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVolunteerUid()).isEqualTo(VOLUNTEER);
    }

    // ---------- 发言权限与封闭 ----------

    @Test
    void memberCanSendAndOrgCanSendAsAdmin() {
        ActivityGroup group = openGroup();
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity());
        when(userProfileMapper.selectOne(any())).thenReturn(null);
        when(userAccountMapper.selectById(VOLUNTEER)).thenReturn(null);

        var memberMessage = service.sendMessageAsVolunteer(VOLUNTEER, GROUP_ID, "周六八点南门集合");
        var orgMessage = service.sendMessageAsOrganization(ORG, GROUP_ID, "请大家准时到场");

        assertThat(memberMessage.senderType()).isEqualTo("VOLUNTEER");
        assertThat(orgMessage.senderType()).isEqualTo("ORGANIZATION");
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(any(ActivityGroupMessage.class));
    }

    @Test
    void nonMemberCannotSendOrRead() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(memberMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.sendMessageAsVolunteer(OTHER_VOLUNTEER, GROUP_ID, "蹭个群"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.listMessagesForVolunteer(OTHER_VOLUNTEER, GROUP_ID, null, 50))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void foreignOrganizationCannotSend() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());

        assertThatThrownBy(() -> service.sendMessageAsOrganization("org-other", GROUP_ID, "我是外机构"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("群管理员");
    }

    /** 群状态还是 OPEN、但活动已到结束时刻：实时闸门必须拦住（不能等定时任务）。 */
    @Test
    void cannotSendAfterActivityEndTimeEvenIfGroupStillOpen() {
        ActivityGroup group = openGroup();
        VolunteerActivity activity = activity();
        activity.setEndsAt(LocalDateTime.now().minusMinutes(1));
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        assertThatThrownBy(() -> service.sendMessageAsVolunteer(VOLUNTEER, GROUP_ID, "都结束了还想说"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("活动已结束");
    }

    @Test
    void cannotSendToClosedGroup() {
        ActivityGroup group = openGroup();
        group.setStatus("CLOSED");
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.sendMessageAsVolunteer(VOLUNTEER, GROUP_ID, "封闭了还想说"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("封闭");
    }

    // ---------- 违禁词 ----------

    @Test
    void bannedWordBlocksSendAndDoesNotPersist() {
        ActivityGroup group = openGroup();
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity());
        BannedWord word = new BannedWord();
        word.setWord("转账");
        when(bannedWordMapper.selectList(any())).thenReturn(List.of(word));

        assertThatThrownBy(() -> service.sendMessageAsVolunteer(VOLUNTEER, GROUP_ID, "请给我转账500"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("违禁词")
                .hasMessageContaining("转账");
        verify(messageMapper, never()).insert(any(ActivityGroupMessage.class));
    }

    // ---------- 撤回与举报 ----------

    @Test
    void orgCanWithdrawGroupMessage() {
        ActivityGroupMessage message = message();
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        service.withdrawMessage(ORG, MESSAGE_ID);

        verify(messageMapper).update(isNull(), any());
    }

    @Test
    void withdrawAlreadyWithdrawnConflicts() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(messageMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.withdrawMessage(ORG, MESSAGE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("已撤回");
    }

    /** 撤回时限：机构与志愿者共用一条规则，超窗一律 409。 */
    @Test
    void withdrawBeyondWindowRejected() {
        ActivityGroupMessage stale = message();
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(stale);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());

        assertThatThrownBy(() -> service.withdrawMessage(ORG, MESSAGE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("可撤回时间");
        assertThatThrownBy(() -> service.withdrawOwnMessage(OTHER_VOLUNTEER, MESSAGE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("可撤回时间");
    }

    @Test
    void volunteerCanWithdrawOwnMessageWithinWindow() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message());
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        service.withdrawOwnMessage(OTHER_VOLUNTEER, MESSAGE_ID);

        verify(messageMapper).update(isNull(), any());
        // 自撤不发通知（自己操作的，群成员刷新即见墓碑）。
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    void volunteerCannotWithdrawForeignMessage() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message());

        assertThatThrownBy(() -> service.withdrawOwnMessage(VOLUNTEER, MESSAGE_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("自己发送");
    }

    /** 机构撤回后给群内志愿者发「机构撤回了一条消息」的站内通知。 */
    @Test
    void orgWithdrawNotifiesGroupMembers() {
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity());
        ActivityGroupMember member = new ActivityGroupMember();
        member.setGroupId(GROUP_ID);
        member.setVolunteerUid(VOLUNTEER);
        member.setStatus("ACTIVE");
        when(memberMapper.selectList(any())).thenReturn(List.of(member));
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        service.withdrawMessage(ORG, MESSAGE_ID);

        verify(notificationService).notify(org.mockito.Mockito.eq(VOLUNTEER),
                org.mockito.Mockito.eq("USER"),
                org.mockito.Mockito.eq(NotificationService.TYPE_GROUP_MESSAGE_WITHDRAWN),
                org.mockito.Mockito.eq("机构撤回了一条消息"),
                org.mockito.Mockito.contains("徒步陪伴"),
                org.mockito.Mockito.eq("/volunteer/group-chat?activityId=" + ACTIVITY_ID));
    }

    /** 已撤回的消息以墓碑形式下发：withdrawn=true、内容为空（不下发原文）。 */
    @Test
    void withdrawnMessageListedAsTombstone() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(memberMapper.selectCount(any())).thenReturn(1L);
        ActivityGroupMessage gone = message();
        gone.setStatus("WITHDRAWN");
        when(messageMapper.selectList(any())).thenReturn(List.of(gone));

        GroupMessagePage page = service.listMessagesForVolunteer(VOLUNTEER, GROUP_ID, null, 50);

        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).withdrawn()).isTrue();
        assertThat(page.records().get(0).content()).isEmpty();
    }

    @Test
    void memberReportsMessageOnce() {
        ActivityGroupMessage message = message();
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(memberMapper.selectCount(any())).thenReturn(1L);
        when(reportMapper.selectCount(any())).thenReturn(0L, 1L);
        ActivityGroupReport stored = report("PENDING");
        when(reportMapper.selectById(anyString())).thenReturn(stored);

        var response = service.reportMessage(VOLUNTEER, MESSAGE_ID, "疑似诈骗");
        assertThat(response.status()).isEqualTo("PENDING");

        assertThatThrownBy(() -> service.reportMessage(VOLUNTEER, MESSAGE_ID, "再报一次"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("已举报");
    }

    @Test
    void resolveReportWithWithdrawMarksMessageAndReport() {
        ActivityGroupReport pending = report("PENDING");
        ActivityGroupReport resolved = report("RESOLVED");
        when(reportMapper.selectById("rpt-1")).thenReturn(pending, resolved);
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(messageMapper.selectById(MESSAGE_ID)).thenReturn(message());
        when(messageMapper.update(isNull(), any())).thenReturn(1);
        when(reportMapper.update(isNull(), any())).thenReturn(1);

        var response = service.resolveReport(ORG, "rpt-1", true, "已撤回");

        assertThat(response.status()).isEqualTo("RESOLVED");
        verify(messageMapper).update(isNull(), any());
    }

    @Test
    void resolveReportByForeignOrganizationRejected() {
        when(reportMapper.selectById("rpt-1")).thenReturn(report("PENDING"));
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());

        assertThatThrownBy(() -> service.resolveReport("org-other", "rpt-1", true, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---------- 定时封闭 ----------

    @Test
    void sweepClosesGroupOfEndedActivityAndTransitionsStatus() {
        ActivityGroup group = openGroup();
        when(groupMapper.selectList(any())).thenReturn(List.of(group));
        VolunteerActivity ended = activity();
        ended.setStatus("RECRUITING");
        ended.setEndsAt(LocalDateTime.now().minusMinutes(5));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(ended);
        when(activityMapper.update(isNull(), any())).thenReturn(1);
        when(groupMapper.update(isNull(), any())).thenReturn(1);

        service.sweepActivityLifecycle();

        verify(groupMapper).update(isNull(), any());
        // 活动表两次推进：RECRUITING→IN_PROGRESS 与 →ENDED。
        verify(activityMapper, org.mockito.Mockito.times(2)).update(isNull(), any());
    }

    @Test
    void sweepKeepsOpenGroupOfRunningActivity() {
        when(groupMapper.selectList(any())).thenReturn(List.of(openGroup()));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity());

        service.sweepActivityLifecycle();

        verify(groupMapper, never()).update(isNull(), any());
    }

    // ---------- 消息游标 ----------

    @Test
    void listMessagesReversesToAscendingAndReportsHasMore() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(openGroup());
        when(memberMapper.selectCount(any())).thenReturn(1L);
        // 查询结果按 createdAt 倒序（模拟 SQL），limit=2 时取前 3 条发现 hasMore。
        ActivityGroupMessage newest = messageAt("m1", "2026-10-27T10:03:00");
        ActivityGroupMessage middle = messageAt("m2", "2026-10-27T10:02:00");
        ActivityGroupMessage oldest = messageAt("m3", "2026-10-27T10:01:00");
        when(messageMapper.selectList(any())).thenReturn(List.of(newest, middle, oldest));

        GroupMessagePage page = service.listMessagesForVolunteer(VOLUNTEER, GROUP_ID, null, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.records()).extracting("id").containsExactly("m2", "m1");
    }

    // ---------- 工具 ----------

    private VolunteerActivity activity() {
        VolunteerActivity activity = new VolunteerActivity();
        activity.setId(ACTIVITY_ID);
        activity.setStatus("IN_PROGRESS");
        activity.setOrganizationId(ORG);
        activity.setTitle("徒步陪伴");
        activity.setStartsAt(LocalDateTime.now().minusHours(1));
        activity.setEndsAt(LocalDateTime.now().plusHours(3));
        return activity;
    }

    private ActivityGroup openGroup() {
        ActivityGroup group = new ActivityGroup();
        group.setId(GROUP_ID);
        group.setStatus("OPEN");
        group.setActivityId(ACTIVITY_ID);
        group.setOrganizationId(ORG);
        return group;
    }

    private ActivityGroupMessage message() {
        ActivityGroupMessage message = new ActivityGroupMessage();
        message.setId(MESSAGE_ID);
        message.setStatus("NORMAL");
        message.setGroupId(GROUP_ID);
        message.setSenderUid(OTHER_VOLUNTEER);
        message.setSenderType("VOLUNTEER");
        message.setContent("大家好");
        // 撤回时限按 createdAt 判定；默认「刚发出」，在窗口内。
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }

    private ActivityGroupMessage messageAt(String id, String createdAt) {
        ActivityGroupMessage message = message();
        message.setId(id);
        message.setCreatedAt(LocalDateTime.parse(createdAt));
        return message;
    }

    private ActivityGroupReport report(String status) {
        ActivityGroupReport report = new ActivityGroupReport();
        report.setId("rpt-1");
        report.setStatus(status);
        report.setGroupId(GROUP_ID);
        report.setMessageId(MESSAGE_ID);
        report.setReporterUid(VOLUNTEER);
        report.setReason("疑似诈骗");
        return report;
    }
}
