package com.sullivan.android.data.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「什么时候该把 401 当成登录失效」的回归测试。
 *
 * <p>这一组是为一个真实故障写的：监护人扫受护人的登录码后，界面直接结束 ——
 * 既没有选人列表，受护人端也没有任何登录完成。根因不是扫码逻辑，而是**监护人端
 * 的登录早已失效却没有任何提示**：401 被当成普通错误写进页面最末尾的状态行，
 * 而用户扫码时看不到那一行。
 *
 * <p>{@link AuthExpiry} 是这次收口后的唯一判断点。它必须同时满足两个要求：
 * <b>令牌失效要清会话</b>（否则本地永远"以为自己还登着"），
 * <b>登录时密码错误不要清已有会话</b>（否则误输一次密码就被踢下线）。
 */
public class AuthExpiryTest {

    /** 带着令牌去、被拒 → 会话确实失效了，必须清掉并提示。 */
    @Test
    public void unauthorizedWithTokenExpiresSession() {
        assertTrue(AuthExpiry.shouldExpireSession(401, true));
    }

    /**
     * 没带令牌的 401 → 不清会话。
     *
     * <p>这是登录接口"账号或密码不对"的正常返回。若这里返回 true，
     * 一个已经登录的用户在设置里误输一次密码，就会把当前会话清掉。
     */
    @Test
    public void unauthorizedWithoutTokenDoesNotExpireSession() {
        assertFalse(AuthExpiry.shouldExpireSession(401, false));
    }

    /**
     * 其它状态码一律不动会话。
     *
     * <p>403（无权限）、404（不存在）、500 都是**服务端的回答**，
     * 不说明令牌无效。把它们也当失效会让用户在网络抖动时莫名其妙被登出。
     */
    @Test
    public void otherStatusCodesLeaveSessionAlone() {
        assertFalse(AuthExpiry.shouldExpireSession(400, true));
        assertFalse(AuthExpiry.shouldExpireSession(403, true));
        assertFalse(AuthExpiry.shouldExpireSession(404, true));
        assertFalse(AuthExpiry.shouldExpireSession(409, true));
        assertFalse(AuthExpiry.shouldExpireSession(500, true));
        // 网络层失败用 0 表示（连不上、超时），绝不能当登录失效：
        // 那种情况下用户明明还登着，只是没网。
        assertFalse(AuthExpiry.shouldExpireSession(0, true));
    }

    /**
     * 200 且带着令牌 —— 正常成功，当然不动。
     *
     * <p>写这条是为了钉住"这个函数只看 401"，防止有人后来把它改成
     * "只要带了令牌就清"之类的写法。
     */
    @Test
    public void successNeverExpiresSession() {
        assertFalse(AuthExpiry.shouldExpireSession(200, true));
        assertFalse(AuthExpiry.shouldExpireSession(204, true));
    }
}
