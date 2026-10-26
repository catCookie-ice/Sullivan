package com.sullivan.protectee.service.call;

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

import com.sullivan.protectee.R;
import com.sullivan.protectee.SullivanApp;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.RealtimeDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.ui.common.realtime.RealtimeActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 常驻的「有人呼叫我」监听服务（受护人端）。
 *
 * <p><b>为什么受护人也需要它。</b>此前只有监护人端有来电监听；受护人想接到
 * 监护人打来的通话，必须自己打开「实时会话」页、在列表里找到那条请求、
 * 再点「接听」—— 人不在这个页面上就永远不知道有人呼叫。
 * 通话的正确体验是「对方发起、我点一次接听就接通」，前提是**得先知道有人在呼叫**。
 *
 * <p><b>实现与监护人端 {@code client-guardian} 的同名服务对齐</b>：前台服务 +
 * 5 秒轮询会话列表 + AlarmManager watchdog。差别只有一点：本端不查报警位置
 * （报警是发给监护人的，受护人自己就是位置的主人）。
 *
 * <p><b>生命周期</b>：登录后由受护人主页启动，退出登录即停。没有登录态时服务
 * 不发任何请求（必然 401）。
 */
public final class IncomingCallService extends Service {

    private static final String TAG = "IncomingCallService";

    /** 轮询间隔：与监护人端的来电监听对齐。 */
    private static final long POLL_INTERVAL_MS = 5_000L;

    private static final String CHANNEL_ID = "sullivan_incoming_call";
    private static final int NOTIFICATION_ID = 0x5A11;

    /** watchdog 的 PendingIntent requestCode（随便一个固定值，只要不冲突）。 */
    private static final int WATCHDOG_REQUEST_CODE = 0x5A12;

    /** 多久检查一次服务还活着。理由见监护人端同名服务。 */
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SessionStore session;

    /**
     * 已经弹过应用内弹窗的会话 ID。
     * 轮询每 5 秒一轮，没有它的话用户还没来得及选，「来电弹窗」就会一直反复弹。
     */
    private static final Set<String> PROMPTED = new HashSet<>();

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
            // Android 12+ 在应用处于后台时禁止启动前台服务；不能让它崩掉应用。
            Log.w(TAG, "启动来电监听服务失败", error);
        }
        scheduleWatchdog(context);
    }

    /**
     * 用 AlarmManager 每隔几分钟把服务重新拉起来。
     * 国产 ROM 会杀前台服务且不受 START_STICKY 约束；这里只负责"确保它还活着"，
     * 真正的时效由服务自己的 5 秒轮询保证。
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
        } catch (RuntimeException error) {
            Log.w(TAG, "安排 watchdog 失败", error);
        }
    }

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
        // 先取消 watchdog：否则服务刚停，15 分钟后又被拉起来。
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
        // 必须在 5 秒内进入前台态。startForeground 可能因缺权限 / 类型不被支持抛异常，
        // 接住退化为普通后台服务，而不是崩掉。
        try {
            startForegroundCompat();
        } catch (RuntimeException error) {
            Log.e(TAG, "进入前台态失败，来电监听无法在后台工作", error);
        }
        if (!session.isLoggedIn()) {
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
                    public void onSuccess(
                            @Nullable List<RealtimeDtos.SessionResponse> body) {
                        RealtimeDtos.SessionResponse call = pendingIncoming(body);
                        if (call == null) {
                            // 没有待接受的来电了（已被处理或已过期），
                            // 清掉弹窗去重记录：同一方下次再呼才能再弹。
                            PROMPTED.clear();
                            return;
                        }
                        // 应用在前台：直接在当前页面上弹「接听 / 拒绝」对话框，
                        // 像主流聊天应用一样一次点击完成处理。
                        showInAppPrompt(call);
                        // 同时更新通知（应用退到后台时靠它）。
                        postIncomingNotification(call);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 后台轮询静默失败：网络抖动时弹提示只会骚扰人。
                        Log.w(TAG, "查询来电失败：" + error.getMessage());
                    }
                });
    }

    /**
     * 从会话列表里挑出「正在呼叫我、而我还没处理」的那一条。
     *
     * <p>判定与监护人端 {@code IncomingCallFilter} 一致，三条缺一不可：
     * 状态是 REQUESTED；接收方是我；不是我发起的。
     *
     * @return 需要提醒的来电；没有则返回 {@code null}
     */
    @Nullable
    private RealtimeDtos.SessionResponse pendingIncoming(
            @Nullable List<RealtimeDtos.SessionResponse> sessions) {
        String myUid = session.principalId();
        if (sessions == null || myUid == null || myUid.isEmpty()) {
            return null;
        }
        for (RealtimeDtos.SessionResponse item : sessions) {
            if (item == null || item.id == null
                    || !"REQUESTED".equals(item.status)
                    || !myUid.equals(item.targetUid)
                    || myUid.equals(item.initiatorUid)) {
                continue;
            }
            return item;
        }
        return null;
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
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification(false, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 要求显式声明前台服务类型（manifest 里是 specialUse）。
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** 把通知升级成「有人呼叫我」，响铃并震动。 */
    private void postIncomingNotification(@NonNull RealtimeDtos.SessionResponse call) {
        Notification incoming = buildNotification(true, call.id);
        try {
            // 对同一个 id 再次调用 startForeground 就是「更新」，不会新增一条。
            startForeground(NOTIFICATION_ID, incoming);
        } catch (RuntimeException error) {
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

    /**
     * @param isIncomingCall true = 有人呼叫我（带「接听」按钮）；false = 常驻的守护通知
     * @param sessionId      来电的会话 ID；「接听」按钮需要它直达那条会话
     */
    private Notification buildNotification(boolean isIncomingCall,
                                           @Nullable String sessionId) {
        Intent open = RealtimeActivity.intentFor(this);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, open, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(isIncomingCall
                        ? R.string.incoming_call_title : R.string.incoming_call_watching))
                .setContentText(getString(isIncomingCall
                        ? R.string.incoming_call_text : R.string.incoming_call_watching_text))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(isIncomingCall
                        ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                // 常态通知不重复响；来电必须每次都响。
                .setOnlyAlertOnce(!isIncomingCall);
        if (isIncomingCall) {
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
