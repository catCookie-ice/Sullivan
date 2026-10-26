package com.sullivan.android.service.call;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;

import com.sullivan.android.R;
import com.sullivan.android.SullivanApp;
import com.sullivan.android.data.api.ApiClient;
import com.sullivan.android.data.api.ApiException;
import com.sullivan.android.data.api.ResultCallback;
import com.sullivan.android.data.api.dto.AlertDtos;
import com.sullivan.android.data.api.dto.RealtimeDtos;
import com.sullivan.android.data.session.SessionStore;
import com.sullivan.android.ui.common.realtime.RealtimeActivity;
import com.sullivan.android.ui.guardian.call.AlertLocationSummary;
import com.sullivan.android.ui.guardian.call.AlertQuery;
import com.sullivan.android.ui.guardian.call.IncomingCallFilter;
import com.sullivan.android.ui.guardian.call.PlaceNameResolver;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 常驻的「有人呼叫我」监听服务。
 *
 * <p><b>为什么必须有它。</b>受护人的紧急求助最可能发生在监护人**没打开应用**的时候
 * （人在上班、手机锁屏放在口袋里）。只在 {@code GuardianActivity} 里轮询，
 * 只能覆盖"应用正开着"这一种情形 —— 用户实测反馈的正是
 * "服务器端已经建立了会话，监护人却收不到通知"。
 *
 * <p><b>为什么用前台服务而不是 WorkManager / AlarmManager。</b>
 * 报警是**秒级**的事：5 秒延迟 + 轮询间隔就已经 10 秒，而 WorkManager 的最小周期
 * 是 15 分钟、AlarmManager 在 Doze 下会被推迟到几十分钟。唯一能稳定按秒级跑完的
 * 就是前台服务。<b>代价是状态栏必须常驻一条通知</b> —— 这是 Android 的硬性规则
 * （前台服务不给通知会被系统杀掉），对"守护"这个定位来说反而是合理的：
 * 用户随时能看到"我正在守着"。
 *
 * <p><b>不做 WebSocket 推送</b>：项目的 `RealtimeSocket` 必须绑定已知的 `sessionId`
 * 才能连，而这里恰恰要发现"我有哪些新会话"，是鸡生蛋问题。要让服务端主动推，
 * 得新开一条按账号鉴权的常驻通道，改动量远大于此处的收益。
 *
 * <p><b>生命周期</b>：登录后由主页启动，退出登录即停。没有登录态时服务不发任何请求
 * （必然 401）。
 */
public final class IncomingCallService extends Service {

    private static final String TAG = "IncomingCallService";

    /** 轮询间隔：与受护人端报警的 5 秒延迟对齐。 */
    private static final long POLL_INTERVAL_MS = 5_000L;

    private static final String CHANNEL_ID = "sullivan_incoming_call";
    private static final int NOTIFICATION_ID = 0x5A11;

    /** watchdog 的 PendingIntent requestCode（随便一个固定值，只要不冲突）。 */
    private static final int WATCHDOG_REQUEST_CODE = 0x5A12;

    /**
     * 多久检查一次服务还活着。
     *
     * <p>15 分钟是个折中：国产 ROM 杀服务往往在熄屏后几分钟到十几分钟内发生，
     * 太长会让"重新站起来"的延迟很明显；太短则频繁唤醒耗电。
     * 真正的事件时效仍然由服务自己的 5 秒轮询保证 —— 这个只是"确保它还在跑"。
     */
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SessionStore session;
    /** startForeground 是否成功。失败时退化为普通后台服务，仍尽量轮询。 */
    private boolean foregroundReady;

    /**
     * 已经弹过应用内弹窗的会话 ID。
     * 轮询每 5 秒一轮，没有它的话用户还没来得及选，「来电弹窗」就会一直反复弹。
     */
    private static final Set<String> PROMPTED = new HashSet<>();

    /**
     * 进程级的「这个来电已经在什么时刻提醒过」记录。
     *
     * <p><b>为什么是静态的。</b>有两条互相补充的提醒路径：常驻服务（应用在后台时靠它）
     * 与 {@code GuardianActivity} 的前台轮询（服务被杀时靠它保底）。
     * 两者都跑起来时会同时发现同一个来电，用户就会看到
     * 「通知栏响一次 + 页面弹窗一次」。用进程级记录去重：谁先发现谁提醒。
     *
     * <p><b>为什么记时间而不是只记 id。</b>清理动作只在服务里做，而服务有可能被杀。
     * 一旦服务没了、只剩前台轮询，记录就永远得不到清理 —— 于是**下一次真的来电**
     * 会因为 id 相同被误判成"提醒过"而彻底静默。
     * 带上时间戳后，超过 {@link #ALERT_MEMORY_MS} 就自然失效，不需要依赖谁来清理。
     */
    private static final java.util.Map<String, Long> ALERTED_SESSION_IDS =
            new java.util.HashMap<>();

    /**
     * 一条提醒记录记多久。
     *
     * <p>10 分钟：一次求助的生命周期远短于此（5 秒排期 + 呼叫 + 用户处理），
     * 而它又远大于 5 秒的轮询间隔，足够防止两条路径重复提醒。
     * 这同时是"服务挂了也不至于永久失聪"的保险。
     */
    private static final long ALERT_MEMORY_MS = 10 * 60 * 1000L;

    /** 尝试占用某个来电的提醒权。返回 true 表示"由我来提醒"。 */
    public static boolean claimAlert(@NonNull String sessionId) {
        long now = System.currentTimeMillis();
        synchronized (ALERTED_SESSION_IDS) {
            Long previous = ALERTED_SESSION_IDS.get(sessionId);
            if (previous != null && now - previous < ALERT_MEMORY_MS) {
                return false;
            }
            ALERTED_SESSION_IDS.put(sessionId, now);
            return true;
        }
    }

    /** 该来电是否已被别的路径提醒过（仍在记忆窗口内）。 */
    public static boolean isAlerted(@NonNull String sessionId) {
        long now = System.currentTimeMillis();
        synchronized (ALERTED_SESSION_IDS) {
            Long previous = ALERTED_SESSION_IDS.get(sessionId);
            return previous != null && now - previous < ALERT_MEMORY_MS;
        }
    }

    /** 清空提醒记录（确认没有待处理来电时调）。 */
    public static void clearAlerts() {
        synchronized (ALERTED_SESSION_IDS) {
            ALERTED_SESSION_IDS.clear();
        }
    }

    /** 当前是否有仍有效的提醒记录。 */
    public static boolean hasAlerts() {
        long now = System.currentTimeMillis();
        synchronized (ALERTED_SESSION_IDS) {
            return ALERTED_SESSION_IDS.values().stream()
                    .anyMatch(at -> now - at < ALERT_MEMORY_MS);
        }
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    /** 启动服务。重复调用安全（服务本身幂等，不会叠加多个轮询）。 */
    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, IncomingCallService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            // Android 12+ 在应用处于后台时禁止启动前台服务。走到这里说明
            // 调用方是在后台调的 —— 那是调用方的 bug，但不能让它崩掉应用。
            Log.w(TAG, "启动来电监听服务失败", error);
        }
        // 再挂一个定时唤醒作兜底。
        scheduleWatchdog(context);
    }

    /**
     * 用 AlarmManager 每隔几分钟把服务重新拉起来。
     *
     * <p><b>为什么前台服务还需要这个。</b>国产 ROM（小米、华为、OPPO、vivo）
     * 的后台管理会主动杀掉前台服务，而且不受 `START_STICKY` 的约束 ——
     * 服务被杀后不会再回来。用户反馈的"只有在首页才会收到会话通知"
     * 正是这个现象：Activity 里的 Handler 还活着，常驻服务已经没了。
     *
     * <p>用 {@code setAndAllowWhileIdle} 而不是精确闹钟：这里是"确保服务还活着"，
     * 不需要精确到秒（真正的时效靠服务自己的 5 秒轮询），
     * 而精确闹钟在 Android 12+ 需要额外权限、也会明显耗电。
     */
    private static void scheduleWatchdog(@NonNull Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(context, IncomingCallService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getService(context, WATCHDOG_REQUEST_CODE, intent, flags);
        long triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
        try {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            Log.i(TAG, "已安排来电监听 watchdog");
        } catch (RuntimeException error) {
            Log.w(TAG, "安排 watchdog 失败", error);
        }
    }

    /** 取消兜底唤醒。停止服务时调，否则停了又会被拉起来。 */
    private static void cancelWatchdog(@NonNull Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        Intent intent = new Intent(context, IncomingCallService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getService(context, WATCHDOG_REQUEST_CODE, intent, flags);
        alarmManager.cancel(pendingIntent);
    }

    /** 停止服务。退出登录时调。 */
    public static void stop(@NonNull Context context) {
        // 先取消 watchdog：否则服务刚停，15 分钟后又被拉起来 ——
        // 用户已经退出登录了，却还在后台跑，既耗电又让人困惑。
        cancelWatchdog(context);
        context.stopService(new Intent(context, IncomingCallService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SessionStore(this);
        createChannel();
        Log.i(TAG, "来电监听服务已创建");
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // 必须在 5 秒内进入前台态，否则系统会抛 ForegroundServiceDidNotStartInTimeException。
        //
        // **startForeground 会抛异常，必须接住。** 缺权限、类型不被支持（部分 ROM
        // 不认 specialUse）、或用户在系统设置里禁了通知，都会让它抛
        // SecurityException / IllegalArgumentException。
        // 不接的后果是服务直接崩掉 —— 表现就是"只有在首页才能收到通知"，
        // 因为前台轮询（Activity 里的 Handler）还活着，后台的这条死了。
        try {
            startForegroundCompat();
        } catch (RuntimeException error) {
            Log.e(TAG, "进入前台态失败，来电监听无法在后台工作", error);
            // 退化为普通后台服务：没有前台态时系统随时可能杀它，
            // 但至少在被杀之前还能轮询，好过立刻什么都不做。
            foregroundReady = false;
        }
        if (!session.isLoggedIn()) {
            // 没有登录态，轮询必然 401，直接停。
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        // 被系统杀掉后重启：守护功能应当自己恢复，而不是等用户再打开一次。
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    private void pollOnce() {
        if (!session.isLoggedIn()) {
            stopSelf();
            return;
        }
        ApiClient.get(this).listSessions()
                .enqueue(new ResultCallback<List<RealtimeDtos.SessionResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<RealtimeDtos.SessionResponse> body) {
                        List<RealtimeDtos.SessionResponse> pending =
                                IncomingCallFilter.pending(body, session.principalId());
                        if (pending.isEmpty()) {
                            // 没有待接受的来电了（已被处理或已过期）。
                            // 清掉弹窗去重记录，同一方下次再呼才能再弹。
                            PROMPTED.clear();
                            // 如果此前报过警，把通知恢复成"正在守着"——
                            // 否则状态栏会一直显示"有人正在呼叫你"，那是假消息。
                            if (hasAlerts()) {
                                clearAlerts();
                                safeRestoreWatchingNotification();
                            }
                            return;
                        }
                        RealtimeDtos.SessionResponse call = pending.get(0);
                        if (!claimAlert(call.id)) {
                            // 前台轮询已经提醒过了，这里不重复。
                            return;
                        }
                        // 应用在前台：直接在当前页面上弹「接听 / 拒绝」对话框，
                        // 像主流聊天应用一样一次点击完成处理。
                        showInAppPrompt(call);
                        // 位置在报警上、不在会话上，所以要多查一次。
                        // 拿不到就照常提示来电（位置是增强，呼叫才是主功能）。
                        loadLocationThenNotify(call.id);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 后台轮询静默失败：网络抖动时弹提示只会骚扰人。
                        // 401 由 ApiClient 的统一出口清会话，下一轮 pollOnce 会发现未登录并自停。
                        Log.w(TAG, "查询来电失败：" + error.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------------
    // 应用内来电弹窗
    // ------------------------------------------------------------------

    /**
     * 在当前前台页面上弹「接听 / 拒绝」对话框。
     *
     * <p>接听 = 带着会话 ID 打开实时会话页，页面自动接受并接通；
     * 拒绝 = 直接调服务端拒绝接口。两条路都不需要用户再进列表找那一行。
     *
     * <p>应用在后台时弹不了对话框（Service 没有 UI 上下文），
     * 这种情况交给带「接听」按钮的通知。
     */
    private void showInAppPrompt(@NonNull RealtimeDtos.SessionResponse call) {
        Activity current = SullivanApp.currentActivity();
        if (current == null || current.isFinishing() || current.isDestroyed()) {
            return;
        }
        if (!PROMPTED.add(call.id)) {
            // 这条来电已经弹过窗了，用户还没选；不要 5 秒弹一次。
            return;
        }

        String who = !TextUtils.isEmpty(call.initiatorName) ? call.initiatorName
                : !TextUtils.isEmpty(call.initiatorLoginName) ? call.initiatorLoginName
                : getString(R.string.session_contact_unnamed);

        new AlertDialog.Builder(current)
                .setTitle(R.string.incoming_call_title)
                .setMessage(getString(R.string.incoming_call_dialog_message, who,
                        typeLabelOf(call.sessionType)))
                .setCancelable(false)
                .setPositiveButton(R.string.incoming_call_action_accept,
                        (dialog, which) -> {
                            PROMPTED.remove(call.id);
                            current.startActivity(
                                    RealtimeActivity.intentFor(current, call.id));
                        })
                .setNegativeButton(R.string.session_action_reject,
                        (dialog, which) -> {
                            PROMPTED.remove(call.id);
                            ApiClient.get(current).rejectSession(call.id)
                                    .enqueue(new ResultCallback<RealtimeDtos.SessionResponse>() {
                                        @Override
                                        public void onSuccess(
                                                @Nullable RealtimeDtos.SessionResponse body) {
                                            // 拒绝成功，无需提示。
                                        }

                                        @Override
                                        public void onError(@NonNull ApiException error) {
                                            // 静默：下一轮轮询看到它还是 REQUESTED 时会再弹。
                                            PROMPTED.remove(call.id);
                                        }
                                    });
                        })
                .show();
    }

    @NonNull
    private String typeLabelOf(@Nullable String sessionType) {
        if (sessionType == null) {
            return getString(R.string.session_type_basic);
        }
        return switch (sessionType.toUpperCase()) {
            case "VOICE" -> getString(R.string.session_type_voice_short);
            case "VIDEO" -> getString(R.string.session_type_video_short);
            case "CAMERA" -> getString(R.string.session_type_camera_short);
            case "EMERGENCY" -> getString(R.string.session_type_emergency_short);
            default -> getString(R.string.session_type_basic);
        };
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.incoming_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.incoming_call_channel_description));
        // 横幅 + 声音 + 震动：受护人可能正处在危险里，这条通知不能安静地躺在通知栏。
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    /** 把通知恢复成"正在守着"。失败只记日志，不影响轮询。 */
    private void safeRestoreWatchingNotification() {
        try {
            startForegroundCompat();
        } catch (RuntimeException error) {
            Log.w(TAG, "恢复常驻通知失败", error);
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 要求显式声明前台服务类型。
            // 用 SPECIAL_USE 而不是 DATA_SYNC：后者有每天 6 小时上限，
            // 超时系统会直接杀掉服务 —— "全天候守着"的功能不能接受这个。
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            // Android 13 及以下不要求（也不校验）类型，直接给通知即可。
            //
            // **不能在这里用 DATA_SYNC**：manifest 里声明的是 specialUse，
            // 而 FOREGROUND_SERVICE_DATA_SYNC 权限已经移除了（不再需要），
            // 在这种分支上指定 dataSync 会因"声明与实现不一致"被系统拒绝。
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /**
     * 查这条会话对应的报警，把位置一起放进通知。
     *
     * <p>会话响应里没有 {@code alertId}，关联是
     * {@code alert_delivery.session_id → realtime_session.id}。
     * 客户端靠「投递给我的报警」列表里的 {@code deliveries} 反查。
     *
     * <p>拿不到报警时照样发通知 —— 位置是增强，呼叫才是主功能。
     */
    private void loadLocationThenNotify(@NonNull String sessionId) {
        ApiClient.get(this).listAlerts()
                .enqueue(new ResultCallback<List<AlertDtos.AlertResponse>>() {
                    @Override
                    public void onSuccess(@Nullable List<AlertDtos.AlertResponse> body) {
                        resolveAndPost(sessionId,
                                AlertQuery.locationForSession(body, sessionId));
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 查不到位置不影响呼叫提醒。
                        resolveAndPost(sessionId, null);
                    }
                });
    }

    /**
     * 先把坐标解析成地名，再发通知。
     *
     * <p>监护人这一刻要判断的是「往哪儿去」：地名是主要信息，
     * 坐标是长按复制的兜底。解析走高德逆地理编码，失败不阻塞 ——
     * 通知照发，只是那一行显示坐标。
     */
    private void resolveAndPost(@NonNull String sessionId,
                                @Nullable AlertDtos.AlertLocation location) {
        if (location == null) {
            postNotification(sessionId, null, null);
            return;
        }
        // 服务端在报警创建时限内解析好就直接用，不再发起本端请求；
        // 没有服务端地名（解析超时 pending / 旧报警 / 服务端没配 Key）时，
        // 服务端已把报警先行转发，这里用本端高德解析兜底补上地名。
        String serverAddress = AlertLocationSummary.serverAddress(location);
        if (serverAddress != null) {
            postNotification(sessionId, location, serverAddress);
            return;
        }
        PlaceNameResolver.resolve(this, location.latitude, location.longitude,
                name -> postNotification(sessionId, location, name));
    }


    /**
     * 把常驻通知升级成"有人呼叫我"，响铃并震动。
     *
     * <p><b>只更新一次，不能既 notify 又 startForeground。</b>两者用的是同一个
     * {@link #NOTIFICATION_ID}：先 {@code notify(来电内容)} 再
     * {@code startForegroundCompat(守着内容)}，后写的会把前者立刻覆盖掉 ——
     * 表现就是"通知栏闪一下又变回『正在守着』"，用户反而错过。
     * 所以这里只做一次，且用的是来电内容。
     */
    private void postNotification(@Nullable String sessionId,
                                  @Nullable AlertDtos.AlertLocation location) {
        postNotification(sessionId, location, null);
    }

    private void postNotification(@Nullable String sessionId,
                                  @Nullable AlertDtos.AlertLocation location,
                                  @Nullable String placeName) {
        Notification incoming = buildNotification(true, sessionId, location, placeName);
        // 前台服务的通知内容也要跟着改，否则状态栏两条说法不一致。
        // startForeground 对同一个 id 再次调用就是"更新"，不会新增一条。
        try {
            startForeground(NOTIFICATION_ID, incoming);
        } catch (RuntimeException error) {
            // 进不了前台态（缺权限/类型不支持）时，退而用普通通知 ——
            // 至少用户能在通知栏看到有人呼叫他。
            Log.e(TAG, "更新前台通知失败，退化为普通通知", error);
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                try {
                    manager.notify(NOTIFICATION_ID, incoming);
                } catch (RuntimeException notifyError) {
                    Log.e(TAG, "连普通通知也发不出去", notifyError);
                }
            }
        }
    }

    private Notification buildNotification(boolean isIncomingCall) {
        return buildNotification(isIncomingCall, null, null, null);
    }

    /**
     * @param sessionId 来电的会话 ID；「接听」按钮需要它直达那条会话，null 表示没有
     * @param location  受护人位置；为 null 表示没有（或还没查到）
     * @param placeName 受护人坐标解析出的地名；null 表示解析失败（回落到坐标展示）
     */
    private Notification buildNotification(boolean isIncomingCall,
                                           @Nullable String sessionId,
                                           @Nullable AlertDtos.AlertLocation location,
                                           @Nullable String placeName) {
        Intent open = RealtimeActivity.intentFor(this);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, open, flags);

        String text = getString(isIncomingCall
                ? R.string.incoming_call_text : R.string.incoming_call_watching_text);

        if (isIncomingCall) {
            // 位置直接写进通知正文。监护人解锁手机时一眼就能看到"往哪儿去"，
            // 不需要点进应用再找。BigTextStyle 让长内容（地名 + 坐标 + 精度）
            // 展开后能完整显示，而不是被截断成"受护人位置（刚刚）…"。
            String detail = AlertLocationSummary.detail(location);
            if (detail != null) {
                String headline = AlertLocationSummary.headline(location);
                // 地名优先：地名是"往哪儿去"的主要信息；坐标整段放后面，长按可复制。
                String body = detail.replace("\n", " · ");
                if (placeName != null) {
                    text = (headline == null ? "" : headline + "\n")
                            + placeName + "\n" + body;
                } else {
                    text = (headline == null ? "" : headline + "\n") + body;
                }
            } else {
                text = text + " · " + getString(R.string.guardian_incoming_call_no_location);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(isIncomingCall
                        ? R.string.incoming_call_title : R.string.incoming_call_watching))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(isIncomingCall
                        ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                // 只有"正在守着"这种常态通知才不重复响；来电必须每次都响，
                // 否则第二次求助就变成静默的了 —— 那正是这个功能存在的意义。
                .setOnlyAlertOnce(!isIncomingCall);
        if (isIncomingCall) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
            // 紧急来电全屏展示：锁屏或后台时直接弹出通话页，而不是一条
            // 一闪而过的横幅 —— 报警求助等不起用户「恰好看到通知」。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && sessionId != null) {
                Intent fullScreen = RealtimeActivity.intentFor(this, sessionId);
                PendingIntent fullScreenIntent = PendingIntent.getActivity(
                        this, 2, fullScreen, flags);
                builder.setFullScreenIntent(fullScreenIntent, true);
            }
            if (sessionId != null) {
                // 通知上直接给「接听」：点一下就接受这条会话并进入通话，
                // 不用先打开应用、在列表里找到那一行、再点一次接受。
                Intent accept = RealtimeActivity.intentFor(this, sessionId);
                PendingIntent acceptIntent = PendingIntent.getActivity(
                        this, 1, accept, flags);
                builder.addAction(0, getString(R.string.incoming_call_action_accept),
                        acceptIntent);
            }
        }
        return builder.build();
    }
}
