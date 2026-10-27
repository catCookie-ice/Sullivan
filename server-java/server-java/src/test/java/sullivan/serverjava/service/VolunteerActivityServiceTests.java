package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.TimeUtil;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;
import sullivan.serverjava.web.ActivityDtos.ActivityCreateRequest;
import sullivan.serverjava.web.ActivityDtos.ActivityResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VolunteerActivityServiceTests {
    private static final String ORG = "org-1";

    private VolunteerActivityMapper activityMapper;
    private OrganizationMapper organizationMapper;
    private VolunteerActivityService service;

    @BeforeEach
    void setUp() {
        activityMapper = mock(VolunteerActivityMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        service = new VolunteerActivityService(activityMapper, organizationMapper, mock(AbuseGuardService.class));
        when(organizationMapper.selectById(ORG)).thenReturn(approvedOrg());
    }

    @Test
    void unapprovedOrganizationCannotCreate() {
        Organization pending = new Organization();
        pending.setId(ORG);
        pending.setStatus("PENDING");
        when(organizationMapper.selectById(ORG)).thenReturn(pending);

        assertThatThrownBy(() -> service.create(ORG, validRequest()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registrationWindowAfterActivityStartRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), valid.startsAt(), valid.endsAt(),
                valid.startsAt().plusHours(1), valid.startsAt().plusHours(2), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 活动结束时间早于开始时间 → 400（相等是允许的，只有「早于」才拒绝）。 */
    @Test
    void endTimeBeforeStartRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(),
                valid.startsAt(), valid.startsAt().minusHours(1),
                valid.registrationStartsAt(), valid.registrationEndsAt(), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("活动结束时间不能早于开始时间")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 「不能早于」按字面执行：结束时间等于开始时间（零时长活动）允许落库。 */
    @Test
    void endTimeEqualToStartIsAccepted() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(),
                valid.startsAt(), valid.startsAt(),
                valid.startsAt().minusDays(2), valid.startsAt(), valid.maxVolunteers(), valid.reviewRequired());

        service.create(ORG, request);

        ArgumentCaptor<VolunteerActivity> captor = ArgumentCaptor.forClass(VolunteerActivity.class);
        verify(activityMapper).insert(captor.capture());
        assertThat(captor.getValue().getEndsAt()).isEqualTo(captor.getValue().getStartsAt());
    }

    /** 报名结束早于报名开始 → 400。 */
    @Test
    void registrationEndBeforeRegistrationStartRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), valid.startsAt(), valid.endsAt(),
                valid.registrationStartsAt(), valid.registrationStartsAt().minusHours(1), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("报名结束时间不能早于报名开始时间")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 12:00 规则对活动开始时间生效。floor-1 秒恒为「前一天 23:59:59」，
     * 无论测试运行在中午前还是中午后都必然越界，用例因此与运行时刻无关。
     */
    @Test
    void activityStartBeforeEarliestAllowedDateRejected() {
        ActivityCreateRequest valid = validRequest();
        LocalDateTime startsAt = TimeUtil.earliestAllowedStart(LocalDateTime.now()).minusSeconds(1);
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(),
                startsAt, startsAt.plusHours(3),
                startsAt.plusMinutes(30), startsAt.plusHours(2), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("活动开始时间不能早于")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 12:00 规则同样约束报名开始时间。 */
    @Test
    void registrationStartBeforeEarliestAllowedDateRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(),
                valid.startsAt(), valid.endsAt(),
                TimeUtil.earliestAllowedStart(LocalDateTime.now()).minusSeconds(1),
                valid.startsAt().minusHours(1), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("报名开始时间不能早于")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 人数必须是 > 0 的数字：0 与 null 都在服务端被拒（DTO 上的 @Min(1)/@NotNull 是第一道）。 */
    @Test
    void nonPositiveVolunteerCountRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest zero = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), valid.startsAt(), valid.endsAt(),
                valid.registrationStartsAt(), valid.registrationEndsAt(), 0, false);
        ActivityCreateRequest nullCount = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), valid.startsAt(), valid.endsAt(),
                valid.registrationStartsAt(), valid.registrationEndsAt(), null, valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, zero))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("大于 0")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.create(ORG, nullCount))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("大于 0")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void activityStartInPastRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), LocalDateTime.now().minusDays(1),
                LocalDateTime.now().plusDays(1), valid.registrationStartsAt(), valid.registrationEndsAt(),
                valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void singleCoordinateRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), new BigDecimal("116.493729"), null, valid.startsAt(), valid.endsAt(),
                valid.registrationStartsAt(), valid.registrationEndsAt(), valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void coordinateOutOfRangeRejected() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), new BigDecimal("200.000000"), new BigDecimal("39.935524"),
                valid.startsAt(), valid.endsAt(), valid.registrationStartsAt(), valid.registrationEndsAt(),
                valid.maxVolunteers(), valid.reviewRequired());

        assertThatThrownBy(() -> service.create(ORG, request))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createPersistsRecruitingActivity() {
        ActivityResponse result = service.create(ORG, validRequest());

        ArgumentCaptor<VolunteerActivity> captor = ArgumentCaptor.forClass(VolunteerActivity.class);
        verify(activityMapper).insert(captor.capture());
        VolunteerActivity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("RECRUITING");
        assertThat(saved.getApprovedCount()).isZero();
        assertThat(saved.getOrganizationId()).isEqualTo(ORG);
        assertThat(saved.getLocationLng()).isEqualByComparingTo("116.493729");
        assertThat(saved.getRegistrationEndsAt()).isBeforeOrEqualTo(saved.getStartsAt());
        assertThat(saved.getReviewRequired()).isFalse();
        assertThat(result.status()).isEqualTo("RECRUITING");
        assertThat(result.approvedCount()).isZero();
    }

    /** 开启「需审核」要原样落库，报名流程据此决定先进 PENDING 还是直接通过。 */
    @Test
    void createPersistsReviewRequiredFlag() {
        ActivityCreateRequest valid = validRequest();
        ActivityCreateRequest request = new ActivityCreateRequest(valid.title(), valid.description(),
                valid.locationText(), valid.locationLng(), valid.locationLat(), valid.startsAt(), valid.endsAt(),
                valid.registrationStartsAt(), valid.registrationEndsAt(), valid.maxVolunteers(), true);

        service.create(ORG, request);

        ArgumentCaptor<VolunteerActivity> captor = ArgumentCaptor.forClass(VolunteerActivity.class);
        verify(activityMapper).insert(captor.capture());
        assertThat(captor.getValue().getReviewRequired()).isTrue();
    }

    @Test
    void listMineForcesOrganizationOwnership() {
        Page<VolunteerActivity> stubPage = new Page<>(1, 20, 1);
        stubPage.setRecords(List.of(activity()));
        when(activityMapper.selectPage(any(), any())).thenReturn(stubPage);

        IPage<ActivityResponse> result = service.listMy(ORG, null, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).organizationId()).isEqualTo(ORG);
    }

    @Test
    void listPublicDefaultsToRecruiting() {
        Page<VolunteerActivity> stubPage = new Page<>(1, 20, 1);
        stubPage.setRecords(List.of(activity()));
        when(activityMapper.selectPage(any(), any())).thenReturn(stubPage);

        IPage<ActivityResponse> result = service.listPublic(null, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).status()).isEqualTo("RECRUITING");
    }

    private ActivityCreateRequest validRequest() {
        return new ActivityCreateRequest("徒步陪伴", "陪伴视障人士徒步", "朝阳公园南门",
                new BigDecimal("116.493729"), new BigDecimal("39.935524"),
                LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(7).plusHours(3),
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(5), 20, false);
    }

    private Organization approvedOrg() {
        Organization org = new Organization();
        org.setId(ORG);
        org.setStatus("APPROVED");
        org.setPublicName("朝阳盲协");
        return org;
    }

    private VolunteerActivity activity() {
        VolunteerActivity activity = new VolunteerActivity();
        activity.setId("act-1");
        activity.setStatus("RECRUITING");
        activity.setOrganizationId(ORG);
        activity.setTitle("徒步陪伴");
        activity.setDescription("陪伴视障人士徒步");
        activity.setLocationText("朝阳公园南门");
        activity.setMaxVolunteers(20);
        activity.setApprovedCount(0);
        return activity;
    }
}
