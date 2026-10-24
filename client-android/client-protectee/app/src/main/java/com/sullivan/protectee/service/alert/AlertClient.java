package com.sullivan.protectee.service.alert;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.AlertDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.service.location.LocationReporter;

/**
 * 报警的发送端：把「触发原因」变成一次 {@code POST /api/v1/alerts}。
 *
 * <p><b>为什么要先强制刷新一次位置。</b>报警的价值一半在「有人来」，另一半在
 * 「知道去哪」。周期上报最多 10 秒前，而人在这 10 秒里可能已经走了十几米。
 * 报警前调一次 {@link LocationReporter#reportOnce()}，把这个窗口压到最小。
 *
 * <p><b>为什么不做本地队列重发。</b>报警不是消息：晚 30 秒送达的报警没有意义，
 * 而且会与「用户已经安全了」的状态冲突。失败就如实告诉用户（界面负责说话），
 * 让他自己再按一次 —— 用户按第二次是有意识的动作，比后台默默重试更可控。
 */
public final class AlertClient {

    private static final String TAG = "AlertClient";

    /** 报警类型，与服务端 alert_type 的可达值一致。 */
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_COLLISION = "COLLISION";

    private final Context context;
    private final SessionStore session;
    private final LocationReporter locationReporter;

    public AlertClient(@NonNull Context context, @NonNull LocationReporter locationReporter) {
        this.context = context.getApplicationContext();
        this.session = new SessionStore(this.context);
        this.locationReporter = locationReporter;
    }

    /** 报警结果回调。在主线程。 */
    public interface Callback {
        void onCreated(@NonNull AlertDtos.AlertResponse alert);

        /**
         * 失败。
         *
         * <p><b>带的是 {@link ApiException} 而不是一句字符串</b>：界面需要区分
         * 「网络不通」和「登录已失效（401）」。后者意味着用户必须先重新登录，
         * 而报警**没有发出去**这件事比一般的请求失败严重得多 ——
         * 受护人可能正因为发不出去而以为已经通知了监护人。
         *
         * <p>早先这里只回传 {@code String}，状态码在这一层就被抹掉了，
         * 界面即使想分辨也分辨不出来。
         */
        void onFailed(@NonNull ApiException error);
    }

    /**
     * 发送一条报警。
     *
     * @param alertType MANUAL 或 COLLISION
     * @param note      一句话说明；可为 null
     * @param nowMs     触发时刻，用于派生幂等键
     * @param hasActiveAlert 是否已有一条未结束的报警。
     *        {@code false} 时必须能开一条**新的** —— 否则用户在 5 分钟窗口内
     *        先误触并点过「我没事」，随后真的求助会被服务端当成重复提交、
     *        返回那条已结束的旧报警（看起来就是"发不出去"）。
     */
    public void send(@NonNull String alertType, @Nullable String note, long nowMs,
                     boolean hasActiveAlert, @NonNull Callback callback) {
        String uid = session.principalId();
        if (uid == null || uid.isEmpty()) {
            // 本地就没有身份：造一个 401，让界面走与"服务端说没身份"完全相同的分支。
            callback.onFailed(new ApiException(401, "尚未登录，无法发送报警"));
            return;
        }

        String key = AlertKeys.of(uid, alertType, nowMs, hasActiveAlert);

        // **先取位置、再发报警**，顺序不能颠倒。
        //
        // 原来是"先 reportOnce() 再发报警"——那是两次独立请求，
        // 实测报警往往先到、位置后到，于是报警里根本没有位置；
        // 而服务端只能读 latest_location 缓存，读到的是几分钟前的旧坐标。
        // 现在位置由**同一次请求**带上去，服务端以它为准。
        //
        // 代价是报警可能晚最多 12 秒（取定位的超时），但：
        //   1) 手上有 20 秒内的位置时立刻返回，不会等；
        //   2) 拿不到也会在超时后继续发 —— **绝不为了坐标而不报警**。
        //
        // 外面还套了一层本类的看门狗（见下方 alertGuard），
        // 双重保险：即便 requestLocationForAlert 因为任何原因没有回调，
        // 报警仍然会在这个截止时间内发出。
        java.util.concurrent.atomic.AtomicBoolean sent =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        locationReporter.requestLocationForAlert(location -> {
            if (!sent.compareAndSet(false, true)) {
                return;
            }
            AlertDtos.AlertCreateRequest request =
                    new AlertDtos.AlertCreateRequest(alertType, key, note);
            if (location != null) {
                request.location = new AlertDtos.AlertCaptureRequest(
                        location.getLatitude(), location.getLongitude());
                if (location.hasAccuracy()) {
                    request.location.accuracyM = (double) location.getAccuracy();
                }
                request.location.capturedAt = formatCapturedAt(location.getTime());
            } else {
                Log.w(TAG, "报警时拿不到位置，将不带坐标发出");
            }
            createAlert(request, callback);
        });

        // 看门狗：位置回调是"应该"会来的，但依赖"应该"是危险的 ——
        // 报警不能因为位置模块的任何疏漏而永远不发。
        // 这里等得比定位超时略久，正常情况下位置回调会先到并把它取消。
        ALERT_GUARD.postDelayed(() -> {
            if (sent.compareAndSet(false, true)) {
                Log.w(TAG, "取位置超时仍未回调，立即不带坐标发出报警");
                createAlert(new AlertDtos.AlertCreateRequest(alertType, key, note), callback);
            }
        }, ALERT_GUARD_DELAY_MS);
    }

    /** 该用哪个 handler / 延迟：单独常量便于测试时对照。 */
    private static final android.os.Handler ALERT_GUARD =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * 看门狗延迟。
     *
     * <p>比 {@code LocationReporter.ALERT_FIX_TIMEOUT_MS}（12 秒）多留 3 秒：
     * 正常的超时回调会先到并取消这个看门狗，只有在定位模块整个失联时它才生效。
     */
    private static final long ALERT_GUARD_DELAY_MS = 15_000L;

    /**
     * 采集时刻格式化成服务端要的样子。
     *
     * <p>又一处 `yyyy-MM-dd HH:mm:ss`（**空格**，不是 ISO 的 `T`）。
     * 这个格式在服务端 `JacksonConfig` 里全局钉死，写错就是 400 ——
     * 位置上报曾经因此整个功能失效过一次。
     */
    @NonNull
    private static String formatCapturedAt(long epochMs) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(epochMs));
    }

    /** 真正发出创建请求。与取位置解耦，便于两处（有/无位置）共用。 */
    private void createAlert(@NonNull AlertDtos.AlertCreateRequest request,
                             @NonNull Callback callback) {
        ApiClient.get(context).createAlert(request).enqueue(new ResultCallback<>() {
            @Override
            public void onSuccess(@Nullable AlertDtos.AlertResponse body) {
                if (body == null) {
                    callback.onFailed(new ApiException(0, "服务端没有返回报警信息"));
                    return;
                }
                Log.i(TAG, "报警已创建 " + body.id + "，状态 " + body.status
                        + "，自动呼叫 " + body.autoSessionState);
                callback.onCreated(body);
            }

            @Override
            public void onError(@NonNull ApiException error) {
                Log.w(TAG, "报警发送失败：" + error.getMessage());
                callback.onFailed(error);
            }
        });
    }

    /**
     * 结束一条报警。
     *
     * <p><b>callback 可为 null，且必须处理这种情况。</b>这是一次真实崩溃的成因：
     * 原来直接把 {@code callback} 透传给 {@code enqueue}，而「我没事」按钮调
     * {@code resolve(alertId, null)} —— Retrofit 在请求返回时会调用
     * {@code callback.onResponse(...)}，于是**必然 NPE 崩溃**。
     *
     * <p>表现极具误导性：用户点「我没事」，报警面板消失，然后应用"异常返回首页"
     * （其实是进程崩溃后重启）。看起来像界面跳转 bug，实际是空指针。
     */
    public void resolve(@NonNull String alertId,
                        @Nullable ResultCallback<AlertDtos.AlertResponse> callback) {
        ApiClient.get(context).resolveAlert(alertId).enqueue(
                callback != null ? callback : new ResultCallback<>() {
                    @Override
                    public void onSuccess(@Nullable AlertDtos.AlertResponse body) {
                        Log.i(TAG, "报警已结束");
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 结束失败不打扰用户：界面已经收起面板了，
                        // 而且下一次「我没事」或服务端超时都会重新收敛。
                        Log.w(TAG, "结束报警失败：" + error.getMessage());
                    }
                });
    }
}
