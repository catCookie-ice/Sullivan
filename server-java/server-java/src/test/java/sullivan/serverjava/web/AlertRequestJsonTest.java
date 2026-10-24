package sullivan.serverjava.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;

import org.junit.jupiter.api.Test;
import sullivan.serverjava.common.TimeUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报警请求体的**线上结构**契约。
 *
 * <p>这一组是为一个真实故障写的：监护人看到的永远是「暂未收到受护人的位置」。
 * 根因是报警请求里根本没有位置字段 —— 服务端只能去读 {@code latest_location}
 * 缓存，而那行可能是几分钟前的，且客户端「刷新位置」与「发报警」是两次独立请求，
 * 报警常常先到、位置后到。
 *
 * <p>现在位置由同一次请求带上来。客户端发出去的 JSON 长这样：
 * <pre>
 * {
 *   "alertType": "MANUAL",
 *   "idempotencyKey": "MANUAL-123-u_x",
 *   "note": null,
 *   "location": {
 *     "latitude": 30.658940,
 *     "longitude": 104.199527,
 *     "accuracyM": 30.0,
 *     "capturedAt": "2026-10-24 17:53:11"
 *   }
 * }
 * </pre>
 * 这里断言它真的能绑定成 {@code AlertCreateRequest}，特别是 {@code capturedAt}
 * 用的是**空格**分隔（服务端 {@code JacksonConfig} 的全局格式），
 * 不是 ISO 的 {@code T} —— 写错就是 400。
 */
class AlertRequestJsonTest {

    private static ObjectMapper mapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        JavaTimeModule module = new JavaTimeModule();
        module.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(TimeUtil.DATE_TIME_FORMATTER));
        objectMapper.registerModule(module);
        return objectMapper;
    }

    /** 客户端实际会发的完整 JSON，必须能原样绑定。 */
    @Test
    void clientPayloadBindsIncludingLocation() throws Exception {
        String json = """
                {
                  "alertType": "MANUAL",
                  "idempotencyKey": "MANUAL-123-u_x",
                  "note": "我在路口摔倒了",
                  "location": {
                    "latitude": 30.658940,
                    "longitude": 104.199527,
                    "accuracyM": 30.0,
                    "capturedAt": "2026-10-24 17:53:11"
                  }
                }
                """;

        AlertDtos.AlertCreateRequest request =
                mapper().readValue(json, AlertDtos.AlertCreateRequest.class);

        assertEquals("MANUAL", request.alertType());
        assertNotNull(request.location());
        assertEquals(0, new BigDecimal("30.658940").compareTo(request.location().latitude()));
        assertEquals(0, new BigDecimal("104.199527").compareTo(request.location().longitude()));
        assertEquals(0, new BigDecimal("30.0").compareTo(request.location().accuracyM()));
        assertEquals(LocalDateTime.of(2026, 10, 24, 17, 53, 11),
                request.location().capturedAt());
    }

    /**
     * 不带 location 的旧格式仍然合法（客户端拿不到定位时就不传）。
     *
     * <p>这条很重要：它保证「拿不到位置」不会让报警本身发不出去。
     */
    @Test
    void payloadWithoutLocationStillBinds() throws Exception {
        String json = """
                {"alertType":"MANUAL","idempotencyKey":"k1","note":null}
                """;

        AlertDtos.AlertCreateRequest request =
                mapper().readValue(json, AlertDtos.AlertCreateRequest.class);

        assertEquals("MANUAL", request.alertType());
        assertNull(request.location());
    }

    /**
     * 客户端如果误用 ISO 的 {@code T} 写 {@code capturedAt}，必须**当场失败**。
     *
     * <p>不静默接受是刻意的：静默接受意味着两个格式并存，
     * 而位置上报曾经因为这个 `T` 整个功能失效过一次。宁可 400 让问题立刻暴露。
     */
    @Test
    void isoTCapturedAtIsRejected() {
        String json = """
                {"alertType":"MANUAL","idempotencyKey":"k1",
                 "location":{"latitude":1.0,"longitude":2.0,
                 "capturedAt":"2026-10-24T17:53:11"}}
                """;
        org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                () -> mapper().readValue(json, AlertDtos.AlertCreateRequest.class));
    }

    /** 缺 capturedAt 也可以：服务端会用当前时间补上。 */
    @Test
    void missingCapturedAtIsAllowed() throws Exception {
        String json = """
                {"alertType":"MANUAL","idempotencyKey":"k1",
                 "location":{"latitude":1.0,"longitude":2.0}}
                """;

        AlertDtos.AlertCreateRequest request =
                mapper().readValue(json, AlertDtos.AlertCreateRequest.class);

        assertNotNull(request.location());
        assertNull(request.location().capturedAt());
    }
}
