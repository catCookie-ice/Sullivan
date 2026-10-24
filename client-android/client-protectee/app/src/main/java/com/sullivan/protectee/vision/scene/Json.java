package com.sullivan.protectee.vision.scene;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个极简的 JSON 读写器。
 *
 * <p><b>为什么不用 {@code org.json}</b>：本工程没有打开
 * {@code testOptions.unitTests.isReturnDefaultValues}，而 {@code org.json} 是 Android 框架
 * 里的类，在纯 JVM 单测里调用会抛 {@code RuntimeException: Stub!}。为了一行日志去给整条
 * 感知链路加一个 Robolectric 依赖不划算，手写这点代码反而全部可测。
 *
 * <p>读回来的是 {@code Map}/{@code List}/{@code String}/{@code Long}/{@code Float}/{@code Boolean}/
 * {@code null} 这几种最朴素的结构，够用就好。
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 把字符串转义成 JSON 字符串字面量的**内容**（不含两边的引号）。 */
    @NonNull
    public static String escape(@NonNull String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        escapeTo(out, value);
        return out.toString();
    }

    /**
     * 转义并追加。
     *
     * <p>非 ASCII 字符（中文类别名）**不转义**：JSON 允许直接放 UTF-8 字符，转成
     * {@code \\uXXXX} 只会让日志没法直接看。
     */
    public static void escapeTo(@NonNull StringBuilder out, @NonNull String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
    }

    /** 追加一个带引号的字符串字面量。 */
    public static void writeString(@NonNull StringBuilder out, @Nullable String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        escapeTo(out, value);
        out.append('"');
    }

    /**
     * 追加一个数字。
     *
     * <p>用 {@code Float.toString} 而不是 {@code String.format("%.3f")}：前者保证"最短且能
     * 唯一回读"，{@code Float.parseFloat(Float.toString(x)) == x} 恒成立，往返测试才能做
     * 精确断言。它偶尔会输出 {@code 1.0E-4} 这类科学计数法——那也是合法 JSON，回读用
     * {@code Float.parseFloat} 就够，不需要自己解析数字。
     *
     * <p><b>NaN 与 Infinity 不是合法 JSON</b>，统一写成 {@code null}。速度、TTC、自运动
     * 补偿量除以零时很容易出 NaN，写成像 {@code NaN} 那样的裸标识符会让日志解析器全部报错。
     */
    public static void writeNumber(@NonNull StringBuilder out, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            out.append("null");
        } else {
            out.append(Float.toString(value));
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 解析一段 JSON。
     *
     * <p>整数（字面量里没有小数点或指数）读成 {@link Long}，其余读成 {@link Float}——
     * {@code Float.toString} 的输出一定带小数点或 {@code E}，所以这条判据不会混淆两者。
     */
    @Nullable
    public static Object parse(@NonNull String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON 结尾有多余内容，位置 " + parser.index);
        }
        return value;
    }

    /** 断言某个值是非空对象，并转成 Map。 */
    @NonNull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(@Nullable Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("期望一个 JSON 对象，实际是 " + describe(value));
        }
        return (Map<String, Object>) value;
    }

    /** 断言某个值是非空数组，并转成 List。 */
    @NonNull
    @SuppressWarnings("unchecked")
    public static List<Object> asList(@Nullable Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("期望一个 JSON 数组，实际是 " + describe(value));
        }
        return (List<Object>) value;
    }

    @Nullable
    public static Object get(@NonNull Map<String, Object> object, @NonNull String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("缺少字段：" + key);
        }
        return object.get(key);
    }

    /**
     * 读一个**可选**的浮点字段：键不存在时返回 {@link Float#NaN}，不抛异常。
     *
     * <p>只用于后加的字段。{@link #getFloat} 对缺失键会抛异常——那是刻意的，
     * 否则拼错字段名会静默读成 NaN，直到回放时才发现数据一直是空的。
     * 但"旧版本日志里没有这个字段"是**预期**情形，不该让解析失败：
     * 深度的 {@code meters} 就是这种字段（加深度图之前写出的日志没有它）。
     */
    public static float getOptionalFloat(@NonNull Map<String, Object> object,
                                         @NonNull String key) {
        if (!object.containsKey(key) || object.get(key) == null) {
            return Float.NaN;
        }
        return getFloat(object, key);
    }

    @NonNull
    public static String getString(@NonNull Map<String, Object> object, @NonNull String key) {
        Object value = get(object, key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("字段 " + key + " 不是字符串：" + describe(value));
        }
        return (String) value;
    }

    public static int getInt(@NonNull Map<String, Object> object, @NonNull String key) {
        Object value = get(object, key);
        if (value instanceof Long) {
            return (int) (long) (Long) value;
        }
        if (value instanceof Float) {
            return ((Float) value).intValue();
        }
        throw new IllegalArgumentException("字段 " + key + " 不是整数：" + describe(value));
    }

    public static boolean getBoolean(@NonNull Map<String, Object> object, @NonNull String key) {
        Object value = get(object, key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException("字段 " + key + " 不是布尔值：" + describe(value));
    }

    /**
     * 读一个浮点数；{@code null}（也就是写出的 NaN/Infinity）读回来还是
     * {@link Float#NaN}，保证"NaN → null → NaN"这条往返路径闭合。
     */
    public static float getFloat(@NonNull Map<String, Object> object, @NonNull String key) {
        Object value = get(object, key);
        if (value == null) {
            return Float.NaN;
        }
        if (value instanceof Float) {
            return (Float) value;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        throw new IllegalArgumentException("字段 " + key + " 不是数字：" + describe(value));
    }

    public static long getLong(@NonNull Map<String, Object> object, @NonNull String key) {
        Object value = get(object, key);
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Float) {
            return ((Float) value).longValue();
        }
        throw new IllegalArgumentException("字段 " + key + " 不是整数：" + describe(value));
    }

    @NonNull
    private static String describe(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    /** 递归下降的解析器。只认值，不认注释、尾逗号这些扩展。 */
    private static final class Parser {
        private final String source;
        private int index;

        Parser(@NonNull String source) {
            this.source = source;
        }

        boolean atEnd() {
            return index >= source.length();
        }

        @Nullable
        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = source.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        @NonNull
        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            index++;   // '{'
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("对象里缺少冒号，位置 " + index);
                }
                index++;
                object.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                index++;
                if (c == '}') {
                    return object;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("对象里缺少逗号或右括号，位置 " + (index - 1));
                }
            }
        }

        @NonNull
        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            index++;   // '['
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                index++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("数组里缺少逗号或右括号，位置 " + (index - 1));
                }
            }
        }

        @NonNull
        private String parseString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("期望字符串，位置 " + index);
            }
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("字符串没有收尾的引号");
                }
                char c = source.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("转义符后面没有内容");
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > source.length()) {
                            throw new IllegalArgumentException("\\u 转义不完整");
                        }
                        out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("无法识别的转义：\\" + escaped);
                }
            }
        }

        @Nullable
        private Object parseNumber() {
            int start = index;
            boolean fractional = false;
            while (!atEnd()) {
                char c = source.charAt(index);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    fractional = true;
                    index++;
                } else {
                    break;
                }
            }
            String token = source.substring(start, index);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("不是合法的 JSON 值，位置 " + start);
            }
            // 带小数点或指数的按 Float 解析：这样 Float.toString 的输出能**精确**回读。
            // 纯整数按 Long 解析，日志里就不会出现 "id":12.0 这种别扭的写法。
            return fractional ? (Object) Float.parseFloat(token) : (Object) Long.parseLong(token);
        }

        private char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return source.charAt(index);
        }

        private void expect(@NonNull String literal) {
            if (!source.startsWith(literal, index)) {
                throw new IllegalArgumentException("期望 " + literal + "，位置 " + index);
            }
            index += literal.length();
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    break;
                }
            }
        }
    }
}
