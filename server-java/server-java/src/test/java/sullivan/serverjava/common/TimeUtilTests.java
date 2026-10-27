package sullivan.serverjava.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class TimeUtilTests {

    @Test
    void formatsLocalDateTimeInStandardPattern() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 8, 16, 25, 8);
        assertThat(TimeUtil.format(dateTime)).isEqualTo("2026-08-08 16:25:08");
    }

    @Test
    void formatMillisKeepsMilliseconds() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 8, 16, 25, 8, 123_000_000);
        assertThat(TimeUtil.formatMillis(dateTime)).isEqualTo("2026-08-08 16:25:08.123");
    }

    @Test
    void formatsDateAndTime() {
        assertThat(TimeUtil.format(LocalDate.of(2026, 8, 8))).isEqualTo("2026-08-08");
        assertThat(TimeUtil.format(LocalTime.of(16, 25, 8))).isEqualTo("16:25:08");
    }

    @Test
    void nullSafeFormatting() {
        assertThat(TimeUtil.format((LocalDateTime) null)).isNull();
        assertThat(TimeUtil.format((LocalDate) null)).isNull();
        assertThat(TimeUtil.format((LocalTime) null)).isNull();
    }

    @Test
    void parsesStandardPattern() {
        assertThat(TimeUtil.parseDateTime("2026-08-08 16:25:08"))
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 16, 25, 8));
        assertThat(TimeUtil.parseDate("2026-08-08")).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(TimeUtil.parseTime("16:25:08")).isEqualTo(LocalTime.of(16, 25, 8));
    }

    @Test
    void nullAndBlankSafeParsing() {
        assertThat(TimeUtil.parseDateTime(null)).isNull();
        assertThat(TimeUtil.parseDateTime("  ")).isNull();
        assertThat(TimeUtil.parseDateTimeLenient(null)).isNull();
    }

    @Test
    void lenientParseAcceptsIsoFormat() {
        assertThat(TimeUtil.parseDateTimeLenient("2026-09-01T00:00:00"))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(TimeUtil.parseDateTimeLenient("2026-08-08 16:25:08.123"))
                .isEqualTo(LocalDateTime.of(2026, 8, 8, 16, 25, 8, 123_000_000));
    }

    /** 12:00 规则：中午前创建的活动，开始时间最早为今天零点。 */
    @Test
    void earliestAllowedStartBeforeNoonIsToday() {
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 10, 27, 0, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 0, 0));
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 10, 27, 11, 59, 59, 999_000_000)))
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 0, 0));
    }

    /** 正好 12:00 整按「之后」处理（取更严格一侧），之后创建的最早为明天零点。 */
    @Test
    void earliestAllowedStartAtOrAfterNoonIsTomorrow() {
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 10, 27, 12, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 0, 0));
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 10, 27, 23, 59, 59)))
                .isEqualTo(LocalDateTime.of(2026, 10, 28, 0, 0));
    }

    /** 跨月、跨年的明天要算对。 */
    @Test
    void earliestAllowedStartRollsOverMonthAndYear() {
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 10, 31, 12, 0, 1)))
                .isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertThat(TimeUtil.earliestAllowedStart(LocalDateTime.of(2026, 12, 31, 18, 0, 0)))
                .isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
    }
}
