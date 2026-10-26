package com.sullivan.android.service.webrtc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.sullivan.android.data.api.dto.RealtimeDtos;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 WebRTC 通话：本地采集、编解码、ICE 与远端画面的渲染。
 *
 * <p><b>媒体为什么不经过服务端</b>：服务端只中继信令（SDP 与 ICE 候选），
 * 音视频在两端之间直连或经 TURN 中转。这样服务端永远不会成为通话内容的
 * 一份副本 —— 它连「这通电话说了什么」都不具备记录的条件。
 *
 * <p><b>信令由调用方送</b>（{@link SignalSender}）。本类不碰 WebSocket：
 * 它只把要发的东西交出去，把收到的 {@code signal} 信封喂进来。这样
 * 「谁连的、连的哪条会话」仍然是 Activity 的事，本类只负责媒体。
 *
 * <p><b>三种媒体形态由 {@link Mode} 决定，不是由「有没有摄像头」猜的</b>：
 * 监护人在 CAMERA 模式下**绝不能上行摄像头** —— 他是来看的，不是给盲人看回去的。
 * 猜的写法（有摄像头就开）会在这一处直接违背产品定义。
 */
public final class RealtimeCall {

    /** 媒体形态。 */
    public enum Mode {
        /** 双向语音，不碰摄像头。 */
        VOICE,
        /** 双向音视频。 */
        VIDEO,
        /** 受护人端：只上行摄像头与麦克风。 */
        CAMERA_SEND,
        /** 监护人端：只接收（摄像头与麦克风都只下行），本机不采集任何东西。 */
        CAMERA_RECEIVE
    }

    /** 把一条信令交给会话通道。返回 false 表示没发出去（链路已断）。 */
    public interface SignalSender {
        boolean sendSignal(@NonNull JsonObject payload);
    }

    /** 通话状态与远端画面。**全部在主线程回调。** */
    public interface Listener {
        /** 本地产生的 SDP（offer/answer），需要发给对方。 */
        void onLocalDescription(@NonNull String kind, @NonNull String sdp);

        /** 本地收集到的 ICE 候选，需要发给对方。 */
        void onLocalCandidate(@NonNull String sdpMid, int sdpMLineIndex, @NonNull String candidate);

        /** 可以渲染远端画面了。调用方负责把它挂到自己的 {@link SurfaceViewRenderer} 上。 */
        void onRemoteVideoTrack(@NonNull VideoTrack track);

        /**
         * 连接状态的人话描述。
         *
         * <p><b>只在真的连上之后才说「已接通」</b>：ICE 的 CONNECTED 才算数。
         * 把 CONNECTING 显示成通话中，用户会对着一个没有声音的画面讲很久。
         */
        void onState(@NonNull String message, boolean connected);

        void onError(@NonNull String message);
    }

    private static final String TAG_TRACK_AUDIO = "sullivan_audio";
    private static final String TAG_TRACK_VIDEO = "sullivan_video";
    private static final String STREAM_LABEL = "sullivan_stream";

    /** 采集参数。摄像头请 640×480@24：通话够用，弱网下也比 720p 更不容易卡死。 */
    private static final int CAPTURE_WIDTH = 640;
    private static final int CAPTURE_HEIGHT = 480;
    private static final int CAPTURE_FPS = 24;

    /** 全局只需初始化一次；重复初始化在 WebRTC 里是未定义行为。 */
    private static boolean factoryInitialized;
    private static PeerConnectionFactory sharedFactory;
    private static EglBase sharedEglBase;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context appContext;
    private final Mode mode;
    private final Listener listener;
    private final SignalSender signalSender;

    @Nullable
    private PeerConnection peerConnection;
    @Nullable
    private AudioSource audioSource;
    @Nullable
    private AudioTrack audioTrack;
    @Nullable
    private VideoSource videoSource;
    @Nullable
    private VideoTrack videoTrack;
    @Nullable
    private CameraVideoCapturer capturer;
    @Nullable
    private SurfaceTextureHelper textureHelper;

    private boolean closed;
    /** 是否已经收到过远端 SDP。没有它就不能建 answer。 */
    private boolean remoteDescriptionSet;

    public RealtimeCall(@NonNull Context context, @NonNull Mode mode, @NonNull Listener listener,
                        @NonNull SignalSender signalSender) {
        this.appContext = context.getApplicationContext();
        this.mode = mode;
        this.listener = listener;
        this.signalSender = signalSender;
    }

    /** 供 Activity 建 SurfaceViewRenderer 用。没有它渲染器初始化不了。 */
    @Nullable
    public static EglBase.Context eglContext() {
        EglBase egl = sharedEglBase;
        return egl == null ? null : egl.getEglBaseContext();
    }

    /**
     * 初始化工厂并建好 PeerConnection。
     *
     * @param iceServers 服务端下发的 ICE 配置（见 {@code /realtime/ice-servers}）
     * @param initiator  true = 本端发起（由本端发 offer）
     */
    public void start(@NonNull List<RealtimeDtos.IceServerResponse> iceServers, boolean initiator) {
        if (closed) {
            return;
        }
        if (!ensureFactory()) {
            post(() -> listener.onError("本机无法初始化通话组件"));
            return;
        }
        List<PeerConnection.IceServer> servers = buildIceServers(iceServers);
        if (servers.isEmpty()) {
            // 一个 ICE 服务器都没有不等于一定连不上（同一局域网内可以靠本机候选直连），
            // 但跨网几乎必然失败。说出来，不要让它表现成「对方没接」。
            post(() -> listener.onError("没有可用的中转服务器，跨网络可能无法接通"));
        }

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // 候选在连接建立前就收集，省掉一轮往返；这是 1 对 1 通话，不需要 trickle 之外的优化。
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = sharedFactory.createPeerConnection(config, new PeerObserver());
        if (peerConnection == null) {
            post(() -> listener.onError("无法建立通话通道"));
            return;
        }

        createLocalTracks();

        if (initiator) {
            peerConnection.createOffer(new SimpleSdpObserver(), offerConstraints());
        }
    }

    // ------------------------------------------------------------------
    // 远端信令
    // ------------------------------------------------------------------

    /** 处理一条来自对方的 {@code signal} 信封。调用方可直接在信封回调里转发进来。 */
    public void onRemoteSignal(@NonNull JsonObject payload) {
        if (closed || peerConnection == null) {
            return;
        }
        String kind = payload.has("kind") ? payload.get("kind").getAsString() : "";
        switch (kind) {
            case "offer" -> {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.OFFER, payload.get("sdp").getAsString());
                setRemoteDescription(sdp, () -> {
                    // 收到 offer 才建 answer：抢在远端 SDP 之前建，是拿不到对端的
                    // 媒体方向的，answer 里会少一个 m-line，表现是「只有一边有声音」。
                    peerConnection.createAnswer(new SimpleSdpObserver(), answerConstraints());
                });
            }
            case "answer" -> {
                SessionDescription sdp = new SessionDescription(
                        SessionDescription.Type.ANSWER, payload.get("sdp").getAsString());
                setRemoteDescription(sdp, null);
            }
            case "candidate" -> {
                String mid = payload.has("sdpMid") && !payload.get("sdpMid").isJsonNull()
                        ? payload.get("sdpMid").getAsString() : null;
                int index = payload.has("sdpMLineIndex")
                        ? payload.get("sdpMLineIndex").getAsInt() : 0;
                String candidate = payload.get("candidate").getAsString();
                peerConnection.addIceCandidate(new IceCandidate(mid, index, candidate));
            }
            case "hangup" -> {
                post(() -> listener.onState("对方已挂断", false));
                close();
            }
            case "renegotiate" -> {
                // 目前只有「从语音切到视频」会用到，本版没有这个入口；
                // 收到了就重发一次 offer，至少不会让两端僵住。
                peerConnection.createOffer(new SimpleSdpObserver(), offerConstraints());
            }
            default -> {
                // 未知 kind：服务端已经拦过一轮，走到这里说明两端版本不一致，
                // 忽略即可，不要弹错误打断正在进行的通话。
            }
        }
    }

    // ------------------------------------------------------------------
    // 本地资源
    // ------------------------------------------------------------------

    private void createLocalTracks() {
        PeerConnection pc = peerConnection;
        if (pc == null) {
            return;
        }
        List<String> streamIds = List.of(STREAM_LABEL);

        // 语音在三种形态里都有：即便是「监护人看画面」，也必须是能说话的
        // （这是产品定义，不是一个可选增强）。
        if (mode != Mode.CAMERA_RECEIVE) {
            audioSource = sharedFactory.createAudioSource(new MediaConstraints());
            audioTrack = sharedFactory.createAudioTrack(TAG_TRACK_AUDIO, audioSource);
            // 用 addTrack 而不是 addStream：UNIFIED_PLAN 下 addTrack 会自己建
            // transceiver 并协商方向，addStream 是已废弃的写法。
            pc.addTrack(audioTrack, streamIds);
        }

        if (mode == Mode.VIDEO || mode == Mode.CAMERA_SEND) {
            startCameraCapture(pc, streamIds);
        }

        if (mode == Mode.CAMERA_RECEIVE) {
            // 明确声明「我要收一路视频」，否则 SDP 里可能只有音频，
            // 对端就算在发也看不到。
            pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    new RtpTransceiver.RtpTransceiverInit(
                            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY, streamIds));
        }
    }

    private void startCameraCapture(@NonNull PeerConnection pc, @NonNull List<String> streamIds) {
        CameraEnumerator enumerator = new Camera2Enumerator(appContext);
        String[] devices = enumerator.getDeviceNames();
        String front = null;
        for (String device : devices) {
            if (enumerator.isFrontFacing(device)) {
                front = device;
                break;
            }
        }
        String target = front != null ? front : (devices.length > 0 ? devices[0] : null);
        if (target == null) {
            post(() -> listener.onError("这台设备没有可用的摄像头"));
            return;
        }

        capturer = enumerator.createCapturer(target, new CameraVideoCapturer.CameraEventsHandler() {
            @Override
            public void onCameraError(@NonNull String error) {
                post(() -> listener.onError("摄像头出错：" + error));
            }

            @Override
            public void onCameraDisconnected() {
                post(() -> listener.onError("摄像头已断开"));
            }

            @Override
            public void onCameraFreezed(@NonNull String description) {
                post(() -> listener.onError("摄像头画面卡住了"));
            }

            @Override
            public void onCameraOpening(@NonNull String cameraName) {
                // 不需要提示：摄像头打开很快，再弹一句只会打断用户。
            }

            @Override
            public void onFirstFrameAvailable() {
                // 首帧到了并不代表接通。状态统一由 ICE 的连接状态驱动。
            }

            @Override
            public void onCameraClosed() {
                // 关闭是正常流程（挂断时发生）。
            }
        });
        if (capturer == null) {
            post(() -> listener.onError("无法打开摄像头"));
            return;
        }

        // VideoSource 不会自己接管摄像头：必须由调用方建 SurfaceTextureHelper，
        // 用它把采集器 initialize() 到 VideoSource 提供的 CapturerObserver 上。
        // 漏掉这一步的表现是「连上了、也协商成功，但画面永远是黑的」，
        // 而且不报任何错 —— 所以这里一步一步写清楚。
        videoSource = sharedFactory.createVideoSource(false);
        textureHelper = SurfaceTextureHelper.create(
                "sullivan_capture", sharedEglBase.getEglBaseContext());
        if (textureHelper == null) {
            post(() -> listener.onError("无法初始化摄像头采集"));
            return;
        }
        capturer.initialize(textureHelper, appContext, videoSource.getCapturerObserver());
        videoSource.adaptOutputFormat(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        videoTrack = sharedFactory.createVideoTrack(TAG_TRACK_VIDEO, videoSource);
        pc.addTrack(videoTrack, streamIds);

        try {
            capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);
        } catch (RuntimeException error) {
            // 摄像头被别的应用占着时这里会抛。老实说，别让用户对着黑屏讲话。
            post(() -> listener.onError("摄像头启动失败：" + error.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // SDP / ICE
    // ------------------------------------------------------------------

    private void setRemoteDescription(@NonNull SessionDescription sdp, @Nullable Runnable after) {
        PeerConnection pc = peerConnection;
        if (pc == null) {
            return;
        }
        pc.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                remoteDescriptionSet = true;
                if (after != null) {
                    after.run();
                }
            }
        }, sdp);
    }

    @NonNull
    private static MediaConstraints offerConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return constraints;
    }

    @NonNull
    private static MediaConstraints answerConstraints() {
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        return constraints;
    }

    /**
     * ICE 配置转换。
     *
     * <p>服务端把 STUN 与 TURN 混在 {@code urls} 里下发（同一批地址可能既有
     * turn: 也有 stun:）。这里按前缀分组：TURN 那一组才需要用户名与密码，
     * 给 stun: 塞凭据在部分实现上会导致该服务器被直接跳过。
     */
    @NonNull
    private static List<PeerConnection.IceServer> buildIceServers(
            @NonNull List<RealtimeDtos.IceServerResponse> entries) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        for (RealtimeDtos.IceServerResponse entry : entries) {
            if (entry == null || entry.urls == null) {
                continue;
            }
            List<String> turnUrls = new ArrayList<>();
            List<String> plainUrls = new ArrayList<>();
            for (String url : entry.urls) {
                if (url == null || url.isBlank()) {
                    continue;
                }
                if (url.startsWith("turn:") || url.startsWith("turns:")) {
                    turnUrls.add(url);
                } else {
                    plainUrls.add(url);
                }
            }
            if (!plainUrls.isEmpty()) {
                result.add(PeerConnection.IceServer.builder(plainUrls).createIceServer());
            }
            if (!turnUrls.isEmpty()) {
                boolean hasCredential = entry.username != null && !entry.username.isBlank()
                        && entry.credential != null && !entry.credential.isBlank();
                if (hasCredential) {
                    result.add(PeerConnection.IceServer.builder(turnUrls)
                            .setUsername(entry.username)
                            .setPassword(entry.credential)
                            .createIceServer());
                }
                // 没凭据的 TURN 是配错了，不是「匿名可用」。这里**不**加进去：
                // 加进去只会得到一次必然失败的候选，白等一轮超时。
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 挂断并释放全部本地资源。幂等。 */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException ignored) {
                // 停止失败不影响后续释放；中断标志按惯例补回。
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 已经停了。
            }
            capturer.dispose();
            capturer = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // 工厂（进程内共享）
    // ------------------------------------------------------------------

    private boolean ensureFactory() {
        return ensureFactoryReady();
    }

    /**
     * 进程级工厂 + EGL 环境就绪。
     *
     * <p><b>必须是 static</b>：{@link #attachRenderer} 在页面 onCreate 里就要用
     * EGL 上下文，而那时进程里往往还没有建过任何通话（工厂不存在）。
     * 旧实现里渲染器拿到 null 上下文会**跳过 init**，之后远端画面没有地方渲染
     * —— 视频通话只看到一块黑屏，且不报任何错误。
     */
    public static boolean ensureFactoryReady() {
        if (sharedFactory != null) {
            return true;
        }
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(
                                    appContextForInit())
                            .createInitializationOptions());
            factoryInitialized = true;
        }
        sharedEglBase = EglBase.create();
        sharedFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(
                        sharedEglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(
                        sharedEglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
        return sharedFactory != null;
    }

    /** 初始化只需要应用上下文；静态方法拿不到实例字段，从 Application 取进程级上下文。 */
    @NonNull
    private static Context appContextForInit() {
        return com.sullivan.android.SullivanApp.appContext();
    }

    // ------------------------------------------------------------------
    // 回调
    // ------------------------------------------------------------------

    private void post(@NonNull Runnable action) {
        mainHandler.post(action);
    }

    /** SDP 回调适配：默认什么都不做，子类只覆写它关心的那个。 */
    private class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(@NonNull SessionDescription sdp) {
            PeerConnection pc = peerConnection;
            if (pc == null || closed) {
                return;
            }
            pc.setLocalDescription(new SimpleSdpObserver(), sdp);
            post(() -> listener.onLocalDescription(
                    sdp.type == SessionDescription.Type.OFFER ? "offer" : "answer", sdp.description));
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(@Nullable String error) {
            post(() -> listener.onError("无法生成通话描述：" + error));
        }

        @Override
        public void onSetFailure(@Nullable String error) {
            post(() -> listener.onError("无法应用通话描述：" + error));
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override
        public void onIceCandidate(@NonNull IceCandidate candidate) {
            String mid = candidate.sdpMid == null ? "" : candidate.sdpMid;
            post(() -> listener.onLocalCandidate(mid, candidate.sdpMLineIndex, candidate.sdp));
        }

        /**
         * 远端轨道到达。SDK 在 Unified Plan 下用的是 {@code onTrack(RtpTransceiver)}，
         * 不是旧的 {@code onTrack(RtpReceiver)} —— 后者在这个版本里已经不存在了。
         */
        @Override
        public void onTrack(@NonNull RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            if (track instanceof VideoTrack incoming) {
                post(() -> listener.onRemoteVideoTrack(incoming));
            }
        }

        @Override
        public void onConnectionChange(@NonNull PeerConnection.PeerConnectionState state) {
            switch (state) {
                case CONNECTED -> post(() -> listener.onState("已接通", true));
                case CONNECTING -> post(() -> listener.onState("正在接通…", false));
                case DISCONNECTED -> post(() -> listener.onState("连接中断，正在尝试恢复", false));
                case FAILED -> {
                    // 跨网打不通最常见的原因就是缺 TURN。如实说出来，
                    // 否则用户会以为是对方挂断了。
                    post(() -> listener.onError("无法建立连接，可能是网络受限（缺少中转服务器）"));
                    post(() -> listener.onState("未接通", false));
                }
                case CLOSED -> post(() -> listener.onState("通话已结束", false));
                default -> {
                    // NEW / 其它中间态不单独提示，避免状态栏来回跳。
                }
            }
        }

        // 下面几个是同接口里必须实现的回调，本类不需要它们。
        @Override
        public void onSignalingChange(@NonNull PeerConnection.SignalingState state) {
        }

        @Override
        public void onIceConnectionChange(@NonNull PeerConnection.IceConnectionState state) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(@NonNull PeerConnection.IceGatheringState state) {
        }

        @Override
        public void onIceCandidatesRemoved(@NonNull IceCandidate[] candidates) {
        }

        @Override
        public void onAddStream(@NonNull MediaStream stream) {
        }

        @Override
        public void onRemoveStream(@NonNull MediaStream stream) {
        }

        @Override
        public void onDataChannel(@NonNull DataChannel channel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }
    }

    /** 让调用方确认远端 SDP 是否已就绪（建 answer 之前的前置条件）。 */
    public boolean hasRemoteDescription() {
        return remoteDescriptionSet;
    }

    /** 渲染器的统一初始化：参数写死在这里，省得每个页面各配一遍还配不一样。 */
    public static void attachRenderer(@NonNull SurfaceViewRenderer renderer) {
        // 先确保工厂与 EGL 存在：第一次进页面时它们还不存在，
        // 拿 null 上下文 init 会被跳过，之后画面永远是黑的。
        ensureFactoryReady();
        EglBase.Context egl = eglContext();
        if (egl != null) {
            renderer.init(egl, null);
        }
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);
    }
}
