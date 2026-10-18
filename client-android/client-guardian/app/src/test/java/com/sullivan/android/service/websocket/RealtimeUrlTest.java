package com.sullivan.android.service.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * WebSocket 地址拼接。**这是整条实时链路里最容易写错的一处**。
 *
 * <p>Android 手上的 baseUrl 是 {@code http://host:8642/api/v1/}，
 * 而服务端的 WebSocket 端点是 {@code /ws/realtime} —— 在**服务器根路径**上，
 * 不在 {@code /api/v1/} 下面（见服务端 {@code config/WebSocketConfig}）。
 * 少削掉这一段，握手就会 404，而 404 在客户端只表现为「连接已断开」，
 * 完全看不出是拼错了前缀。
 */
public class RealtimeUrlTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1XzEifQ.sig_-x";

    @Test
    public void stripsApiPrefixAndSwitchesScheme() {
        assertEquals(
                "ws://10.0.0.211:8642/ws/realtime?token=" + TOKEN + "&sessionId=rs_1",
                RealtimeSocket.url("http://10.0.0.211:8642/api/v1/", TOKEN, "rs_1"));
    }

    @Test
    public void httpsBecomesWssAndKeepsPort() {
        assertEquals(
                "wss://example.org:8443/ws/realtime?token=" + TOKEN + "&sessionId=rs_1",
                RealtimeSocket.url("https://example.org:8443/api/v1/", TOKEN, "rs_1"));
    }

    /** 末尾斜杠可有可无：SessionStore 保证给的是带斜杠的，但别让这个函数依赖它。 */
    @Test
    public void trailingSlashIsOptional() {
        assertEquals(
                "ws://10.0.0.211:8642/ws/realtime?token=" + TOKEN + "&sessionId=rs_1",
                RealtimeSocket.url("http://10.0.0.211:8642/api/v1", TOKEN, "rs_1"));
    }

    /**
     * baseUrl 里没有 {@code /api/v1} 时（有人把部署地址配到根路径），
     * 只削掉斜杠，不要削掉主机后面的东西 —— 否则会把端口或路径吃掉。
     */
    @Test
    public void baseUrlWithoutApiPrefixIsLeftIntact() {
        assertEquals(
                "ws://10.0.0.211:8642/ws/realtime?token=" + TOKEN + "&sessionId=rs_1",
                RealtimeSocket.url("http://10.0.0.211:8642/", TOKEN, "rs_1"));
    }

    /**
     * query 参数必须编码：JWT 里有点、减号、下划线（base64url），
     * 以及偶尔出现的 {@code +} / {@code /} / {@code =}（标准 base64 片段）。
     * 不编码时，{@code +} 会被服务端读成空格，导致「偶尔登录不上」。
     */
    @Test
    public void specialCharactersInParametersAreEncoded() {
        String url = RealtimeSocket.url("http://h:1/api/v1/", "a+b/c=d&e", "rs/1 2");

        assertTrue(url, url.contains("token=a%2Bb%2Fc%3Dd%26e"));
        assertTrue(url, url.contains("sessionId=rs%2F1%202"));
        // 空格必须编成 %20 而不是 +：URLEncoder 的默认行为是后者，这里已修正。
        assertFalse(url, url.contains("1+2"));
    }

    /**
     * 空 baseUrl 不该抛：这个函数只是拼串，拼出来什么都要原样返回。
     * 真正拦住它的地方是 {@code connect}（拼出来不是 ws/wss 就直接报错，不交给 OkHttp
     * 抛 IllegalArgumentException —— 那是崩溃，不是提示）。
     */
    @Test
    public void emptyBaseUrlDoesNotThrow() {
        assertEquals("/ws/realtime?token=t&sessionId=s", RealtimeSocket.url("", "t", "s"));
    }
}
