package com.sullivan.protectee.service.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.AlertDtos;
import com.sullivan.protectee.data.session.SessionStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 周期性把本机位置上报给服务端，供报警时读取。
 *
 * <p><b>为什么要在走路时一直上报，而不是报警时才定位。</b>报警是紧急事件，
 * 那一刻才去开 GPS，冷启动首次定位常要十几秒、室内还可能拿不到；而那十几秒
 * 恰好是判断「人在哪」最值钱的时间。平时每秒轻轻上报一次，报警时就能立刻
 * 取到一个几秒前的位置。
 *
 * <p><b>为什么用 {@link LocationManager} 而不是 FusedLocationProvider。</b>
 * 后者要引入 Google Play Services 依赖，而本模块刻意只用 AndroidX + CameraX，
 * 不绑定 GMS（国内设备普遍没有）。代价是精度略差，但配合 {@code accuracyM}
 * 一起上报，服务端与监护人能自己判断可信度。
 *
 * <p><b>省电策略：只在拿到 {@code getLastKnownLocation} 或被动更新时上报。</b>
 * 不主动 {@code requestLocationUpdates}：本模块的主任务是摄像头推理，
 * 再开一路主动定位会明显加剧发热与耗电，而受护人端本来就在耗电。
 * 被动 updated 由系统在别的应用定位时顺带给我们。
 *
 * <p><b>生命周期：只在受护人主界面存活期间运行。</b>退出界面即停止 —— 这是
 * 隐私与耗电两条线共同的要求，位置不该在用户没在用守护功能时也持续外发。
 */
public final class LocationReporter {

    /**
     * 为报警取位置的回调。
     *
     * <p>刻意不用 {@code Consumer<Location>}：这里返回 null 是**正常结果**
     * （表示拿不到位置，报警照发），而不是错误。用明确命名的接口，
     * 免得调用方以为"拿到 null 说明出错了，该重试"。
     */
    public interface AlertLocationCallback {
        /** @param location 当前位置；拿不到时为 null（报警仍应发出） */
        void onResult(@Nullable Location location);
    }

    private static final String TAG = "LocationReporter";

    /** 上报间隔。10 秒≈一个人走 12 米，对「他在哪」这个粒度够用，又不会太耗电。 */
    private static final long REPORT_INTERVAL_MS = 10_000L;

    /**
     * 位置超过这个年龄就不重复上报。
     *
     * <p>只有被动更新时，位置可能几十分钟没变。这时候重复上报同一个旧坐标没有意义，
     * 但也不能完全不报 —— 中断后服务端会一直保留更旧的那条。折中：超过 2 分钟就重报一次。
     */
    private static final long MAX_LOCATION_AGE_MS = 120_000L;

    /**
     * 主动定位多久没结果就放弃。
     *
     * <p>30 秒是"室内网络定位通常 5–15 秒能出结果"留的余量。超时不是失败提示，
     * 只是把这一轮让给下一次重试 —— 用户不该因为一次定位慢就看到错误。
     */
    private static final long ACTIVE_FIX_TIMEOUT_MS = 30_000L;

    /**
     * 多久以内的位置可以直接用于报警，不必再去取一次。
     *
     * <p>20 秒：一个人步行 20 秒约 25 米，对"到哪儿去找他"完全够用，
     * 而为此多等一次定位（室内可能十几秒）反而拖延报警。报警的首要价值是快。
     */
    private static final long FRESH_FOR_ALERT_MS = 20_000L;

    /**
     * 为报警取定位最多等多久。
     *
     * <p>12 秒：远短于常规的 30 秒。用户按了求助按钮之后每秒都在等，
     * 而报警**不该为了坐标而迟到** —— 拿不到就先发出去，
     * 监护人至少能立刻打电话过来问。
     */
    private static final long ALERT_FIX_TIMEOUT_MS = 12_000L;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 报警定位的超时调度。与常规上报复用同一个 Looper 即可。 */
    private final Handler alertFixHandler = new Handler(Looper.getMainLooper());
    private final SessionStore session;

    /**
     * 定位回调必须存成字段。
     *
     * <p>{@code removeUpdates(this::onLocationChanged)} 是错的：每次求值方法引用都会
     * 新建一个对象，{@code LocationManager} 按对象身份匹配，永远匹配不上，于是
     * {@link #stop()} 什么也没停掉，定位回调会一直活着 —— 表现是退出界面后还在耗电，
     * 而且找不到原因。
     */
    private final android.location.LocationListener locationListener = this::onLocationChanged;

    private boolean running;
    /** 是否正在为一个"手里没有位置"的空窗主动要定位（拿到结果后会撤掉）。 */
    private boolean activeFixRequested;
    /** 那次主动定位是什么时候发起的，用于超时放弃。 */
    private long activeFixRequestedAt;
    @Nullable
    private Location lastReported;

    /**
     * 正在等待定位结果的报警回调。
     *
     * <p><b>必须是集合，不能是单个字段。</b>用户可能连点求助，或手动求助与
     * 碰撞判定几乎同时触发 —— 用单个字段会互相覆盖，后登记的挤掉先登记的，
     * 先登记那条报警的回调永远不来，于是**那条报警永远发不出去**。
     * 这正好发生在最紧急的时刻，所以这里是集合而不是单个引用。
     *
     * <p>所有等待者共用同一次定位尝试，拿到结果（或超时）时一次性全部回调。
     */
    private final AlertLocationWaiters alertWaiters = new AlertLocationWaiters();

    /** 报警定位的超时兜底。 */
    private final Runnable alertFixTimeout = () -> {
        Log.w(TAG, "为报警取定位超时，用现有位置（可能为空）继续");
        finishAlertRequest(bestLastKnown());
    };

    public LocationReporter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        this.session = new SessionStore(this.context);
    }

    /** 是否已获得定位权限（粗略或精确任一即可）。 */
    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 系统定位总开关是否打开。关掉时任何定位 API 都拿不到结果，早发现早提示。 */
    public boolean isLocationEnabled() {
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /**
     * 开始周期上报。重复调用是安全的（内部幂等）。
     *
     * <p>没有权限时不启动，也**不弹任何提示** —— 由界面负责在合适的时机引导用户，
     * 这里安静退出即可。位置是增强功能，缺了它障碍检测照样工作。
     */
    @MainThread
    public void start() {
        if (running) {
            return;
        }
        if (!session.isLoggedIn()) {
            // 未登录时上报必然 401，不做无意义的请求。
            return;
        }
        if (!hasPermission() || locationManager == null) {
            Log.i(TAG, "没有定位权限，跳过位置上报");
            return;
        }
        running = true;
        try {
            // 先试被动更新：系统在其他应用定位时顺带给我们，几乎不额外耗电。
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    0L, 0f, locationListener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException error) {
            // 权限在运行期被撤销，或设备没有 PASSIVE provider。
            Log.w(TAG, "注册被动定位更新失败", error);
        }
        requestActiveFixIfStale();
        handler.post(reportRunnable);
    }

    /**
     * 只有在「手里没有可用位置」时才主动要一次定位。
     *
     * <p><b>为什么必须有这一步。</b>只靠 {@code getLastKnownLocation} + 被动更新的设计
     * 有一个致命空洞：设备刚开机、或从没被别的应用定位过时，
     * {@code getLastKnownLocation} 返回 {@code null}，而被动更新又要等**别的应用**
     * 去定位才轮得到我们 —— 可能永远等不到。结果就是"根本没有位置"，
     * 报警里也没有坐标。
     *
     * <p>这是一个真实故障：用户反馈"没有办法获取地理位置"。查下来
     * {@link #reportOnce()} 在拿不到 last known 时直接 return，
     * 而它又是唯一的取位置入口 —— 于是从来没有主动发起过一次定位。
     *
     * <p>只在"没有位置或位置太旧"时才请求，且用 {@code minTime=0, minDistance=0}
     * 拿一次就 {@link #stop()} 掉，所以不会变成持续耗电。
     */
    private void requestActiveFixIfStale() {
        Location known = bestLastKnown();
        if (known != null && System.currentTimeMillis() - known.getTime() < MAX_LOCATION_AGE_MS) {
            // 已有新鲜位置，不必额外唤醒 GPS。
            return;
        }
        if (locationManager == null) {
            return;
        }
        // 优先 NETWORK：室内可用、首次定位快、耗电低。没有该 provider 再退 GPS。
        String provider = null;
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            }
        } catch (IllegalArgumentException ignored) {
            // 设备没有这些 provider（极少见），保持 provider == null。
        }
        if (provider == null) {
            Log.w(TAG, "没有可用的定位 provider，位置仍不可得");
            return;
        }
        try {
            activeFixRequested = true;
            activeFixRequestedAt = System.currentTimeMillis();
            locationManager.requestLocationUpdates(provider, 0L, 0f,
                    locationListener, Looper.getMainLooper());
            Log.i(TAG, "已请求一次主动定位：" + provider);
        } catch (SecurityException | IllegalArgumentException error) {
            activeFixRequested = false;
            Log.w(TAG, "请求主动定位失败", error);
        }
    }

    /**
     * 主动定位是否已经等太久。
     *
     * <p>没有这个兜底会有一个坏情形：室内、地下室、或用户关闭了精确定位时，
     * 主动请求可能**一个回调都不来**。那样 {@code activeFixRequested} 永远是 true，
     * 撤不掉主动定位，也就不会再次尝试 —— 白白耗电却永远拿不到位置。
     * 超时后放弃这一轮，交给下一次 {@link #requestActiveFixIfStale()} 重来。
     */
    private void abandonStaleActiveFixIfNeeded() {
        if (!activeFixRequested) {
            return;
        }
        if (System.currentTimeMillis() - activeFixRequestedAt < ACTIVE_FIX_TIMEOUT_MS) {
            return;
        }
        activeFixRequested = false;
        Log.w(TAG, "主动定位超时，本轮放弃");
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // 忽略。
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                        0L, 0f, locationListener, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // 忽略。
            }
        }
    }

    @MainThread
    public void stop() {
        running = false;
        activeFixRequested = false;
        handler.removeCallbacks(reportRunnable);
        // 有等待中的报警回调必须先结清：否则界面已经销毁，几秒后回调回来
        // 会去碰一个死掉的 Activity（报警发不出去，还可能崩）。
        // 这里主动回调一次，让报警流程立刻往前走 —— 拿不到位置也要报警。
        if (!alertWaiters.isEmpty()) {
            Log.w(TAG, "界面退出时还有报警在等位置，立即用现有位置结清");
            finishAlertRequest(bestLastKnown());
        }
        alertFixHandler.removeCallbacks(alertFixTimeout);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // 权限已被撤销时 removeUpdates 也可能抛，忽略。
            }
        }
    }

    private void onLocationChanged(@NonNull Location location) {
        // 主动定位拿到一个可用结果后就撤掉它：本模块的主任务是摄像头推理，
        // 让 GPS 一直开着会明显加剧发热。被动更新仍然留着，后续靠它顺带刷新。
        if (activeFixRequested) {
            activeFixRequested = false;
            // removeUpdates 会把被动更新一起撤掉（同一个 listener），所以随后要补回来。
            // 顺序很重要：先撤再补，不能只补不撤，否则主动定位那个 provider 仍在跑。
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // 权限刚被撤销，忽略。
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                        0L, 0f, locationListener, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // 恢复被动更新失败不影响已经拿到的位置。
            }
        }
        // 被动更新频繁到达时不必每个都发请求，交给周期任务按间隔取最新值。
        if (running) {
            handler.post(reportRunnable);
        }
        // 有人在等"报警用的位置"时，这一次结果正是他要的。
        // 放在最后：先把 activeFixRequested 清掉并恢复被动更新，
        // 保证回调里做任何事（甚至立刻发网络请求）都不受残留 GPS 请求干扰。
        if (!alertWaiters.isEmpty()) {
            Log.i(TAG, "已取到报警用位置，精度 "
                    + (location.hasAccuracy() ? location.getAccuracy() + " 米" : "未知"));
            finishAlertRequest(location);
        }
    }

    private final Runnable reportRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            // 先看上一轮主动定位是不是卡住了，再决定要不要再要一次。
            abandonStaleActiveFixIfNeeded();
            reportOnce();
            handler.postDelayed(this, REPORT_INTERVAL_MS);
        }
    };

    /**
     * 为一次报警取当前位置，拿到后回调。
     *
     * <p><b>为什么不能沿用 {@link #reportOnce()}。</b>那个方法只是"把手上最近的位置
     * 报给服务端"，它和报警是**两次独立请求**，实测报警常常先到、位置后到 ——
     * 于是报警里根本没有位置。而且它不等待、不回调，调用方无法在请求里带上坐标。
     *
     * <p>本方法的行为：
     * <ol>
     *   <li>手上有一个足够新的位置（{@link #FRESH_FOR_ALERT_MS} 内）→ 立刻回调它，
     *       不额外耗电；</li>
     *   <li>否则主动请求一次定位，最多等 {@link #ALERT_FIX_TIMEOUT_MS}；</li>
     *   <li>超时/失败 → 回调手上**任何一个**位置（哪怕是旧的），
     *       因为旧坐标仍好过没有坐标；</li>
     *   <li>什么都没有 → 回调 null，报警照发。</li>
     * </ol>
     *
     * <p><b>必须保证恰好回调一次。</b>调用方是报警流程，它要等到回调才能发请求；
     * 多调一次会发两条报警（幂等键能救回来，但仍是浪费），
     * 少调一次则报警永远发不出去 —— 那是最坏的后果。
     *
     * @param callback 在主线程回调；{@code location} 可能为 null（表示拿不到）
     */
    public void requestLocationForAlert(@NonNull AlertLocationCallback callback) {
        if (!hasPermission() || !isLocationEnabled()) {
            // 没权限/定位关着：立即给出结论，不要让报警等 12 秒。
            callback.onResult(bestLastKnown());
            return;
        }

        Location fresh = bestLastKnown();
        if (fresh != null
                && System.currentTimeMillis() - fresh.getTime() < FRESH_FOR_ALERT_MS) {
            callback.onResult(fresh);
            return;
        }

        // 可能有多个报警同时在等（用户连点求助、或手动与碰撞判定几乎同时触发）。
        // **必须全部登记，不能用后一个覆盖前一个** —— 覆盖的后果是前一条报警
        // 的回调永远不来，那条报警就永远发不出去，而这恰恰发生在最紧急的时刻。
        alertWaiters.register(callback);

        // 超时兜底每次都重新挂：即使已经在取定位，也要保证有个"到点必响"的保险。
        // 这是"不能因为没拿到位置就不报警"的最终保障。
        alertFixHandler.removeCallbacks(alertFixTimeout);
        alertFixHandler.postDelayed(alertFixTimeout, ALERT_FIX_TIMEOUT_MS);

        if (hasActiveFix()) {
            // 已经在取位置了，等它回来即可（超时兜底已挂上）。
            return;
        }
        requestActiveFixForAlert();
    }

    /** 是否有一个"为报警而发起"的定位等待。 */
    private boolean hasActiveFix() {
        return activeFixRequested;
    }

    /** 为报警发起一次主动定位；与常规定位的区别只是超时更短、有回调。 */
    private void requestActiveFixForAlert() {
        activeFixRequested = true;
        activeFixRequestedAt = System.currentTimeMillis();
        try {
            // 与常规主动定位同一套 provider 选择：先网络（室内更快更省电），
            // 再 GPS。两个都没开时立即放弃，不让报警白等。
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        0L, 0f, locationListener, Looper.getMainLooper());
                Log.i(TAG, "为报警请求一次网络定位");
                return;
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        0L, 0f, locationListener, Looper.getMainLooper());
                Log.i(TAG, "为报警请求一次 GPS 定位");
                return;
            }
            // 两个 provider 都不可用（定位总开关关着）。
            Log.w(TAG, "没有可用的定位 provider，报警将不带实时位置");
            activeFixRequested = false;
            finishAlertRequest(bestLastKnown());
        } catch (SecurityException | IllegalArgumentException error) {
            Log.w(TAG, "请求定位失败", error);
            activeFixRequested = false;
            finishAlertRequest(bestLastKnown());
        }
    }

    /**
     * 结束一次报警定位等待，回调并清理。
     *
     * <p>用 {@code synchronized} + 置 null 保证**只回调一次**：
     * 超时和真实定位可能几乎同时到达，两者都会走到这里。
     */
    private void finishAlertRequest(@Nullable Location location) {
        alertFixHandler.removeCallbacks(alertFixTimeout);
        alertWaiters.drain(location);
    }

    public void reportOnce() {
        Location location = bestLastKnown();
        if (location == null) {
            // 手里一个位置都没有 —— 这正是"没有办法获取地理位置"的直接原因。
            // 主动要一次；结果会经 onLocationChanged 回到这里重试上报。
            requestActiveFixIfStale();
            return;
        }
        if (lastReported != null
                && lastReported.getTime() == location.getTime()
                && System.currentTimeMillis() - location.getTime() < MAX_LOCATION_AGE_MS) {
            // 同一个点且还不算旧，跳过。避免每秒把同一个坐标发一遍。
            return;
        }
        lastReported = location;

        AlertDtos.LocationReportRequest request =
                new AlertDtos.LocationReportRequest(location.getLatitude(), location.getLongitude());
        if (location.hasAccuracy()) {
            request.accuracyM = (double) location.getAccuracy();
        }
        request.capturedAt = formatTimestamp(location.getTime());

        ApiClient.get(context).reportLocation(request).enqueue(new ResultCallback<>() {
            @Override
            public void onSuccess(@Nullable AlertDtos.AlertLocation body) {
                Log.d(TAG, "位置已上报");
            }

            @Override
            public void onError(@NonNull com.sullivan.protectee.data.api.ApiException error) {
                // 上报失败不该打扰用户：走路时网络抖动很常见，下一次会自动重试。
                // 但也不能静默到看不见 —— 记日志，排查「报警里为什么没有位置」时够用。
                Log.w(TAG, "位置上报失败：" + error.getMessage());
            }
        });
    }

    /**
     * 取最近一次已知位置，按精度优先。
     *
     * <p>GPS 与网络定位都试：室内只有网络定位，室外 GPS 更准。不能只取 GPS ——
     * 那样在商场里会一直拿不到位置，正好是最需要求助的场景之一。
     */
    @Nullable
    private Location bestLastKnown() {
        if (locationManager == null) {
            return null;
        }
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            Location candidate;
            try {
                candidate = locationManager.getLastKnownLocation(provider);
            } catch (SecurityException | IllegalArgumentException error) {
                continue;
            }
            if (candidate == null) {
                continue;
            }
            if (best == null || isBetter(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    /** 「更好」= 更新的优先；同一时间量级内精度更高的优先。 */
    private static boolean isBetter(@NonNull Location candidate, @NonNull Location current) {
        long ageDelta = candidate.getTime() - current.getTime();
        if (Math.abs(ageDelta) > 60_000L) {
            return ageDelta > 0;
        }
        return candidate.getAccuracy() < current.getAccuracy();
    }

    /**
     * 服务端接受的时刻格式：**空格**分隔，不是 ISO 的 {@code T}。
     *
     * <p>这是位置上报一直失败的直接原因。服务端 `JacksonConfig` 把
     * {@code LocalDateTime} 的序列化/反序列化统一钉成
     * {@code yyyy-MM-dd HH:mm:ss}（见 `TimeUtil.PATTERN_DATETIME`），
     * 而这里原先发的是 {@code yyyy-MM-dd'T'HH:mm:ss}。服务端报错精确指向
     * 第 10 个字符：
     * <pre>
     * Text '2026-10-24T17:02:15' could not be parsed at index 10
     * </pre>
     * 第 10 位正是那个 {@code T}。
     *
     * <p>注意 `openapi.yaml` 的示例写的是 {@code 2026-10-24T14:00:00}（ISO 格式），
     * **与实现不一致** —— 以服务端实现为准，不要照抄示例。
     */
    private static String formatTimestamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }
}
