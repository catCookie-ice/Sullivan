package com.sullivan.protectee.data.api;

import com.sullivan.protectee.data.api.dto.ActivityDtos;
import com.sullivan.protectee.data.api.dto.AlertDtos;
import com.sullivan.protectee.data.api.dto.AuthDtos;
import com.sullivan.protectee.data.api.dto.BindingDtos;
import com.sullivan.protectee.data.api.dto.LookupDtos;
import com.sullivan.protectee.data.api.dto.QrDtos;
import com.sullivan.protectee.data.api.dto.RealtimeDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * 与后端 REST 接口一一对应（服务端见 {@code server-java/.../web/}）。
 *
 * <p><b>为什么是同步 {@link Call} 而不是 RxJava / suspend</b>：本模块是 Java + View，
 * 没有协程也没有 RxJava；{@code Call} 不需要额外调度器依赖，
 * 调用方在自带的后台线程里 {@code execute()}、回主线程更新界面即可
 * （见 {@code ApiClient.callBlocking}）。
 *
 * <p>路径前的 {@code api/v1/} 已由 baseUrl 带全，这里只写后半段。
 *
 * <p><b>这里只列受护人端真的会调的方法</b>。它与 {@code client-guardian} 的同名文件
 * 同源但**不共享**（两端各存一份，用户已选定）：监护人/志愿者侧的账号注册、找回密码、
 * 代建受护人、志愿者兼任申请、以及「监护人扫受护人的码」那三个方法都不在这里。
 * 不要因为「看起来漏了」而补回来 —— 补回来就会有人以为受护人端也能注册。
 *
 * <p><b>实时长连接的 WebSocket 端点不在这里</b>：{@code /ws/realtime} 位于服务器根路径，
 * 不在 {@code /api/v1/} 下，Retrofit 这条路够不着它。见 {@code service.websocket.RealtimeSocket}。
 */
public interface ApiService {

    // ------------------------------------------------------------------
    // 功能 A：扫码绑定（受护人出示 + 监护人扫码）
    // ------------------------------------------------------------------

    /** 受护人本人签发一张新的绑定码（同一位受护人的旧码会被作废）。 */
    @POST("qr/bind-codes")
    Call<QrDtos.BindCodeResponse> issueBindCode();

    // ------------------------------------------------------------------
    // 功能 B：扫码登录（受护人出示登录码 + 监护人授权）
    // 本端只有「出示」这一侧：受护人不扫码，扫的是监护人的手机。
    // ------------------------------------------------------------------

    /** 出示设备建票。**匿名可调用** —— 此时这台设备还没登录。 */
    @POST("qr/login-tickets")
    Call<QrDtos.LoginTicketCreateResponse> createLoginTicket(
            @Body QrDtos.LoginTicketCreateRequest request);

    /** 出示设备轮询状态。**匿名可调用**；响应里不会有令牌。 */
    @GET("qr/login-tickets/{ticket}")
    Call<QrDtos.LoginTicketStatusResponse> loginTicketStatus(@Path("ticket") String ticket);

    /**
     * 出示设备用本机 clientSecret + 拿到的 exchangeCode 换回令牌，**一次性**。
     * 匿名可调用：secret 就是这一步的凭证。
     */
    @POST("qr/login-tickets/{ticket}/exchange")
    Call<AuthDtos.TokenResponse> exchangeLoginTicket(
            @Path("ticket") String ticket, @Body QrDtos.LoginTicketExchangeRequest request);

    // ------------------------------------------------------------------
    // 绑定管理（双向：监护人与受护人都能发起，接收方确认）
    // ------------------------------------------------------------------

    /** 我作为监护人或受护人参与的全部绑定关系，含 PENDING 的请求。 */
    @GET("bindings")
    Call<List<BindingDtos.BindingResponse>> listBindings();

    /**
     * 发起绑定。当前用户是监护人时 {@code targetRole=PROTECTEE}，
     * 是受护人时 {@code targetRole=GUARDIAN}；方向搞反服务端会拒绝。
     */
    @POST("bindings")
    Call<BindingDtos.BindingResponse> requestBinding(@Body BindingDtos.Request request);

    /** 接收方确认。只有「不是发起方」的那一方能确认。 */
    @POST("bindings/{id}/accept")
    Call<BindingDtos.BindingResponse> acceptBinding(@Path("id") String bindingId);

    /** 接收方拒绝。 */
    @POST("bindings/{id}/reject")
    Call<BindingDtos.BindingResponse> rejectBinding(
            @Path("id") String bindingId, @Body BindingDtos.DecisionRequest request);

    /** 发起方在确认前撤回自己的请求。 */
    @POST("bindings/{id}/cancel")
    Call<BindingDtos.BindingResponse> cancelBinding(@Path("id") String bindingId);

    /**
     * 任一方解除已生效的绑定。
     *
     * <p><b>永久绑定（{@code permanent=true}）会返回 403</b>，这不是异常而是规则。
     * 界面应当在永久绑定的行上不显示这个按钮 —— 但也不要在这里预先拦截：
     * 万一将来规则变了，让服务端说了算，客户端只负责把它的原话显示出来。
     */
    @POST("bindings/{id}/terminate")
    Call<BindingDtos.BindingResponse> terminateBinding(
            @Path("id") String bindingId, @Body BindingDtos.DecisionRequest request);

    // ------------------------------------------------------------------
    // 账号查找
    // ------------------------------------------------------------------

    /** 按账号 ID 查基本信息。发起绑定时用它确认「这是谁」，避免手滑绑错人。 */
    @GET("users/{id}")
    Call<LookupDtos.UserResponse> getUser(@Path("id") String userId);

    // ------------------------------------------------------------------
    // 公益活动（只读）
    // ------------------------------------------------------------------

    /**
     * 公开活动列表。**需要登录**（服务端 {@code requireLoggedIn}），
     * 默认只返回 RECRUITING。{@code status} 传 null 表示用服务端默认值。
     */
    @GET("activities")
    Call<ActivityDtos.PageResult<ActivityDtos.ActivityResponse>> listActivities(
            @Query("status") String status,
            @Query("page") long page,
            @Query("size") long size);

    // ------------------------------------------------------------------
    // 实时会话（REST 部分；长连接见 RealtimeSocket）
    // ------------------------------------------------------------------

    /** 发起会话，状态为 REQUESTED，需对方接受。 */
    @POST("realtime/sessions")
    Call<RealtimeDtos.SessionResponse> createSession(
            @Body RealtimeDtos.SessionCreateRequest request);

    /** 与我有关的全部会话。 */
    @GET("realtime/sessions")
    Call<List<RealtimeDtos.SessionResponse>> listSessions();

    /** 接收方接受会话，状态转 ACTIVE，此时双方才可以建立 WebSocket。 */
    @POST("realtime/sessions/{id}/accept")
    Call<RealtimeDtos.SessionResponse> acceptSession(@Path("id") String sessionId);

    /** 接收方拒绝。 */
    @POST("realtime/sessions/{id}/reject")
    Call<RealtimeDtos.SessionResponse> rejectSession(@Path("id") String sessionId);

    /** 任一方结束会话。 */
    @POST("realtime/sessions/{id}/end")
    Call<RealtimeDtos.SessionResponse> endSession(@Path("id") String sessionId);

    // ------------------------------------------------------------------
    // 报警与位置
    // ------------------------------------------------------------------

    /**
     * 上报最新位置（覆盖式，只保留最新一条）。
     *
     * <p>受护人端在**进主界面后周期性**上报，而不是只在报警时上报：报警那一刻需要
     * 立刻知道位置，等到报警再定位，冷启动 GPS 可能要十几秒，那十几秒正是最关键的。
     */
    @PUT("me/location")
    Call<AlertDtos.AlertLocation> reportLocation(@Body AlertDtos.LocationReportRequest request);

    /** 我最近上报的位置；从未上报过时 404。 */
    @GET("me/location")
    Call<AlertDtos.AlertLocation> myLocation();

    /**
     * 创建报警。
     *
     * <p>服务端先落库再路由，并在 5 秒后（可由用户关闭）按绑定确认时间依次向全部
     * 生效监护人发起实时会话，同时附上此刻的位置快照。
     */
    @POST("alerts")
    Call<AlertDtos.AlertResponse> createAlert(@Body AlertDtos.AlertCreateRequest request);

    /** 我的报警列表（受护人视角，倒序）。 */
    @GET("alerts")
    Call<List<AlertDtos.AlertResponse>> listAlerts();

    /** 报警详情；服务端限定受护人本人与其生效监护人可读。 */
    @GET("alerts/{id}")
    Call<AlertDtos.AlertResponse> getAlert(@Path("id") String alertId);

    /** 结束报警（受护人本人），同时取消尚未执行的自动呼叫。 */
    @POST("alerts/{id}/resolve")
    Call<AlertDtos.AlertResponse> resolveAlert(@Path("id") String alertId);

    /** 读取报警偏好；从未配置过时服务端返回默认值（开启、5 秒）。 */
    @GET("me/alert-setting")
    Call<AlertDtos.AlertSettingResponse> getAlertSetting();

    /** 修改报警偏好；关闭时会一并取消已排期但尚未执行的自动呼叫。 */
    @PUT("me/alert-setting")
    Call<AlertDtos.AlertSettingResponse> updateAlertSetting(
            @Body AlertDtos.AlertSettingUpdateRequest request);

    /** 报警会依次通知谁（受护人端展示「会通知谁」）。 */
    @GET("me/alert-guardians")
    Call<List<String>> alertGuardians();
}
