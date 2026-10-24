package com.sullivan.android.data.api;

/**
 * 什么时候该把一次 401 当成「登录失效、必须重新登录」。
 *
 * <p>抽成纯函数是为了能在 JVM 单测里钉住它 —— 这段判断此前散在各页面的
 * {@code onError} 里，有的写了有的没写，于是出现了真实故障：
 * 监护人扫码后什么都没发生（见 {@code GuardianActivity.showSessionExpired} 的注释）。
 *
 * <p><b>为什么不能简单地「见到 401 就清会话」。</b>登录接口自己在账号密码错误时
 * 也返回 401。如果那种 401 也清会话，用户在一个已经登录的账号上误输一次密码，
 * 就会被顺手踢下线。区别在于：**登录请求没有带令牌**，而"令牌失效"的前提是
 * 我们确实带了一个令牌去。
 */
public final class AuthExpiry {

    private AuthExpiry() {
    }

    /**
     * @param httpCode    响应的 HTTP 状态码
     * @param sentToken   这次请求是否**带着**我们的令牌去了
     * @return 是否应当清除本地会话并提示重新登录
     */
    public static boolean shouldExpireSession(int httpCode, boolean sentToken) {
        // 401 且带了令牌 → 令牌不被接受 → 会话确实失效了。
        // 401 但没带令牌 → 多半是登录接口在说"账号或密码不对"，与现有会话无关。
        return httpCode == 401 && sentToken;
    }
}
