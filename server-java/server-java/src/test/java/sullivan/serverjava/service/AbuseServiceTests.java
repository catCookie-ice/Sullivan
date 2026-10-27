package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AbuseReport;
import sullivan.serverjava.domain.AbuseRestriction;
import sullivan.serverjava.domain.ActivityGroup;
import sullivan.serverjava.domain.Appeal;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.Penalty;
import sullivan.serverjava.domain.PenaltyPart;
import sullivan.serverjava.domain.PresetPenalty;
import sullivan.serverjava.domain.PresetPenaltyPart;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.AbuseReportMapper;
import sullivan.serverjava.mapper.AbuseRestrictionMapper;
import sullivan.serverjava.mapper.ActivityGroupMapper;
import sullivan.serverjava.mapper.AppealMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.PenaltyMapper;
import sullivan.serverjava.mapper.PenaltyPartMapper;
import sullivan.serverjava.mapper.PresetPenaltyMapper;
import sullivan.serverjava.mapper.PresetPenaltyPartMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;
import sullivan.serverjava.web.AbuseDtos.MergedReportGroup;
import sullivan.serverjava.web.AbuseDtos.PenaltyResponse;
import sullivan.serverjava.web.AbuseDtos.PresetPartRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 举报治理核心规则：预设约束、级联执行、匿名反诉与阈值升级、申诉联动。 */
class AbuseServiceTests {
    private static final String ADMIN = "adm-1";
    private static final String OTHER_ADMIN = "adm-2";
    private static final String VOLUNTEER = "usr-reporter";
    private static final String TARGET_USER = "usr-target";
    private static final String ORG = "org-1";
    private static final String ACTIVITY = "act-1";

    private AbuseReportMapper reportMapper;
    private PresetPenaltyMapper presetMapper;
    private PresetPenaltyPartMapper presetPartMapper;
    private PenaltyMapper penaltyMapper;
    private PenaltyPartMapper penaltyPartMapper;
    private AppealMapper appealMapper;
    private AbuseRestrictionMapper restrictionMapper;
    private ActivityGroupMapper groupMapper;
    private OrganizationMapper organizationMapper;
    private UserAccountMapper userAccountMapper;
    private VolunteerActivityMapper activityMapper;
    private ActivityGroupService groupService;
    private ActivityEnrollmentService enrollmentService;
    private NotificationService notificationService;
    private AbuseService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(AbuseReportMapper.class);
        presetMapper = mock(PresetPenaltyMapper.class);
        presetPartMapper = mock(PresetPenaltyPartMapper.class);
        penaltyMapper = mock(PenaltyMapper.class);
        penaltyPartMapper = mock(PenaltyPartMapper.class);
        appealMapper = mock(AppealMapper.class);
        restrictionMapper = mock(AbuseRestrictionMapper.class);
        groupMapper = mock(ActivityGroupMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        activityMapper = mock(VolunteerActivityMapper.class);
        groupService = mock(ActivityGroupService.class);
        enrollmentService = mock(ActivityEnrollmentService.class);
        notificationService = mock(NotificationService.class);
        service = new AbuseService(reportMapper, presetMapper, presetPartMapper, penaltyMapper, penaltyPartMapper,
                appealMapper, restrictionMapper,
                groupMapper, organizationMapper, userAccountMapper, activityMapper, groupService,
                enrollmentService, notificationService, mock(AbuseGuardService.class));
        // 阈值压小便于测试：锁举报=2，禁活动=3，封账户=4，窗口 30 天，驳回占比 0.6。
        ReflectionTestUtils.setField(service, "windowDays", 30);
        ReflectionTestUtils.setField(service, "lockThreshold", 2);
        ReflectionTestUtils.setField(service, "activityBanThreshold", 3);
        ReflectionTestUtils.setField(service, "accountBanThreshold", 4);
        ReflectionTestUtils.setField(service, "dismissRatio", 0.6);
        // 默认无附带组件；需要组合语义的用例自行覆盖该打桩。
        org.mockito.Mockito.when(presetPartMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        // Lambda wrapper 需要实体的 TableInfo 缓存；mock mapper 不会注册它，测试里手工初始化。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        List.of(AbuseReport.class, PresetPenalty.class, PresetPenaltyPart.class, Penalty.class, PenaltyPart.class,
                        Appeal.class, AbuseRestriction.class,
                        VolunteerActivity.class, UserAccount.class, Organization.class, ActivityGroup.class)
                .forEach(type -> TableInfoHelper.initTableInfo(assistant, type));
    }

    private AbuseReport pendingReport(String category, String targetUserId) {
        AbuseReport report = new AbuseReport();
        report.setId("rep-1");
        report.setStatus("PENDING");
        report.setCategory(category);
        report.setReporterUid(VOLUNTEER);
        report.setReason("违规理由");
        report.setTargetUserId(targetUserId);
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

    private PresetPenalty activePreset(String category, String action, Integer days) {
        PresetPenalty preset = new PresetPenalty();
        preset.setId("preset-1");
        preset.setStatus("ACTIVE");
        preset.setCategory(category);
        preset.setAction(action);
        preset.setDurationDays(days);
        preset.setName("下架活动");
        preset.setCreatedBy(OTHER_ADMIN);
        return preset;
    }

    @Test
    void cannotReportYourself() {
        assertThatThrownBy(() -> service.createReport(VOLUNTEER, "USER", VOLUNTEER, "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void missingTargetIsRejected() {
        when(userAccountMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.createReport(VOLUNTEER, "USER", "nope", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(404));
    }

    @Test
    void duplicatePendingReportConflicts() {
        when(userAccountMapper.selectById(TARGET_USER)).thenReturn(new UserAccount());
        when(reportMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createReport(VOLUNTEER, "USER", TARGET_USER, "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));
    }

    @Test
    void dismissNotifiesReporterAndEvaluates() {
        AbuseReport report = pendingReport("USER", TARGET_USER);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(reportMapper.selectList(any())).thenReturn(List.of(report)); // 评估：1 条中 1 条驳回，未到锁定量
        service.decide(ADMIN, "rep-1", true, null, "查无违规");
        assertThat(report.getStatus()).isEqualTo("DISMISSED");
        verify(notificationService).notify(eq(VOLUNTEER), eq("USER"), eq("REPORT_DISMISSED"),
                any(), any(), any());
        verify(restrictionMapper, never()).insert(any(AbuseRestriction.class));
    }

    @Test
    void decisionAppliesOnlyActiveCategoryMatchedPreset() {
        AbuseReport report = pendingReport("USER", TARGET_USER);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        PresetPenalty wrong = activePreset("ORGANIZATION", "BAN_ORG", 3);
        when(presetMapper.selectById("preset-1")).thenReturn(wrong);
        assertThatThrownBy(() -> service.decide(ADMIN, "rep-1", false, "preset-1", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        wrong.setCategory("USER");
        wrong.setStatus("PENDING");
        assertThatThrownBy(() -> service.decide(ADMIN, "rep-1", false, "preset-1", null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void banUserPenaltyRestrictsDisablesAndKicks() {
        AbuseReport report = pendingReport("USER", TARGET_USER);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("USER", "BAN_USER", 3));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);

        service.decide(ADMIN, "rep-1", false, "preset-1", "查实违规");

        assertThat(report.getStatus()).isEqualTo("RESOLVED");
        assertThat(report.getPenaltyId()).isNotNull();
        ArgumentCaptor<Penalty> penalty = ArgumentCaptor.forClass(Penalty.class);
        verify(penaltyMapper).insert(penalty.capture());
        assertThat(penalty.getValue().getAction()).isEqualTo("BAN_USER");
        assertThat(penalty.getValue().getExpiresAt()).isNotNull();
        assertThat(penalty.getValue().getAccountDisabled()).isFalse();
        verify(restrictionMapper).insert(any(AbuseRestriction.class));
        verify(userAccountMapper).update(isNull(), any());
        verify(enrollmentService).kickFromAllActiveActivities(eq(TARGET_USER), any());
        verify(notificationService).notify(eq(TARGET_USER), eq("USER"), eq("PENALTY_APPLIED"),
                any(), any(), eq("/volunteer/penalties"));
    }

    @Test
    void banOrgTemporarilySuspendsAllActivitiesAndGroups() {
        AbuseReport report = pendingReport("ORGANIZATION", null);
        report.setTargetOrgId(ORG);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ORGANIZATION", "BAN_ORG", 3));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        VolunteerActivity a1 = new VolunteerActivity();
        a1.setId(ACTIVITY);
        a1.setStatus("RECRUITING");
        VolunteerActivity a2 = new VolunteerActivity();
        a2.setId("act-2");
        a2.setStatus("IN_PROGRESS");
        when(activityMapper.selectList(any())).thenReturn(List.of(a1, a2));

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        // 非永久机构封禁：其活动/群聊「至少暂时下架/封禁」同时长（不再永久下架）。
        verify(activityMapper, org.mockito.Mockito.times(2)).update(isNull(), any());
        verify(groupService).suspendGroupOfActivity(eq(ACTIVITY), any());
        verify(groupService).suspendGroupOfActivity(eq("act-2"), any());
        verify(groupService, never()).closeGroupOfActivity(any(), any());
        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("ORG_BANNED");
        assertThat(restriction.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(restriction.getValue().getExpiresAt()).isNotNull();
        verify(notificationService).notify(eq(ORG), eq("ORGANIZATION"), eq("PENALTY_APPLIED"),
                any(), any(), eq("/penalties"));
    }

    @Test
    void orgPermanentBanStillDelistsActivitiesPermanently() {
        AbuseReport report = pendingReport("ORGANIZATION", null);
        report.setTargetOrgId(ORG);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ORGANIZATION", "BAN_ORG", null));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        VolunteerActivity ongoing = new VolunteerActivity();
        ongoing.setId(ACTIVITY);
        ongoing.setStatus("RECRUITING");
        when(activityMapper.selectList(any())).thenReturn(List.of(ongoing));

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        // 永久封禁才永久下架：活动 CANCELLED + 群聊解散。
        verify(activityMapper).update(isNull(), any());
        verify(groupService).closeGroupOfActivity(any(), any());
        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getExpiresAt()).isNull();
    }

    @Test
    void tempDelistSuspendsActivityAndGroup() {
        AbuseReport report = pendingReport("ACTIVITY", null);
        report.setTargetActivityId(ACTIVITY);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ACTIVITY", "TEMP_DELIST", 5));

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        ArgumentCaptor<Penalty> penalty = ArgumentCaptor.forClass(Penalty.class);
        verify(penaltyMapper).insert(penalty.capture());
        assertThat(penalty.getValue().getExpiresAt()).isNotNull();
        verify(activityMapper).update(isNull(), any()); // SUSPENDED，不是 CANCELLED
        verify(groupService).suspendGroupOfActivity(eq(ACTIVITY), eq(penalty.getValue().getExpiresAt()));
        verify(groupService, never()).closeGroupOfActivity(any(), any());
    }

    @Test
    void banGroupPenaltySuspendsGroupAndNotifiesOrg() {
        AbuseReport report = pendingReport("GROUP", null);
        report.setTargetGroupId("grp-1");
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("GROUP", "BAN_GROUP", 3));
        ActivityGroup group = new ActivityGroup();
        group.setId("grp-1");
        group.setOrganizationId(ORG);
        group.setActivityId(ACTIVITY);
        when(groupMapper.selectById("grp-1")).thenReturn(group);

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        verify(groupService).suspendGroupById(eq("grp-1"), any());
        verify(groupService, never()).closeGroupById(any(), any());
        // 群聊处罚通知管理该群的机构。
        verify(notificationService).notify(eq(ORG), eq("ORGANIZATION"), eq("PENALTY_APPLIED"),
                any(), any(), eq("/penalties"));
    }

    @Test
    void permCloseGroupAlsoDelistsItsActivity() {
        AbuseReport report = pendingReport("GROUP", null);
        report.setTargetGroupId("grp-1");
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1"))
                .thenReturn(activePreset("GROUP", "PERM_CLOSE", null));
        // 组合：主=永久封群 + 附带(活动, 永久下架)——即旧「永久封群并下架活动」。
        when(presetPartMapper.selectList(any())).thenReturn(List.of(part("ACTIVITY", "DELIST", null)));
        ActivityGroup group = new ActivityGroup();
        group.setId("grp-1");
        group.setOrganizationId(ORG);
        group.setActivityId(ACTIVITY);
        when(groupMapper.selectById("grp-1")).thenReturn(group);

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        verify(groupService).closeGroupById(eq("grp-1"), any());
        verify(activityMapper).update(isNull(), any()); // 附带组件：活动永久下架
        verify(groupService).closeGroupOfActivity(eq(ACTIVITY), any());
    }

    @Test
    void sweepResumesExpiredSuspensionsAndMarksPenaltyExpired() {
        Penalty penalty = new Penalty();
        penalty.setId("pen-1");
        penalty.setStatus("ACTIVE");
        penalty.setAction("TEMP_DELIST");
        penalty.setTargetActivityId(ACTIVITY);
        penalty.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(penaltyMapper.selectList(any())).thenReturn(List.of(penalty));
        VolunteerActivity activity = new VolunteerActivity();
        activity.setId(ACTIVITY);
        activity.setStatus("SUSPENDED");
        activity.setStartsAt(LocalDateTime.now().minusDays(1));
        activity.setEndsAt(LocalDateTime.now().plusDays(1));
        when(activityMapper.selectById(ACTIVITY)).thenReturn(activity);
        ActivityGroup group = new ActivityGroup();
        group.setId("grp-1");
        group.setActivityId(ACTIVITY);
        group.setStatus("SUSPENDED");
        when(groupMapper.selectOne(any())).thenReturn(group);

        service.sweepExpiredPenalties();

        // 活动按自然时间轴恢复（进行中→IN_PROGRESS），群聊恢复，处罚行翻 EXPIRED。
        verify(activityMapper).update(isNull(), any());
        verify(groupService).resumeGroupById("grp-1");
        verify(penaltyMapper).update(isNull(), any());
    }

    @Test
    void delistPenaltyCancelsActivityAndClosesGroup() {
        AbuseReport report = pendingReport("ACTIVITY", null);
        report.setTargetActivityId(ACTIVITY);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ACTIVITY", "DELIST", null));

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        verify(activityMapper).update(isNull(), any());
        verify(groupService).closeGroupOfActivity(eq(ACTIVITY), any());
        verify(restrictionMapper, never()).insert(any(AbuseRestriction.class));
    }

    @Test
    void delistPresetMustNotCarryDurationDays() {
        // 单纯下架是纯永久动作：带封禁天数直接拒绝（前端同样隐藏该字段）。
        assertThatThrownBy(() -> service.createPreset(ADMIN, "下架活动", "ACTIVITY", "DELIST", 3, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void tempDelistPresetRequiresDurationDays() {
        assertThatThrownBy(() -> service.createPreset(ADMIN, "暂时下架", "ACTIVITY", "TEMP_DELIST", null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.createPreset(ADMIN, "暂时下架", "ACTIVITY", "TEMP_DELIST", 0, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
    }

    @Test
    void groupCategoryPresetsAccepted() {
        when(presetMapper.selectById(any())).thenReturn(null);
        // 旧「永久封群并下架活动」在新模型下 = 主 PERM_CLOSE + 附带(活动, DELIST)。
        var created = service.createPreset(ADMIN, "永久封禁群聊并下架活动",
                "GROUP", "PERM_CLOSE", null, List.of(partReq("ACTIVITY", "DELIST", null)), null);
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void banPublishRestrictsOrgWithoutTouchingActivities() {
        AbuseReport report = pendingReport("ORGANIZATION", null);
        report.setTargetOrgId(ORG);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ORGANIZATION", "BAN_PUBLISH", 5));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        // 只挡发布：不触碰活动行、不封群。
        verify(activityMapper, never()).update(isNull(), any());
        verify(groupService, never()).closeGroupOfActivity(any(), any());
        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("PUBLISH_BANNED");
        assertThat(restriction.getValue().getOrgId()).isEqualTo(ORG);
        assertThat(restriction.getValue().getExpiresAt()).isNotNull();
    }

    @Test
    void delistAndBanPublishUsesAttachedDurationSeparately() {
        AbuseReport report = pendingReport("ACTIVITY", null);
        report.setTargetActivityId(ACTIVITY);
        report.setTargetOrgId(ORG);
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        // 组合：主=暂时下架 3 天 + 附带(机构, 禁止发布 7 天)——两个时长独立。
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("ACTIVITY", "TEMP_DELIST", 3));
        when(presetPartMapper.selectList(any()))
                .thenReturn(List.of(part("ORGANIZATION", "BAN_PUBLISH", 7)));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        LocalDateTime before = LocalDateTime.now();

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        verify(activityMapper).update(isNull(), any()); // 主处罚：暂时下架
        verify(groupService).suspendGroupOfActivity(eq(ACTIVITY), any());
        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("PUBLISH_BANNED");
        assertThat(restriction.getValue().getOrgId()).isEqualTo(ORG);
        // 附带时长 7 天：明显晚于主处罚的 3 天窗口，证明附带时长被独立采用而非回退。
        assertThat(restriction.getValue().getExpiresAt())
                .isAfter(before.plusDays(6))
                .isBefore(before.plusDays(8));
    }

    @Test
    void attachedBlankFallsBackToMainDurationForOrgFallout() {
        AbuseReport report = pendingReport("GROUP", null);
        report.setTargetGroupId("grp-1");
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        // 组合：主=永久封群（无天数）；附带机构处罚未单独设置 → 回退主时长=null=永久封机构。
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("GROUP", "PERM_CLOSE", null));
        when(presetPartMapper.selectList(any())).thenReturn(List.of(part("ORGANIZATION", "BAN_ORG", null)));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        ActivityGroup group = new ActivityGroup();
        group.setId("grp-1");
        group.setOrganizationId(ORG);
        group.setActivityId(ACTIVITY);
        when(groupMapper.selectById("grp-1")).thenReturn(group);
        when(activityMapper.selectList(any())).thenReturn(List.of(new VolunteerActivity()));

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("ORG_BANNED");
        // 附带留空 → 与主处罚时长相同（此处主处罚为永久）→ 机构永久封禁。
        assertThat(restriction.getValue().getExpiresAt()).isNull();
    }

    @Test
    void compositePresetCoexistsMultipleParts() {
        AbuseReport report = pendingReport("GROUP", null);
        report.setTargetGroupId("grp-1");
        report.setTargetUserId(null);
        when(reportMapper.selectById("rep-1")).thenReturn(report);
        // 复合共存：永久封群 + 暂时下架活动 5 天 + 封禁机构 2 天（三个基础惩罚并存）。
        when(presetMapper.selectById("preset-1")).thenReturn(activePreset("GROUP", "PERM_CLOSE", null));
        when(presetPartMapper.selectList(any())).thenReturn(List.of(
                part("ACTIVITY", "TEMP_DELIST", 5),
                part("ORGANIZATION", "BAN_ORG", 2)));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        ActivityGroup group = new ActivityGroup();
        group.setId("grp-1");
        group.setOrganizationId(ORG);
        group.setActivityId(ACTIVITY);
        when(groupMapper.selectById("grp-1")).thenReturn(group);

        service.decide(ADMIN, "rep-1", false, "preset-1", null);

        verify(groupService).closeGroupById(eq("grp-1"), any());
        verify(activityMapper).update(isNull(), any()); // 活动暂时下架
        verify(groupService).suspendGroupOfActivity(eq(ACTIVITY), any());
        // 三条组件执行留痕：主 PERM_CLOSE + 附带 TEMP_DELIST + 附带 BAN_ORG。
        verify(penaltyPartMapper, times(3)).insert(any(PenaltyPart.class));
        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("ORG_BANNED");
    }

    @Test
    void compositeValidationRejectsBadParts() {
        // 附带对象与预设对象相同 → 400。
        assertThatThrownBy(() -> service.createPreset(ADMIN, "组合", "GROUP", "PERM_CLOSE", null,
                List.of(partReq("GROUP", "BAN_GROUP", 3)), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        // 同一对象重复附带 → 400。
        assertThatThrownBy(() -> service.createPreset(ADMIN, "组合", "GROUP", "PERM_CLOSE", null,
                List.of(partReq("ORGANIZATION", "BAN_ORG", null), partReq("ORGANIZATION", "BAN_PUBLISH", null)), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        // 主处罚为永久时，附带暂时类必须自带时长 → 400。
        assertThatThrownBy(() -> service.createPreset(ADMIN, "组合", "GROUP", "PERM_CLOSE", null,
                List.of(partReq("ACTIVITY", "TEMP_DELIST", null)), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        // 个人处罚没有附带对象 → 400。
        assertThatThrownBy(() -> service.createPreset(ADMIN, "组合", "USER", "BAN_USER", 3,
                List.of(partReq("ORGANIZATION", "BAN_ORG", null)), null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(400));
        // 合法组合通过：永久封群 + 永久下架活动。
        when(presetMapper.selectById(any())).thenReturn(null);
        var created = service.createPreset(ADMIN, "组合", "GROUP", "PERM_CLOSE", null,
                List.of(partReq("ACTIVITY", "DELIST", null)), null);
        assertThat(created.status()).isEqualTo("PENDING");
    }

    @Test
    void mergedReportsGroupByTarget() {
        AbuseReport first = pendingReport("USER", TARGET_USER);
        AbuseReport second = pendingReport("USER", TARGET_USER);
        second.setId("rep-2");
        AbuseReport other = pendingReport("USER", "usr-other");
        other.setId("rep-3");
        when(reportMapper.selectList(any())).thenReturn(List.of(first, second, other));
        UserAccount account = new UserAccount();
        account.setId(TARGET_USER);
        account.setLoginName("target-name");
        when(userAccountMapper.selectById(any())).thenReturn(account);

        List<MergedReportGroup> groups = service.adminListGrouped(null, null);

        // 同一对象的举报合并为一条；不同对象各一条。
        assertThat(groups).hasSize(2);
        MergedReportGroup merged = groups.get(0);
        assertThat(merged.reportCount()).isEqualTo(2);
        assertThat(merged.pendingCount()).isEqualTo(2);
        assertThat(merged.latestStatus()).isEqualTo("PENDING");
        assertThat(merged.reports()).hasSize(2);
        assertThat(merged.targetSummary()).contains("target-name");
        assertThat(groups.get(1).reportCount()).isEqualTo(1);
    }

    private PresetPenaltyPart part(String targetKind, String action, Integer days) {
        PresetPenaltyPart row = new PresetPenaltyPart();
        row.setId("part-" + targetKind + "-" + action);
        row.setPresetId("preset-1");
        row.setTargetKind(targetKind);
        row.setAction(action);
        row.setDurationDays(days);
        return row;
    }

    private PresetPartRequest partReq(String targetKind, String action, Integer days) {
        return new PresetPartRequest(targetKind, action, days);
    }

    @Test
    void presetCreatorCannotApproveOwn() {
        PresetPenalty preset = new PresetPenalty();
        preset.setId("preset-1");
        preset.setStatus("PENDING");
        preset.setCreatedBy(ADMIN);
        when(presetMapper.selectById("preset-1")).thenReturn(preset);
        assertThatThrownBy(() -> service.approvePreset(ADMIN, "preset-1", true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(403));
        service.approvePreset(OTHER_ADMIN, "preset-1", true);
        assertThat(preset.getStatus()).isEqualTo("ACTIVE");
        assertThat(preset.getApprovedBy()).isEqualTo(OTHER_ADMIN);
    }

    @Test
    void counterReportOnlyForDismissedTargetAndTriggersEvaluation() {
        AbuseReport source = pendingReport("USER", VOLUNTEER); // 别人举报我，已被驳回
        source.setStatus("DISMISSED");
        source.setReporterUid(TARGET_USER);
        when(reportMapper.selectById("rep-1")).thenReturn(source);
        when(reportMapper.selectCount(any())).thenReturn(0L);
        // 评估：原举报人近窗口 4 条举报全部被驳回 → 达到封账户阈值（4）。
        AbuseReport dismissed = new AbuseReport();
        dismissed.setStatus("DISMISSED");
        when(reportMapper.selectList(any())).thenReturn(List.of(dismissed, dismissed, dismissed, dismissed));
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        UserAccount account = new UserAccount();
        account.setId(TARGET_USER);
        account.setStatus("ENABLED");
        when(userAccountMapper.selectById(TARGET_USER)).thenReturn(account);

        var response = service.counterReport(VOLUNTEER, "rep-1", "恶意举报");

        assertThat(response.category()).isEqualTo("REPORT_ABUSE");
        ArgumentCaptor<AbuseReport> inserted = ArgumentCaptor.forClass(AbuseReport.class);
        verify(reportMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTargetUserId()).isEqualTo(TARGET_USER); // 目标=原举报人
        assertThat(inserted.getValue().getReporterUid()).isEqualTo(VOLUNTEER);
        // 阈值升级：封账户 + 踢出未结束活动 + 禁用账户。
        verify(restrictionMapper).insert(any(AbuseRestriction.class));
        verify(userAccountMapper).update(isNull(), any());
        verify(enrollmentService).kickFromAllActiveActivities(eq(TARGET_USER), any());
    }

    @Test
    void counterReportRejectedUnlessDismissedAndMine() {
        AbuseReport source = pendingReport("USER", TARGET_USER); // 还没驳回
        when(reportMapper.selectById("rep-1")).thenReturn(source);
        assertThatThrownBy(() -> service.counterReport(VOLUNTEER, "rep-1", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));
        source.setStatus("DISMISSED");
        assertThatThrownBy(() -> service.counterReport(VOLUNTEER, "rep-1", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(403)); // 我不是被举报人
    }

    @Test
    void lockThresholdLocksReportingWithoutKicking() {
        AbuseReport dismissed = new AbuseReport();
        dismissed.setStatus("DISMISSED");
        dismissed.setReporterUid(VOLUNTEER);
        when(reportMapper.selectList(any())).thenReturn(List.of(dismissed, dismissed)); // 2/2=100% ≥ 0.6，到锁定量
        when(restrictionMapper.selectCount(any())).thenReturn(0L);

        service.evaluateReporter(VOLUNTEER);

        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("REPORT_LOCKED");
        verify(enrollmentService, never()).kickFromAllActiveActivities(any(), any());
        verify(userAccountMapper, never()).update(isNull(), any());
    }

    @Test
    void activityBanThresholdKicksButDoesNotDisableAccount() {
        AbuseReport dismissed = new AbuseReport();
        dismissed.setStatus("DISMISSED");
        dismissed.setReporterUid(VOLUNTEER);
        when(reportMapper.selectList(any())).thenReturn(List.of(dismissed, dismissed, dismissed)); // 3 条驳回
        when(restrictionMapper.selectCount(any())).thenReturn(0L);

        service.evaluateReporter(VOLUNTEER);

        ArgumentCaptor<AbuseRestriction> restriction = ArgumentCaptor.forClass(AbuseRestriction.class);
        verify(restrictionMapper).insert(restriction.capture());
        assertThat(restriction.getValue().getKind()).isEqualTo("ACTIVITY_BANNED");
        verify(enrollmentService).kickFromAllActiveActivities(eq(VOLUNTEER), any());
        verify(userAccountMapper, never()).update(isNull(), any());
    }

    @Test
    void appealOnlyByTargetOnceAndOverturnLiftsEverything() {
        Penalty penalty = new Penalty();
        penalty.setId("pen-1");
        penalty.setStatus("ACTIVE");
        penalty.setTargetUserId(TARGET_USER);
        penalty.setAccountDisabled(true);
        when(penaltyMapper.selectById("pen-1")).thenReturn(penalty);
        when(appealMapper.selectCount(any())).thenReturn(0L);

        // 不是被处罚方 → 403。
        assertThatThrownBy(() -> service.createAppeal(VOLUNTEER, "USER", "pen-1", "理由"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(403));

        var appeal = service.createAppeal(TARGET_USER, "USER", "pen-1", "处罚过重");
        assertThat(appeal.status()).isEqualTo("PENDING");
        // 已申诉过 → 409。
        when(appealMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.createAppeal(TARGET_USER, "USER", "pen-1", "再来一次"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status().value()).isEqualTo(409));

        // 管理员撤销 → 处罚 LIFTED + 限制解除 + 账户恢复。
        Appeal stored = new Appeal();
        stored.setId("apl-1");
        stored.setStatus("PENDING");
        stored.setPenaltyId("pen-1");
        stored.setAppellantType("USER");
        stored.setAppellantUid(TARGET_USER);
        when(appealMapper.selectById("apl-1")).thenReturn(stored);
        service.handleAppeal(ADMIN, "apl-1", false, "撤销合理");
        assertThat(stored.getStatus()).isEqualTo("OVERTURNED");
        assertThat(penalty.getStatus()).isEqualTo("LIFTED");
        verify(restrictionMapper).update(isNull(), any());
        verify(userAccountMapper).update(isNull(), any());
        verify(notificationService).notify(eq(TARGET_USER), eq("USER"), eq("APPEAL_DECIDED"),
                any(), any(), eq("/volunteer/penalties"));
    }

    @Test
    void receivedReportsOnlyListDismissedWithoutDecision() {
        AbuseReport dismissed = pendingReport("USER", VOLUNTEER);
        dismissed.setStatus("DISMISSED");
        dismissed.setDecisionNote("内部意见不下发");
        when(reportMapper.selectList(any())).thenReturn(List.of(dismissed));
        when(reportMapper.selectCount(any())).thenReturn(0L); // 尚未反诉

        var received = service.receivedReports(VOLUNTEER);

        assertThat(received).hasSize(1);
        // 反诉入口契约：只有驳回的、不带状态与处置意见字段（DTO 已删），可反诉为真。
        assertThat(received.get(0).counterReportable()).isTrue();
        assertThat(dismissed.getStatus()).isEqualTo("DISMISSED");
        verify(reportMapper).selectList(any());
    }

    @Test
    void myPenaltiesMarksAppealable() {
        Penalty active = new Penalty();
        active.setId("pen-a");
        active.setStatus("ACTIVE");
        active.setCategory("USER");
        active.setAction("BAN_USER");
        active.setPresetId("preset-1");
        active.setReason("违规");
        active.setTargetUserId(TARGET_USER);
        active.setAppliedAt(LocalDateTime.now());
        PresetPenalty preset = activePreset("USER", "BAN_USER", 3);
        when(penaltyMapper.selectList(any())).thenReturn(List.of(active));
        when(presetMapper.selectById("preset-1")).thenReturn(preset);
        when(appealMapper.selectCount(any())).thenReturn(0L);

        List<PenaltyResponse> mine = service.penaltiesFor("USER", TARGET_USER);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).appealable()).isTrue();
    }
}
