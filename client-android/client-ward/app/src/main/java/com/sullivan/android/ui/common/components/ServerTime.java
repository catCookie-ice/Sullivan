package com.sullivan.android.ui.common.components;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 服务端时间字符串的显示处理。**纯字符串操作，不解析时间**（可单测，见 {@code ServerTimeTest}）。
 *
 * <p><b>为什么不解析成 {@code LocalDateTime}</b>：服务端给的是 {@code LocalDateTime}
 * （如 {@code 2026-08-17T10:00:00}），**没有时区**。解析再按设备时区格式化，
 * 会凭空引入一次「这是哪个时区的时间」的判断 —— 判错了，活动开始时间会整体偏移几小时，
 * 而用户唯一的依据就是这行字。
 *
 * <p>客户端对这些时间只需要「大致知道是几点、哪一天」，所以只做两件事：
 * 把 {@code T} 换成空格、砍掉秒。这样在世界的任何时区
 * 显示的都是服务端写的那个时刻，不会被设备时区二次解释。
 */
public final class ServerTime {

    /** 服务端时间串里 {@code T} 的位置固定，砍到这里就是 {@code yyyy-MM-dd HH:mm}。 */
    private static final int SHORT_LENGTH = 16;

    private ServerTime() {
    }

    /**
     * {@code 2026-08-17T10:00:00} → {@code 2026-08-17 10:00}。
     *
     * <p>短于预期长度（只有日期、或空）时原样返回，**不补零、不截断**：
     * 编造一个缺失的时分比显示得短更糟。
     */
    @NonNull
    public static String shortForm(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return "—";
        }
        String value = raw.replace('T', ' ');
        if (value.length() >= SHORT_LENGTH && value.charAt(10) == ' ') {
            return value.substring(0, SHORT_LENGTH);
        }
        return value;
    }

    /**
     * 一段区间：{@code 2026-09-01 09:00 ~ 2026-09-01 12:00}。
     *
     * <p>两端的日期相同就把前半段省掉（{@code 2026-09-01 09:00 ~ 12:00}）——
     * 重复一遍日期在窄屏上会把这行挤成两行，而它本来只是活动的辅助信息。
     */
    @NonNull
    public static String range(@Nullable String start, @Nullable String end) {
        String from = shortForm(start);
        String to = shortForm(end);
        if (from.length() > 10 && to.length() > 10 && from.regionMatches(0, to, 0, 10)) {
            return from + " ~ " + to.substring(11);
        }
        return from + " ~ " + to;
    }
}
