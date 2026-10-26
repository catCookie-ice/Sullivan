package com.sullivan.protectee.data.api.dto;

import java.util.List;

/**
 * 报警与位置 DTO，字段名与服务端 {@code web/AlertDtos.java} 逐字对应。
 *
 * <p>经纬度用 {@code Double} 而不是 {@code float}：服务端用 DECIMAL(9,6) 存，
 * 6 位小数约合 0.1 米，float 只有约 7 位有效数字，在这个量级上会把末位吃掉。
 */
public final class AlertDtos {

    private AlertDtos() {
    }

    /** {@code PUT /api/v1/me/location} 请求体。 */
    public static final class LocationReportRequest {
        public double latitude;
        public double longitude;
        /** 定位精度（米）；设备给不出时为 null。 */
        public Double accuracyM;
        /** 采集时刻（ISO-8601，无时区）；null 时服务端按收到时刻计。 */
        public String capturedAt;

        public LocationReportRequest(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** 位置快照，上报与读取共用。 */
    public static final class AlertLocation {
        public double latitude;
        public double longitude;
        public Double accuracyM;
        public String capturedAt;
        /** 距今秒数；越大越不可信。 */
        public Long ageSeconds;
        /** 服务端解析好的地名（逆地理编码）；没配 Key 或解析失败时为 null。 */
        public String address;
        /** true = 服务端解析超时未完成，报警未等待直接转发（接收方本端兜底解析）。 */
        public Boolean addressPending;
    }

    /**
     * {@code POST /api/v1/alerts} 请求体。
     *
     * <p>{@code idempotencyKey} 必须由客户端生成，且**同一次报警的多次提交要复用同一个键**。
     * 弱网重试、用户连点两次都很常见；没有它就会变成同一件事打好几次电话。
     */
    public static final class AlertCreateRequest {
        /** MANUAL / VOICE_HELP / COLLISION。 */
        public String alertType;
        public String idempotencyKey;
        /** 一句话说明情况；可为 null。 */
        public String note;
        /**
         * 报警**当下**刚取到的位置；拿不到时为 null。
         *
         * <p>服务端以它为准（优先于 {@code latest_location} 缓存）。
         * 这里必须真的传上来，不能指望服务端去读缓存 ——
         * 缓存可能是几分钟前的，而报警是要拿去找人的。
         */
        public AlertCaptureRequest location;

        public AlertCreateRequest(String alertType, String idempotencyKey, String note) {
            this.alertType = alertType;
            this.idempotencyKey = idempotencyKey;
            this.note = note;
        }
    }

    /** 报警携带的实时位置。 */
    public static final class AlertCaptureRequest {
        public double latitude;
        public double longitude;
        /** 定位精度（米）；设备给不出时为 null。 */
        public Double accuracyM;
        /** 采集时刻，服务端时间格式（**空格**分隔，如 {@code 2026-10-24 17:02:15}）。 */
        public String capturedAt;

        public AlertCaptureRequest(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    /** 一个接收人的派发结果。 */
    public static final class AlertDeliveryView {
        public String receiverUid;
        public Integer dispatchOrder;
        public String status;
        public String sessionId;
        public String failureReason;
    }

    /** 报警详情。 */
    public static final class AlertResponse {
        public String id;
        public String status;
        public String alertType;
        public String routeType;
        public String routeNote;
        public String protecteeUid;
        public AlertLocation location;
        public String createdAt;
        public String resolvedAt;
        /** NONE / SCHEDULED / RUNNING / SKIPPED / DONE。 */
        public String autoSessionState;
        public String autoSessionDueAt;
        public List<AlertDeliveryView> deliveries;

        /** 是否已经排期、即将自动呼叫监护人。 */
        public boolean isAutoSessionPending() {
            return "SCHEDULED".equals(autoSessionState);
        }

        /** 用户关掉了自动呼叫（而不是「没有监护人」）。 */
        public boolean isAutoSessionSkipped() {
            return "SKIPPED".equals(autoSessionState);
        }
    }

    /** 报警偏好。 */
    public static final class AlertSettingResponse {
        public boolean autoSessionEnabled;
        public int delaySeconds;
    }

    /** {@code PUT /api/v1/me/alert-setting} 请求体。 */
    public static final class AlertSettingUpdateRequest {
        public boolean autoSessionEnabled;

        public AlertSettingUpdateRequest(boolean autoSessionEnabled) {
            this.autoSessionEnabled = autoSessionEnabled;
        }
    }
}
