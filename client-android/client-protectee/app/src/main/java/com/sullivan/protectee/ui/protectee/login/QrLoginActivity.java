package com.sullivan.protectee.ui.protectee.login;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.WriterException;
import com.sullivan.protectee.R;
import com.sullivan.protectee.data.api.ApiClient;
import com.sullivan.protectee.data.api.ApiException;
import com.sullivan.protectee.data.api.ResultCallback;
import com.sullivan.protectee.data.api.dto.AuthDtos;
import com.sullivan.protectee.data.api.dto.QrDtos;
import com.sullivan.protectee.data.session.SessionStore;
import com.sullivan.protectee.qr.LoginTicketSecret;
import com.sullivan.protectee.qr.QrBitmapGenerator;
import com.sullivan.protectee.qr.QrPayload;
import com.sullivan.protectee.ui.protectee.home.ProtecteeActivity;

import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import retrofit2.Call;

/**
 * 受护人扫码登录：出示登录二维码，轮询等监护人授权。
 *
 * <p>流程：建票（带本机 secret 的哈希）→ 画二维码 → 每 1.5 秒轮询一次状态 →
 * 轮到时 {@code status=CONFIRMED} 拿到 {@code exchangeCode} → 用 secret 兑换令牌 →
 * 落 {@link SessionStore} → 进受护人主页。
 *
 * <p><b>本页只做扫码登录，没有账密表单</b>：这是用户明确选定的口径
 * （见 {@code docs/final/17-开发日记-2026-10-16.md} 的授权决策记录）。
 * 代价是这条路径绕过了「受护人知道自己的密码」这个唯一的持有证明，
 * 一旦监护关系被滥用，受护人没有第二条独立登录途径可自证。
 *
 * <p>三个实现要点：
 * <ul>
 *   <li><b>轮询间隔 1.5 秒</b>：受护人举着手机等，超过两秒就会怀疑是不是坏了；
 *       而每次轮询都直查主键、没有索引扫描，1.5 秒对本工程的使用规模完全不构成压力。</li>
 *   <li><b>二维码位图在后台线程生成</b>：720p 的 {@code QRCodeWriter.encode} 要几十毫秒，
 *       放主线程会让界面卡一下；而且换新码时每次都要重生成。</li>
 *   <li><b>换到令牌后立刻停轮询</b>：否则 {@code exchange} 让票据变成 CONSUMED，
 *       之后每一次轮询都会拿到 404，界面上刷出一串「登录码不存在或已失效」。</li>
 * </ul>
 */
public class QrLoginActivity extends AppCompatActivity {

    private static final String TAG = "QrLoginActivity";

    /**
     * 登录成功后是否直接进受护人主页。
     *
     * <p>默认 {@code true}：这个页面通常是用户主动打开的（「扫码登录」），
     * 登进去就该进主页。唯一传 {@code false} 的是「我的绑定码」那条路 ——
     * 用户点进来是为了看码，登完之后应该**回到原来的页面继续看码**，
     * 而不是被丢到障碍检测界面、还得自己找回来。
     */
    public static final String EXTRA_NAVIGATE_ON_SUCCESS = "navigate_on_success";

    public static Intent intentFor(@NonNull Context context, boolean navigateOnSuccess) {
        return new Intent(context, QrLoginActivity.class)
                .putExtra(EXTRA_NAVIGATE_ON_SUCCESS, navigateOnSuccess);
    }

    private static final long POLL_INTERVAL_MS = 1500L;

    /** 倒计时刷新间隔，与轮询错开也无妨，都是本地计算。 */
    private static final long TICK_INTERVAL_MS = 1000L;

    /** 与布局里二维码的显示尺寸一致；按 2 倍生成再由 fitCenter 缩小，边缘更锐利。 */
    private static final int QR_SIZE_PX = 560;

    /** 成功之后是否跳到受护人主页，见 {@link #EXTRA_NAVIGATE_ON_SUCCESS}。 */
    private boolean navigateOnSuccess = true;

    private SessionStore session;
    private ImageView qrImage;
    private TextView statusText;
    private TextView ttlText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bitmapExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private String ticket;
    @Nullable
    private String clientSecret;
    /** 拿到 exchangeCode 后暂存，避免重复轮询到 CONFIRMED 时反复提交兑换。 */
    @Nullable
    private String consumedExchangeCode;
    private long expiresAtMs;
    private boolean exchanging;
    private boolean finishedLogin;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (finishedLogin || ticket == null) {
                return;
            }
            pollOnce();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (finishedLogin) {
                return;
            }
            updateTtl();
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_login);

        session = new SessionStore(this);
        navigateOnSuccess = getIntent().getBooleanExtra(EXTRA_NAVIGATE_ON_SUCCESS, true);
        qrImage = findViewById(R.id.qrlogin_image);
        statusText = findViewById(R.id.qrlogin_status);
        ttlText = findViewById(R.id.qrlogin_ttl);
        findViewById(R.id.qrlogin_retry).setOnClickListener(view -> startTicket());

        startTicket();
    }

    @Override
    protected void onDestroy() {
        finishedLogin = true;
        handler.removeCallbacksAndMessages(null);
        bitmapExecutor.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 建票
    // ------------------------------------------------------------------

    private void startTicket() {
        finishedLogin = false;
        consumedExchangeCode = null;
        exchanging = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(tick);

        statusText.setText(R.string.qrlogin_step_wait);
        qrImage.setImageDrawable(null);

        clientSecret = LoginTicketSecret.newSecret();
        ApiClient.get(this)
                .createLoginTicket(new QrDtos.LoginTicketCreateRequest(
                        LoginTicketSecret.hash(clientSecret)))
                .enqueue(new ResultCallback<QrDtos.LoginTicketCreateResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.LoginTicketCreateResponse body) {
                        if (body == null || TextUtils.isEmpty(body.ticket)) {
                            statusText.setText(getString(R.string.qrlogin_failed,
                                    getString(R.string.login_failed_empty)));
                            return;
                        }
                        ticket = body.ticket;
                        expiresAtMs = System.currentTimeMillis() + body.ttlSeconds * 1000L;
                        renderQr(QrPayload.login(body.ticket));
                        updateTtl();
                        handler.postDelayed(poll, POLL_INTERVAL_MS);
                        handler.postDelayed(tick, TICK_INTERVAL_MS);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        statusText.setText(getString(R.string.qrlogin_failed, error.getMessage()));
                    }
                });
    }

    /**
     * 画二维码。位图在单线程 executor 上生成，回到主线程再 setImageBitmap。
     *
     * <p>生成失败（{@link WriterException}，理论上只有文本过长才会）时把界面明确写成
     * 「生成登录码失败」而不是留一张灰图 —— 受护人看不见图上的区别，但会一直等下去。
     */
    private void renderQr(@NonNull QrPayload payload) {
        String text = payload.encode();
        try {
            bitmapExecutor.execute(() -> {
                try {
                    Bitmap bitmap = QrBitmapGenerator.encode(text, QR_SIZE_PX);
                    handler.post(() -> {
                        if (!finishedLogin) {
                            qrImage.setImageBitmap(bitmap);
                        }
                    });
                } catch (WriterException error) {
                    Log.e(TAG, "生成登录码失败", error);
                    handler.post(() -> statusText.setText(
                            getString(R.string.qrlogin_failed, String.valueOf(error.getMessage()))));
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 界面正在销毁，executor 已关闭；不必再画。
        }
    }

    private void updateTtl() {
        long remainMs = expiresAtMs - System.currentTimeMillis();
        if (remainMs <= 0) {
            ttlText.setText("");
            return;
        }
        ttlText.setText(getString(R.string.qrlogin_ttl, remainMs / 1000L));
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    private void pollOnce() {
        String current = ticket;
        if (current == null) {
            return;
        }
        ApiClient.get(this).loginTicketStatus(current)
                .enqueue(new ResultCallback<QrDtos.LoginTicketStatusResponse>() {
                    @Override
                    public void onSuccess(@Nullable QrDtos.LoginTicketStatusResponse body) {
                        if (body == null || finishedLogin) {
                            return;
                        }
                        onStatus(body);
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        // 票据被消费之后服务端返回 404。这**不是**错误：
                        // 正常路径上我们会先停轮询，这条只是兜住竞态（兑换成功的响应
                        // 与下一次轮询几乎同时到达）。
                        if (error.httpCode() == 404 && exchanging) {
                            return;
                        }
                        if (error.httpCode() == 404) {
                            // 真的没了（被清理或超时清理过），重新建一张比干等有用。
                            statusText.setText(R.string.qrlogin_expired);
                            startTicket();
                            return;
                        }
                        statusText.setText(getString(R.string.qrlogin_exchange_failed,
                                error.getMessage()));
                    }
                });
    }

    private void onStatus(@NonNull QrDtos.LoginTicketStatusResponse status) {
        switch (status.status == null ? "" : status.status) {
            case "PENDING":
                statusText.setText(R.string.qrlogin_step_wait);
                break;
            case "SCANNED":
                statusText.setText(R.string.qrlogin_step_scanned);
                break;
            case "CONFIRMED":
                statusText.setText(R.string.qrlogin_step_exchanging);
                if (status.exchangeCode != null
                        && !status.exchangeCode.equals(consumedExchangeCode)) {
                    consumedExchangeCode = status.exchangeCode;
                    exchange(status.exchangeCode);
                }
                break;
            case "EXPIRED":
                statusText.setText(R.string.qrlogin_expired);
                startTicket();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // 兑换令牌
    // ------------------------------------------------------------------

    private void exchange(@NonNull String exchangeCode) {
        String secret = clientSecret;
        String current = ticket;
        if (secret == null || current == null || exchanging) {
            return;
        }
        exchanging = true;
        ApiClient.get(this).exchangeLoginTicket(current,
                        new QrDtos.LoginTicketExchangeRequest(secret, exchangeCode))
                .enqueue(new ResultCallback<AuthDtos.TokenResponse>() {
                    @Override
                    public void onSuccess(@Nullable AuthDtos.TokenResponse body) {
                        exchanging = false;
                        if (body == null || TextUtils.isEmpty(body.accessToken)) {
                            statusText.setText(getString(R.string.qrlogin_exchange_failed,
                                    getString(R.string.login_failed_empty)));
                            return;
                        }
                        // 走到这里票据已被核销，必须马上停轮询，
                        // 否则下一次轮询会拿到 404 并把界面切回「重新生成」。
                        finishedLogin = true;
                        handler.removeCallbacks(poll);
                        handler.removeCallbacks(tick);

                        session.saveLogin(body.accessToken, body.principalId, body.principalType,
                                body.roles == null ? new HashSet<>() : new HashSet<>(body.roles),
                                null);
                        // 调用方拿到 RESULT_OK 就可以继续它自己的事了
                        // （「我的绑定码」正是靠这个在登录完成后把码取出来的）。
                        setResult(RESULT_OK);
                        if (navigateOnSuccess) {
                            startActivity(new Intent(QrLoginActivity.this, ProtecteeActivity.class));
                        }
                        finish();
                    }

                    @Override
                    public void onError(@NonNull ApiException error) {
                        exchanging = false;
                        statusText.setText(getString(R.string.qrlogin_exchange_failed,
                                error.getMessage()));
                    }
                });
    }
}
