package sullivan.serverjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动就绪后打印简短横幅：端口、健康检查与接口文档地址。
 *
 * <p>全部路由列表不再打印（过长），改为 dev 环境的调试接口 GET /dev/routes，见 {@code DevInfoController}。</p>
 */
@Component
public class StartupInfoPrinter implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(StartupInfoPrinter.class);

    private final String serverPort;
    private final String appEnv;

    public StartupInfoPrinter(@Value("${server.port:8080}") String serverPort,
                              @Value("${app.env:dev}") String appEnv) {
        this.serverPort = serverPort;
        this.appEnv = appEnv;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("================================================================");
        log.info("SullivanMINI Java 业务后端已启动（运行环境：{}）", appEnv);
        log.info("服务端口    : {}", serverPort);
        log.info("健康检查    : http://localhost:{}/actuator/health", serverPort);
        log.info("Swagger UI  : http://localhost:{}/swagger-ui/index.html", serverPort);
        log.info("OpenAPI JSON: http://localhost:{}/v3/api-docs", serverPort);
        if ("dev".equals(appEnv)) {
            log.info("路由列表接口: http://localhost:{}/dev/routes（仅 dev 环境可用）", serverPort);
        }
        log.info("================================================================");
    }
}
