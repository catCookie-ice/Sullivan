package com.sullivan.android.data.api.dto;

import java.util.List;

/**
 * 公益活动 DTO，字段名与服务端 {@code web/ActivityDtos.java} 逐字对应。
 *
 * <p>客户端是**只读**的：机构发布活动走的是机构端（本次不做），
 * Android 这边只有 {@code GET /activities} 一个公开列表接口。
 */
public final class ActivityDtos {

    private ActivityDtos() {
    }

    /** {@code GET /api/v1/activities} 的分页元素。 */
    public static final class ActivityResponse {
        public String id;
        /** RECRUITING（招募中）/ IN_PROGRESS（进行中）/ ENDED（已结束）/ CANCELLED（已取消）。 */
        public String status;
        public String organizationId;
        public String organizationPublicName;
        public String title;
        public String description;
        public String locationText;
        /** 高德经度；可为 null（与纬度同空）。 */
        public String locationLng;
        public String locationLat;
        /** 以下四个时间服务端是 LocalDateTime，这边一律 String，只做显示截断。 */
        public String startsAt;
        public String endsAt;
        public String registrationStartsAt;
        public String registrationEndsAt;
        public Integer maxVolunteers;
        public Integer approvedCount;
        public String createdAt;
    }

    /**
     * 分页信封，对应服务端 MyBatis-Plus 的 {@code IPage<T>} 序列化结果。
     *
     * <p>{@code IPage} 其实还会多序列化出 {@code orders} / {@code optimizeCountSql}
     * 之类的字段，客户端不声明就自动忽略 —— Gson 对多出来的字段是宽容的，
     * 不需要 {@code @SerializedName} 或 ignore 注解。
     *
     * <p>泛型是给 Retrofit 的 {@code Call<PageResult<ActivityResponse>>} 用的：
     * Retrofit 从方法的返回类型里拿 {@code Type}，泛型信息不会丢，
     * 所以这里可以放心用泛型，不需要为每种分页单独写一个类。
     */
    public static final class PageResult<T> {
        public List<T> records;
        public long total;
        public long size;
        public long current;
        public long pages;
    }
}
