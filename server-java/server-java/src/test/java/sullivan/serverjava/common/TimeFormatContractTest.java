package sullivan.serverjava.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

/**
 * 时间字段的**线上格式**契约。
 *
 * <p>这一组是为一个真实故障写的：受护人端位置上报一直失败，服务端日志报
 * <pre>
 * JSON parse error: Cannot deserialize value of type `java.time.LocalDateTime`
 * from String "2026-10-24T17:02:15": Text '2026-10-24T17:02:15'
 * could not be parsed at index 10
 * </pre>
 *
 * <p>`index 10` 就是那个 {@code T}。原因是服务端把 {@code LocalDateTime} 全局钉成
 * {@link TimeUtil#PATTERN_DATETIME}（{@code yyyy-MM-dd HH:mm:ss}，**空格**分隔），
 * 而客户端按 ISO-8601 发了 {@code yyyy-MM-dd'T'HH:mm:ss}。
 *
 * <p><b>为什么这个测试必须存在：</b>`openapi.yaml` 里的示例写的是
 * {@code 2026-10-24T14:00:00}（ISO 格式），与实现**不一致**。
 * 任何人照抄示例写客户端都会踩同一个坑。这个测试把"实现以空格为准"这件事钉死，
 * 并明确断言 ISO 的 {@code T} 会被拒 —— 如果将来有人想改成接受 ISO，
 * 会先在这里看到"有人依赖了空格格式"。
 */
class TimeFormatContractTest {

    /** 与 {@code JacksonConfig} 里注册的那份配置一致。 */
    private static ObjectMapper mapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        JavaTimeModule module = new JavaTimeModule();
        module.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(TimeUtil.DATE_TIME_FORMATTER));
        objectMapper.registerModule(module);
        return objectMapper;
    }

    /** 空格分隔是客户端**必须**发的格式，必须能解析。 */
    @Test
    void spaceSeparatedDateTimeIsAccepted() throws Exception {
        Holder holder = mapper().readValue("{\"t\":\"2026-10-24 17:02:15\"}", Holder.class);
        assertEquals(LocalDateTime.of(2026, 10, 24, 17, 2, 15), holder.t);
    }

    /**
     * ISO 的 {@code T} 会被拒 —— 这正是那个故障的现场。
     *
     * <p>断言"会抛异常"而不是"能解析"，是为了让这份契约有两个方向：
     * 改实现去兼容 ISO 时，这条测试会失败，从而提醒改动者
     * "客户端已经在发空格了，别只改一半"。
     */
    @Test
    void isoDateTimeWithTSeparatorIsRejected() {
        assertThrows(InvalidFormatException.class, () ->
                mapper().readValue("{\"t\":\"2026-10-24T17:02:15\"}", Holder.class));
    }

    /** 带上毫秒也不行：格式串里没有毫秒段。客户端必须截断到秒。 */
    @Test
    void millisecondsAreRejected() {
        assertThrows(InvalidFormatException.class, () ->
                mapper().readValue("{\"t\":\"2026-10-24 17:02:15.123\"}", Holder.class));
    }

    static class Holder {
        public LocalDateTime t;
    }
}
