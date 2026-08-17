package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserRole;
import sullivan.serverjava.domain.VolunteerApplication;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.mapper.UserRoleMapper;
import sullivan.serverjava.mapper.VolunteerApplicationMapper;
import sullivan.serverjava.web.VolunteerDtos.VolunteerApplicationResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VolunteerApplicationServiceTests {
    private static final String APPLICANT = "guardian-1";

    private VolunteerApplicationMapper applicationMapper;
    private UserAccountMapper userAccountMapper;
    private UserRoleMapper userRoleMapper;
    private VolunteerApplicationService service;

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                VolunteerApplication.class);
    }

    @BeforeEach
    void setUp() {
        applicationMapper = mock(VolunteerApplicationMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        service = new VolunteerApplicationService(applicationMapper, userAccountMapper,
                mock(UserProfileMapper.class), userRoleMapper, 90);
        when(userAccountMapper.selectById(APPLICANT)).thenReturn(account(APPLICANT, LocalDateTime.now().minusDays(100)));
    }

    @Test
    void submitRejectedWhenRegistrationTooNew() {
        when(userAccountMapper.selectById(APPLICANT)).thenReturn(account(APPLICANT, LocalDateTime.now().minusDays(30)));
        when(userRoleMapper.selectCount(any())).thenReturn(1L, 0L);

        ApiException exception = (ApiException) catchThrowable(() -> service.submit(APPLICANT, null));

        assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getMessage()).contains("还差");
    }

    @Test
    void submitCreatesPendingApplication() {
        when(userRoleMapper.selectCount(any())).thenReturn(1L, 0L, 0L);

        VolunteerApplicationResponse result = service.submit(APPLICANT, "希望参与志愿活动");

        ArgumentCaptor<VolunteerApplication> captor = ArgumentCaptor.forClass(VolunteerApplication.class);
        verify(applicationMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getApplicantUid()).isEqualTo(APPLICANT);
        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void duplicatePendingApplicationReturnsConflict() {
        when(userRoleMapper.selectCount(any())).thenReturn(1L, 0L);
        when(applicationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submit(APPLICANT, null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void approveGrantsVolunteerRoleAndMarksApproved() {
        VolunteerApplication application = pendingApplication();
        when(applicationMapper.selectById("va1")).thenReturn(application);
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(applicationMapper.update(any(), any())).thenReturn(1);
        when(userRoleMapper.selectOne(any())).thenReturn(null);

        VolunteerApplicationResponse result = service.approve("admin-1", "va1");

        assertThat(result.status()).isEqualTo("APPROVED");
        ArgumentCaptor<UserRole> roleCaptor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoleMapper).insert(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getRoleCode()).isEqualTo("VOLUNTEER");
        assertThat(roleCaptor.getValue().getUserId()).isEqualTo(APPLICANT);
    }

    @Test
    void approveRechecksRegistrationAge() {
        VolunteerApplication application = pendingApplication();
        when(applicationMapper.selectById("va1")).thenReturn(application);
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        // 审核时配置或账号年龄已不满足（注册时间过近）
        when(userAccountMapper.selectById(APPLICANT)).thenReturn(account(APPLICANT, LocalDateTime.now().minusDays(10)));

        assertThatThrownBy(() -> service.approve("admin-1", "va1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectRequiresReason() {
        VolunteerApplication application = pendingApplication();
        when(applicationMapper.selectById("va1")).thenReturn(application);

        assertThatThrownBy(() -> service.reject("admin-1", "va1", "  "))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void applicantCancelsOwnPending() {
        VolunteerApplication application = pendingApplication();
        when(applicationMapper.selectById("va1")).thenReturn(application);
        when(applicationMapper.update(any(), any())).thenReturn(1);

        VolunteerApplicationResponse result = service.cancel(APPLICANT, "va1");

        assertThat(result.status()).isEqualTo("CANCELLED");
    }

    private VolunteerApplication pendingApplication() {
        VolunteerApplication application = new VolunteerApplication();
        application.setId("va1");
        application.setStatus("PENDING");
        application.setApplicantUid(APPLICANT);
        application.setReason("希望参与志愿活动");
        application.setCreatedAt(LocalDateTime.now());
        return application;
    }

    private UserAccount account(String id, LocalDateTime createdAt) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus("ENABLED");
        account.setRoleCode("GUARDIAN");
        account.setCreatedAt(createdAt);
        return account;
    }
}
