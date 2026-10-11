package com.sullivan.android.ui.protectee.home;

import android.Manifest;
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

import com.sullivan.android.R;
import com.sullivan.android.vision.assist.AnnounceRate;
import com.sullivan.android.vision.assist.AssistSettings;
import com.sullivan.android.vision.assist.ObstacleAdvisor;
import com.sullivan.android.vision.assist.PromptAnnouncer;
import com.sullivan.android.vision.assist.SceneAnalyzer;
import com.sullivan.android.vision.detect.Detection;
import com.sullivan.android.vision.detect.ModelStore;
import com.sullivan.android.vision.detect.RgbaFrame;
import com.sullivan.android.vision.detect.YoloV8Detector;
import com.sullivan.android.vision.motion.ShakeSensor;
import com.sullivan.android.vision.track.ObjectTracker;
import com.sullivan.android.vision.view.OverlayView;
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

    /** 置信度阈值，低于此值的检测框直接丢弃。 */
    private static final float CONFIDENCE_THRESHOLD = 0.35f;
    /** NMS 的 IoU 阈值。 */
    private static final float IOU_THRESHOLD = 0.45f;

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

    /** 当前的播报频率档位；界面与两个下游都要跟着它走，所以只留一份在界面里。 */
    @NonNull
    private AnnounceRate rate = AnnounceRate.STANDARD;

    /** 上一次打印场景 JSON 的帧时刻；只在推理线程访问。 */
    private long lastSceneLogAt;

    /** 「没有陀螺仪」只提醒一次（见 {@link #reportMissingGyroscope}）。 */
    private boolean gyroscopeNoticeShown;

    @Nullable
    private volatile YoloV8Detector detector;
    @Nullable
    private ImageAnalysis imageAnalysis;
    @Nullable
    private ProcessCameraProvider cameraProvider;

    private ActivityResultLauncher<String> permissionLauncher;
    private ActivityResultLauncher<String[]> modelPickerLauncher;

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
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        permissionHint.setVisibility(View.GONE);
                        startCamera();
                    } else {
                        showPermissionHint();
                    }
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
        pickModelButton.setOnClickListener(
                view -> modelPickerLauncher.launch(new String[]{"*/*"}));
        findViewById(R.id.grant_permission_button).setOnClickListener(
                view -> permissionLauncher.launch(Manifest.permission.CAMERA));

        shakeSensor = new ShakeSensor(this, nowMs -> onShakeDetected());

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
    }

    @Override
    protected void onPause() {
        ShakeSensor sensor = shakeSensor;
        if (sensor != null) {
            sensor.stop();
        }
        super.onPause();
    }

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
        });
    }

    private void dismissAlarm() {
        alarmPanel.setVisibility(View.GONE);
        PromptAnnouncer current = announcer;
        if (current != null) {
            current.dismissAlarm();
        }
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
                YoloV8Detector created = new YoloV8Detector(
                        modelBytes, classNames, CONFIDENCE_THRESHOLD, IOU_THRESHOLD);

                YoloV8Detector previous = detector;
                detector = created;
                if (previous != null) {
                    previous.close();
                }

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
                runOnUiThread(() -> modelStatusText.setText(
                        getString(R.string.status_model_ready, description)));
            } catch (Throwable error) {
                Log.e(TAG, "模型加载失败", error);
                runOnUiThread(() -> showModelError(error));
            }
        });
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

    /** 关掉检测器；在推理线程上排队关闭，避免打断正在进行的推理。 */
    private void closeDetector() {
        YoloV8Detector current = detector;
        detector = null;
        if (current == null) {
            inferenceExecutor.shutdown();
            return;
        }
        try {
            inferenceExecutor.execute(current::close);
        } catch (RejectedExecutionException ignored) {
            current.close();
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
        YoloV8Detector current = detector;
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

    private void runDetection(@NonNull YoloV8Detector current, @NonNull RgbaFrame frame,
                              long capturedAtMs) {
        long startedAt = SystemClock.elapsedRealtime();
        List<Detection> detections;
        try {
            detections = current.detect(frame);
        } catch (Exception error) {
            Log.w(TAG, "推理失败", error);
            return;
        }
        // 画面几何有任何变化（转屏、换分辨率），轨迹都会在内部整体作废。
        ObjectTracker.FrameEpoch epoch = new ObjectTracker.FrameEpoch(
                frame.rotatedWidth(), frame.rotatedHeight(), frame.rotationDegrees);
        SceneAnalyzer.Decision decision = sceneAnalyzer.analyze(detections, capturedAtMs, epoch);
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
