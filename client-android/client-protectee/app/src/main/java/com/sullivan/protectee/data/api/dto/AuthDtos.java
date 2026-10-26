package com.sullivan.protectee.data.api.dto;

import java.util.List;

/**
 * 登录响应体，字段名与服务端 {@code web/RegistrationDtos.java} 逐字对应。
 *
 * <p><b>本端只保留 {@code TokenResponse}，不是漏搬</b>：受护人端**没有账密登录、
 * 没有自助注册、也不能代建受护人** —— 这三件事都在 {@code client-guardian} 上，
 * 对应的 {@code LoginRequest} / {@code RegisterUserRequest} /
 * {@code ProtecteeCreateRequest} / {@code RegistrationResponse} 只存在于那边。
 * 受护人这边唯一的登录路径是扫码之后的一次性 {@code exchange}，
 * 它返回的正是 {@link TokenResponse}。
 *
 * <p><b>为什么是 public 字段的 POJO 而不是 record</b>：Gson 靠反射读写字段名，
 * release 构建开了 R8。record 在 Gson 2.10 + 混淆下的可用性依赖编译期生成的方法名，
 * 而 public 字段配 {@code proguard-rules.pro} 里那条 {@code -keep} 是最老也最稳的组合。
 * 这里不省这点样板。
 *
 * <p>时间字段一律不用 {@code LocalDateTime}：Gson 没有内置适配器，要额外注册
 * 序列化器才能收敛格式。客户端真正需要的是「还剩几秒」，服务端已经给了
 * {@code ttlSeconds}，所以 {@code expiresAt} 干脆用 String 收下不解析。
 * 服务端的 {@code LocalDateTime} 到了这边一律是 String，显示时只做截断，
 * 不做时区换算。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * {@code POST /api/v1/qr/login-tickets/{ticket}/exchange} 的响应体。
     *
     * <p>与服务端账密登录的响应体是同一个类型，所以扫码换回来的令牌结构与
     * 监护人那边完全一致 —— 客户端不需要为扫码路径单独写一套落库逻辑。
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
         *
         * <p>受护人端**不弹**这个提醒（那是监护人/志愿者登录后的事），
         * 字段留着是因为服务端会返回它，去掉也不影响，留着不必为「映射不上」多写一条 ignore。
         */
        public boolean recoveryReminder;
    }
}
