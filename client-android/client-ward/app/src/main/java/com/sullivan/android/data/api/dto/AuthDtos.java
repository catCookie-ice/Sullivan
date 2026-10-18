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
 * 这条在 {@link ActivityDtos}/{@link VolunteerDtos} 里也适用 —— 服务端的
 * {@code LocalDateTime} 到了这边一律是 String，显示时只做截断，不做时区换算。
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

    /**
     * {@code POST /api/v1/auth/register/user} 请求体。
     *
     * <p>{@link #role} 只能是 GUARDIAN 或 VOLUNTEER：受护人账号**不能自助注册**，
     * 只能由已登录的监护人通过 {@code POST /guardians/protectees} 代建
     * （见 {@link ProtecteeCreateRequest}）。这是刻意的 ——
     * 受护人若自己注册，就没有任何一条「谁对他负责」的记录，
     * 而绑定关系正是这个应用的全部价值。
     *
     * <p>两个找回字段留空是允许的，但代价是**密码永久找不回**（服务端没有别的找回途径）。
     * 界面必须把这句话写出来，而不是把它当成可选项默默跳过。
     */
    public static final class RegisterUserRequest {
        public String loginName;
        public String password;
        public String displayName;
        /** GUARDIAN 或 VOLUNTEER。 */
        public String role;
        /** 可选；格式 1[3-9]\d{9}，服务端会校验，客户端只做空值判断。 */
        public String recoveryPhone;
        /** 可选；服务端会校验格式。 */
        public String recoveryEmail;

        public RegisterUserRequest(String loginName, String password, String displayName,
                                   String role, String recoveryPhone, String recoveryEmail) {
            this.loginName = loginName;
            this.password = password;
            this.displayName = displayName;
            this.role = role;
            this.recoveryPhone = recoveryPhone;
            this.recoveryEmail = recoveryEmail;
        }
    }

    /**
     * {@code POST /api/v1/guardians/protectees} 请求体：监护人代建受护人账号。
     *
     * <p>服务端会在同一个事务里把新受护人与发起人建立一条 {@code permanent=true}
     * 的主绑定 —— 所以这个接口创建出来的受护人**天生就绑好了**，
     * 不需要再扫一次码。永久绑定也因此不可解除（terminate 返回 403）。
     */
    public static final class ProtecteeCreateRequest {
        public String loginName;
        public String password;
        public String displayName;
        /** 可选，如「对花生过敏；紧急联系人李女士 13800000000」，最长 500 字。 */
        public String emergencyNote;

        public ProtecteeCreateRequest(String loginName, String password, String displayName,
                                      String emergencyNote) {
            this.loginName = loginName;
            this.password = password;
            this.displayName = displayName;
            this.emergencyNote = emergencyNote;
        }
    }

    /**
     * 注册类接口的响应体：{@code POST /auth/register/user} 与
     * {@code POST /guardians/protectees} 共用。
     *
     * <p>{@link #status} 对自然人恒为 ENABLED（机构才是 PENDING 待审核），
     * 所以客户端注册完可以直接登，不需要等审核态。
     */
    public static final class RegistrationResponse {
        public String id;
        public String principalType;
        public String status;
        public List<String> roles;
    }
}
