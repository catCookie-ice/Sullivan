package com.sullivan.android.data.api.dto;

import java.util.List;

/**
 * 找回密码相关请求/响应体，字段名与服务端 {@code web/RecoveryDtos.java} 逐字对应。
 *
 * <p><b>这套流程没有验证码</b>：{@code start} 只返回「你当初设过哪几种方式」的掩码前缀，
 * {@code verify} 要求用户把完整手机号/邮箱<em>补全</em>出来，服务端比对命中才算通过。
 * 也就是说这是一道「你知道自己填过什么」的知识题，不是「你能收到短信」的持有题。
 * 客户端不要自作主张加「发送验证码」按钮 —— 后端没有这个接口。
 */
public final class RecoveryDtos {

    private RecoveryDtos() {
    }

    /** 一种已设置的找回方式。{@link #maskedPrefix} 只用于让用户认出是哪一条。 */
    public static final class Method {
        /** PHONE 或 EMAIL。 */
        public String type;
        /** 如 {@code ****8000}。服务端只给不加密的片段，完整值不下发。 */
        public String maskedPrefix;
    }

    /** {@code POST /api/v1/auth/password-recovery/start} 请求体。 */
    public static final class StartRequest {
        /** 恒为 "USER"：受护人/监护人都是自然人账号。 */
        public String principalType = "USER";
        public String loginName;

        public StartRequest(String loginName) {
            this.loginName = loginName;
        }
    }

    /**
     * {@code POST /api/v1/auth/password-recovery/start} 响应体。
     *
     * <p>{@link #methods} 为空有两种可能：账号不存在，或者账号存在但没设过找回方式。
     * 服务端**故意不区分**（否则这个接口就成了「登录名是否已注册」的探测器），
     * 所以界面也照这个口径写一句统一的话，不要试图猜。
     */
    public static final class StartResponse {
        public List<Method> methods;
    }

    /** {@code POST /api/v1/auth/password-recovery/verify} 请求体。 */
    public static final class VerifyRequest {
        public String principalType = "USER";
        public String loginName;
        /** PHONE 或 EMAIL，须来自 {@link StartResponse#methods} 中的某一项。 */
        public String recoveryType;
        /** 用户补全后的完整手机号或邮箱。 */
        public String fullContact;

        public VerifyRequest(String loginName, String recoveryType, String fullContact) {
            this.loginName = loginName;
            this.recoveryType = recoveryType;
            this.fullContact = fullContact;
        }
    }

    /**
     * {@code POST /api/v1/auth/password-recovery/verify} 响应体。
     *
     * <p>校验不通过是 **200 + verified=false**，不是 4xx —— 别按状态码判断成败。
     */
    public static final class VerifyResponse {
        public boolean verified;
    }

    /** {@code PUT /api/v1/auth/me/recovery} 请求体：至少填一项才能提交。 */
    public static final class SetRequest {
        public String recoveryPhone;
        public String recoveryEmail;

        public SetRequest(String recoveryPhone, String recoveryEmail) {
            this.recoveryPhone = recoveryPhone;
            this.recoveryEmail = recoveryEmail;
        }
    }

    /** {@code GET /api/v1/auth/me/recovery} 响应体。 */
    public static final class StatusResponse {
        public boolean hasRecovery;
        /** 本次登录后是否还需要提醒设置；已经设过或已选过「不再提醒」时为 false。 */
        public boolean remindRequired;
        public List<Method> methods;
    }

    /** {@code remind-later} / {@code dismiss} 的响应体，两个接口共用。 */
    public static final class ActionResponse {
        public boolean ok;
    }
}
