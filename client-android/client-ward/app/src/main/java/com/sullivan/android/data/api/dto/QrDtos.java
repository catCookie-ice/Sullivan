package com.sullivan.android.data.api.dto;

import java.util.List;

/**
 * 二维码相关请求/响应体，字段名与服务端 {@code web/QrDtos.java} 逐字对应。
 * 见 {@link AuthDtos} 里关于「public 字段 + Gson + R8」的说明。
 */
public final class QrDtos {

    private QrDtos() {
    }

    // ------------------------------------------------------------------
    // 功能 A：扫码绑定
    // ------------------------------------------------------------------

    /** {@code POST /api/v1/qr/bind-codes} 与 {@code GET .../current} 的响应体。 */
    public static final class BindCodeResponse {
        /** 12 位、取自去掉了 0/O/1/I/L/U 的字母表，可朗读、可手输。 */
        public String code;
        public String protecteeId;
        public String protecteeName;
        public String expiresAt;
        /** 剩余有效秒数；用来跑倒计时，避免依赖客户端与服务端的时钟差。 */
        public long ttlSeconds;
    }

    /** {@code POST /api/v1/qr/bind-codes/scan} 请求体。 */
    public static final class BindScanRequest {
        public String code;

        public BindScanRequest(String code) {
            this.code = code;
        }
    }

    /** {@code POST /api/v1/qr/bind-codes/scan} 响应体。 */
    public static final class BindScanResponse {
        public String bindingId;
        public String status;
        public String guardianId;
        public String protecteeId;
        public String protecteeName;
        /**
         * true 表示关系此前已生效、本次扫码是幂等重扫。
         * 这是**正常分支**，不是错误：服务端不会为重复扫返回 409。
         */
        public boolean alreadyBound;
        /** 服务端拼好的中文提示，直接显示/朗读即可。 */
        public String message;
    }

    // ------------------------------------------------------------------
    // 功能 B：扫码登录
    // ------------------------------------------------------------------

    /**
     * {@code POST /api/v1/qr/login-tickets} 请求体。
     *
     * <p>只上传 {@code sha256(clientSecret)}，secret 本身留在本机。
     * 二维码里只有 ticket；没有 secret 的话，光看见二维码换不出令牌。
     */
    public static final class LoginTicketCreateRequest {
        public String secretHash;

        public LoginTicketCreateRequest(String secretHash) {
            this.secretHash = secretHash;
        }
    }

    /** {@code POST /api/v1/qr/login-tickets} 响应体。 */
    public static final class LoginTicketCreateResponse {
        public String ticket;
        public String expiresAt;
        public long ttlSeconds;
    }

    /**
     * {@code GET /api/v1/qr/login-tickets/{ticket}} 响应体。
     *
     * <p>状态取值：PENDING（待扫）/ SCANNED（已扫待选人）/ CONFIRMED（已授权，
     * 此时 {@link #exchangeCode} 非空）/ EXPIRED。
     *
     * <p><b>这个响应里永远不会有 accessToken</b>：令牌只经由一次性的
     * {@code exchange} 下发。轮询是带凭据出网的 GET，令牌放这里进各级日志。
     */
    public static final class LoginTicketStatusResponse {
        public String status;
        public String exchangeCode;
        public String protecteeName;
        public String protecteeId;
        public long ttlSeconds;
    }

    /** {@code POST .../{ticket}/scan} 响应里的候选项：该监护人已 ACTIVE 绑定的受护人。 */
    public static final class ProtecteeCandidate {
        public String protecteeId;
        public String displayName;
        public String loginName;
    }

    /** {@code POST /api/v1/qr/login-tickets/{ticket}/scan} 响应体。 */
    public static final class LoginTicketScanResponse {
        public String status;
        public List<ProtecteeCandidate> candidates;
    }

    /** {@code POST .../{ticket}/confirm} 请求体：监护人从候选里选中的那一个。 */
    public static final class LoginTicketConfirmRequest {
        public String protecteeId;

        public LoginTicketConfirmRequest(String protecteeId) {
            this.protecteeId = protecteeId;
        }
    }

    /** {@code POST .../{ticket}/exchange} 请求体：一次性核销，换取令牌。 */
    public static final class LoginTicketExchangeRequest {
        public String clientSecret;
        public String exchangeCode;

        public LoginTicketExchangeRequest(String clientSecret, String exchangeCode) {
            this.clientSecret = clientSecret;
            this.exchangeCode = exchangeCode;
        }
    }

    /**
     * 服务端统一的错误体 {@code {"error": "..."}}（由 {@code common/ApiErrorHandler} 产出）。
     * 非 2xx 时用它把中文原因提取出来，而不是把原始 JSON 甩给用户看。
     */
    public static final class ApiError {
        public String error;
    }
}
