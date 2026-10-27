package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公益活动：机构（APPROVED）发布，含报名窗口与高德经纬度。
 * 报名者清单由 activity_enrollment 承载；本服务不含金额字段。
 */
@Service
public class VolunteerActivityService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal MAX_LNG = new BigDecimal("180.000000");
    private static final BigDecimal MAX_LAT = new BigDecimal("90.000000");

    private final VolunteerActivityMapper activityMapper;
    private final OrganizationMapper organizationMapper;
    private final AbuseGuardService abuseGuardService;

    public VolunteerActivityService(VolunteerActivityMapper activityMapper, OrganizationMapper organizationMapper,
                                    AbuseGuardService abuseGuardService) {
        this.activityMapper = activityMapper;
        this.organizationMapper = organizationMapper;
        this.abuseGuardService = abuseGuardService;
    }

    /**
     * 机构发布活动：机构须 APPROVED；时间/人数/经纬度校验；状态由服务端固定为 RECRUITING。
     *
     * <p>时间规则（org-web 创建表单保持同口径，前后端双向校验）：
     * ① 活动开始、报名开始两个「开始时间」都不得早于 12:00 规则的下限
     *    （{@link TimeUtil#earliestAllowedStart}：12:00 前创建 → 今天零点；12:00 及之后 → 明天零点）；
     * ② 活动结束不得早于活动开始，报名结束不得早于报名开始（「不能早于」允许相等）；
     * ③ 报名开始、报名截止都不得晚于活动开始；
     * ④ 活动开始仍须晚于当前时间（留 60 秒时钟余量）。</p>
     */
    @Transactional
    public ActivityResponse create(String organizationId, ActivityCreateRequest request) {
        Organization organization = organizationMapper.selectById(organizationId);
        if (organization == null || !"APPROVED".equals(organization.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "机构未认证，不能发布活动");
        }
        // 治理守卫：被封禁的机构禁止发布新活动（机构封禁同时已附带下架其全部未结束活动）。
        abuseGuardService.assertOrgCanPublish(organizationId);
        if (request.maxVolunteers() == null || request.maxVolunteers() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "需要的志愿者人数必须大于 0");
        }
        LocalDateTime now = LocalDateTime.now();
        requireNotBeforeEarliestStart(request.startsAt(), now, "活动开始时间");
        requireNotBeforeEarliestStart(request.registrationStartsAt(), now, "报名开始时间");
        if (!request.startsAt().isAfter(now.plusSeconds(60))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "活动开始时间必须晚于当前时间");
        }
        if (request.endsAt().isBefore(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "活动结束时间不能早于开始时间");
        }
        if (request.registrationStartsAt().isAfter(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报名开始时间不能晚于活动开始时间");
        }
        if (request.registrationEndsAt().isBefore(request.registrationStartsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报名结束时间不能早于报名开始时间");
        }
        if (request.registrationEndsAt().isAfter(request.startsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "报名截止时间不能晚于活动开始时间");
        }
        validateCoordinates(request.locationLng(), request.locationLat());

        VolunteerActivity activity = new VolunteerActivity();
        activity.setId(IdGenerator.next());
        activity.setStatus("RECRUITING");
        activity.setOrganizationId(organizationId);
        activity.setTitle(request.title().trim());
        activity.setDescription(request.description().trim());
        activity.setLocationText(request.locationText().trim());
        activity.setLocationLng(request.locationLng());
        activity.setLocationLat(request.locationLat());
        activity.setStartsAt(request.startsAt());
        activity.setEndsAt(request.endsAt());
        activity.setRegistrationStartsAt(request.registrationStartsAt());
        activity.setRegistrationEndsAt(request.registrationEndsAt());
        activity.setMaxVolunteers(request.maxVolunteers());
        activity.setReviewRequired(Boolean.TRUE.equals(request.reviewRequired()));
        activity.setApprovedCount(0);
        activity.setIsFree(true);
        activity.setCreatedAt(LocalDateTime.now());
        activityMapper.insert(activity);
        return toResponse(activity, organization.getPublicName());
    }

    /** 我的活动列表（机构归属强制为令牌主体）。 */
    public IPage<ActivityResponse> listMy(String organizationId, String status, long page, long size) {
        LambdaQueryWrapper<VolunteerActivity> wrapper = Wrappers.<VolunteerActivity>lambdaQuery()
                .eq(VolunteerActivity::getOrganizationId, organizationId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(VolunteerActivity::getStatus, status);
        }
        wrapper.orderByDesc(VolunteerActivity::getCreatedAt);
        Page<VolunteerActivity> result = activityMapper.selectPage(validatedPage(page, size), wrapper);
        Organization organization = organizationMapper.selectById(organizationId);
        String publicName = organization != null ? organization.getPublicName() : null;
        return toPage(result, publicName);
    }

    /** 公开活动列表：默认只出招募中活动，不含报名者清单等非公开字段。 */
    public IPage<ActivityResponse> listPublic(String status, long page, long size) {
        String effectiveStatus = (status == null || status.isBlank()) ? "RECRUITING" : status;
        LambdaQueryWrapper<VolunteerActivity> wrapper = Wrappers.<VolunteerActivity>lambdaQuery()
                .eq(VolunteerActivity::getStatus, effectiveStatus)
                .orderByAsc(VolunteerActivity::getStartsAt);
        Page<VolunteerActivity> result = activityMapper.selectPage(validatedPage(page, size), wrapper);

        List<String> orgIds = result.getRecords().stream()
                .map(VolunteerActivity::getOrganizationId).distinct().toList();
        Map<String, String> orgNames = orgIds.isEmpty() ? Map.of()
                : organizationMapper.selectBatchIds(orgIds).stream()
                        .collect(Collectors.toMap(Organization::getId, Organization::getPublicName, (a, b) -> a));
        Page<ActivityResponse> out = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream()
                .map(activity -> toResponse(activity, orgNames.get(activity.getOrganizationId())))
                .toList());
        return out;
    }

    /** 12:00 规则（{@link TimeUtil#earliestAllowedStart}）：开始时间不得早于今天/明天零点，报错里带上具体下限。 */
    private void requireNotBeforeEarliestStart(LocalDateTime start, LocalDateTime now, String label) {
        LocalDateTime floor = TimeUtil.earliestAllowedStart(now);
        if (start.isBefore(floor)) {
            String earliest = floor.toLocalDate().equals(now.toLocalDate()) ? "今天" : "明天";
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    label + "不能早于" + earliest + "（" + TimeUtil.format(floor) + "）");
        }
    }

    private void validateCoordinates(BigDecimal lng, BigDecimal lat) {
        boolean lngEmpty = lng == null;
        boolean latEmpty = lat == null;
        if (lngEmpty != latEmpty) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "经纬度必须同时填写或同时留空");
        }
        if (!lngEmpty) {
            if (lng.compareTo(MAX_LNG.negate()) < 0 || lng.compareTo(MAX_LNG) > 0
                    || lat.compareTo(MAX_LAT.negate()) < 0 || lat.compareTo(MAX_LAT) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "经纬度超出合法范围");
            }
        }
    }

    private Page<VolunteerActivity> validatedPage(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new Page<>(safePage, safeSize);
    }

    private IPage<ActivityResponse> toPage(Page<VolunteerActivity> page, String orgPublicName) {
        Page<ActivityResponse> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(page.getRecords().stream()
                .map(activity -> toResponse(activity, orgPublicName)).toList());
        return out;
    }

    private ActivityResponse toResponse(VolunteerActivity activity, String orgPublicName) {
        return new ActivityResponse(activity.getId(), activity.getStatus(), activity.getOrganizationId(),
                orgPublicName, activity.getTitle(), activity.getDescription(), activity.getLocationText(),
                activity.getLocationLng(), activity.getLocationLat(), activity.getStartsAt(), activity.getEndsAt(),
                activity.getRegistrationStartsAt(), activity.getRegistrationEndsAt(),
                activity.getMaxVolunteers(), activity.getReviewRequired(), activity.getApprovedCount(),
                activity.getCreatedAt());
    }
}
