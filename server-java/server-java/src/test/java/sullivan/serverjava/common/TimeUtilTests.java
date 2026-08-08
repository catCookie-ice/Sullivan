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
}
