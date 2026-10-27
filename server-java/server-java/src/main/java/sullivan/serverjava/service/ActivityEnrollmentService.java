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
import sullivan.serverjava.domain.ActivityEnrollment;
import sullivan.serverjava.domain.Organization;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.domain.VolunteerActivity;
import sullivan.serverjava.mapper.ActivityEnrollmentMapper;
import sullivan.serverjava.mapper.OrganizationMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.mapper.VolunteerActivityMapper;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentPage;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentResponse;
import sullivan.serverjava.web.EnrollmentDtos.EnrollmentSummary;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动报名：志愿者报名，机构（开启「需审核」时）审核。
 *
 * <p><b>名额口径</b>：{@code approved_count} 只统计 APPROVED。直接报名（未开审核）
 * 与机构批准走同一个条件更新占位（{@code approved_count < max_volunteers}），
 * 数据库 CHECK（quota）再兜一层；满员返回 409 而不是让 CHECK 炸成 500。</p>
 *
 * <p><b>审核的并发口径</b>：先把报名行 PENDING→APPROVED 条件更新（保证只有一次裁决），
 * 再占名额——占满抛 409 让整个事务回滚，报名行退回 PENDING，机构可改判他人。
 * 入群放在最后，同样随事务回滚。</p>
 */
@Service
public class ActivityEnrollmentService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ActivityEnrollmentMapper enrollmentMapper;
    private final VolunteerActivityMapper activityMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final OrganizationMapper organizationMapper;
    private final ActivityGroupService groupService;
    private final NotificationService notificationService;
    private final AbuseGuardService abuseGuardService;

    public ActivityEnrollmentService(ActivityEnrollmentMapper enrollmentMapper,
                                     VolunteerActivityMapper activityMapper,
                                     UserRoleMapper userRoleMapper,
                                     UserAccountMapper userAccountMapper,
                                     UserProfileMapper userProfileMapper,
                                     OrganizationMapper organizationMapper,
                                     ActivityGroupService groupService,
                                     NotificationService notificationService,
                                     AbuseGuardService abuseGuardService) {
        this.enrollmentMapper = enrollmentMapper;
        this.activityMapper = activityMapper;
        this.userRoleMapper = userRoleMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.organizationMapper = organizationMapper;
        this.groupService = groupService;
        this.notificationService = notificationService;
        this.abuseGuardService = abuseGuardService;
    }

    /**
     * 志愿者报名：活动须 RECRUITING 且处于报名窗口内；未开审核直接通过入群，
     * 开了审核进 PENDING 等机构裁决。
     */
    @Transactional
    public EnrollmentResponse enroll(String volunteerUid, String activityId) {
        requireVolunteerRole(volunteerUid);
        // 治理守卫：被封禁/被限制参加活动的账号不能报名。
        abuseGuardService.assertCanEnroll(volunteerUid);
        VolunteerActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        }
        if (!"RECRUITING".equals(activity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动当前状态不可报名");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getRegistrationStartsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "报名尚未开始");
        }
        if (now.isAfter(activity.getRegistrationEndsAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "报名已截止");
        }
        Long duplicate = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getVolunteerUid, volunteerUid)
                .in(ActivityEnrollment::getStatus, "PENDING", "APPROVED"));
        if (duplicate > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "已报名该活动，无需重复报名");
        }
        ActivityEnrollment enrollment = new ActivityEnrollment();
        enrollment.setId(IdGenerator.next());
        enrollment.setActivityId(activityId);
        enrollment.setVolunteerUid(volunteerUid);
        if (Boolean.TRUE.equals(activity.getReviewRequired())) {
            enrollment.setStatus("PENDING");
            enrollmentMapper.insert(enrollment);
            // 待审核通知给机构（角色不同，通知不同）：铃铛点亮，点进去直达报名审核页。
            notificationService.notify(activity.getOrganizationId(), "ORGANIZATION",
                    NotificationService.TYPE_ENROLLMENT_PENDING,
                    "新的报名待审核",
                    volunteerDisplayName(volunteerUid) + " 报名了「" + activity.getTitle() + "」，请及时审核",
                    "/enrollments");
        } else {
            // 直接通过：先标记 APPROVED，再占名额——占满抛 409 连同报名行一起回滚。
            enrollment.setStatus("APPROVED");
            enrollment.setDecidedAt(now);
            enrollmentMapper.insert(enrollment);
            occupyQuota(activityId);
            groupService.joinActivityGroup(activity, volunteerUid);
        }
        return toResponse(enrollmentMapper.selectById(enrollment.getId()));
    }

    /** 志愿者撤回报名：PENDING 直接撤；APPROVED 退出群并释放名额。 */
    @Transactional
    public EnrollmentResponse cancel(String volunteerUid, String enrollmentId) {
        ActivityEnrollment enrollment = enrollmentMapper.selectById(enrollmentId);
        if (enrollment == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报名不存在");
        }
        if (!enrollment.getVolunteerUid().equals(volunteerUid)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能撤回自己的报名");
        }
        if ("PENDING".equals(enrollment.getStatus())) {
            int updated = conditionalTransition(enrollment, "CANCELLED", null);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "报名状态已变化，请刷新后重试");
            }
        } else if ("APPROVED".equals(enrollment.getStatus())) {
            int updated = conditionalTransition(enrollment, "CANCELLED", null);
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "报名状态已变化，请刷新后重试");
            }
            releaseQuota(enrollment.getActivityId());
            groupService.removeFromActivityGroup(enrollment.getActivityId(), volunteerUid);
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "当前状态不能撤回");
        }
        return toResponse(enrollmentMapper.selectById(enrollmentId));
    }

    /** 机构裁决报名（仅本机构活动、仅 PENDING 可裁）。通过则占名额并入群。 */
    @Transactional
    public EnrollmentResponse decide(String organizationId, String enrollmentId, boolean approve, String reason) {
        ActivityEnrollment enrollment = enrollmentMapper.selectById(enrollmentId);
        if (enrollment == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报名不存在");
        }
        VolunteerActivity activity = activityMapper.selectById(enrollment.getActivityId());
        if (activity == null || !activity.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能审核本机构活动的报名");
        }
        if (!"PENDING".equals(enrollment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该报名已处理");
        }
        int updated = conditionalTransition(enrollment, approve ? "APPROVED" : "REJECTED", reason);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "该报名已处理");
        }
        if (approve) {
            // 名额占满抛 409，本事务回滚：报名行退回 PENDING，机构可改判别人。
            occupyQuota(activity.getId());
            groupService.joinActivityGroup(activity, enrollment.getVolunteerUid());
            // 通知放在占名额/入群都成功之后：万一回滚，不会留下「已通过」的假通知。
            notificationService.notify(enrollment.getVolunteerUid(), "USER",
                    NotificationService.TYPE_ENROLLMENT_APPROVED,
                    "报名审核通过",
                    "你报名的「" + activity.getTitle() + "」已通过审核，快去活动群聊看看吧",
                    "/volunteer/group-chat?activityId=" + activity.getId());
        } else {
            notificationService.notify(enrollment.getVolunteerUid(), "USER",
                    NotificationService.TYPE_ENROLLMENT_REJECTED,
                    "报名未通过",
                    "你报名的「" + activity.getTitle() + "」未通过审核"
                            + (reason == null || reason.isBlank() ? "" : "：" + reason.strip()),
                    "/volunteer/enrollments");
        }
        return toResponse(enrollmentMapper.selectById(enrollmentId));
    }

    /** 机构查看某活动的报名列表（分页 + 各状态统计；总报名人数含未通过的）。 */
    public EnrollmentPage listForActivity(String organizationId, String activityId, String status,
                                          long page, long size) {
        VolunteerActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        }
        if (!activity.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能查看本机构活动的报名");
        }
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LambdaQueryWrapper<ActivityEnrollment> wrapper = Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(status != null && !status.isBlank(), ActivityEnrollment::getStatus, status)
                .orderByDesc(ActivityEnrollment::getCreatedAt);
        Page<ActivityEnrollment> result = enrollmentMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        EnrollmentSummary summary = summarize(activityId);
        return new EnrollmentPage(toResponses(result.getRecords(), activity), result.getTotal(), safePage, safeSize,
                summary);
    }

    /** 我的报名列表。 */
    public IPage<EnrollmentResponse> myEnrollments(String volunteerUid, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<ActivityEnrollment> result = enrollmentMapper.selectPage(new Page<>(safePage, safeSize),
                Wrappers.<ActivityEnrollment>lambdaQuery()
                        .eq(ActivityEnrollment::getVolunteerUid, volunteerUid)
                        .orderByDesc(ActivityEnrollment::getCreatedAt));
        Page<EnrollmentResponse> out = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(toResponses(result.getRecords(), null));
        return out;
    }

    /**
     * 治理链路踢人：把某人从所有未结束活动中移出（APPROVED 释放名额退群，
     * PENDING 一并取消；已结束/已取消的报名不动——历史不是惩罚对象）。
     * 供账户封禁/禁参加活动时调用；返回实际踢出的活动数。
     */
    @Transactional
    public int kickFromAllActiveActivities(String volunteerUid, String reason) {
        List<ActivityEnrollment> rows = enrollmentMapper.selectList(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getVolunteerUid, volunteerUid)
                .in(ActivityEnrollment::getStatus, "PENDING", "APPROVED"));
        int kicked = 0;
        for (ActivityEnrollment row : rows) {
            VolunteerActivity activity = activityMapper.selectById(row.getActivityId());
            if (activity == null || "ENDED".equals(activity.getStatus()) || "CANCELLED".equals(activity.getStatus())) {
                continue;
            }
            int updated = conditionalTransition(row, "CANCELLED", reason);
            if (updated == 0) {
                continue;
            }
            if ("APPROVED".equals(row.getStatus())) {
                releaseQuota(activity.getId());
                groupService.removeFromActivityGroup(activity.getId(), volunteerUid);
            }
            kicked++;
        }
        return kicked;
    }

    // ---------- 名额（条件更新 + CHECK 兜底） ----------

    /** 占名额：条件更新防并发超卖；0 行说明已满。 */
    private void occupyQuota(String activityId) {
        int updated = activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .setSql("approved_count = approved_count + 1")
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getId, activityId)
                .apply("approved_count < max_volunteers"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "志愿者名额已满");
        }
    }

    /** 释放名额：只在确实大于 0 时减，避免历史脏数据减成负数（CHECK 会炸成 500）。 */
    private void releaseQuota(String activityId) {
        activityMapper.update(null, Wrappers.<VolunteerActivity>lambdaUpdate()
                .setSql("approved_count = approved_count - 1")
                .setSql("version = version + 1")
                .eq(VolunteerActivity::getId, activityId)
                .apply("approved_count > 0"));
    }

    private int conditionalTransition(ActivityEnrollment enrollment, String target, String reason) {
        var update = Wrappers.<ActivityEnrollment>lambdaUpdate()
                .set(ActivityEnrollment::getStatus, target)
                .set(ActivityEnrollment::getDecidedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(ActivityEnrollment::getId, enrollment.getId())
                .eq(ActivityEnrollment::getStatus, enrollment.getStatus());
        if (reason != null && !reason.isBlank()) {
            update.set(ActivityEnrollment::getDecisionReason, reason.strip());
        }
        return enrollmentMapper.update(null, update);
    }

    private EnrollmentSummary summarize(String activityId) {
        long total = enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId));
        long pending = countByStatus(activityId, "PENDING");
        long approved = countByStatus(activityId, "APPROVED");
        long rejected = countByStatus(activityId, "REJECTED");
        long cancelled = countByStatus(activityId, "CANCELLED");
        return new EnrollmentSummary(total, pending, approved, rejected, cancelled);
    }

    private long countByStatus(String activityId, String status) {
        return enrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId)
                .eq(ActivityEnrollment::getStatus, status));
    }

    // ---------- 角色与装配 ----------

    /** 志愿者展示名（通知文案用）：昵称 → 登录名 → uid。 */
    private String volunteerDisplayName(String volunteerUid) {
        UserProfile profile = userProfileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, volunteerUid));
        if (profile != null && profile.getDisplayName() != null && !profile.getDisplayName().isBlank()) {
            return profile.getDisplayName();
        }
        UserAccount account = userAccountMapper.selectById(volunteerUid);
        return account == null || account.getLoginName() == null ? volunteerUid : account.getLoginName();
    }

    /** 志愿者角色校验：有 ACTIVE 的 VOLUNTEER 角色行，或账号主角色即 VOLUNTEER。 */
    private void requireVolunteerRole(String volunteerUid) {
        Long count = userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, volunteerUid)
                .eq(UserRole::getRoleCode, "VOLUNTEER")
                .eq(UserRole::getStatus, "ACTIVE"));
        if (count > 0) {
            return;
        }
        UserAccount account = userAccountMapper.selectById(volunteerUid);
        if (account != null && "VOLUNTEER".equals(account.getRoleCode())) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "只有志愿者可以报名活动");
    }

    private List<EnrollmentResponse> toResponses(List<ActivityEnrollment> records, VolunteerActivity fixedActivity) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> volunteerIds = new HashSet<>();
        Set<String> activityIds = new HashSet<>();
        for (ActivityEnrollment record : records) {
            volunteerIds.add(record.getVolunteerUid());
            activityIds.add(record.getActivityId());
        }
        if (fixedActivity != null) {
            activityIds.add(fixedActivity.getId());
        }
        Map<String, UserAccount> accounts = new HashMap<>();
        userAccountMapper.selectBatchIds(volunteerIds).forEach(account -> accounts.put(account.getId(), account));
        Map<String, String> displayNames = new HashMap<>();
        userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery().in(UserProfile::getUserId, volunteerIds))
                .forEach(profile -> displayNames.put(profile.getUserId(), profile.getDisplayName()));
        Map<String, VolunteerActivity> activities = new HashMap<>();
        activityMapper.selectBatchIds(activityIds).forEach(activity -> activities.put(activity.getId(), activity));
        Set<String> orgIds = new HashSet<>();
        activities.values().forEach(activity -> orgIds.add(activity.getOrganizationId()));
        Map<String, Organization> organizations = new HashMap<>();
        if (!orgIds.isEmpty()) {
            organizationMapper.selectBatchIds(orgIds)
                    .forEach(organization -> organizations.put(organization.getId(), organization));
        }
        return records.stream()
                .map(record -> toResponse(record, recordOf(activities, record, fixedActivity),
                        accounts.get(record.getVolunteerUid()), displayNames.get(record.getVolunteerUid()),
                        organizations))
                .toList();
    }

    private VolunteerActivity recordOf(Map<String, VolunteerActivity> activities, ActivityEnrollment record,
                                       VolunteerActivity fixedActivity) {
        if (fixedActivity != null && fixedActivity.getId().equals(record.getActivityId())) {
            return fixedActivity;
        }
        return activities.get(record.getActivityId());
    }

    private EnrollmentResponse toResponse(ActivityEnrollment enrollment) {
        return toResponses(List.of(enrollment), null).get(0);
    }

    private EnrollmentResponse toResponse(ActivityEnrollment enrollment, VolunteerActivity activity,
                                          UserAccount account, String displayName,
                                          Map<String, Organization> organizations) {
        String orgName = null;
        if (activity != null) {
            Organization organization = organizations.get(activity.getOrganizationId());
            orgName = organization == null ? null : organization.getPublicName();
        }
        return new EnrollmentResponse(enrollment.getId(), enrollment.getActivityId(),
                activity == null ? null : activity.getTitle(), orgName,
                enrollment.getVolunteerUid(),
                account == null ? null : account.getLoginName(),
                displayName == null || displayName.isBlank()
                        ? (account == null ? null : account.getLoginName()) : displayName,
                enrollment.getStatus(), enrollment.getDecisionReason(), enrollment.getDecidedAt(),
                enrollment.getCreatedAt());
    }
}
