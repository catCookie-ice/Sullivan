package com.sullivan.android.data.api;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.sullivan.android.BuildConfig;
import com.sullivan.android.data.api.dto.QrDtos;
import com.sullivan.android.data.session.SessionStore;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Retrofit / OkHttp 的进程内单例。
 *
 * <p>取代了原先的 Hilt {@code di/AppModule} —— 本工程不启用 Hilt（见
 * {@code docs/final/17-开发日记-2026-10-16.md}），依赖关系简单到用不着注解处理器：
 * 需要 {@link ApiService} 的地方调 {@link #get(Context)} 即可。
 *
 * <p><b>就是进程内单例，没有别的花样</b>：后端地址来自构建期常量
 * （见 {@link SessionStore#baseUrl()}），一个安装包只可能连一台服务器，
 * 所以这里不需要「地址变了就重建」那套逻辑。此前那套
 * （按 baseUrl 缓存 + {@code invalidate()}）随地址输入框一并撤销。
 */
public final class ApiClient {

    private static final String TAG = "ApiClient";

    private static final Gson GSON = new Gson();

    private static volatile ApiService service;

    /**
     * 「登录态失效」的进程级观察点。
     *
     * <p><b>为什么放在网络层。</b>修一个真实故障时发现的：令牌过期后，各页面各自
     * 决定怎么处理 401 —— 有的清会话并提示，有的只把错误塞进页面底部的状态行，
     * 而 {@code GuardianActivity} 的扫码回调里**一份都没有**。表现是「监护人扫完码
     * 直接结束，既没有选人列表，受护人端也没登录」，而且毫无提示 ——
     * 因为那行状态文字在最末尾，用户扫码时根本看不到。
     *
     * <p>把判断收拢到唯一一条出口（{@link #attachToken} 已经在拦每一个请求），
     * 就不会再有"新写的页面忘了处理 401"这种事。界面只需要订阅一次，
     * 而不必在每个 {@code onError} 里重复判断。
     *
     * <p>回调在 **OkHttp 的工作线程**，订阅方要自己切主线程。
     */
    public interface LoginExpiredListener {
        void onLoginExpired();
    }

    @Nullable
    private static volatile LoginExpiredListener loginExpiredListener;

    /** 订阅登录失效。传 {@code null} 取消订阅。 */
    public static void setLoginExpiredListener(@Nullable LoginExpiredListener listener) {
        loginExpiredListener = listener;
    }

    /**
     * 收到 401 时的统一动作：清本地会话 + 通知订阅方。
     *
     * <p>**清会话是必须的**：{@code SessionStore.isLoggedIn()} 只看令牌字符串是否非空，
     * 令牌过期后它仍然返回 true，于是本地一直"以为自己还登着"，
     * 这是"掉了却没踢下线"的根源。
     */
    private static void onUnauthorized(@NonNull SessionStore session) {
        if (!session.isLoggedIn()) {
            // 已经是未登录状态（比如刚被别处清过），不重复通知。
            return;
        }
        session.clearLogin();
        LoginExpiredListener listener = loginExpiredListener;
        if (listener != null) {
            listener.onLoginExpired();
        }
    }

    private ApiClient() {
    }

    /** 取接口实例（进程内单例，首次调用时构建）。 */
    @NonNull
    public static ApiService get(@NonNull Context context) {
        ApiService current = service;
        if (current == null) {
            synchronized (ApiClient.class) {
                if (service == null) {
                    SessionStore session = new SessionStore(context);
                    service = build(session.baseUrl(), session).create(ApiService.class);
                }
                current = service;
            }
        }
        return current;
    }

    private static Retrofit build(@NonNull String baseUrl, @NonNull SessionStore session) {
        OkHttpClient.Builder http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(chain -> attachToken(chain, session));

        if (BuildConfig.DEBUG) {
            // 只在 debug 开 BODY 级日志。release 里绝不开：登录请求体带密码、
            // 兑换请求体带 clientSecret，请求头带 accessToken，全都会进 logcat，
            // 而 Android 上任何应用读到这些日志都是不合法的边界问题。
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
                    message -> Log.d(TAG, message));
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            http.addInterceptor(logging);
        }

        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http.build())
                .addConverterFactory(GsonConverterFactory.create(GSON))
                .build();
    }

    private static Response attachToken(okhttp3.Interceptor.Chain chain, SessionStore session)
            throws IOException {
        Request request = chain.request();
        // 请求上已经带了 Authorization 就不覆盖：留出「用别人的身份试一下」这种排查余地，
        // 也避免将来某个接口要用临时凭据时被这里悄悄换掉。
        String token = request.header("Authorization") != null ? null : session.accessToken();
        Response response = (token == null)
                ? chain.proceed(request)
                : chain.proceed(request.newBuilder()
                        .header("Authorization", "Bearer " + token)
                        .build());

        // 401 = 令牌缺失/过期/无效。这里是**唯一**能覆盖每一个请求的地方，
        // 所以统一在这里收口（理由见 LoginExpiredListener）。
        //
        // 判断交给 AuthExpiry：登录接口自己在密码错时也返回 401，那种不该清会话
        // （它的请求根本没带令牌）。
        if (AuthExpiry.shouldExpireSession(response.code(), token != null)) {
            onUnauthorized(session);
        }
        return response;
    }

    /**
     * 把服务端的 {@code {"error": "..."}} 错误体解出来。
     *
     * <p>服务端所有业务错误都走 {@code common/ApiErrorHandler}，格式统一；
     * 解不出来（如 502 网关页、空体）才退回按状态码给一句笼统的话。
     */
    @NonNull
    static ApiException toApiException(@NonNull retrofit2.Response<?> response) {
        int code = response.code();
        String message = null;
        try {
            if (response.errorBody() != null) {
                String raw = response.errorBody().string();
                if (!TextUtils.isEmpty(raw)) {
                    QrDtos.ApiError parsed = GSON.fromJson(raw, QrDtos.ApiError.class);
                    if (parsed != null && !TextUtils.isEmpty(parsed.error)) {
                        message = parsed.error;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 错误体读不出来不是致命问题，下面按状态码兜底。
        }
        if (TextUtils.isEmpty(message)) {
            message = fallbackMessage(code);
        }
        return new ApiException(code, message);
    }

    private static String fallbackMessage(int code) {
        switch (code) {
            case 401:
                return "登录已失效，请重新登录";
            case 403:
                return "没有权限执行该操作";
            case 404:
                return "请求的内容不存在或已失效";
            case 409:
                return "操作冲突，请刷新后重试";
            case 410:
                return "内容已过期，请重新生成";
            case 400:
                return "请求参数不合法";
            default:
                return "服务器返回错误（HTTP " + code + "）";
        }
    }

    /**
     * 连 OkHttp / Gson 这一层都没过的失败，统一包成 {@link ApiException}。
     *
     * <p>分两类，因为给用户的话完全不同：
     * <ul>
     *   <li>{@link IOException}（连不上、超时、DNS 解析不了）——把原始信息附在括号里。
     *       这类信息（如 {@code Failed to connect to /10.0.2.2:8642}）是排查「地址填错了」
     *       最直接的线索，本工程正处于真机联调阶段，留着比藏起来有用。</li>
     *   <li>其它（JSON 结构与 DTO 对不上等）——说明前后端版本不一致，
     *       原始异常是英文技术细节，对用户没有意义，只记日志。</li>
     * </ul>
     */
    @NonNull
    static ApiException transportFailure(@Nullable Throwable cause) {
        if (cause instanceof IOException) {
            String detail = cause.getMessage();
            return new ApiException(TextUtils.isEmpty(detail)
                    ? "网络连接失败，请检查服务器地址与网络"
                    : "网络连接失败：" + detail, cause);
        }
        Log.w(TAG, "响应解析失败", cause);
        return new ApiException("响应无法解析，请确认客户端与服务端版本一致", cause);
    }
}
