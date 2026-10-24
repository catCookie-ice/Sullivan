package com.sullivan.protectee.ui.protectee.home;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.sullivan.protectee.R;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.AlertDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.ui.protectee.binding.BindCodeActivity;
import com.sullivan.protectee.vision.assist.AnnounceRate;
import com.sullivan.protectee.vision.assist.AssistSettings;
import com.sullivan.protectee.vision.assist.ObstacleAdvisor;
import com.sullivan.protectee.vision.assist.PromptAnnouncer;
import com.sullivan.protectee.vision.assist.SceneAnalyzer;
import com.sullivan.protectee.service.alert.AlertClient;
import com.sullivan.protectee.service.alert.CollisionAlertGate;
import com.sullivan.protectee.service.location.LocationReporter;
import com.sullivan.protectee.vision.depth.DepthEstimator;
import com.sullivan.protectee.vision.depth.DepthMap;
import com.sullivan.protectee.vision.detect.Detection;
import com.sullivan.protectee.vision.detect.ModelStore;
import com.sullivan.protectee.vision.detect.RgbaFrame;
import com.sullivan.protectee.vision.detect.Letterbox;
import com.sullivan.protectee.vision.detect.Yolo26Detector;
import com.sullivan.protectee.vision.motion.ShakeSensor;
import com.sullivan.protectee.vision.track.ObjectTracker;
import com.sullivan.protectee.vision.view.OverlayView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摄像头障碍检测主界面。
 *
 * <p>数据流：CameraX 逐帧回调 → 复制出一份 RGBA 数据并立刻归还 ImageProxy →
 * 后台线程跑 YOLO 推理 → {@link ObstacleAdvisor} 生成中文提示 → 叠加层与语音/振动
 * 输出。推理期间到达的帧会被直接丢弃，保证画面跟手。
 */
public class ProtecteeActivity extends AppCompatActivity {

    private static final String TAG = "ProtecteeActivity";

    /**
     * 置信度阈值，低于此值的检测框直接丢弃。
     *
     * <p>作用在 **sigmoid 之后**的概率上（YOLO26 的 end2end 输出是原始 logits）。
     * 0.35 比模型内部的 0.25 略高：end2end 已经按低阈值筛过一遍，这里再抬一点
     * 可以把"有框但很虚"的那些挡在播报之外 —— 受护人听到的每一条提示都会有代价。
     */
    private static final float CONFIDENCE_THRESHOLD = 0.35f;

    /**
     * 深度模型每隔几帧跑一次。
     *
     * <p>实测（桌面 CPU，onnxruntime Python）：检测 640 是 43 ms、深度 768 是 85 ms，
     * 合计 128 ms/帧 —— 7.8 FPS 的上限，手机只会更慢。而深度输出的是一张**变化很慢**
     * 的距离场：一帧之内人和墙不会移动多少，隔一两帧复用完全够用。
     *
     * <p>所以把深度放在 2 帧跑一次（约 15 FPS 的检测 + 7.5 Hz 的米数刷新）。
     * 这是**帧率与米数新鲜度的折中**，不是性能优化 —— 若真机仍然卡，
     * 第一个该动的就是这个数（调到 3 或 4），而不是把深度整个关掉。
     */
    private static final int DEPTH_INTERVAL_FRAMES = 2;

    /**
     * 是否把每帧的场景快照写进 logcat。
     *
     * <p>调阈值时打开：拿真机走一段路，把日志倒出来当回放数据，就不用为了改一个门限反复
     * 出门。默认关闭——每秒一行 JSON 会在 logcat 里淹掉别的信息。
     */
    private static final boolean LOG_SCENE_JSON = false;
    /** 场景 JSON 的打印间隔。 */
    private static final long SCENE_LOG_INTERVAL_MS = 1000L;

    /**
     * 用户碰过屏幕之后多久内不做晃动判定。
     *
     * <p>掏出手机、按下按钮、塞回兜里，这一串动作的角速度完全够得上"剧烈且无规则"。
     * 2 秒覆盖得住"点一下→手晃一下"这一段，又短到不会把真的摔倒漏掉。
     */
    private static final long TOUCH_SUPPRESS_MS = 2000L;

    /** 没有陀螺仪时，隔这么久再出声念一遍（TTS 引擎启动时还没绑定好）。 */
    private static final long NO_GYROSCOPE_NOTICE_DELAY_MS = 4000L;

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView promptText;
    private TextView modelStatusText;
    private TextView perfText;
    private View permissionHint;
    private View alarmPanel;
    /** 报警面板上的状态行（「正在发出求助…」「已通知监护人」）。 */
    private TextView alarmStatusText;
    private Button rateButton;

    /** 相机回调线程：只做数据拷贝，不做重活。 */
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    /** 推理线程：模型加载与推理都在这里串行执行，避免模型被并发使用或提前关闭。 */
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();

    /** 是否已有一次推理在跑；为 true 时新帧直接丢弃。 */
    private final AtomicBoolean detecting = new AtomicBoolean(false);

    /**
     * 感知 + 决策管线。**只在推理线程上访问**：内部保存着跨帧的轨迹与滞回状态，
     * 靠"单线程串行"代替加锁。
     */
    private final SceneAnalyzer sceneAnalyzer = new SceneAnalyzer();

    private PromptAnnouncer announcer;
    private AssistSettings settings;
    @Nullable
    private ShakeSensor shakeSensor;

    /** 位置周期上报；只在界面存活期间跑。 */
    @Nullable
    private LocationReporter locationReporter;
    @Nullable
    private AlertClient alertClient;
    /** 碰撞→报警的边沿判定（见 {@link CollisionAlertGate}）。 */
    private final CollisionAlertGate collisionGate = new CollisionAlertGate();

    /**
     * 当前这条仍未结束的报警 ID。
     *
     * <p>存下来是为了让「我没事」能把它 resolve 掉：不结束的话，服务端那条报警
     * 会一直挂在列表里，而监护人那边看到的永远是「还没处理」。
     */
    @Nullable
    private String activeAlertId;
    /** 本机是否已弹过「尚未绑定监护人」的提示，避免每帧重复说。 */
    private boolean noGuardianNoticeShown;
    /** 权限请求成功后要顺带申请定位（不阻塞相机启动）。 */
    private boolean pendingLocationPermission;

    /** 当前的播报频率档位；界面与两个下游都要跟着它走，所以只留一份在界面里。 */
    @NonNull
    private AnnounceRate rate = AnnounceRate.STANDARD;

    /** 上一次打印场景 JSON 的帧时刻；只在推理线程访问。 */
    private long lastSceneLogAt;

    /** 「没有陀螺仪」只提醒一次（见 {@link #reportMissingGyroscope}）。 */
    private boolean gyroscopeNoticeShown;

    @Nullable
    private volatile Yolo26Detector detector;
    /**
     * 深度模型；{@code null} 表示没装（或加载失败），此时距离退回几何启发式。
     *
     * <p>只有检测模型是"必须"的，深度是可选增强：20 MB 的额外体积与每帧约
     * 一次额外推理的代价，不该成为装不上 App 的理由。
     */
    @Nullable
    private volatile DepthEstimator depthEstimator;

    /**
     * 上一次深度推理的结果与它用的几何，用于在隔帧期间复用。
     *
     * <p>只在推理线程读写，不需要同步。{@code null} 表示还没有可复用的深度图。
     */
    @Nullable
    private DepthMap lastDepth;
    @Nullable
    private Letterbox lastDepthLetterbox;
    /** 距上次深度推理过了多少帧。只在推理线程读写。 */
    private int framesSinceDepth;

    @Nullable
    private ImageAnalysis imageAnalysis;
    @Nullable
    private ProcessCameraProvider cameraProvider;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String[]> modelPickerLauncher;
    private ActivityResultLauncher<String[]> depthModelPickerLauncher;
    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    /** 用于计算推理帧率的平滑值。 */
    private long lastDetectionAt;
    private float smoothedFps;

    private final ImageAnalysis.Analyzer analyzer = this::onFrame;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_protectee);

        previewView = findViewById(R.id.preview_view);
        overlayView = findViewById(R.id.overlay_view);
        promptText = findViewById(R.id.prompt_text);
        modelStatusText = findViewById(R.id.model_status_text);
        perfText = findViewById(R.id.perf_text);
        permissionHint = findViewById(R.id.permission_hint);
        alarmPanel = findViewById(R.id.alarm_panel);
        alarmStatusText = findViewById(R.id.alarm_status_text);
        bindAccountLabel();
        Button pickModelButton = findViewById(R.id.pick_model_button);
        rateButton = findViewById(R.id.rate_button);
        SwitchMaterial speechSwitch = findViewById(R.id.speech_switch);
        SwitchMaterial hapticsSwitch = findViewById(R.id.haptics_switch);

        // 与叠加层换算显示区域所依据的规则保持一致。
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        settings = new AssistSettings(this);
        announcer = new PromptAnnouncer(this);
        // 先把存档里的设置灌进去，再启动任何会出声的东西：否则用户会先听到一句用错误档位
        // 播出来的提示。
        announcer.setSpeechEnabled(settings.isSpeechEnabled());
        announcer.setHapticsEnabled(settings.isHapticsEnabled());
        applyRate(settings.rate());

        modelPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        importModel(uri);
                    }
                });
        depthModelPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        importDepthModel(uri);
                    }
                });
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        permissionHint.setVisibility(View.GONE);
                        startCamera();
                    } else {
                        showPermissionHint();
                    }
                });
        locationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    LocationReporter reporter = locationReporter;
                    if (reporter != null && reporter.hasPermission()) {
                        reporter.start();
                    }
                    // 拒绝时不提示、不追问：它会持续影响用户的操作流，
                    // 而位置只是增强。用户想开的时候系统设置里能开。
                });
        speechSwitch.setChecked(announcer.isSpeechEnabled());
        speechSwitch.setOnCheckedChangeListener((view, checked) -> {
            announcer.setSpeechEnabled(checked);
            settings.setSpeechEnabled(checked);
        });
        hapticsSwitch.setChecked(announcer.isHapticsEnabled());
        hapticsSwitch.setOnCheckedChangeListener((view, checked) -> {
            announcer.setHapticsEnabled(checked);
            settings.setHapticsEnabled(checked);
        });
        rateButton.setOnClickListener(view -> {
            AnnounceRate next = rate.next();
            applyRate(next);
            // 看不见屏幕的人只能靠耳朵确认按对了没有。走 speakNow：频率档位管的是日常
            // 提示的间隔，用户正在改的就是它，这条确认音不该被它自己压住。
            announcer.speakNow(getString(R.string.action_rate, getString(labelOf(next))));
        });
        findViewById(R.id.alarm_dismiss_button).setOnClickListener(
                view -> dismissAlarm());
        findViewById(R.id.sos_button).setOnClickListener(view -> confirmManualSos());
        SwitchMaterial autoSessionSwitch = findViewById(R.id.auto_session_switch);
        autoSessionSwitch.setChecked(settings.isAlertAutoSessionEnabled());
        autoSessionSwitch.setOnCheckedChangeListener((view, checked) -> {
            // 本地先落，界面立刻跟手；服务端同步失败也不回滚 —— 用户的选择是明确的，
            // 下一次同步会把它补上去。回滚反而会让开关"自己跳回去"，更让人困惑。
            settings.setAlertAutoSessionEnabled(checked);
            pushAutoSessionSetting(checked);
        });
        pickModelButton.setOnClickListener(
                view -> modelPickerLauncher.launch(new String[]{"*/*"}));
        // 深度模型单独一个入口：它与检测模型是两个文件，替换其中一个不该牵动另一个。
        findViewById(R.id.pick_depth_model_button).setOnClickListener(
                view -> depthModelPickerLauncher.launch(new String[]{"*/*"}));
        // 绑定码是网络功能，而本页（障碍检测）必须保持离线可用：
        // 所以这里只加一个入口，不在这页做任何登录前置。
        findViewById(R.id.my_bind_code_button).setOnClickListener(
                view -> startActivity(new Intent(this, BindCodeActivity.class)));
        findViewById(R.id.grant_permission_button).setOnClickListener(
                view -> permissionLauncher.launch(Manifest.permission.CAMERA));

        shakeSensor = new ShakeSensor(this, nowMs -> onShakeDetected());

        locationReporter = new LocationReporter(this);
        alertClient = new AlertClient(this, locationReporter);
        // 定位权限单独申请，且**不阻塞**相机启动：障碍检测是主功能，
        // 没有定位也必须能用。用户拒绝就永远不再问（Android 也会自己不再弹），
        // 报警时只少了坐标，不影响通知监护人。
        if (locationReporter.hasPermission()) {
            locationReporter.start();
        } else if (!hasAskedLocationPermission()) {
            markAskedLocationPermission();
            pendingLocationPermission = true;
        }

        loadModel();

        if (hasCameraPermission()) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 报警**不**随 onPause 停止：用户可能把手机掉在地上，屏幕熄了、应用退到后台，
        // 报警必须继续。这里只管传感器。
        ShakeSensor sensor = shakeSensor;
        if (sensor != null && !sensor.start()) {
            reportMissingGyroscope();
        }

        LocationReporter reporter = locationReporter;
        if (reporter != null && reporter.hasPermission()) {
            reporter.start();
        } else if (pendingLocationPermission) {
            // 相机权限那次申请已经走完，现在再问定位。分开问是刻意的：
            // 一次弹两个权限，用户更容易一起点拒绝。
            pendingLocationPermission = false;
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    @Override
    protected void onPause() {
        ShakeSensor sensor = shakeSensor;
        if (sensor != null) {
            sensor.stop();
        }
        // 位置上报只在界面可见时跑：退出即停，这是隐私与耗电两条线共同的要求。
        LocationReporter reporter = locationReporter;
        if (reporter != null) {
            reporter.stop();
        }
        super.onPause();
    }

    /** 是否已经问过定位权限（不再重复打扰）。 */
    private boolean hasAskedLocationPermission() {
        return getPreferences(MODE_PRIVATE).getBoolean(PREF_ASKED_LOCATION, false);
    }

    private void markAskedLocationPermission() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_ASKED_LOCATION, true).apply();
    }

    private static final String PREF_ASKED_LOCATION = "asked_location_permission";

    /**
     * 用户碰过屏幕时，把这一小段时间从晃动判定里摘出去。
     *
     * <p>钩在 {@code dispatchTouchEvent} 而不是某个按钮的点击上：任何一次触摸都算，
     * 包括按在空白处、划一下画面、点「我没事」——用户"醒着并且在用手"这件事本身就是
     * 不报警的理由。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        ShakeSensor sensor = shakeSensor;
        if (sensor != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            sensor.suppressUntil(SystemClock.elapsedRealtime(), TOUCH_SUPPRESS_MS);
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        analysisExecutor.shutdown();
        closeDetector();

        if (shakeSensor != null) {
            shakeSensor.stop();
            shakeSensor = null;
        }
        if (locationReporter != null) {
            locationReporter.stop();
            locationReporter = null;
        }
        alertClient = null;
        if (announcer != null) {
            announcer.shutdown();
            announcer = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    /**
     * 换播报频率档位。**唯一的调用点**：界面、两个下游、存档都在这里一起改。
     *
     * <p>分成两处写迟早会漂移——用户改了档位，界面显示"少"但播报还是"标准"的频率，
     * 这种不一致没有任何测试能替你发现，只能靠只有一个入口。
     */
    private void applyRate(@NonNull AnnounceRate next) {
        rate = next;
        sceneAnalyzer.setRate(next);
        announcer.setRate(next);
        settings.setRate(next);
        rateButton.setText(getString(R.string.action_rate, getString(labelOf(next))));
    }

    private static int labelOf(@NonNull AnnounceRate rate) {
        switch (rate) {
            case QUIET:
                return R.string.rate_quiet;
            case CHATTY:
                return R.string.rate_chatty;
            default:
                return R.string.rate_standard;
        }
    }

    // ------------------------------------------------------------------
    // 剧烈晃动报警
    // ------------------------------------------------------------------

    /** 传感器线程回调，切回主线程再碰界面与语音。 */
    private void onShakeDetected() {
        runOnUiThread(() -> {
            PromptAnnouncer current = announcer;
            if (current == null) {
                // Activity 已经销毁。
                return;
            }
            current.startAlarm();
            alarmPanel.setVisibility(View.VISIBLE);
            alarmStatusText.setText(R.string.alarm_sending);
            // 晃动是「可能摔了」的信号，与用户自己按求助是同一个后果：立刻发出去。
            // 不等用户确认 —— 他可能已经动不了了，而「我没事」按钮还在那里可以撤销。
            sendAlert(AlertClient.TYPE_MANUAL, getString(R.string.alarm_shake));
            // 让自动触发也进入冷却，避免同一段晃动里视觉判定又触发一次。
            collisionGate.suppressUntil(SystemClock.elapsedRealtime());
        });
    }

    private void dismissAlarm() {
        alarmPanel.setVisibility(View.GONE);
        PromptAnnouncer current = announcer;
        if (current != null) {
            current.dismissAlarm();
        }
        // 「我没事」= 这件事结束了。必须同步给服务端，否则监护人那边一直显示未处理。
        String alertId = activeAlertId;
        activeAlertId = null;
        AlertClient client = alertClient;
        if (alertId != null && client != null) {
            client.resolve(alertId, null);
        }
    }

    // ------------------------------------------------------------------
    // 当前账号
    // ------------------------------------------------------------------

    /**
     * 在顶部显示"当前账号"。
     *
     * <p><b>为什么这件事需要专门处理。</b>受护人端的登录方式是「监护人扫码」，
     * 走的是 {@code QrLoginActivity} 的兑换流程：兑换响应里只有 {@code principalId}，
     * 没有名字。如果不在扫码那边把 {@code protecteeName} 带下来，这里能显示的就只有
     * 一串 26 位 ID —— 那等于没显示，用户会怀疑自己到底登没登进去。
     *
     * <p>三级兜底，逐级降级但都不留空白：
     * 登录名 → 显示名（扫码时带下来的）→ "已登录"。
     */
    private void bindAccountLabel() {
        android.widget.TextView label = findViewById(R.id.account_text);
        SessionStore session = new SessionStore(this);
        String name = session.accountLabel();
        if (name == null || name.trim().isEmpty()) {
            label.setText(R.string.account_unknown);
            return;
        }
        label.setText(getString(R.string.account_current, name));
    }

    // ------------------------------------------------------------------
    // 紧急求助（手动 + 碰撞自动）
    // ------------------------------------------------------------------
    /**
     * 手动求助：先确认一次，再发。
     *
     * <p>为什么要确认：这是屏幕底部一整个大按钮，走路时手掌随时可能压到。
     * 一次误触就把监护人全部叫起来，代价太高。确认框只有一个「发出求助」和一个「取消」，
     * 且确认后立刻用语音反馈 —— 看不见屏幕的人必须能听出「按下了」与「发出去了」的区别。
     */
    private void confirmManualSos() {
        PromptAnnouncer current = announcer;
        if (current != null) {
            // 先出声：对话框是无障碍节点，但盲人用户更依赖语音立刻确认自己按对了。
            current.speakNow(getString(R.string.action_sos));
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.sos_confirm)
                .setPositiveButton(R.string.sos_confirm_yes,
                        (dialog, which) -> sendAlert(AlertClient.TYPE_MANUAL, null))
                .setNegativeButton(R.string.sos_confirm_no, null)
                .show();
    }

    /**
     * 真正发出报警。
     *
     * <p>{@code alertType} 用 {@code COLLISION} 时，地址栏不会跳出来抢注意力：
     * 这条是替他发的，用户可能还不知道发生了什么，界面只补一句"已替你通知"。
     */
    private void sendAlert(@NonNull String alertType, @Nullable String note) {
        AlertClient client = alertClient;
        if (client == null) {
            return;
        }
        alarmPanel.setVisibility(View.VISIBLE);
        alarmStatusText.setText(R.string.alarm_sending);

        long now = SystemClock.elapsedRealtime();
        // 先假定自己已经报过了：即便请求在飞的过程中视觉判定再次命中，也不会重复发。
        collisionGate.suppressUntil(now);

        // hasActiveAlert：手上还挂着一条未结束的报警才允许复用幂等键。
        // 否则（比如用户先误触、点了「我没事」）必须能开出**新的**一条 ——
        // 详见 AlertKeys.of(..., hasActiveAlert) 的注释。
        boolean hasActiveAlert = activeAlertId != null;
        client.send(alertType, note, System.currentTimeMillis(), hasActiveAlert,
                new AlertClient.Callback() {
            @Override
            public void onCreated(@NonNull AlertDtos.AlertResponse alert) {
                activeAlertId = alert.id;
                int statusRes = statusResourceOf(alert);
                alarmStatusText.setText(statusRes);
                PromptAnnouncer current = announcer;
                if (current != null) {
                    String spoken = getString(statusRes);
                    // 没有位置是个需要让用户知道的事实：他自己也许能补一句"我在 XX"。
                    if (alert.location == null) {
                        spoken = spoken + "。" + getString(R.string.alarm_location_missing);
                    }
                    current.speakNow(spoken);
                }
                if (AlertClient.TYPE_COLLISION.equals(alertType)) {
                    Toast.makeText(ProtecteeActivity.this, R.string.alarm_collision_sent,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailed(@NonNull ApiException error) {
                // 401 要单独说清楚：报警**没有发出去**，而不是"发失败了"。
                // 受护人可能正因为这个提示而以为监护人已经被通知了 —— 这是安全相关的差别。
                if (error.isUnauthorized()) {
                    alarmStatusText.setText(R.string.alarm_session_expired);
                    PromptAnnouncer expiredAnnouncer = announcer;
                    if (expiredAnnouncer != null) {
                        expiredAnnouncer.speakNow(getString(R.string.alarm_session_expired));
                    }
                    return;
                }
                String message = error.getMessage();
                alarmStatusText.setText(getString(R.string.alarm_send_failed, message));
                PromptAnnouncer current = announcer;
                if (current != null) {
                    current.speakNow(getString(R.string.alarm_send_failed, message));
                }
            }
        });
    }

    /**
     * 把报警结果映射到一句人话。
     *
     * <p>刻意区分「已排期」与「用户关掉了自动呼叫」：两者监护人都会收到通知，
     * 但**只有前者会真的打电话过来**。混成一句会让用户以为有人正在赶来。
     */
    private int statusResourceOf(@NonNull AlertDtos.AlertResponse alert) {
        if ("NO_RECIPIENT".equals(alert.status)) {
            return R.string.alarm_no_guardian;
        }
        if (alert.isAutoSessionSkipped()) {
            return R.string.alarm_sent_skipped;
        }
        if (alert.isAutoSessionPending()) {
            return R.string.alarm_sent_scheduled;
        }
        return R.string.alarm_sent_dispatched;
    }

    /** 把开关推到服务端；失败只提示，不改本地状态。 */
    private void pushAutoSessionSetting(boolean enabled) {
        ApiClient.get(this)
                .updateAlertSetting(new AlertDtos.AlertSettingUpdateRequest(enabled))
                .enqueue(new ResultCallback<>() {
                    @Override
                    public void onSuccess(@Nullable AlertDtos.AlertSettingResponse body) {
                        Toast.makeText(ProtecteeActivity.this, R.string.auto_session_saved,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull com.sullivan.protectee.data.api.ApiException error) {
                        // 本地已经按用户的选择生效了（下一次报警仍按本地开关走），
                        // 所以这里只提示同步失败，不回滚。
                        Toast.makeText(ProtecteeActivity.this,
                                getString(R.string.auto_session_save_failed, error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * 设备没有陀螺仪时告诉用户一声。**一次 Activity 生命周期内只说一次。**
     *
     * <p>Toast 给看得见的人，语音给看不见的人——但 TTS 引擎在 onCreate 时通常还没绑定好，
     * 那时念等于白念，所以隔几秒再说。这条通知本身没什么可失败的：真念不出来，也不过是
     * 少一句话，不影响别的。
     *
     * <p>之所以要防重：{@link #onResume} 会调用它，而按 Home 回来、过一遍权限弹窗都会走
     * onResume。不防的话，用户每回到应用就被 {@code speakNow} 打断一次正在播的提示，
     * 而"这台设备没有陀螺仪"是个不会变的事实，说第二遍没有任何新信息。
     */
    private void reportMissingGyroscope() {
        if (gyroscopeNoticeShown) {
            return;
        }
        gyroscopeNoticeShown = true;
        Toast.makeText(this, R.string.hint_no_gyroscope, Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            PromptAnnouncer current = announcer;
            if (current != null) {
                current.speakNow(getString(R.string.hint_no_gyroscope));
            }
        }, NO_GYROSCOPE_NOTICE_DELAY_MS);
    }

    // ------------------------------------------------------------------
    // 模型
    // ------------------------------------------------------------------

    private void loadModel() {
        modelStatusText.setText(R.string.status_model_loading);
        inferenceExecutor.execute(() -> {
            try {
                byte[] modelBytes = ModelStore.read(ProtecteeActivity.this);
                if (modelBytes == null) {
                    runOnUiThread(this::showModelMissing);
                    return;
                }
                String[] classNames = ModelStore.readClassNames(ProtecteeActivity.this);
                Yolo26Detector created = new Yolo26Detector(
                        modelBytes, classNames, CONFIDENCE_THRESHOLD);

                Yolo26Detector previous = detector;
                detector = created;
                if (previous != null) {
                    previous.close();
                }

                // 深度模型是可选的：任何加载失败都只是"没有米数"，不该影响障碍检测。
                // 因此单独 try/catch，绝不把异常抛到外层 —— 那会连带让检测器被判为失效。
                DepthEstimator depth = loadDepthEstimator();

                String sourceName = ModelStore.hasImported(this)
                        ? getString(R.string.model_source_imported)
                        : ModelStore.ASSET_NAME;
                String description = sourceName + " · " + created.getInputSize();
                // 换模型之后类别表与坐标语义都可能变，旧轨迹继续用只会算出乱七八糟的速度；
                // 正在念的那句描述的也是旧场景。
                sceneAnalyzer.reset();
                PromptAnnouncer currentAnnouncer = announcer;
                if (currentAnnouncer != null) {
                    currentAnnouncer.reset();
                }
                String status = getString(R.string.status_model_ready, description);
                if (depth != null) {
                    status += getString(R.string.status_depth_ready);
                }
                String finalStatus = status;
                runOnUiThread(() -> modelStatusText.setText(finalStatus));
            } catch (Throwable error) {
                Log.e(TAG, "模型加载失败", error);
                runOnUiThread(() -> showModelError(error));
            }
        });
    }

    /**
     * 加载深度模型；拿不到或加载失败都返回 {@code null}。
     *
     * <p>在推理线程上调用。返回 null 之后每帧会跳过深度推理，距离判断自动退回
     * 几何启发式 —— 用户不会因为少装一个模型就失去障碍提示。
     */
    @Nullable
    private DepthEstimator loadDepthEstimator() {
        DepthEstimator previous = depthEstimator;
        depthEstimator = null;
        if (previous != null) {
            previous.close();
        }
        try {
            byte[] bytes = ModelStore.readDepth(this);
            if (bytes == null) {
                Log.i(TAG, "未找到深度模型，距离改用几何启发式");
                return null;
            }
            DepthEstimator created = new DepthEstimator(bytes);
            depthEstimator = created;
            Log.i(TAG, "深度模型已加载: " + created.describe());
            return created;
        } catch (Throwable error) {
            // 不降级为"报错并停止检测"：深度只是增强，检测才是主功能。
            Log.w(TAG, "深度模型加载失败，距离改用几何启发式", error);
            return null;
        }
    }

    private void importModel(@NonNull Uri uri) {
        inferenceExecutor.execute(() -> {
            try {
                ModelStore.importFrom(this, uri);
                runOnUiThread(() -> Toast.makeText(this, R.string.model_imported,
                        Toast.LENGTH_SHORT).show());
                loadModel();
            } catch (IOException error) {
                Log.e(TAG, "模型导入失败", error);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.model_import_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 导入深度模型；成功后重新加载（会连带重建检测器，代价只有一次几百毫秒）。 */
    private void importDepthModel(@NonNull Uri uri) {
        inferenceExecutor.execute(() -> {
            try {
                ModelStore.importDepthFrom(this, uri);
                runOnUiThread(() -> Toast.makeText(this, R.string.depth_model_imported,
                        Toast.LENGTH_SHORT).show());
                loadModel();
            } catch (IOException error) {
                Log.e(TAG, "深度模型导入失败", error);
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.model_import_failed, error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 关掉检测器；在推理线程上排队关闭，避免打断正在进行的推理。 */
    private void closeDetector() {
        Yolo26Detector current = detector;
        DepthEstimator currentDepth = depthEstimator;
        detector = null;
        depthEstimator = null;
        if (current == null && currentDepth == null) {
            inferenceExecutor.shutdown();
            return;
        }
        try {
            inferenceExecutor.execute(() -> {
                if (current != null) {
                    current.close();
                }
                if (currentDepth != null) {
                    currentDepth.close();
                }
            });
        } catch (RejectedExecutionException ignored) {
            if (current != null) {
                current.close();
            }
            if (currentDepth != null) {
                currentDepth.close();
            }
        }
        inferenceExecutor.shutdown();
    }

    private void showModelMissing() {
        detector = null;
        modelStatusText.setText(R.string.status_model_missing);
        promptText.setText(R.string.hint_model_missing);
        promptText.setTextColor(ContextCompat.getColor(this, R.color.level_caution));
        overlayView.clear();
    }

    private void showModelError(@NonNull Throwable error) {
        detector = null;
        modelStatusText.setText(R.string.status_model_failed);
        String reason = error.getMessage() == null ? error.toString() : error.getMessage();
        promptText.setText(getString(R.string.hint_model_failed, reason));
        promptText.setTextColor(ContextCompat.getColor(this, R.color.level_danger));
        overlayView.clear();
    }

    // ------------------------------------------------------------------
    // 相机
    // ------------------------------------------------------------------

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionHint() {
        permissionHint.setVisibility(View.VISIBLE);
        promptText.setText(R.string.prompt_no_permission);
        promptText.setTextColor(ContextCompat.getColor(this, R.color.level_caution));
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindUseCases(future.get());
            } catch (Exception error) {
                Log.e(TAG, "相机启动失败", error);
                promptText.setText(getString(R.string.hint_camera_failed,
                        String.valueOf(error.getMessage())));
                promptText.setTextColor(ContextCompat.getColor(this, R.color.level_danger));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(@NonNull ProcessCameraProvider provider) {
        cameraProvider = provider;
        // 预览与分析都用 16:9，保证叠加层的坐标换算与预览画面严格对齐。
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build();

        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();
        analysis.setAnalyzer(analysisExecutor, analyzer);
        imageAnalysis = analysis;

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    // ------------------------------------------------------------------
    // 逐帧处理
    // ------------------------------------------------------------------

    private void onFrame(@NonNull ImageProxy image) {
        Yolo26Detector current = detector;
        if (current == null || detecting.get()) {
            image.close();
            return;
        }
        RgbaFrame frame = RgbaFrame.from(image);
        // 曝光时刻必须在 image 关闭前取出；推理耗时 60–120 ms，与帧间隔同量级，
        // 用推理完成时刻当时间轴会给测速注入同样大的抖动。
        long capturedAtMs = captureTimeMs(frame);
        image.close();

        if (!detecting.compareAndSet(false, true)) {
            return;
        }
        try {
            inferenceExecutor.execute(() -> {
                try {
                    runDetection(current, frame, capturedAtMs);
                } catch (Throwable error) {
                    Log.w(TAG, "推理失败", error);
                } finally {
                    detecting.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            detecting.set(false);
        }
    }

    /**
     * 帧的采集时刻（毫秒）。
     *
     * <p>优先用传感器时间戳，它与 {@link SystemClock#elapsedRealtime()} 同属单调时钟，
     * 但反映的是曝光瞬间而不是回调瞬间。设备没给时间戳时退回回调时刻，
     * 二者只差一帧的传输延迟，比完全没有时间轴好。
     */
    private static long captureTimeMs(@NonNull RgbaFrame frame) {
        return frame.timestampNanos > 0L
                ? frame.timestampNanos / 1_000_000L
                : SystemClock.elapsedRealtime();
    }

    private void runDetection(@NonNull Yolo26Detector current, @NonNull RgbaFrame frame,
                              long capturedAtMs) {
        long startedAt = SystemClock.elapsedRealtime();
        List<Detection> detections;
        DepthMap depth = null;
        Letterbox depthLetterbox = null;
        try {
            // 检测与深度**各做各的 letterbox**：官方权重一个是 640 输入、一个是 768，
            // 不是同一份张量。第一版假设"共用一次预处理"，那个假设是错的
            // （拿 640 喂 768 会直接报 Got invalid dimensions）。
            Yolo26Detector.Prepared prepared = current.preprocess(frame);
            detections = current.detect(prepared);

            DepthEstimator currentDepth = depthEstimator;
            if (currentDepth != null) {
                // 深度隔帧跑：实测 640 检测 43 ms + 768 深度 85 ms = 128 ms/帧，
                // 桌面机都只有 7.8 FPS，手机更慢。全帧跑会把相机回调拖垮。
                // 隔帧的代价是米数最多滞后一个深度周期 —— 对"1.5 米处有人"这种
                // 提示完全可接受，而帧率掉下去是立刻能感觉到的卡顿。
                boolean due = (framesSinceDepth % DEPTH_INTERVAL_FRAMES == 0);
                if (due) {
                    try {
                        depth = currentDepth.predict(frame);
                        depthLetterbox = currentDepth.letterboxOf(frame);
                        lastDepth = depth;
                        lastDepthLetterbox = depthLetterbox;
                    } catch (Throwable depthError) {
                        // 深度坏了（内存不足、模型不匹配）不能让障碍检测一起失效。
                        // 本帧没有米数，下次到点再试 —— 一次 OOM 之后恢复很常见。
                        Log.w(TAG, "深度推理失败，本帧距离退回启发式", depthError);
                        depth = null;
                        depthLetterbox = null;
                        lastDepth = null;
                        lastDepthLetterbox = null;
                    }
                } else if (lastDepth != null && lastDepthLetterbox != null) {
                    // 复用上一张深度图。geometry 变了（转屏/换分辨率）就作废，
                    // 否则旧图的像素坐标会与新的一帧对不上。
                    Letterbox expected = currentDepth.letterboxOf(frame);
                    if (sameGeometry(expected, lastDepthLetterbox)) {
                        depth = lastDepth;
                        depthLetterbox = lastDepthLetterbox;
                    }
                }
                framesSinceDepth++;
            }

            // 画面几何有任何变化（转屏、换分辨率），轨迹都会在内部整体作废。
            ObjectTracker.FrameEpoch epoch = new ObjectTracker.FrameEpoch(
                    frame.rotatedWidth(), frame.rotatedHeight(), frame.rotationDegrees);
            // 传给分析器的是**深度那一份** letterbox：深度图是 768 的全图，
            // 用它自己的 pad/content 才能把检测框（内容区归一化）落回正确像素。
            SceneAnalyzer.Decision decision = sceneAnalyzer.analyze(
                    detections, capturedAtMs, epoch, depth, depthLetterbox);
            long costMs = SystemClock.elapsedRealtime() - startedAt;

            if (LOG_SCENE_JSON && capturedAtMs - lastSceneLogAt >= SCENE_LOG_INTERVAL_MS) {
                lastSceneLogAt = capturedAtMs;
                Log.i(TAG, "scene " + decision.snapshot.toJson());
            }

            int frameWidth = frame.rotatedWidth();
            int frameHeight = frame.rotatedHeight();
            runOnUiThread(() -> {
                if (detector != current) {
                    return;
                }
                overlayView.setFrameSize(frameWidth, frameHeight);
                overlayView.setResult(decision.snapshot.tracks, decision.snapshot.freeRatio);
                promptText.setText(decision.message);
                promptText.setTextColor(ContextCompat.getColor(this, colorOf(decision.level)));
                updatePerf(capturedAtMs, costMs);
            });

            // Activity 可能已经在上一帧之后销毁，这里取一次局部引用避免空指针。
            PromptAnnouncer currentAnnouncer = announcer;
            if (currentAnnouncer != null) {
                currentAnnouncer.announce(decision.message, decision.level, decision.dedupKey,
                        decision.hysteretic);
            }

            // 碰撞自动求助：只在推理线程里累计时间轴，真正发请求要切回主线程。
            // DANGER 在这里指的是「障碍物已进入立即停下的距离」（有深度图时按米，
            // 没有时按原来的几何判据），不是「画面里有人」。
            boolean fired = collisionGate.onFrame(
                    decision.level == ObstacleAdvisor.Level.DANGER, SystemClock.elapsedRealtime());
            if (fired) {
                runOnUiThread(() -> sendAlert(AlertClient.TYPE_COLLISION,
                        getString(R.string.alarm_collision_detected)));
            }
        } catch (Exception error) {
            Log.w(TAG, "推理失败", error);
        }
    }

    /**
     * 两次 letterbox 的几何是否一致（用于判断旧深度图还能不能复用）。
     *
     * <p>只比真正影响"归一化坐标 → 深度像素"的那几个量：pad 与内容区尺寸、旋转。
     * 源分辨率变了但内容区没变的情况（比如相机换了档位）其实是同一套几何，
     * 复用是安全的，所以不去比源宽高。
     */
    private static boolean sameGeometry(@NonNull Letterbox a, @NonNull Letterbox b) {
        return a.padX == b.padX
                && a.padY == b.padY
                && a.contentWidth == b.contentWidth
                && a.contentHeight == b.contentHeight
                && a.rotationDegrees == b.rotationDegrees;
    }

    /** 帧率按**采集间隔**算（曝光到曝光），耗时才按推理墙钟算，两者不要混。 */
    private void updatePerf(long capturedAtMs, long costMs) {
        if (lastDetectionAt != 0L) {
            float deltaSeconds = (capturedAtMs - lastDetectionAt) / 1000f;
            if (deltaSeconds > 0f) {
                float instantFps = 1f / deltaSeconds;
                smoothedFps = smoothedFps == 0f
                        ? instantFps
                        : smoothedFps * 0.8f + instantFps * 0.2f;
            }
        }
        lastDetectionAt = capturedAtMs;
        perfText.setText(String.format(Locale.US, "%.1f FPS · %d ms", smoothedFps, costMs));
    }

    private static int colorOf(@NonNull ObstacleAdvisor.Level level) {
        switch (level) {
            case DANGER:
                return R.color.level_danger;
            case CAUTION:
                return R.color.level_caution;
            default:
                return R.color.level_clear;
        }
    }
}
