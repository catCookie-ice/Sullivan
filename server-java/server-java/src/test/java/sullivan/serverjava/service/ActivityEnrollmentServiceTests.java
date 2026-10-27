package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.ActivityEnrollment;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.ActivityEnrollmentMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityEnrollmentServiceTests {
    private static final String VOLUNTEER = "vol-1";
    private static final String ORG = "org-1";
    private static final String ACTIVITY_ID = "act-1";

    private ActivityEnrollmentMapper enrollmentMapper;
    private VolunteerActivityMapper activityMapper;
    private UserRoleMapper userRoleMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private OrganizationMapper organizationMapper;
    private ActivityGroupService groupService;
    private NotificationService notificationService;
    private AbuseGuardService abuseGuardService;
    private ActivityEnrollmentService service;

    @BeforeEach
    void setUp() {
        enrollmentMapper = mock(ActivityEnrollmentMapper.class);
        activityMapper = mock(VolunteerActivityMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        groupService = mock(ActivityGroupService.class);
        notificationService = mock(NotificationService.class);
        abuseGuardService = mock(AbuseGuardService.class);
        service = new ActivityEnrollmentService(enrollmentMapper, activityMapper, userRoleMapper,
                userAccountMapper, userProfileMapper, organizationMapper, groupService, notificationService,
                abuseGuardService);
        // Lambda wrapper 需要实体的 TableInfo 缓存；mock mapper 不会注册它，测试里手工初始化。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ActivityEnrollment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                VolunteerActivity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                sullivan.serverjava.domain.UserRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                sullivan.serverjava.domain.UserProfile.class);
        // 默认：志愿者角色成立。
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
    }

    @Test
    void enrollWithoutReviewApprovedAndJoinsGroup() {
        VolunteerActivity activity = activity(false);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);
        when(enrollmentMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.update(isNull(), any())).thenReturn(1);
        when(enrollmentMapper.selectById(any())).thenAnswer(invocation -> capturedEnrollment());

        service.enroll(VOLUNTEER, ACTIVITY_ID);

        ActivityEnrollment inserted = capturedEnrollment();
        assertThat(inserted.getStatus()).isEqualTo("APPROVED");
        assertThat(inserted.getDecidedAt()).isNotNull();
        verify(activityMapper).update(isNull(), any());
        verify(groupService).joinActivityGroup(activity, VOLUNTEER);
    }

    @Test
    void enrollWithReviewStaysPendingAndDoesNotJoinGroup() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(true));
        when(enrollmentMapper.selectCount(any())).thenReturn(0L);
        when(enrollmentMapper.selectById(any())).thenAnswer(invocation -> capturedEnrollment());

        service.enroll(VOLUNTEER, ACTIVITY_ID);

        assertThat(capturedEnrollment().getStatus()).isEqualTo("PENDING");
        assertThat(capturedEnrollment().getDecidedAt()).isNull();
        verify(activityMapper, never()).update(isNull(), any());
        verify(groupService, never()).joinActivityGroup(any(), any());
        // 待审核通知发给机构（角色不同，通知不同）。
        verify(notificationService).notify(org.mockito.Mockito.eq(ORG),
                org.mockito.Mockito.eq("ORGANIZATION"),
                org.mockito.Mockito.eq(NotificationService.TYPE_ENROLLMENT_PENDING),
                org.mockito.Mockito.contains("待审核"), any(), org.mockito.Mockito.eq("/enrollments"));
    }

    /** 未开审核的活动报名即通过：不产生「待审核」通知（否则机构被空通知轰炸）。 */
    @Test
    void enrollWithoutReviewDoesNotNotifyPending() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.update(isNull(), any())).thenReturn(1);
        when(enrollmentMapper.selectById(any())).thenAnswer(invocation -> capturedEnrollment());

        service.enroll(VOLUNTEER, ACTIVITY_ID);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    /** 未开审核直接报名但名额已满：409，且不让入群（真实事务里报名行也会回滚）。 */
    @Test
    void enrollFullQuotaRejected() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.selectCount(any())).thenReturn(0L);
        when(activityMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.enroll(VOLUNTEER, ACTIVITY_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("名额已满");
        verify(groupService, never()).joinActivityGroup(any(), any());
    }

    @Test
    void duplicateEffectiveEnrollmentRejected() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.enroll(VOLUNTEER, ACTIVITY_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("重复报名");
    }

    @Test
    void enrollAfterRegistrationEndsRejected() {
        VolunteerActivity activity = activity(false);
        activity.setRegistrationEndsAt(LocalDateTime.now().minusHours(1));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);

        assertThatThrownBy(() -> service.enroll(VOLUNTEER, ACTIVITY_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("报名已截止");
    }

    @Test
    void enrollRequiresVolunteerRole() {
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        UserAccount guardian = new UserAccount();
        guardian.setId(VOLUNTEER);
        guardian.setRoleCode("GUARDIAN");
        when(userAccountMapper.selectById(VOLUNTEER)).thenReturn(guardian);

        assertThatThrownBy(() -> service.enroll(VOLUNTEER, ACTIVITY_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("志愿者");
    }

    @Test
    void decideApproveOccupiesQuotaAndJoinsGroup() {
        ActivityEnrollment pending = enrollment("PENDING");
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(pending);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);
        when(activityMapper.update(isNull(), any())).thenReturn(1);
        ActivityEnrollment approved = enrollment("APPROVED");
        when(enrollmentMapper.selectById("enr-1")).thenReturn(pending, approved);

        var response = service.decide(ORG, ACTIVITY_ENROLLMENT_ID, true, "欢迎");

        assertThat(response.status()).isEqualTo("APPROVED");
        verify(activityMapper).update(isNull(), any());
        verify(groupService).joinActivityGroup(any(), any());
        // 通过通知发给志愿者，链接直达群聊。
        verify(notificationService).notify(org.mockito.Mockito.eq(VOLUNTEER),
                org.mockito.Mockito.eq("USER"),
                org.mockito.Mockito.eq(NotificationService.TYPE_ENROLLMENT_APPROVED),
                any(), org.mockito.Mockito.contains("徒步陪伴"),
                org.mockito.Mockito.eq("/volunteer/group-chat?activityId=" + ACTIVITY_ID));
    }

    /** 拒绝：不发站内「已通过」通知，而是带原因的「未通过」；不动名额不入群。 */
    @Test
    void decideRejectNotifiesVolunteerWithoutQuota() {
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(enrollment("PENDING"));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);
        ActivityEnrollment rejected = enrollment("REJECTED");
        when(enrollmentMapper.selectById("enr-1")).thenReturn(enrollment("PENDING"), rejected);

        var response = service.decide(ORG, ACTIVITY_ENROLLMENT_ID, false, "名额已留给更合适的志愿者");

        assertThat(response.status()).isEqualTo("REJECTED");
        verify(activityMapper, never()).update(isNull(), any());
        verify(groupService, never()).joinActivityGroup(any(), any());
        verify(notificationService).notify(org.mockito.Mockito.eq(VOLUNTEER),
                org.mockito.Mockito.eq("USER"),
                org.mockito.Mockito.eq(NotificationService.TYPE_ENROLLMENT_REJECTED),
                any(), org.mockito.Mockito.contains("名额已留给"), any());
    }

    /** 批准但名额已满：409（真实事务回滚，报名行退回 PENDING 可改判他人）。 */
    @Test
    void decideWithFullQuotaThrowsConflict() {
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(enrollment("PENDING"));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);
        when(activityMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.decide(ORG, ACTIVITY_ENROLLMENT_ID, true, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("名额已满");
    }

    @Test
    void decideByForeignOrganizationRejected() {
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(enrollment("PENDING"));
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));

        assertThatThrownBy(() -> service.decide("org-other", ACTIVITY_ENROLLMENT_ID, true, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessageContaining("本机构");
    }

    @Test
    void cancelApprovedReleasesQuotaAndLeavesGroup() {
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(enrollment("APPROVED"));
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);

        service.cancel(VOLUNTEER, ACTIVITY_ENROLLMENT_ID);

        verify(activityMapper).update(isNull(), any());
        verify(groupService).removeFromActivityGroup(ACTIVITY_ID, VOLUNTEER);
    }

    @Test
    void cancelPendingDoesNotTouchGroupOrQuota() {
        when(enrollmentMapper.selectById(ACTIVITY_ENROLLMENT_ID)).thenReturn(enrollment("PENDING"));
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);

        service.cancel(VOLUNTEER, ACTIVITY_ENROLLMENT_ID);

        verify(activityMapper, never()).update(isNull(), any());
        verify(groupService, never()).removeFromActivityGroup(any(), any());
    }

    @Test
    void listForActivityComputesSummary() {
        VolunteerActivity activity = activity(false);
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity);
        Page<ActivityEnrollment> page = new Page<>(1, 20, 0);
        page.setRecords(List.of());
        when(enrollmentMapper.selectPage(any(), any())).thenReturn(page);
        // summarize 的调用顺序：total、PENDING、APPROVED、REJECTED、CANCELLED。
        when(enrollmentMapper.selectCount(any())).thenReturn(16L, 8L, 6L, 1L, 1L);

        var result = service.listForActivity(ORG, ACTIVITY_ID, null, 1, 20);

        assertThat(result.summary().total()).isEqualTo(16);
        assertThat(result.summary().pending()).isEqualTo(8);
        assertThat(result.summary().approved()).isEqualTo(6);
        assertThat(result.summary().rejected()).isEqualTo(1);
        assertThat(result.summary().cancelled()).isEqualTo(1);
    }

    @Test
    void listForActivityByForeignOrganizationRejected() {
        when(activityMapper.selectById(ACTIVITY_ID)).thenReturn(activity(false));

        assertThatThrownBy(() -> service.listForActivity("org-other", ACTIVITY_ID, null, 1, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private static final String ACTIVITY_ENROLLMENT_ID = "enr-1";

    private VolunteerActivity activity(boolean reviewRequired) {
        VolunteerActivity activity = new VolunteerActivity();
        activity.setId(ACTIVITY_ID);
        activity.setStatus("RECRUITING");
        activity.setOrganizationId(ORG);
        activity.setTitle("徒步陪伴");
        activity.setStartsAt(LocalDateTime.now().plusDays(7));
        activity.setEndsAt(LocalDateTime.now().plusDays(8));
        activity.setRegistrationStartsAt(LocalDateTime.now().minusDays(1));
        activity.setRegistrationEndsAt(LocalDateTime.now().plusDays(5));
        activity.setMaxVolunteers(20);
        activity.setApprovedCount(6);
        activity.setReviewRequired(reviewRequired);
        return activity;
    }

    private ActivityEnrollment enrollment(String status) {
        ActivityEnrollment enrollment = new ActivityEnrollment();
        enrollment.setId("enr-1");
        enrollment.setStatus(status);
        enrollment.setActivityId(ACTIVITY_ID);
        enrollment.setVolunteerUid(VOLUNTEER);
        return enrollment;
    }

    /** 捕获 insert 落库的报名行（enroll 里 selectById 的再读打桩到它）。 */
    private ActivityEnrollment capturedEnrollment() {
        ArgumentCaptor<ActivityEnrollment> captor = ArgumentCaptor.forClass(ActivityEnrollment.class);
        org.mockito.Mockito.verify(enrollmentMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getValue();
    }
}
