package sullivan.serverjava.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间格式化工具。
 *
 * <p>统一项目中的时间展示/解析格式（中国习惯 {@code yyyy-MM-dd HH:mm:ss}），并作为全局
 * Jackson 序列化/反序列化格式使用（见 {@code JacksonConfig}）。数据库 DATETIME(3) 保留毫秒，
 * 需要毫秒精度时用 {@link #formatMillis(LocalDateTime)}。</p>
 */
public final class TimeUtil {
    private TimeUtil() {
    }

    /** 日期+时间（精确到秒），全局默认展示格式。 */
    public static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";
    /** 仅日期。 */
    public static final String PATTERN_DATE = "yyyy-MM-dd";
    /** 仅时间（精确到秒）。 */
    public static final String PATTERN_TIME = "HH:mm:ss";
    /** 日期+时间（含毫秒），用于需要毫秒精度的场景。 */
    public static final String PATTERN_DATETIME_MS = "yyyy-MM-dd HH:mm:ss.SSS";

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(PATTERN_DATETIME);
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(PATTERN_DATE);
    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(PATTERN_TIME);
    public static final DateTimeFormatter DATE_TIME_MS_FORMATTER = DateTimeFormatter.ofPattern(PATTERN_DATETIME_MS);

    /** 当前时间。 */
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    // ---------- 活动创建的最早开始时刻（12:00 规则） ----------

    /**
     * 创建志愿活动时，「开始时间」（活动开始时间与报名开始时间都算）允许的最早时刻：
     * 12:00 之前创建不早于今天零点；12:00 及之后创建不早于明天零点。
     *
     * <p>正好 12:00 整按「之后」处理（取更严格一侧），让边界没有歧义。
     * 服务端校验与前端选择器禁用日期共用同一条规则，两端口径必须一致。</p>
     */
    public static LocalDateTime earliestAllowedStart(LocalDateTime now) {
        LocalDate base = now.toLocalDate();
        boolean beforeNoon = now.toLocalTime().isBefore(LocalTime.NOON);
        return (beforeNoon ? base : base.plusDays(1)).atStartOfDay();
    }

    // ---------- 格式化（null 安全） ----------

    /** 格式化为 yyyy-MM-dd HH:mm:ss。 */
    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_FORMATTER);
    }

    /** 格式化为 yyyy-MM-dd HH:mm:ss.SSS（保留毫秒）。 */
    public static String formatMillis(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_MS_FORMATTER);
    }

    /** 格式化为 yyyy-MM-dd。 */
    public static String format(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMATTER);
    }

    /** 格式化为 HH:mm:ss。 */
    public static String format(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    // ---------- 解析（null/空 安全） ----------

    /** 解析 yyyy-MM-dd HH:mm:ss。 */
    public static LocalDateTime parseDateTime(String text) {
        return text == null || text.isBlank() ? null : LocalDateTime.parse(text.trim(), DATE_TIME_FORMATTER);
    }

    /** 解析 yyyy-MM-dd。 */
    public static LocalDate parseDate(String text) {
        return text == null || text.isBlank() ? null : LocalDate.parse(text.trim(), DATE_FORMATTER);
    }

    /** 解析 HH:mm:ss。 */
    public static LocalTime parseTime(String text) {
        return text == null || text.isBlank() ? null : LocalTime.parse(text.trim(), TIME_FORMATTER);
    }

    /**
     * 宽松解析：依次尝试 yyyy-MM-dd HH:mm:ss、yyyy-MM-dd HH:mm:ss.SSS、ISO-8601（LocalDateTime 默认）。
     * 用于对既有格式不敏感的入参。
     */
    public static LocalDateTime parseDateTimeLenient(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return LocalDateTime.parse(trimmed, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(trimmed, DATE_TIME_MS_FORMATTER);
            } catch (DateTimeParseException ignored2) {
                return LocalDateTime.parse(trimmed);
            }
        }
    }
}
