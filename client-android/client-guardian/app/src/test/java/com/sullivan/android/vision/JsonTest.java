package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.scene.Json;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * 手写 JSON 读写器的契约。
 *
 * <p>之所以不用 {@code org.json}：本工程没开
 * {@code testOptions.unitTests.isReturnDefaultValues}，Android 框架类在纯 JVM 单测里
 * 只会抛 {@code RuntimeException: Stub!}。
 */
public class JsonTest {

    private static Map<String, Object> object(String json) {
        return Json.asObject(Json.parse(json));
    }

    /** 转义必须覆盖会破坏结构的字符。 */
    @Test
    public void escapingRoundTripsAwkwardCharacters() {
        String awkward = "引号\" 反斜杠\\ 换行\n 制表\t 回车\r 退格\b 换页\f 结束";
        String json = "{\"v\":" + quote(awkward) + "}";

        assertEquals(awkward, Json.getString(object(json), "v"));
        // 序列化出来必须是合法 JSON：引号和反斜杠都要被转义。
        assertTrue(Json.escape(awkward).contains("\\\""));
        assertTrue(Json.escape(awkward).contains("\\\\"));
        assertTrue(Json.escape(awkward).contains("\\n"));
    }

    /** 中文类别名直接以 UTF-8 写出，不转成 \\uXXXX——否则日志没法直接看。 */
    @Test
    public void chineseIsNotEscapedButControlCharactersAre() {
        assertEquals("行人", Json.escape("行人"));

        String json = "{\"v\":" + quote("前\u0001方") + "}";
        assertEquals("前\u0001方", Json.getString(object(json), "v"));
        assertEquals("\\u0001", Json.escape("\u0001"));
    }

    /** {@code \\uXXXX} 要能读回来，代理对也不能坏掉。 */
    @Test
    public void unicodeEscapesAreDecoded() {
        assertEquals("A", Json.getString(object("{\"v\":\"\\u0041\"}"), "v"));
        assertEquals("行", Json.getString(object("{\"v\":\"\\u884c\"}"), "v"));
        assertEquals("😀", Json.getString(object("{\"v\":\"\\ud83d\\ude00\"}"), "v"));
    }

    /**
     * 浮点往返必须**精确**：这正是选 {@code Float.toString} 而不是
     * {@code String.format("%.3f")} 的原因。
     */
    @Test
    public void floatsRoundTripExactly() {
        float[] values = {0f, -0f, 1f, 0.1f, -1.5f, 1e-4f, Float.MIN_VALUE,
                Float.MAX_VALUE, 1f / 3f, 0.87f};
        for (float value : values) {
            String json = "{\"v\":" + Float.toString(value) + "}";
            float parsed = Json.getFloat(object(json), "v");
            assertEquals("往返必须精确：" + value, 0, Float.compare(value, parsed));
        }
    }

    /** 科学计数法是合法 JSON，回读交给 {@code Float.parseFloat} 就行。 */
    @Test
    public void scientificNotationIsParsed() {
        assertEquals(1e-4f, Json.getFloat(object("{\"v\":1.0E-4}"), "v"), 0f);
        assertEquals("1.0E-4", Float.toString(1e-4f));
    }

    /** NaN / Infinity 不是合法 JSON，写成 {@code null}，读回来仍是 NaN。 */
    @Test
    public void nonFiniteNumbersAreWrittenAsNull() {
        StringBuilder out = new StringBuilder();
        Json.writeNumber(out, Float.NaN);
        Json.writeNumber(out, Float.POSITIVE_INFINITY);
        assertEquals("nullnull", out.toString());

        assertTrue(Float.isNaN(Json.getFloat(object("{\"v\":null}"), "v")));
    }

    /** 整数读成 Long，带小数点的读成 Float，日志里就不会出现 {@code "id":12.0}。 */
    @Test
    public void integersAndFractionsAreDistinguished() {
        assertEquals(12, Json.getInt(object("{\"v\":12}"), "v"));
        assertEquals(12L, Json.getLong(object("{\"v\":12}"), "v"));
        assertEquals(12.5f, Json.getFloat(object("{\"v\":12.5}"), "v"), 0f);
        assertEquals(12, Json.getInt(object("{\"v\":12.0}"), "v"));
    }

    /** 嵌套结构与空容器。 */
    @Test
    public void nestedStructuresAreParsed() {
        Map<String, Object> root = object(
                "{\"a\":{\"b\":[1,2,{\"c\":true}]},\"d\":[],\"e\":{},\"f\":false,\"g\":null}");
        Map<String, Object> a = Json.asObject(Json.get(root, "a"));
        List<Object> b = Json.asList(Json.get(a, "b"));
        assertEquals(3, b.size());
        assertEquals(1, ((Long) b.get(0)).intValue());
        assertEquals(Boolean.TRUE, Json.get(Json.asObject(b.get(2)), "c"));
        assertEquals(0, Json.asList(Json.get(root, "d")).size());
        assertEquals(0, Json.asObject(Json.get(root, "e")).size());
        assertEquals(Boolean.FALSE, Json.get(root, "f"));
        assertNull(Json.get(root, "g"));
    }

    /** 空白（换行、缩进）不该影响解析：真机日志很可能是格式化过的。 */
    @Test
    public void whitespaceIsIgnored() {
        Map<String, Object> root = object("{\n  \"a\" : [ 1 , 2 ]\n}\n");
        assertEquals(2, Json.asList(Json.get(root, "a")).size());
    }

    /** 坏输入要抛出可读的异常，而不是静默返回半个结果。 */
    @Test
    public void malformedInputThrows() {
        assertThrows("{");
        assertThrows("{\"a\":}");
        assertThrows("{\"a\" 1}");
        assertThrows("[1,2");
        assertThrows("\"没关引号");
        assertThrows("{} 尾巴");
    }

    /** 缺字段要明确报出来，别在调用处拿到 null 再崩在别的地方。 */
    @Test
    public void missingFieldThrows() {
        try {
            Json.getFloat(object("{}"), "rate");
            throw new AssertionError("缺字段时应当抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rate"));
        }
    }

    private static String quote(String value) {
        return "\"" + Json.escape(value) + "\"";
    }

    private static void assertThrows(String json) {
        try {
            Json.parse(json);
            throw new AssertionError("这段 JSON 应当解析失败：" + json);
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
