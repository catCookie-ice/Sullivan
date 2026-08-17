package sullivan.serverjava.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register/user", "/api/v1/auth/register/organization",
                                "/api/v1/auth/register/admin", "/api/v1/auth/login", "/actuator/health").permitAll()
                        // 找回密码 start/verify 公开；me/recovery 系列需登录（anyRequest 统一拦截）
                        .requestMatchers("/api/v1/auth/password-recovery/start",
                                "/api/v1/auth/password-recovery/verify").permitAll()
                        // OpenAPI 接口文档（Swagger UI / JSON 契约）免认证访问
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                                "/api-docs", "/api-docs/**").permitAll()
                        // 开发调试路由接口（仅 dev 环境注册了处理器；prod 下该路径无处理器返回 404）
                        .requestMatchers("/dev/routes").permitAll()
                        // 实时长连接 WebSocket 端点：握手鉴权由 RealtimeHandshakeInterceptor 完成
                        .requestMatchers("/ws/**").permitAll()
                        // 错误页渲染不拦截，避免未知路径在授权后转发 /error 时再被拦截
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
