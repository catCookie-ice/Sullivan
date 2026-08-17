package sullivan.serverjava.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用定时任务（绑定过期扫描等 @Scheduled 方法）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
