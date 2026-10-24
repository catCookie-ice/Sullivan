package sullivan.serverjava.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.mapper.UserAccountMapper;
import sullivan.serverjava.security.JwtService;
import sullivan.serverjava.web.RegistrationDtos.LoginRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 登录入口的参数兜底。
 *
 * <p>这个测试类只盯一件事：**缺 {@code principalType} 应当是 401，不是 500**。
 *
 * <p>起因是 2026-10-24 做报警闭环时手工跑 HTTP 发现的：不带 {@code principalType}
 * 调 {@code POST /api/v1/auth/login}，返回的是 500 而不是 400/401。
 * 根因是 {@code AuthService.login} 里写了
 * {@code switch (request.principalType())} —— Java 的 switch 选择器为 null 时
 * 直接抛 NPE（不是走 default），于是这个纯粹的前端疏忽变成了"服务器内部错误"。
 *
 * <p>为什么一个 500 值得单独写测试：前端看到 500 会去查服务端日志、怀疑数据库、
 * 怀疑部署，而真正的原因是它自己少传了一个字段。错误码的**语义正确性**是
 * 可诊断性的一部分，不只是洁癖。
 */
class AuthServiceLoginTests {

    private static AuthService service() {
        // 只测"进不到查库那一步"的路径，依赖全部用 mock 顶上。
        return new AuthService(
                mock(UserAccountMapper.class),
                mock(sullivan.serverjava.mapper.UserRoleMapper.class),
                mock(sullivan.serverjava.mapper.OrganizationMapper.class),
                mock(sullivan.serverjava.mapper.AdminAccountMapper.class),
                mock(AdminAccessService.class),
                mock(RecoveryService.class),
                new BCryptPasswordEncoder(),
                mock(JwtService.class));
    }

    /** 直接调 service：即便校验被绕过，也不能抛 NPE。 */
    @Test
    void missingPrincipalTypeIsUnauthorizedNotNpe() {
        AuthService service = service();
        LoginRequest request = new LoginRequest(null, "someone", "Passw0rd!23");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** 未知的 principalType 走 default 分支，同样是 401。 */
    @Test
    void unknownPrincipalTypeIsUnauthorized() {
        AuthService service = service();
        LoginRequest request = new LoginRequest("NOT_A_TYPE", "someone", "Passw0rd!23");

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
