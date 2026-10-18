package com.sullivan.protectee.ui.protectee.binding;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.WriterException;
import com.sullivan.protectee.R;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.QrDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.qr.QrBitmapGenerator;
import com.sullivan.protectee.qr.QrPayload;
import com.sullivan.protectee.ui.protectee.login.QrLoginActivity;
import com.sullivan.protectee.vision.assist.PromptAnnouncer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 受护人「我的绑定码」：展示一张绑定二维码给监护人扫。
 *
 * <p><b>码只在登录后才能展示</b>：未登录时本页不画码，而是先引导到扫码登录
 * （{@link QrLoginActivity}），登完再回来接着取码。这条是用户明确的口径，
 * 也是服务端的设计 —— {@code POST /qr/bind-codes} 要求受护人 Bearer 身份，
 * 服务端要从令牌知道「这张码属于谁」。
 *
 * <p>为什么进这一页不强制先登录、而是进去之后再引导：{@code ProtecteeActivity}
 * （障碍检测）是**离线可用**的核心能力，全工程此前也没有任何网络调用。
 * 若在这一页的入口处就拦一道登录，离线环境下受护人会连障碍检测都点不进去。
 * 所以拦截点严格收在「要看绑定码」这一个动作上。
 *
 * <p>二维码会过期（默认 120 秒）。过期后自动换一张：受护人没法盯着倒计时
 * 去按刷新，让他举着一张已经失效的码等下去，只会得到「监护人扫了没反应」。
 */
public class BindCodeActivity extends AppCompatActivity {

    private static final String TAG = "BindCodeActivity";

    /** 与布局里的显示尺寸一致；按 2 倍生成再缩小，边缘更锐利。 */
    private static final int QR_SIZE_PX = 560;

    private static final long TICK_INTERVAL_MS = 1000L;

    private SessionStore session;
    private ImageView codeImage;
    private TextView ownerText;
    private TextView statusText;
    private TextView codeText;
    private TextView ttlText;

    private PromptAnnouncer announcer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bitmapExecutor = Executors.newSingleThreadExecutor();

    /** 当前这张码的短码原文，用于朗读与手动抄写。 */
    @Nullable
    private String currentCode;
    private long expiresAtMs;
    private boolean requesting;

    private ActivityResultLauncher<Intent> qrLoginLauncher;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateTtl();
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bind_code);

        session = new SessionStore(this);
        codeImage = findViewById(R.id.bindcode_image);
        ownerText = findViewById(R.id.bindcode_owner);
        statusText = findViewById(R.id.bindcode_status);
        codeText = findViewById(R.id.bindcode_text);
        ttlText = findViewById(R.id.bindcode_ttl);
        Button speakButton = findViewById(R.id.bindcode_speak);
        Button refreshButton = findViewById(R.id.bindcode_refresh);

        announcer = new PromptAnnouncer(this);

        qrLoginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        requestCode();
                    } else {
                        // 登录没成功（用户返回了）。留在本页，让「换一张」还能再试。
                        statusText.setText(R.string.guardian_need_login);
                    }
                });

        refreshButton.setOnClickListener(view -> requestCode());
        speakButton.setOnClickListener(view -> speakCode());

        if (session.isLoggedIn()) {
            requestCode();
        } else {
            // 未登录：本页画不出码（服务端要 Bearer 身份），先把人送去扫码登录。
            statusText.setText(R.string.guardian_need_login);
            codeImage.setImageDrawable(null);
            codeText.setText("");
            ttlText.setText("");
            // navigate_on_success=false：登完之后回到本页接着取码，
            // 而不是被丢进障碍检测界面、还要自己找回来。
            qrLoginLauncher.launch(QrLoginActivity.intentFor(this, false));
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        bitmapExecutor.shutdown();
        if (announcer != null) {
            announcer.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 取码
    // ------------------------------------------------------------------

    private void requestCode() {
        if (requesting || !session.isLoggedIn()) {
            return;
        }
        requesting = true;
        statusText.setText(R.string.bindcode_loading);
        ApiClient.get(this).issueBindCode()
                .enqueue(new ResultCallback<QrDtos.BindCodeResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.BindCodeResponse body) {
                        requesting = false;
                        if (body == null || TextUtils.isEmpty(body.code)) {
                            statusText.setText(getString(R.string.bindcode_failed,
                                    getString(R.string.login_failed_empty)));
                            return;
                        }
                        currentCode = body.code;
                        expiresAtMs = System.currentTimeMillis() + body.ttlSeconds * 1000L;
                        ownerText.setText(TextUtils.isEmpty(body.protecteeName)
                                ? getString(R.string.bindcode_title)
                                : getString(R.string.bindcode_owner, body.protecteeName));
                        // 短码按 4 位分组：一口气念/抄 12 位连号容易掉位。
                        codeText.setText(grouped(body.code));
                        statusText.setText("");
                        renderQr(QrPayload.bind(body.code));
                        updateTtl();
                        handler.removeCallbacks(tick);
                        handler.postDelayed(tick, TICK_INTERVAL_MS);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        requesting = false;
                        if (error.isUnauthorized()) {
                            // 令牌过期了（默认 30 分钟）。回头走一遍扫码登录，
                            // 别让受护人对着「生成失败」发呆。
                            session.clearLogin();
                            statusText.setText(R.string.guardian_need_login);
                            qrLoginLauncher.launch(QrLoginActivity.intentFor(
                                    BindCodeActivity.this, false));
                            return;
                        }
                        statusText.setText(getString(R.string.bindcode_failed, error.getMessage()));
                    }
                });
    }

    private void renderQr(@NonNull QrPayload payload) {
        String text = payload.encode();
        try {
            bitmapExecutor.execute(() -> {
                try {
                    Bitmap bitmap = QrBitmapGenerator.encode(text, QR_SIZE_PX);
                    handler.post(() -> codeImage.setImageBitmap(bitmap));
                } catch (WriterException error) {
                    Log.e(TAG, "生成绑定码失败", error);
                    handler.post(() -> statusText.setText(
                            getString(R.string.bindcode_failed, String.valueOf(error.getMessage()))));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 界面正在销毁：不必再画。
        }
    }

    private void updateTtl() {
        if (currentCode == null) {
            return;
        }
        long remainMs = expiresAtMs - System.currentTimeMillis();
        if (remainMs <= 0) {
            // 过期就自动换一张，不等用户去点「换一张」。
            ttlText.setText("");
            requestCode();
            return;
        }
        ttlText.setText(getString(R.string.bindcode_ttl, remainMs / 1000L));
    }

    // ------------------------------------------------------------------
    // 朗读
    // ------------------------------------------------------------------

    /**
     * 把短码念出来。
     *
     * <p>这是整页对低视力用户**唯一**有效的信息出口：二维码对他们零信息量，
     * 而短码是他们能说给监护人听、让监护人手动输入的东西。
     * 走 {@code speakNow} 而不是 {@code announce}：这是一次用户主动触发的动作，
     * 不该被播报频率档位节流掉。
     */
    private void speakCode() {
        String code = currentCode;
        if (TextUtils.isEmpty(code)) {
            return;
        }
        // 逐位念更清楚，但 TTS 对 12 个连续大写字母会当成一个词念。
        // 按 4 位分组、组间留空，靠停顿把分组读出来。
        StringBuilder spoken = new StringBuilder();
        String grouped = grouped(code);
        for (int i = 0; i < grouped.length(); i++) {
            char character = grouped.charAt(i);
            spoken.append(character == ' ' ? '，' : character);
        }
        announcer.speakNow(getString(R.string.bindcode_speaking, spoken.toString()));
    }

    /** {@code K7M2XQ9TB4ND} → {@code K7M2 XQ9T B4ND}。 */
    @NonNull
    private static String grouped(@NonNull String code) {
        StringBuilder builder = new StringBuilder(code.length() + code.length() / 4);
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                builder.append(' ');
            }
            builder.append(code.charAt(i));
        }
        return builder.toString();
    }
}
