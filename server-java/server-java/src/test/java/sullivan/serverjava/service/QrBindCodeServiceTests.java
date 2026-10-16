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
import sullivan.serverjava.domain.ProtecteeBindCode;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.domain.UserProfile;
import sullivan.serverjava.mapper.ProtecteeBindCodeMapper;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.mapper.UserProfileMapper;
import sullivan.serverjava.web.QrDtos.BindCodeResponse;
import sullivan.serverjava.web.QrDtos.BindScanResponse;
import sullivan.serverjava.web.RegistrationDtos.BindingResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QrBindCodeServiceTests {
    private static final String PROTECTEE = "protectee-1";
    private static final String GUARDIAN = "guardian-1";
    private static final String OTHER_GUARDIAN = "guardian-2";
    /** 绑定码字母表（与 RandomCode 一致）：不含 0 O 1 I L U。 */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    private ProtecteeBindCodeMapper bindCodeMapper;
    private UserAccountMapper userAccountMapper;
    private UserProfileMapper userProfileMapper;
    private BindingService bindingService;
    private RoleGuard roleGuard;
    private QrBindCodeService service;

    /** 纯 Mockito 测试不加载 Spring，需手动初始化实体 lambda 缓存（同 RealtimeSessionServiceTests）。 */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProtecteeBindCode.class);
        TableInfoHelper.initTableInfo(assistant, UserProfile.class);
    }

    @BeforeEach
    void setUp() {
        bindCodeMapper = mock(ProtecteeBindCodeMapper.class);
        userAccountMapper = mock(UserAccountMapper.class);
        userProfileMapper = mock(UserProfileMapper.class);
        bindingService = mock(BindingService.class);
        roleGuard = mock(RoleGuard.class);
        service = new QrBindCodeService(bindCodeMapper, userAccountMapper, userProfileMapper,
                bindingService, roleGuard, 120);
        when(userAccountMapper.selectById(PROTECTEE)).thenReturn(account(PROTECTEE, "ENABLED"));
        UserProfile profile = new UserProfile();
        profile.setUserId(PROTECTEE);
        profile.setDisplayName("王小明");
        when(userProfileMapper.selectOne(any())).thenReturn(profile);
    }

    @Test
    void issueCodeRevokesPreviousAndSignsReadableCode() {
        BindCodeResponse response = service.issueCode(PROTECTEE);

        // 先作废旧码，保证 active_code_key 唯一键不被自己挡住。
        verify(bindCodeMapper).update(any(), any());
        ArgumentCaptor<ProtecteeBindCode> captor = ArgumentCaptor.forClass(ProtecteeBindCode.class);
        verify(bindCodeMapper).insert(captor.capture());
        ProtecteeBindCode inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getProtecteeUid()).isEqualTo(PROTECTEE);
        assertThat(inserted.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(response.code()).hasSize(12);
        assertThat(response.protecteeName()).isEqualTo("王小明");
        // 码必须只由可朗读字母表组成，否则「手动输入兜底」会在 0/O、1/I 上反复出错。
        assertThat(response.code()).matches("^[" + CODE_ALPHABET + "]{12}$");
    }

    @Test
    void issueCodeRejectedForDisabledProtectee() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "受护人账号已停用"))
                .when(roleGuard).requireEnabledProtectee(PROTECTEE);

        assertThatThrownBy(() -> service.issueCode(PROTECTEE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(bindCodeMapper, never()).insert(any(ProtecteeBindCode.class));
    }

    @Test
    void currentCodeReturnsNullWhenNoActiveCode() {
        when(bindCodeMapper.selectOne(any())).thenReturn(null);
        assertThat(service.currentCode(PROTECTEE)).isNull();
    }

    @Test
    void currentCodeReturnsRemainingTtl() {
        ProtecteeBindCode bindCode = bindCode(PROTECTEE, "ACTIVE", LocalDateTime.now().plusSeconds(90));
        when(bindCodeMapper.selectOne(any())).thenReturn(bindCode);

        BindCodeResponse response = service.currentCode(PROTECTEE);

        assertThat(response.code()).isEqualTo(bindCode.getCode());
        assertThat(response.ttlSeconds()).isBetween(85L, 90L);
    }

    @Test
    void scanUnknownCodeReturnsNotFound() {
        when(bindCodeMapper.selectById("NOPE")).thenReturn(null);

        assertThatThrownBy(() -> service.scan(GUARDIAN, "NOPE"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void scanRevokedCodeReturnsGone() {
        when(bindCodeMapper.selectById(anyString()))
                .thenReturn(bindCode(PROTECTEE, "REVOKED", LocalDateTime.now().plusSeconds(90)));

        assertThatThrownBy(() -> service.scan(GUARDIAN, "SOMECODE"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.GONE);
    }

    @Test
    void scanExpiredCodeReturnsGone() {
        when(bindCodeMapper.selectById(anyString()))
                .thenReturn(bindCode(PROTECTEE, "ACTIVE", LocalDateTime.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.scan(GUARDIAN, "SOMECODE"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.GONE);
        verify(bindingService, never()).bindViaScan(anyString(), anyString());
    }

    @Test
    void scanCreatesActiveBinding() {
        when(bindCodeMapper.selectById(anyString()))
                .thenReturn(bindCode(PROTECTEE, "ACTIVE", LocalDateTime.now().plusSeconds(90)));
        when(bindingService.bindViaScan(PROTECTEE, GUARDIAN))
                .thenReturn(new BindingService.ScanBindResult(
                        binding("b1", "ACTIVE", false), false));

        BindScanResponse response = service.scan(GUARDIAN, "SOMECODE");

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.alreadyBound()).isFalse();
        assertThat(response.protecteeId()).isEqualTo(PROTECTEE);
        assertThat(response.guardianId()).isEqualTo(GUARDIAN);
        assertThat(response.message()).isEqualTo("已绑定 王小明");
    }

    @Test
    void rescanningBySameGuardianIsIdempotentNotAnError() {
        when(bindCodeMapper.selectById(anyString()))
                .thenReturn(bindCode(PROTECTEE, "ACTIVE", LocalDateTime.now().plusSeconds(90)));
        when(bindingService.bindViaScan(PROTECTEE, OTHER_GUARDIAN))
                .thenReturn(new BindingService.ScanBindResult(
                        binding("b2", "ACTIVE", false), true));

        BindScanResponse response = service.scan(OTHER_GUARDIAN, "SOMECODE");

        assertThat(response.alreadyBound()).isTrue();
        assertThat(response.message()).isEqualTo("你已经是 王小明 的监护人");
    }

    @Test
    void expireExpiredCodesFlipsOnlyActiveRows() {
        service.expireExpiredCodes();
        verify(bindCodeMapper).update(any(), any());
    }

    private ProtecteeBindCode bindCode(String protecteeUid, String status, LocalDateTime expiresAt) {
        ProtecteeBindCode bindCode = new ProtecteeBindCode();
        bindCode.setCode("K7M2XQ9TB4ND");
        bindCode.setProtecteeUid(protecteeUid);
        bindCode.setStatus(status);
        bindCode.setExpiresAt(expiresAt);
        bindCode.setVersion(0L);
        return bindCode;
    }

    private BindingResponse binding(String id, String status, boolean primary) {
        return new BindingResponse(id, status, GUARDIAN, PROTECTEE, false, primary, GUARDIAN);
    }

    private UserAccount account(String id, String status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setStatus(status);
        account.setRoleCode("PROTECTEE");
        account.setLoginName(id);
        return account;
    }
}
