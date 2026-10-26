package com.sullivan.android.ui.guardian.call;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.data.api.dto.AlertDtos;

/**
 * 把一条报警的位置快照变成「监护人能直接看懂/听懂的一句话」。
 *
 * <p>抽成纯函数是为了能单测 —— 这类文案的分支很多（没有位置、坐标缺失、
 * 精度未知、快照很旧），而每一种都会在真机上出现过。
 *
 * <p><b>为什么要把"多旧"说出来。</b>位置是受护人平时每 10 秒上报一次的**缓存**，
 * 报警时服务端读的是"最近一条"。它可能是 3 秒前的，也可能是几分钟前的
 * （受护人刚进电梯、或者一直没拿到定位）。两者对"人现在在哪"的价值完全不同，
 * 不说清楚会让监护人按一个过期坐标去找人。
 *
 * <p><b>为什么坐标要原样给出。</b>监护人的最终动作多半是"复制坐标去地图里看"。
 * 只给"精度 12 米"这类描述没有用；反过来只给裸坐标也不够（看不懂是什么地方）。
 * 所以两者都给，坐标单独一行便于长按复制。
 */
public final class AlertLocationSummary {

    private AlertLocationSummary() {
    }

    /**
     * 一句话摘要，给界面标题用。
     *
     * @return 永不为 null；没有位置时返回 {@code null}（由调用方决定怎么提示）
     */
    @Nullable
    public static String headline(@Nullable AlertDtos.AlertLocation location) {
        if (location == null || !hasUsableCoordinates(location)) {
            return null;
        }
        String age = ageText(location.ageSeconds);
        if (age == null) {
            return "受护人位置已收到";
        }
        return "受护人位置（" + age + "）";
    }

    /**
     * 详细内容：坐标 + 精度 + 新鲜度。
     *
     * @return 没有可用坐标时返回 null
     */
    @Nullable
    public static String detail(@Nullable AlertDtos.AlertLocation location) {
        if (location == null || !hasUsableCoordinates(location)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(java.util.Locale.US, "%.6f, %.6f",
                location.latitude, location.longitude));

        String age = ageText(location.ageSeconds);
        if (age != null) {
            builder.append("\n位置来自").append(age);
        }
        if (location.accuracyM != null && location.accuracyM > 0) {
            builder.append("\n定位精度约 ")
                    .append(Math.round(location.accuracyM))
                    .append(" 米");
        }
        return builder.toString();
    }

    /**
     * 服务端已解析好的地名；没有则返回 null，调用方此时必须走本端
     * {@link PlaceNameResolver} 兜底解析。
     *
     * <p>没有的两种情形：{@code addressPending} 为 true（服务端在时限内没解析完，
     * 报警没等它直接转发——这正是本端兜底存在的意义）；或旧报警 / 服务端没配 Key /
     * 解析确定失败。两种情形客户端动作相同，标识只是把「为什么没有」说清楚。
     */
    @Nullable
    public static String serverAddress(@Nullable AlertDtos.AlertLocation location) {
        if (location == null || location.address == null) {
            return null;
        }
        String address = location.address.trim();
        return address.isEmpty() ? null : address;
    }

    /**
     * 坐标是否可用。
     *
     * <p>(0, 0) 被当作不可用：那是"几内亚湾"，但实践中它几乎总是
     * "字段没填"的默认值，而不是真的有人漂在大西洋上。
     * 显示它会把监护人引到一个毫无意义的点。
     */
    public static boolean hasUsableCoordinates(@NonNull AlertDtos.AlertLocation location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            return false;
        }
        if (location.latitude < -90 || location.latitude > 90) {
            return false;
        }
        return location.longitude >= -180 && location.longitude <= 180;
    }

    /** 把秒数变成"刚刚/23 秒前/4 分钟前"。 */
    @Nullable
    private static String ageText(@Nullable Long ageSeconds) {
        if (ageSeconds == null || ageSeconds < 0) {
            return null;
        }
        if (ageSeconds < 10) {
            return "刚刚";
        }
        if (ageSeconds < 60) {
            return ageSeconds + " 秒前";
        }
        long minutes = ageSeconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        return (minutes / 60) + " 小时前";
    }
}
