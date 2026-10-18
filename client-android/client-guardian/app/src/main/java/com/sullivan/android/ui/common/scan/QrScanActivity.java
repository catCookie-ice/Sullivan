package com.sullivan.android.ui.common.scan;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.sullivan.android.R;
import com.sullivan.android.qr.QrImageAnalyzer;
import com.sullivan.android.qr.QrPayload;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用扫码页：打开摄像头找二维码，也可以手动输入。
 *
 * <p>结果通过 {@link #EXTRA_RESULT_TEXT} 回传（{@code RESULT_OK}），
 * 调用方拿 {@link QrPayload#parse} 自己判断拿到的是绑定码还是登录票据 ——
 * 判定逻辑只写一份，页面本身不关心自己是被谁调起来的，
 * 只多一个 {@link #EXTRA_EXPECTED} 用来在扫错类型时给一句更准的提示。
 *
 * <p>三个必须自己处理的点：
 * <ul>
 *   <li><b>自己申请 CAMERA 权限</b>。监护人第一次走进这个页面时，
 *       应用可能刚装好、从没跑过受护人端那条授权路径，不能指望权限已经给过。</li>
 *   <li><b>不调用 {@code provider.unbindAll()}</b>。那是**进程级**的操作
 *       （{@code ProtecteeActivity} 里用它是因为那个界面独占相机），
 *       会拆掉本进程内所有已绑定的用例。这里只 {@code unbind(preview, analysis)}
 *       解开自己绑的两个。</li>
 *   <li><b>分辨率取 1280×720 那一档</b>。默认档约 640×480，
 *       手举到正常距离时二维码可能只有几十像素，二值化后就糊成一片了。</li>
 * </ul>
 */
public class QrScanActivity extends AppCompatActivity {

    private static final String TAG = "QrScanActivity";

    /** 回传扫到/输入的文本，形如 {@code sullivan://bind?code=…}，调用方用 {@link QrPayload#parse} 还原。 */
    public static final String EXTRA_RESULT_TEXT = "result_text";

    /** 期望的码类型：{@link QrPayload.Kind} 的名字；不给则不校验。 */
    public static final String EXTRA_EXPECTED = "expected_kind";

    private static final String EXTRA_TITLE_RES = "title_res";

    /** 构造本页面的 Intent。{@code titleRes} 用来说清用户此刻要扫的是哪一种码。 */
    public static Intent intentFor(@NonNull Context context, @NonNull QrPayload.Kind expected,
                                  int titleRes) {
        return new Intent(context, QrScanActivity.class)
                .putExtra(EXTRA_EXPECTED, expected.name())
                .putExtra(EXTRA_TITLE_RES, titleRes);
    }

    private PreviewView previewView;
    private TextView tipText;
    private Button grantButton;

    private ExecutorService analysisExecutor;
    private QrImageAnalyzer analyzer;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private ActivityResultLauncher<String> permissionLauncher;

    /** 已经交付结果给调用方；之后不再接受任何扫描结果。 */
    private boolean delivered;

    @Nullable
    private QrPayload.Kind expectedKind;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        previewView = findViewById(R.id.scan_preview);
        tipText = findViewById(R.id.scan_tip);
        grantButton = findViewById(R.id.scan_grant_button);
        TextView titleText = findViewById(R.id.scan_title);
        Button manualButton = findViewById(R.id.scan_manual_button);

        int titleRes = getIntent().getIntExtra(EXTRA_TITLE_RES, R.string.scan_title_bind);
        if (titleRes != 0) {
            titleText.setText(titleRes);
        }
        String expectedName = getIntent().getStringExtra(EXTRA_EXPECTED);
        if (expectedName != null) {
            try {
                expectedKind = QrPayload.Kind.valueOf(expectedName);
            } catch (IllegalArgumentException ignored) {
                expectedKind = null;
            }
        }

        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        showCameraUi();
                        startCamera();
                    } else {
                        showPermissionUi();
                    }
                });

        grantButton.setOnClickListener(
                view -> permissionLauncher.launch(Manifest.permission.CAMERA));
        manualButton.setOnClickListener(view -> promptManualEntry());

        if (hasCameraPermission()) {
            showCameraUi();
            startCamera();
        } else {
            showPermissionUi();
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onDestroy() {
        // 先停分析器：它可能在解码线程上马上就要 post 回主线程，
        // 这里置位之后那些回调会自行丢弃。
        if (analyzer != null) {
            analyzer.stop();
            analyzer = null;
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null) {
            // 只解自己绑的两个 —— 绝不能 unbindAll，那是进程级的。
            cameraProvider.unbind(preview, imageAnalysis);
            cameraProvider = null;
        }
        preview = null;
        imageAnalysis = null;
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 相机
    // ------------------------------------------------------------------

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showCameraUi() {
        previewView.setVisibility(View.VISIBLE);
        grantButton.setVisibility(View.GONE);
        tipText.setText(R.string.scan_camera_tip);
    }

    private void showPermissionUi() {
        previewView.setVisibility(View.INVISIBLE);
        grantButton.setVisibility(View.VISIBLE);
        tipText.setText(R.string.scan_no_permission);
    }

    private void startCamera() {
        if (cameraProvider != null) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindUseCases(future.get());
            } catch (Exception error) {
                Log.e(TAG, "相机启动失败", error);
                tipText.setText(getString(R.string.scan_camera_failed,
                        String.valueOf(error.getMessage())));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(@NonNull ProcessCameraProvider provider) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        cameraProvider = provider;
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build();

        preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        analysisExecutor = Executors.newSingleThreadExecutor();
        analyzer = new QrImageAnalyzer(this::onQrDecoded);

        // **不设 setOutputImageFormat**：保留默认的 YUV_420_888，
        // ZXing 要的是 Y 平面。设成 RGBA 是喂 ONNX 时的做法，在这里等于把亮度信息丢掉。
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, analyzer);
        imageAnalysis = analysis;

        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    private void onQrDecoded(@NonNull String text) {
        if (delivered) {
            return;
        }
        deliver(QrPayload.parse(text));
    }

    private void promptManualEntry() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.scan_manual_hint);

        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_manual_title)
                .setView(input)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    QrPayload parsed = QrPayload.parse(input.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, R.string.scan_manual_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    deliver(parsed);
                })
                .show();
    }

    private void deliver(@Nullable QrPayload payload) {
        if (delivered) {
            return;
        }
        if (payload == null) {
            // 扫到了东西但不是我们能认的码（比如别人的网址）。不退出页面，
            // 让用户继续对准；提示一次就够，别骚扰。
            Toast.makeText(this, R.string.scan_unrecognized, Toast.LENGTH_SHORT).show();
            return;
        }
        if (expectedKind != null && payload.kind() != expectedKind) {
            // 扫错类型是最容易发生的一次性错误（两个入口并排放在监护人主页上）。
            // 说清楚「该用哪个入口」，而不是笼统一句「无效二维码」。
            tipText.setText(payload.kind() == QrPayload.Kind.LOGIN
                    ? R.string.scan_wrong_kind_login
                    : R.string.scan_wrong_kind_bind);
            Toast.makeText(this, tipText.getText(), Toast.LENGTH_LONG).show();
            return;
        }
        delivered = true;
        if (analyzer != null) {
            analyzer.stop();
        }
        Intent result = new Intent();
        // 回传**原始文本**而不是把 payload 拆开：调用方对绑定码要的是那 12 位短码
        // （要拿去发请求），对票据要的是 ticket，两种都只是 payload.value()。
        result.putExtra(EXTRA_RESULT_TEXT, payload.encode());
        setResult(RESULT_OK, result);
        finish();
    }
}
