package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AbuseRestriction;
import sullivan.serverjava.mapper.AbuseRestrictionMapper;

import java.time.LocalDateTime;

/**
 * 限制守卫：业务入口处的前置检查（只读，不产生副作用）。
 *
 * <p>独立成一个只依赖 restriction 表的小服务，让活动发布/报名/举报等入口
 * 都能零环路地接入治理——处罚与阈值升级写在 {@link AbuseService}，这里只负责「拦」。</p>
 */
@Service
public class AbuseGuardService {

    public static final String KIND_REPORT_LOCKED = "REPORT_LOCKED";
    public static final String KIND_ACTIVITY_BANNED = "ACTIVITY_BANNED";
    public static final String KIND_ACCOUNT_BANNED = "ACCOUNT_BANNED";
    public static final String KIND_ORG_BANNED = "ORG_BANNED";
    /** 机构禁止发布活动（比机构封禁轻：不影响既有活动与群聊，只挡新建/重新发布） */
    public static final String KIND_PUBLISH_BANNED = "PUBLISH_BANNED";

    private final AbuseRestrictionMapper restrictionMapper;

    public AbuseGuardService(AbuseRestrictionMapper restrictionMapper) {
        this.restrictionMapper = restrictionMapper;
    }

    /** 用户是否有某类生效中的限制（expires_at 为空视为永久）。 */
    public boolean hasActiveUserRestriction(String userId, String kind) {
        return restrictionMapper.selectCount(Wrappers.<AbuseRestriction>lambdaQuery()
                .eq(AbuseRestriction::getUserId, userId)
                .eq(AbuseRestriction::getKind, kind)
                .eq(AbuseRestriction::getStatus, "ACTIVE")
                .and(w -> w.isNull(AbuseRestriction::getExpiresAt).or()
                        .gt(AbuseRestriction::getExpiresAt, LocalDateTime.now()))) > 0;
    }

    /** 机构是否有某类生效中的限制。 */
    public boolean hasActiveOrgRestriction(String orgId, String kind) {
        return restrictionMapper.selectCount(Wrappers.<AbuseRestriction>lambdaQuery()
                .eq(AbuseRestriction::getOrgId, orgId)
                .eq(AbuseRestriction::getKind, kind)
                .eq(AbuseRestriction::getStatus, "ACTIVE")
                .and(w -> w.isNull(AbuseRestriction::getExpiresAt).or()
                        .gt(AbuseRestriction::getExpiresAt, LocalDateTime.now()))) > 0;
    }

    public void assertCanReport(String reporterUid) {
        if (hasActiveUserRestriction(reporterUid, KIND_REPORT_LOCKED)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "你的举报功能已被锁定，如有异议请通过客服申诉");
        }
    }

    public void assertCanEnroll(String volunteerUid) {
        if (hasActiveUserRestriction(volunteerUid, KIND_ACCOUNT_BANNED)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "账户已被封禁，不能报名活动");
        }
        if (hasActiveUserRestriction(volunteerUid, KIND_ACTIVITY_BANNED)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "你已被限制参加活动，不能报名");
        }
    }

    public void assertOrgCanPublish(String organizationId) {
        if (hasActiveOrgRestriction(organizationId, KIND_ORG_BANNED)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "机构已被封禁，禁止发布新活动");
        }
        if (hasActiveOrgRestriction(organizationId, KIND_PUBLISH_BANNED)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "机构已被禁止发布活动");
        }
    }
}
