package sullivan.serverjava.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI（Swagger）接口文档配置。
 *
 * <p>页面地址：{@code http://localhost:8080/swagger-ui.html}（端口随 server.port 变化）；
 * JSON 契约：{@code /v3/api-docs}。</p>
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI sullivanOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SullivanMINI 业务后端 API")
                        .version("0.1.0")
                        .description("""
                                视障人士出行辅助与公益服务平台（毕业设计 MINI 版）统一业务后端接口。
                                Java 是业务数据的唯一写入方，本服务对外提供账号、角色权限、监护绑定与管理员注册码等接口。
                                \n\n
                                ## 认证方式
                                \n
                                除注册、登录和健康检查外，其余接口都需要在请求头携带 Bearer 令牌：
                                \n
                                `Authorization: Bearer <登录返回的 accessToken>`
                                \n
                                JWT 访问令牌默认有效期为 1800 秒（30 分钟）。
                                """)
                        .license(new License().name("毕业设计内部使用")))
                .servers(List.of(
                        new Server().url("http://localhost:" + serverPort)//配置swagger的baseURL，但是注意，只需要配置到端口号，之后的路由会自动链接
                                .description("Java 业务应用（当前端口）")
//                      ,new Server().url("http://localhost:8080")
//                                .description("Java 业务应用（默认端口）")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的访问令牌（accessToken），格式：Bearer <token>")));
    }
}
