package sullivan.serverjava.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sullivan.serverjava.common.TimeUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Jackson 时间格式全局配置。
 *
 * <p>LocalDateTime / LocalDate / LocalTime 统一按 {@link TimeUtil} 的格式序列化与反序列化，
 * 使所有接口的响应时间字段与请求时间字段格式一致（如 {@code 2026-08-08 16:25:08}）。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(TimeUtil.DATE_TIME_FORMATTER))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(TimeUtil.DATE_TIME_FORMATTER))
                .serializerByType(LocalDate.class, new LocalDateSerializer(TimeUtil.DATE_FORMATTER))
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(TimeUtil.DATE_FORMATTER))
                .serializerByType(LocalTime.class, new LocalTimeSerializer(TimeUtil.TIME_FORMATTER))
                .deserializerByType(LocalTime.class, new LocalTimeDeserializer(TimeUtil.TIME_FORMATTER));
    }
}
