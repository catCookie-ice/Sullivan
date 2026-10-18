package com.sullivan.android.data.api;

import com.sullivan.android.data.api.dto.ActivityDtos;
import com.sullivan.android.data.api.dto.AuthDtos;
import com.sullivan.android.data.api.dto.BindingDtos;
import com.sullivan.android.data.api.dto.LookupDtos;
import com.sullivan.android.data.api.dto.QrDtos;
import com.sullivan.android.data.api.dto.RealtimeDtos;
import com.sullivan.android.data.api.dto.RecoveryDtos;
import com.sullivan.android.data.api.dto.VolunteerDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PUT;
import retrofit2.http.POST;
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
 * <p><b>并不是每个后端接口都在这里</b>：只列客户端真的会调的。
 * 机构端（发布活动、审核报名）与管理端（审核志愿者、审核机构）是桌面控制台，
 * Android 侧没有对应界面，因此也没有对应方法 —— 不给「留着以后用」的接口
 * 在这里占位，它们只会在重构时变成没人敢删的死代码。
 *
 * <p><b>实时长连接的 WebSocket 端点不在这里</b>：{@code /ws/realtime} 位于服务器根路径，
 * 不在 {@code /api/v1/} 下，Retrofit 这条路够不着它。见 {@code service.websocket.RealtimeSocket}。
 */
public interface ApiService {

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    /** 监护人账密登录。扫码授权本身要求监护人已登录，所以这一步无法用扫码替代。 */
    @POST("auth/login")
    Call<AuthDtos.TokenResponse> login(@Body AuthDtos.LoginRequest request);

    /** 注册监护人/志愿者。受护人账号不能自助注册，只能由监护人代建。 */
    @POST("auth/register/user")
    Call<AuthDtos.RegistrationResponse> registerUser(@Body AuthDtos.RegisterUserRequest request);

    // ------------------------------------------------------------------
    // 找回密码
    // ------------------------------------------------------------------

    /** 第一步：拿这个账号已设置的找回方式（只给掩码前缀）。 */
    @POST("auth/password-recovery/start")
    Call<RecoveryDtos.StartResponse> recoveryStart(@Body RecoveryDtos.StartRequest request);

    /** 第二步：用户补全完整手机号/邮箱，服务端比对。**不通过是 200 + verified=false**。 */
    @POST("auth/password-recovery/verify")
    Call<RecoveryDtos.VerifyResponse> recoveryVerify(@Body RecoveryDtos.VerifyRequest request);

    /** 登录后查自己的找回设置状态。 */
    @GET("auth/me/recovery")
    Call<RecoveryDtos.StatusResponse> myRecovery();

    /** 设置找回手机号/邮箱（至少一项）。 */
    @PUT("auth/me/recovery")
    Call<RecoveryDtos.ActionResponse> setRecovery(@Body RecoveryDtos.SetRequest request);

    /** 「下次再说」：本次登录不再提醒，但下次登录还会提醒。 */
    @POST("auth/me/recovery/remind-later")
    Call<RecoveryDtos.ActionResponse> recoveryRemindLater();

    /** 「不再提醒」：服务端记下之后不再提醒。 */
    @POST("auth/me/recovery/dismiss")
    Call<RecoveryDtos.ActionResponse> recoveryDismiss();

    // ------------------------------------------------------------------
    // 功能 A：扫码绑定（受护人出示 + 监护人扫码）
    //
    // 本端只有「扫码」这一侧：签发绑定码的 issueBindCode() 与取当前码
    // currentBindCode() 属于**出示设备**（受护人那台），随受护人端拆到
    // client-protectee 去了。currentBindCode 在拆分时顺带删掉 —— 它在拆分前
    // 就已经没有任何调用方。
    // ------------------------------------------------------------------

    /**
     * 监护人扫码绑定。{@code guardianId} 由服务端从 Bearer 令牌取得，
     * 请求体里只有 code —— 不允许客户端指定监护人身份。
     */
    @POST("qr/bind-codes/scan")
    Call<QrDtos.BindScanResponse> scanBindCode(@Body QrDtos.BindScanRequest request);

    // ------------------------------------------------------------------
    // 功能 B：扫码登录（受护人出示登录码 + 监护人授权）
    //
    // 本端只有「授权」这一侧。建票（createLoginTicket）、轮询（loginTicketStatus）、
    // 换令牌（exchangeLoginTicket）都是**出示设备**的动作，随受护人端拆到
    // client-protectee 去了 —— 那三步都由受护人那台手机发起。
    // ------------------------------------------------------------------

    /** 监护人扫到登录码：标记 SCANNED，并取回自己名下可作为授权对象的受护人候选。 */
    @POST("qr/login-tickets/{ticket}/scan")
    Call<QrDtos.LoginTicketScanResponse> scanLoginTicket(@Path("ticket") String ticket);

    /** 监护人从候选里选定一位 → 服务端复核 ACTIVE 绑定 → 生成 exchangeCode。 */
    @POST("qr/login-tickets/{ticket}/confirm")
    Call<QrDtos.LoginTicketStatusResponse> confirmLoginTicket(
            @Path("ticket") String ticket, @Body QrDtos.LoginTicketConfirmRequest request);

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
    // 账号查找与受护人代建
    // ------------------------------------------------------------------

    /**
     * 按账号 ID 查基本信息。发起绑定时用它把用户输入的登录名换成一个可确认的「这是谁」，
     * 避免用户把一个手滑打错的 ID 绑上去。
     */
    @GET("users/{id}")
    Call<LookupDtos.UserResponse> getUser(@Path("id") String userId);

    /**
     * 监护人代建受护人账号。服务端在同一个事务里建立 permanent=true 的主绑定，
     * 所以创建完就已经绑好了，不需要再扫码。
     */
    @POST("guardians/protectees")
    Call<AuthDtos.RegistrationResponse> createProtectee(
            @Body AuthDtos.ProtecteeCreateRequest request);

    // ------------------------------------------------------------------
    // 志愿者兼任申请（监护人 → 志愿者）
    // ------------------------------------------------------------------

    /** 提交申请。已经提交过 PENDING 申请时服务端会拒绝，界面不要重复放行。 */
    @POST("users/me/roles/volunteer")
    Call<VolunteerDtos.ApplicationResponse> applyVolunteer(
            @Body VolunteerDtos.ApplyRequest request);

    /** 我的全部申请记录（含历史）。 */
    @GET("users/me/roles/volunteer")
    Call<List<VolunteerDtos.ApplicationResponse>> myVolunteerApplications();

    /** 撤销一条申请。只有 PENDING 才有意义。 */
    @POST("users/me/roles/volunteer/cancel/{applicationId}")
    Call<VolunteerDtos.ApplicationResponse> cancelVolunteer(
            @Path("applicationId") String applicationId);

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
}
