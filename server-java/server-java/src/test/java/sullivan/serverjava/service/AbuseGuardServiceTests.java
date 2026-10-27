package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.AbuseRestriction;
import sullivan.serverjava.mapper.AbuseRestrictionMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 限制守卫：发布/报名/举报入口的拦截规则。 */
class AbuseGuardServiceTests {
    private AbuseRestrictionMapper restrictionMapper;
    private AbuseGuardService guard;

    @BeforeEach
    void setUp() {
        restrictionMapper = mock(AbuseRestrictionMapper.class);
        guard = new AbuseGuardService(restrictionMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AbuseRestriction.class);
    }

    @Test
    void orgBanBlocksPublishing() {
        when(restrictionMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> guard.assertOrgCanPublish("org-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("封禁");
    }

    @Test
    void publishBanBlocksPublishingWithDistinctMessage() {
        // 第一次查 ORG_BANNED（无）、第二次查 PUBLISH_BANNED（有）——命中独立的「禁止发布」分支。
        when(restrictionMapper.selectCount(any())).thenReturn(0L, 1L);
        assertThatThrownBy(() -> guard.assertOrgCanPublish("org-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁止发布活动");
    }

    @Test
    void userBansBlockEnrolling() {
        when(restrictionMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> guard.assertCanEnroll("usr-1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void reportLockBlocksReportingOnly() {
        when(restrictionMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> guard.assertCanReport("usr-1"))
                .isInstanceOf(ApiException.class);
        // 锁举报不禁活动：账号限制检查里 REPORT_LOCKED 不参与（此处仅验证守卫不抛错）。
        when(restrictionMapper.selectCount(any())).thenReturn(0L);
        assertThatCode(() -> {
            guard.assertCanEnroll("usr-1");
            guard.assertOrgCanPublish("org-1");
            guard.assertCanReport("usr-1");
        }).doesNotThrowAnyException();
    }
}
