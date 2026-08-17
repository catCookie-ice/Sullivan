package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.domain.VolunteerApplication;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.mapper.VolunteerApplicationMapper;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationAdminResponse;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationResponse;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 监护人兼任志愿者申请：提交 → 管理员审核（通过事务内追加 VOLUNTEER 角色）。
 *
 * <p>申请限制：注册时间须满配置天数（默认 90 天），提交与审核两处校验。拒绝必填原因。</p>
 */
@Service
public class VolunteerApplicationService {
    private static final int MAX_PAGE_SIZE = 200;

    private final long minRegistrationDays;

    private final VolunteerApplicationMapper applicationMapper;
    private final UserAccountMapper userAccountMapper;
    private final UserProfileMapper userProfileMapper;
    private final UserRoleMapper userRoleMapper;

    public VolunteerApplicationService(VolunteerApplicationMapper applicationMapper,
                                       UserAccountMapper userAccountMapper, UserProfileMapper userProfileMapper,
                                       UserRoleMapper userRoleMapper,
                                       @Value("${app.volunteer.min-registration-days:90}") long minRegistrationDays) {
        this.applicationMapper = applicationMapper;
        this.userAccountMapper = userAccountMapper;
        this.userProfileMapper = userProfileMapper;
        this.userRoleMapper = userRoleMapper;
        this.minRegistrationDays = minRegistrationDays;
    }

    /** 提交志愿者兼任申请（幂等防重复 PENDING）。 */
    @Transactional
    public VolunteerApplicationResponse submit(String applicantUid, String reason) {
        UserAccount account = userAccountMapper.selectById(applicantUid);
        if (account == null || !"ENABLED".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账号不可用");
        }
        requireActiveRole(applicantUid, "GUARDIAN");
        if (hasActiveRole(applicantUid, "VOLUNTEER")) {
            throw new ApiException(HttpStatus.CONFLICT, "当前已是志愿者，无需重复申请");
        }
        requireRegistrationAge(applicantUid);
        Long duplicate = applicationMapper.selectCount(Wrappers.<VolunteerApplication>lambdaQuery()
                .eq(VolunteerApplication::getApplicantUid, applicantUid)
                .eq(VolunteerApplication::getStatus, "PENDING"));
        if (duplicate > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "已存在待审核的志愿者申请");
        }
        VolunteerApplication application = new VolunteerApplication();
        application.setId(IdGenerator.next());
        application.setStatus("PENDING");
        application.setApplicantUid(applicantUid);
        application.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "已存在待审核的志愿者申请");
        }
        return toResponse(application);
    }

    /** 我的申请列表。 */
    public List<VolunteerApplicationResponse> myApplications(String applicantUid) {
        return applicationMapper.selectList(Wrappers.<VolunteerApplication>lambdaQuery()
                        .eq(VolunteerApplication::getApplicantUid, applicantUid)
                        .orderByDesc(VolunteerApplication::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /** 撤销自己的 PENDING 申请。 */
    @Transactional
    public VolunteerApplicationResponse cancel(String applicantUid, String applicationId) {
        VolunteerApplication application = load(applicationId);
        if (!applicantUid.equals(application.getApplicantUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能撤销自己的申请");
        }
        int updated = conditionalUpdate(application, "CANCELLED", null);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "申请已处理，无法撤销");
        }
        application.setStatus("CANCELLED");
        return toResponse(application);
    }

    /** 管理员分页查看申请（含申请人登录名/显示名/注册时间）。 */
    public IPage<VolunteerApplicationAdminResponse> listForAdmin(String status, long page, long size) {
        LambdaQueryWrapper<VolunteerApplication> wrapper = Wrappers.lambdaQuery();
        if (status != null && !status.isBlank()) {
            wrapper.eq(VolunteerApplication::getStatus, status);
        }
        wrapper.orderByAsc(VolunteerApplication::getStatus).orderByDesc(VolunteerApplication::getCreatedAt);
        Page<VolunteerApplication> result = applicationMapper.selectPage(validatedPage(page, size), wrapper);

        List<String> userIds = result.getRecords().stream()
                .map(VolunteerApplication::getApplicantUid).distinct().toList();
        Map<String, UserAccount> accountMap = userIds.isEmpty() ? Map.of()
                : userAccountMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<String, UserProfile> profileMap = userIds.isEmpty() ? Map.of()
                : userProfileMapper.selectList(Wrappers.<UserProfile>lambdaQuery()
                                .in(UserProfile::getUserId, userIds)).stream()
                        .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));

        Page<VolunteerApplicationAdminResponse> out = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        out.setRecords(result.getRecords().stream().map(application -> {
            UserAccount account = accountMap.get(application.getApplicantUid());
            UserProfile profile = profileMap.get(application.getApplicantUid());
            return new VolunteerApplicationAdminResponse(application.getId(), application.getStatus(),
                    application.getApplicantUid(),
                    account != null ? account.getLoginName() : null,
                    profile != null ? profile.getDisplayName() : null,
                    account != null ? account.getCreatedAt() : null,
                    application.getReason(), application.getDecidedByAdminId(),
                    application.getDecidedAt(), application.getDecisionNote());
        }).toList());
        return out;
    }

    /** 审核通过：事务内追加 VOLUNTEER 角色并置 APPROVED（二次校验注册时长/角色/账号）。 */
    @Transactional
    public VolunteerApplicationResponse approve(String adminId, String applicationId) {
        VolunteerApplication application = load(applicationId);
        if (!"PENDING".equals(application.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "申请已处理");
        }
        requireRegistrationAge(application.getApplicantUid());
        requireActiveRole(application.getApplicantUid(), "GUARDIAN");
        UserAccount account = userAccountMapper.selectById(application.getApplicantUid());
        if (account == null || !"ENABLED".equals(account.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "申请人账号不可用");
        }
        int updated = conditionalUpdate(application, "APPROVED", null);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "申请已处理");
        }
        grantVolunteerRole(application.getApplicantUid(), adminId);
        application.setStatus("APPROVED");
        application.setDecidedByAdminId(adminId);
        application.setDecidedAt(LocalDateTime.now());
        return toResponse(application);
    }

    /** 审核拒绝（原因必填）。 */
    @Transactional
    public VolunteerApplicationResponse reject(String adminId, String applicationId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "拒绝原因必填");
        }
        VolunteerApplication application = load(applicationId);
        if (!"PENDING".equals(application.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "申请已处理");
        }
        int updated = conditionalUpdate(application, "REJECTED", reason.trim());
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "申请已处理");
        }
        application.setStatus("REJECTED");
        application.setDecidedByAdminId(adminId);
        application.setDecidedAt(LocalDateTime.now());
        application.setDecisionNote(reason.trim());
        return toResponse(application);
    }

    /** 幂等追加 VOLUNTEER 角色：无行则插入，REVOKED 则改回 ACTIVE。 */
    private void grantVolunteerRole(String userId, String adminId) {
        UserRole existing = userRoleMapper.selectOne(Wrappers.<UserRole>lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .eq(UserRole::getRoleCode, "VOLUNTEER"));
        if (existing == null) {
            UserRole role = new UserRole();
            role.setId(IdGenerator.next());
            role.setUserId(userId);
            role.setRoleCode("VOLUNTEER");
            role.setStatus("ACTIVE");
            role.setCreatedByUserId(adminId != null ? adminId : userId);
            userRoleMapper.insert(role);
        } else if (!"ACTIVE".equals(existing.getStatus())) {
            existing.setStatus("ACTIVE");
            userRoleMapper.updateById(existing);
        }
    }

    private void requireRegistrationAge(String applicantUid) {
        UserAccount account = userAccountMapper.selectById(applicantUid);
        LocalDateTime createdAt = account != null ? account.getCreatedAt() : null;
        if (createdAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法校验注册时间");
        }
        LocalDateTime eligible = createdAt.plusDays(minRegistrationDays);
        if (eligible.isAfter(LocalDateTime.now())) {
            long remaining = Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), eligible));
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "注册需满 " + minRegistrationDays + " 天才能申请，还差 " + remaining + " 天");
        }
    }

    private int conditionalUpdate(VolunteerApplication application, String status, String note) {
        var update = Wrappers.<VolunteerApplication>lambdaUpdate()
                .set(VolunteerApplication::getStatus, status)
                .set(VolunteerApplication::getDecidedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(VolunteerApplication::getId, application.getId())
                .eq(VolunteerApplication::getStatus, "PENDING");
        if (note != null && !note.isBlank()) {
            update.set(VolunteerApplication::getDecisionNote, note);
        }
        return applicationMapper.update(null, update);
    }

    private void requireActiveRole(String userId, String roleCode) {
        if (!hasActiveRole(userId, roleCode)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前用户不具备" + roleCode + "角色");
        }
    }

    private boolean hasActiveRole(String userId, String roleCode) {
        return userRoleMapper.selectCount(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleCode, roleCode)
                        .eq(UserRole::getStatus, "ACTIVE")) > 0
                || roleCode.equals(userAccountMapper.selectById(userId).getRoleCode());
    }

    private VolunteerApplication load(String applicationId) {
        VolunteerApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "申请不存在");
        }
        return application;
    }

    private Page<VolunteerApplication> validatedPage(long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return new Page<>(safePage, safeSize);
    }

    private VolunteerApplicationResponse toResponse(VolunteerApplication application) {
        return new VolunteerApplicationResponse(application.getId(), application.getStatus(),
                application.getApplicantUid(), application.getReason(), application.getCreatedAt());
    }
}
