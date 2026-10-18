package com.sullivan.android.service.websocket;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sullivan.android.data.api.dto.RealtimeDtos;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 实时会话的长连接（OkHttp WebSocket），以及它的地址拼接规则。
 *
 * <p><b>用 OkHttp 自带的 WebSocket，不引第三方库</b>：依赖里已经有 OkHttp 4.12
 * （Retrofit 带的），{@code newWebSocket} 就够用。引一个「更好用的」WebSocket 库
 * 换来的只是少写三十行回调转发，代价是多一个要在 R8 里配规则的依赖。
 *
 * <p><b>地址拼接是纯静态函数，且有单元测试</b>（{@code RealtimeUrlTest}）：
 * 端点 {@code /ws/realtime} 在**服务器根路径**，不在 {@code /api/v1/} 下 ——
 * 而 Android 手上的 baseUrl 是带 {@code /api/v1/} 的，必须把它削掉。
 * 这是整个连接流程里最容易写错、写错了又最难看懂的一处（表现是握手 404），
 * 所以它被单独拎出来，不藏在这三十行回调里。
 *
 * <p><b>没有断线重连、没有前台服务</b>：网页端也没有。会话是「当面用一下」的
 * 短时对话（比如监护人问一句「到家了吗」），断了就回列表页重新进，
 * 比做一个看不见的重连状态机、并在重连失败时让用户对着一个其实已经死掉的
 * 输入框打字要诚实得多。
 */
public final class RealtimeSocket {

    private static final Gson GSON = new Gson();

    /** WebSocket 帧里中继消息的负载字段名；服务端只透传不解析，所以这是客户端自己的约定。 */
    private static final String PAYLOAD_KEY_TEXT = "text";

    /** OkHttp 自己的心跳帧（不是应用层 ping/pong），20 秒一次，用来让中间的 NAT 不掐连接。 */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private WebSocket socket;
    @Nullable
    private Listener listener;
    @Nullable
    private String sessionId;
    @Nullable
    private String selfUid;
    private boolean closed;

    /**
     * 由 baseUrl 推出 WebSocket 地址。**纯函数**。
     *
     * <p>规则：
     * <ol>
     *   <li>去掉末尾的 {@code /}；</li>
     *   <li>去掉末尾的 {@code /api/v1}（如果有）—— REST 与 WebSocket 不在同一个前缀下；</li>
     *   <li>{@code http} → {@code ws}、{@code https} → {@code wss}
     *       （只替换协议头，不动 host，所以端口会原样保留）；</li>
     *   <li>拼上 {@code /ws/realtime?token=…&sessionId=…}，两个参数都做 URL 编码。</li>
     * </ol>
     *
     * @param baseUrl   {@code SessionStore.baseUrl()} 的返回值，如
     *                  {@code http://10.0.0.211:8642/api/v1/}
     * @param token     JWT（含 {@code .} 与 base64url 字符）
     * @param sessionId 会话 ID，如 {@code rs_1234567890}
     */
    @NonNull
    public static String url(@NonNull String baseUrl, @NonNull String token,
                             @NonNull String sessionId) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/api/v1")) {
            base = base.substring(0, base.length() - "/api/v1".length());
        }
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        }
        return base + "/ws/realtime?token=" + encode(token) + "&sessionId=" + encode(sessionId);
    }

    /**
     * {@link URLEncoder} 是 form 编码：它把空格编成 {@code +}，而 query 里
     * {@code +} 会被服务端读成空格。这两个参数都不含空格，但换成别的调用方时
     * 这个差异会变成「token 里偶尔多出一个空格」这种极难查的故障，所以在这里就修正掉。
     */
    @NonNull
    private static String encode(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            // UTF-8 是 Java 保证支持的编码，走不到这里。
            throw new IllegalStateException("设备不支持 UTF-8", impossible);
        }
    }

    /**
     * 连接并开始收帧。重复调用会先关掉上一根连接。
     *
     * @param selfUid 本机账号 ID，用来填 {@code relay} 帧的 fromUid（服务端会校验它等于令牌主体）
     */
    public void connect(@NonNull String baseUrl, @NonNull String token, @NonNull String sessionId,
                        @NonNull String selfUid, @NonNull Listener listener) {
        close();
        this.listener = listener;
        this.sessionId = sessionId;
        this.selfUid = selfUid;
        this.closed = false;
        String endpoint = url(baseUrl, token, sessionId);
        if (!endpoint.startsWith("ws://") && !endpoint.startsWith("wss://")) {
            // baseUrl 没配好（空串或写成了裸主机名）。交给 OkHttp 的话，
            // Request.Builder.url() 会抛 IllegalArgumentException —— 那是崩溃。
            // 这里当成一次普通的连接失败报出去，界面照旧显示「连接已断开：…」。
            deliverError("服务器地址不可用，无法建立实时连接：" + endpoint);
            return;
        }
        Request request = new Request.Builder().url(endpoint).build();
        this.socket = CLIENT.newWebSocket(request, new Bridge());
    }

    /** 发一条文本消息给对方。未连接时返回 false，调用方据此提示「还没连上」。 */
    public boolean sendRelay(@NonNull String toUid, @NonNull String text) {
        return send(envelopeFor(toUid, text));
    }

    /** 应用层心跳。服务端会回一条 {@code pong} 信封。 */
    public boolean ping() {
        return send(RealtimeDtos.Envelope.control("ping"));
    }

    /** 主动离开：告诉服务端把这条连接从池子里摘掉，然后关闭。 */
    public void leave() {
        send(RealtimeDtos.Envelope.control("leave"));
        close();
    }

    /** 关闭连接。幂等。 */
    public void close() {
        closed = true;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.close(1000, null);
        }
    }

    private boolean send(@NonNull RealtimeDtos.Envelope envelope) {
        WebSocket current = socket;
        if (current == null || closed) {
            return false;
        }
        envelope.sessionId = sessionId;
        envelope.fromUid = selfUid;
        return current.send(GSON.toJson(envelope));
    }

    @NonNull
    private static RealtimeDtos.Envelope envelopeFor(@NonNull String toUid, @NonNull String text) {
        JsonObject payload = new JsonObject();
        payload.add(PAYLOAD_KEY_TEXT, new JsonPrimitive(text));
        return RealtimeDtos.Envelope.relay(null, null, toUid, payload);
    }

    // ------------------------------------------------------------------
    // 回调转发：一律投递到主线程，Activity 里可以直接改 View
    // ------------------------------------------------------------------

    private void post(@NonNull Runnable action) {
        mainHandler.post(action);
    }

    private void deliverError(@NonNull String message) {
        Listener target = listener;
        if (target != null) {
            post(() -> target.onError(message));
        }
    }

    /** OkHttp 的回调都在自己的线程上，全部经这里转到主线程。 */
    private final class Bridge extends WebSocketListener {

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            Listener target = listener;
            if (target != null) {
                post(target::onOpen);
            }
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            RealtimeDtos.Envelope envelope;
            try {
                envelope = GSON.fromJson(text, RealtimeDtos.Envelope.class);
            } catch (RuntimeException malformed) {
                deliverError("收到无法解析的消息，已忽略");
                return;
            }
            if (envelope == null) {
                return;
            }
            // 服务端的错误帧是「一条正常送达的消息」，不是连接失败；
            // 但对界面而言两者都是「这一下没成，原因是什么」，所以合并成同一个回调。
            if ("error".equals(envelope.type)) {
                deliverError(payloadText(envelope.payload, "服务端拒绝了这个操作"));
                return;
            }
            Listener target = listener;
            if (target != null) {
                post(() -> target.onEnvelope(envelope));
            }
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable cause,
                              @Nullable Response response) {
            if (closed) {
                // 我们自己关的，不是故障。
                return;
            }
            String detail = cause.getMessage();
            deliverError(detail == null ? "连接已断开" : "连接已断开：" + detail);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            if (closed) {
                return;
            }
            closed = true;
            socket = null;
            Listener target = listener;
            if (target != null) {
                post(target::onClosed);
            }
        }
    }

    /**
     * 从 payload 里取一句话。
     *
     * <p>服务端的 error 帧 payload 是一个 JSON 字符串（{@code getNodeFactory().textNode(msg)}），
     * 但中继回来的消息 payload 可能是别的形状；取不出来时给一句兜底的中文，
     * 不要把原始 JSON 甩到界面上。
     */
    @NonNull
    private static String payloadText(@Nullable JsonElement payload, @NonNull String fallback) {
        if (payload == null || payload.isJsonNull()) {
            return fallback;
        }
        try {
            if (payload.isJsonPrimitive()) {
                return payload.getAsString();
            }
            if (payload.isJsonObject() && payload.getAsJsonObject().has(PAYLOAD_KEY_TEXT)) {
                return payload.getAsJsonObject().get(PAYLOAD_KEY_TEXT).getAsString();
            }
        } catch (RuntimeException ignored) {
            // 落到兜底文案。
        }
        return fallback;
    }

    /** 连接事件回调。**全部在主线程上调用**，可直接更新 View。 */
    public interface Listener {

        /** 握手通过、连接可用。 */
        void onOpen();

        /** 收到一条正常信封（relay / ping / pong / session.joined / …）。 */
        void onEnvelope(@NonNull RealtimeDtos.Envelope envelope);

        /** 连接失败、被服务端拒绝，或服务端回了一条 error 帧。 */
        void onError(@NonNull String message);

        /** 对端或本机关闭了连接。 */
        void onClosed();
    }
}
