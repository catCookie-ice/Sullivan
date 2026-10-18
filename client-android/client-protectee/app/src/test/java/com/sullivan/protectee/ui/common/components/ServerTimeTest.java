package com.sullivan.protectee.ui.common.components;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 服务端时间串的显示处理。
 *
 * <p>这里的每个用例都对应一次真实见过的坏显示：多出来的 {@code T}、
 * 被设备时区解释掉的小时、以及区间两边重复一整段日期。
 */
public class ServerTimeTest {

    @Test
    public void dropsSecondsAndSwapsT() {
        assertEquals("2026-08-17 10:00", ServerTime.shortForm("2026-08-17T10:00:00"));
    }

    /** 带毫秒也要砍到分钟：秒之后的东西对这行字没有任何信息量。 */
    @Test
    public void dropsFractionalSeconds() {
        assertEquals("2026-08-17 10:00", ServerTime.shortForm("2026-08-17T10:00:00.123"));
    }

    /** 只有日期时原样返回，不补一个假的 00:00。 */
    @Test
    public void dateOnlyIsLeftAlone() {
        assertEquals("2026-08-17", ServerTime.shortForm("2026-08-17"));
    }

    @Test
    public void missingValueBecomesDash() {
        assertEquals("—", ServerTime.shortForm(null));
        assertEquals("—", ServerTime.shortForm(""));
    }

    /** 同一天的两端只写一次日期 —— 窄屏上重复的日期会把整行挤成两行。 */
    @Test
    public void sameDayRangeOmitsRepeatedDate() {
        assertEquals("2026-09-01 09:00 ~ 12:00",
                ServerTime.range("2026-09-01T09:00:00", "2026-09-01T12:00:00"));
    }

    @Test
    public void crossDayRangeKeepsBothDates() {
        assertEquals("2026-09-01 22:00 ~ 2026-09-02 06:00",
                ServerTime.range("2026-09-01T22:00:00", "2026-09-02T06:00:00"));
    }
}
