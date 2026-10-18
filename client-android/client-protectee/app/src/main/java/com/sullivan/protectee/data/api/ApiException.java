package com.sullivan.protectee.data.api;

/**
 * 一次接口调用失败，且已经拿到了可以给人看的中文原因。
 *
 * <p>存在的意义是把「服务端说了什么」和「网络根本不通」区分开：
 * 服务端的非 2xx 响应体是 {@code {"error": "..."}}，那串中文是给用户看的；
 * 连不上则是另一回事。两类都收敛成这个异常，界面层只认 {@link #getMessage()}。
 */
public final class ApiException extends RuntimeException {

    /** HTTP 状态码；网络层失败（连不上、超时）时为 0。 */
    private final int httpCode;

    public ApiException(int httpCode, String message) {
        super(message);
        this.httpCode = httpCode;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpCode = 0;
    }

    public int httpCode() {
        return httpCode;
    }

    /** 401：令牌缺失/过期/无效。界面层据此把用户送回登录页。 */
    public boolean isUnauthorized() {
        return httpCode == 401;
    }
}
