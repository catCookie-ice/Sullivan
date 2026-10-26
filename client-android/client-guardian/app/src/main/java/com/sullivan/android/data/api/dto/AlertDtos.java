package com.sullivan.android.data.api.dto;

/**
 * 报警 DTO（监护人视角）。
 *
 * <p>**为什么监护人端也需要这个。** 受护人报警 5 秒后，服务端会自动为每位监护人
 * 建一条实时会话，监护人端原本只能看到"一条不知来路的会话"，既不知道是谁求助、
 * 也不知道他在哪。这个 DTO 补上的正是那一半信息。
 *
 * <p>字段名与服务端 {@code web/AlertDtos.AlertResponse} 逐字对应；
 * 时间字段是服务端 {@code JacksonConfig} 的格式（**空格**分隔，
 * 形如 {@code 2026-10-24 17:02:15}），本端只展示不解析，故用 String 承接。
 */
public final class AlertDtos {

    private AlertDtos() {
    }

    /** 报警时的位置快照。整个对象可能为 null（受护人没上报过位置）。 */
    public static final class AlertLocation {
        public double latitude;
        public double longitude;
        /** 定位精度（米）；服务端可能不给。 */
        public Double accuracyM;
        /** 采集时刻，服务端时间格式（空格分隔）。 */
        public String capturedAt;
        /**
         * 距今秒数，服务端算好的。
         *
         * <p>用服务端算好的而不是客户端自己拿 {@code capturedAt} 减现在：
         * 两台设备的时钟可能差好几分钟，自己算会出现"位置来自 8 分钟前"
         * 这种明显错误的提示。服务端的时钟是唯一基准。
         */
        public Long ageSeconds;
        /**
         * 服务端在报警创建时解析好的地名（高德逆地理编码）。
         *
         * <p>有它就不再用本端的 {@code PlaceNameResolver} 二次解析 ——
         * 一次解析随报警定格，三处展示（通知/弹窗/会话页）直接用。
         * NULL = 服务端没解析出来（没配 Web Key、失败，或超时——见
         * {@link #addressPending}），此时才走本端解析兜底。
         */
        public String address;
        /**
         * true = 服务端在时限内（默认 1 秒）没完成解析，报警**未等待**直接转发。
         * 这是服务端与接收方之间的明确分工信号：见到它就必须本端解析，
         * 而不是等下一轮刷新碰运气。false/null = 不是超时（成功或确定失败）。
         */
        public Boolean addressPending;
    }

    /** 一个接收人的派发结果。监护人可用它拿到自己那条会话的 id。 */
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
        /** 可能为 null —— 受护人没上报过位置时服务端给 null。 */
        public AlertLocation location;
        public String createdAt;
        public String resolvedAt;
        public String autoSessionState;
        public String autoSessionDueAt;
        public java.util.List<AlertDeliveryView> deliveries;
    }
}
