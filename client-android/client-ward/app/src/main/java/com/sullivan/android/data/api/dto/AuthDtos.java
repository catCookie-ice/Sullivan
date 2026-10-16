package com.sullivan.android.data.api.dto;

import java.util.List;

/**
 * 登录相关请求/响应体，字段名与服务端 {@code web/RegistrationDtos.java} 逐字对应。
 *
 * <p><b>为什么是 public 字段的 POJO 而不是 record</b>：Gson 靠反射读写字段名，
 * release 构建开了 R8。record 在 Gson 2.10 + 混淆下的可用性依赖编译期生成的方法名，
 * 而 public 字段配 {@code proguard-rules.pro} 里那条 {@code -keep} 是最老也最稳的组合。
 * 这里不省这点样板。
 *
 * <p>时间字段一律不用 {@code LocalDateTime}：Gson 没有内置适配器，要额外注册
 * 序列化器才能收敛格式。客户端真正需要的是「还剩几秒」，服务端已经给了
 * {@code ttlSeconds}，所以 {@code expiresAt} 干脆用 String 收下不解析。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** {@code POST /api/v1/auth/login} 请求体。 */
    public static final class LoginRequest {
        /** 恒为 "USER"：本工程只做自然人登录，机构/管理员入口不在本次范围内。 */
        public String principalType = "USER";
        public String loginName;
        public String password;

        public LoginRequest(String loginName, String password) {
            this.loginName = loginName;
            this.password = password;
        }
    }

    /**
     * {@code POST /api/v1/auth/login} 与
     * {@code POST /api/v1/qr/login-tickets/{ticket}/exchange} 的响应体。
     *
     * <p>两个接口返回的是同一个 {@code TokenResponse}，所以扫码登录换回来的令牌
     * 与账密登录拿到的令牌结构完全一致 —— 客户端不需要为扫码路径单独写一套落库逻辑。
     */
    public static final class TokenResponse {
        public String accessToken;
        public String principalId;
        public String principalType;
        public List<String> roles;
        public List<String> permissions;
        /**
         * 服务端字段名就是 recoveryReminder（不是 reminderRequired），照抄即可，
         * 不需要 {@code @SerializedName} —— 字段名保真是 {@code proguard-rules.pro}
         * 里那条 keep 规则的职责，见 {@link AuthDtos} 的说明。
         */
        public boolean recoveryReminder;
    }
}
