package com.sullivan.android.data.api.dto;

/**
 * 绑定关系 DTO，字段名与服务端 {@code RegistrationDtos.BindingResponse} 逐字对应
 * （服务端 {@code BindingController} 直接复用了注册模块里的那个 record）。
 *
 * <p><b>这个类在客户端是「共享词汇」</b>：监护人和受护人都用同一份结构，
 * 判断「当前用户在这条关系里是哪一方」靠的是把 {@link BindingResponse#guardianId}
 * 与本机 {@code SessionStore.principalId()} 比对（见
 * {@code ui.guardian.binding.BindingActionPolicy}）。服务端不返回
 * 「我在这条关系里是谁」，因为同一个 id 可能是监护人、也可能是受护人。
 */
public final class BindingDtos {

    private BindingDtos() {
    }

    /**
     * {@code GET /api/v1/bindings} 的列表元素，也是 accept/reject/cancel/terminate 的响应体。
     *
     * <p>状态取值：PENDING（待接收方确认）/ ACTIVE（已生效）/ REJECTED / CANCELLED /
     * EXPIRED / TERMINATED。
     */
    public static final class BindingResponse {
        public String id;
        public String status;
        public String guardianId;
        public String protecteeId;
        /**
         * 永久绑定：由「监护人代建受护人」建立的主绑定，true 时**不可解除**
         * （terminate 返回 403）。所以界面上这类关系不该出现「解除」按钮 ——
         * 别让用户点一个必然失败的按钮，再把 403 当错误弹给他。
         */
        public boolean permanent;
        /** 主绑定：受护人的第一监护关系。 */
        public boolean primary;
        /**
         * 发起方 ID。等于 {@link #guardianId} 表示监护人发起，
         * 等于 {@link #protecteeId} 表示受护人发起；据此判断「谁该点确认、谁能点取消」。
         */
        public String requestedBy;
        /**
         * 双方登录名与显示名（服务端 2026-10-26 起带回）。
         *
         * <p><b>界面显示人一律用这几个字段，不要用 id</b>：uid 是服务端的唯一性标识，
         * 用户既不知道自己的、也念不出别人的（00 §7 第 11 条）。
         */
        public String guardianLoginName;
        public String protecteeLoginName;
        /** 显示名，可能为 null（没填资料时）。 */
        public String guardianName;
        public String protecteeName;
    }

    /** {@code POST /api/v1/bindings} 请求体：发起绑定（新流程，需接收方确认）。 */
    public static final class Request {
        /** 目标账号 ID。 */
        public String targetId;
        /** PROTECTEE（当前用户是监护人）或 GUARDIAN（当前用户是受护人）。 */
        public String targetRole;

        public Request(String targetId, String targetRole) {
            this.targetId = targetId;
            this.targetRole = targetRole;
        }
    }

    /**
     * reject / terminate 的可选请求体：拒绝或解除的原因。
     *
     * <p>服务端把这两个 body 标成 {@code required = false}，所以传 {@code null} 是合法的；
     * 界面上目前不收集原因，一律传 null —— 但把类型留着，免得以后要加时
     * 得改接口签名。
     */
    public static final class DecisionRequest {
        public String reason;

        public DecisionRequest(String reason) {
            this.reason = reason;
        }
    }
}
