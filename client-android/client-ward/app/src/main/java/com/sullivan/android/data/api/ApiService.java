package com.sullivan.android.data.api;

import com.sullivan.android.data.api.dto.AuthDtos;
import com.sullivan.android.data.api.dto.QrDtos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * 与后端 REST 接口一一对应（服务端见 {@code server-java/.../web/}）。
 *
 * <p><b>为什么是同步 {@link Call} 而不是 RxJava / suspend</b>：本模块是 Java + View，
 * 没有协程也没有 RxJava；{@code Call} 不需要额外调度器依赖，
 * 调用方在自带的后台线程里 {@code execute()}、回主线程更新界面即可
 * （见 {@code ApiClient.callBlocking}）。
 *
 * <p>路径前的 {@code api/v1/} 已由 baseUrl 带全，这里只写后半段。
 */
public interface ApiService {

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    /** 监护人账密登录。扫码授权本身要求监护人已登录，所以这一步无法用扫码替代。 */
    @POST("auth/login")
    Call<AuthDtos.TokenResponse> login(@Body AuthDtos.LoginRequest request);

    // ------------------------------------------------------------------
    // 功能 A：扫码绑定（受护人出示 + 监护人扫码）
    // ------------------------------------------------------------------

    /** 受护人本人签发一张新的绑定码（同一位受护人的旧码会被作废）。 */
    @POST("qr/bind-codes")
    Call<QrDtos.BindCodeResponse> issueBindCode();

    /** 受护人本人取当前有效码；没有有效码时服务端返回 204，调用方得到空 body。 */
    @GET("qr/bind-codes/current")
    Call<QrDtos.BindCodeResponse> currentBindCode();

    /**
     * 监护人扫码绑定。{@code guardianId} 由服务端从 Bearer 令牌取得，
     * 请求体里只有 code —— 不允许客户端指定监护人身份。
     */
    @POST("qr/bind-codes/scan")
    Call<QrDtos.BindScanResponse> scanBindCode(@Body QrDtos.BindScanRequest request);

    // ------------------------------------------------------------------
    // 功能 B：扫码登录（受护人出示登录码 + 监护人授权）
    // ------------------------------------------------------------------

    /** 出示设备建票。**匿名可调用** —— 此时这台设备还没登录。 */
    @POST("qr/login-tickets")
    Call<QrDtos.LoginTicketCreateResponse> createLoginTicket(
            @Body QrDtos.LoginTicketCreateRequest request);

    /** 出示设备轮询状态。**匿名可调用**；响应里不会有令牌。 */
    @GET("qr/login-tickets/{ticket}")
    Call<QrDtos.LoginTicketStatusResponse> loginTicketStatus(@Path("ticket") String ticket);

    /** 监护人扫到登录码：标记 SCANNED，并取回自己名下可作为授权对象的受护人候选。 */
    @POST("qr/login-tickets/{ticket}/scan")
    Call<QrDtos.LoginTicketScanResponse> scanLoginTicket(@Path("ticket") String ticket);

    /** 监护人从候选里选定一位 → 服务端复核 ACTIVE 绑定 → 生成 exchangeCode。 */
    @POST("qr/login-tickets/{ticket}/confirm")
    Call<QrDtos.LoginTicketStatusResponse> confirmLoginTicket(
            @Path("ticket") String ticket, @Body QrDtos.LoginTicketConfirmRequest request);

    /**
     * 出示设备用本机 clientSecret + 拿到的 exchangeCode 换回令牌，**一次性**。
     * 匿名可调用：secret 就是这一步的凭证。
     */
    @POST("qr/login-tickets/{ticket}/exchange")
    Call<AuthDtos.TokenResponse> exchangeLoginTicket(
            @Path("ticket") String ticket, @Body QrDtos.LoginTicketExchangeRequest request);
}
