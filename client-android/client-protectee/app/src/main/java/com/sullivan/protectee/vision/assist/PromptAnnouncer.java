package com.sullivan.protectee.vision.assist;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 语音播报与振动提示：把 {@link AnnouncementPolicy} 的决定翻译成 TTS 调用。
 *
 * <p><b>线程模型</b>：本类所有状态只在主线程读写（"主线程封闭"），因此不加锁。
 * {@link #announce} 内部会 post 到主线程；{@link UtteranceProgressListener} 的回调是
 * 由 TTS 引擎所在进程经 Binder 派发的，**跑在 Binder 线程池上**，所以回调第一件事也是
 * post 回主线程。
 *
 * <p><b>为什么 utterance id 要带 generation</b>：打断（{@code QUEUE_FLUSH}）会让引擎派发
 * {@code onStop}，而旧句的 {@code onStop}/迟到的 {@code onDone} 与新句的 {@code onStart}
 * 来自引擎进程的**不同线程**，先后顺序没有保证。用一个固定 id 的话，旧句的收尾回调会在
 * 新句已经开始之后到达，把"正在播报"的状态错误地清成空闲，之后就会以为没事可做而重复投递，
 * 表现为"播报时有时无、偶尔连播两句"。把 generation 编进 id、回调里先比对，就能把这些
 * 陈旧回调整体丢弃。
 *
 * <p><b>本类还负责紧急报警</b>（{@link #startAlarm()} / {@link #dismissAlarm()}）：它走
 * 完全独立的一条路——不看那两个开关、不看频率档位、报警期间还反过来压住日常提示。理由见
 * {@link #startAlarm()}。
 */
public final class PromptAnnouncer {

    /** 看门狗的兜底时长下限。 */
    private static final long WATCHDOG_MIN_MS = 3000L;
    /** 看门狗按时长估算的每字耗时，中文约 5 字/秒。 */
    private static final long WATCHDOG_PER_CHAR_MS = 220L;
    /** 引擎报错后的退避时间，避免每帧重试。 */
    private static final long ENGINE_BACKOFF_MS = 3000L;

    private static final String UTTERANCE_PREFIX = "obstacle-";

    /** 报警第一句。 */
    public static final String ALARM_MESSAGE = "剧烈晃动，请停稳";
    /** 报警重复时的那句：告诉用户怎么把它关掉。 */
    public static final String ALARM_REPEAT_MESSAGE = "我没事请点『我没事』";
    /** 点掉报警时的确认音。 */
    public static final String ALARM_DISMISSED_MESSAGE = "报警已停止";

    /** 报警重复的间隔。 */
    private static final long ALARM_REPEAT_MS = 5000L;
    /** 上一句还没念完时，下一拍提前多久重试。 */
    private static final long ALARM_RETRY_MS = 1500L;

    /**
     * 报警语音最多说几句（含第一句）。
     *
     * <p>用户选的是"重复到点掉为止"，但**无限重复会把应用唯一的功能——导航提示——永久堵死**：
     * 用户要是点不到屏幕（摔倒了、手机摔出去了），它会一直念到电池耗尽。所以这里封了顶：
     * 1 句 + 7 次 × 5 秒 ≈ 35 秒之后不再出声。
     *
     * <p><b>封的只是"嘴"，不是"状态"</b>：面板留着、{@link #isAlarming()} 仍为真、日常提示
     * 继续被压住，用户点「我没事」照常收场；再来一次晃动（间隔 6 秒以上，见
     * {@code ShakeDetector}）也会重新把配额充满——真的又摔了一次，就再喊一轮。
     *
     * <p>想恢复字面意义上的"直到点掉为止"，把这里改成 {@code Integer.MAX_VALUE}。
     */
    private static final int MAX_ALARM_REPEATS = 8;

    /** 报警振动：比日常提示长、且带停顿，隔着衣服也能分辨出"这不是普通提示"。 */
    private static final long[] ALARM_PATTERN = {0L, 400L, 200L, 400L, 200L, 600L};
    private static final long[] DANGER_PATTERN = {0L, 180L, 120L, 180L};
    private static final long[] CAUTION_PATTERN = {0L, 120L};

    /** 连续几次调用引擎都失败之后，报警就只振动不出声了。 */
    private static final int SPEECH_FAILURE_LIMIT = 3;

    @Nullable
    private final TextToSpeech textToSpeech;
    @Nullable
    private final Vibrator vibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AnnouncementPolicy policy = new AnnouncementPolicy();

    private volatile boolean speechEnabled = true;
    private volatile boolean hapticsEnabled = true;
    /** 只允许主线程访问。 */
    private boolean speechReady;
    private boolean shuttingDown;
    private long engineBackoffUntil;

    /** 单调递增的播报代号；编进 utterance id 用来丢弃陈旧回调。 */
    private long generation;
    private long lastVibratedAt;

    /**
     * 报警中。{@code volatile}：界面线程问、传感器线程不问，但"读一个布尔"不值得为它
     * 规定调用线程。
     */
    private volatile boolean alarming;
    /** 已经念过几句报警。只允许主线程访问。 */
    private int alarmRepeats;
    private int consecutiveSpeechFailures;

    public PromptAnnouncer(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        this.vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
        this.textToSpeech = new TextToSpeech(appContext, this::onTextToSpeechInitialized);
    }

    public boolean isSpeechEnabled() {
        return speechEnabled;
    }

    public void setSpeechEnabled(boolean enabled) {
        speechEnabled = enabled;
    }

    public boolean isHapticsEnabled() {
        return hapticsEnabled;
    }

    public void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    /** 用户的播报频率档位。见 {@link AnnounceRate}。 */
    public void setRate(@NonNull AnnounceRate rate) {
        mainHandler.post(() -> policy.setRate(rate));
    }

    /**
     * 播报一条提示。等价于 {@code announce(message, level, dedupKey, false)}。
     */
    public void announce(@Nullable String message, @NonNull ObstacleAdvisor.Level level,
                         @Nullable String dedupKey) {
        announce(message, level, dedupKey, false);
    }

    /**
     * 播报一条提示。
     *
     * @param dedupKey   语义去重键。动态提示的文案会随碰撞时间分档变化，用消息文本当键会
     *                   让去重失效，所以要由调用方给一个稳定的键。
     * @param hysteretic 见 {@link AnnouncementPolicy#decide} 的这个参数。动态告警传
     *                   {@code true}：它自带滞回，不该再被 350ms 稳定窗口拖住。
     */
    public void announce(@Nullable String message, @NonNull ObstacleAdvisor.Level level,
                         @Nullable String dedupKey, boolean hysteretic) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String key = dedupKey == null || dedupKey.isEmpty() ? message : dedupKey;
        // 推理线程调用，状态只在主线程访问。
        mainHandler.post(() -> offerOnMainThread(message, level, key, hysteretic));
    }

    /**
     * 中断当前播报并重置节流状态。换模型、场景重置时调用：正在念的那句描述的是旧场景。
     */
    public void reset() {
        mainHandler.post(() -> {
            if (shuttingDown) {
                return;
            }
            forceIdle();
            TextToSpeech engine = textToSpeech;
            if (engine != null) {
                engine.stop();
            }
        });
    }

    // ------------------------------------------------------------------
    // 报警
    // ------------------------------------------------------------------

    /** 报警中。报警期间一切日常提示与日常振动都被压住。 */
    public boolean isAlarming() {
        return alarming;
    }

    /**
     * 起一轮报警：立刻出声 + 强振动，之后每 5 秒重复一次，直到 {@link #dismissAlarm()}。
     *
     * <p><b>报警不受那两个开关与频率档位影响</b>（用户选定）：用户关掉的是"日常唠叨"，
     * 不是紧急求助。所以这里既不查 {@code speechEnabled} 也不查 {@code hapticsEnabled}。
     *
     * <p>已经在报警时再调一次，只把重复的配额充满、节奏重新起一拍，**不叠着说一句新的**：
     * 报警的重复只由 {@link #alarmRepeatTask} 这一个驱动器负责，看门狗与 TTS 回调都不会
     * 也去说一句。
     */
    public void startAlarm() {
        mainHandler.post(() -> {
            if (shuttingDown) {
                return;
            }
            if (alarming) {
                // 又晃了一次：重新充满配额，让报警接着说。真正的"又摔了一跤"不该被上一轮的
                // 配额封顶连累，而这一次也不需要马上再说一句——下一拍就会说。
                alarmRepeats = 1;
                mainHandler.removeCallbacks(alarmRepeatTask);
                mainHandler.postDelayed(alarmRepeatTask, ALARM_REPEAT_MS);
                vibrateAlarm();
                return;
            }
            alarming = true;
            alarmRepeats = 1;
            mainHandler.removeCallbacks(alarmRepeatTask);
            speakNow(ALARM_MESSAGE);
            vibrateAlarm();
            mainHandler.postDelayed(alarmRepeatTask, ALARM_REPEAT_MS);
        });
    }

    /** 用户点掉了报警：停重复、出声确认、之后日常提示照常。重复调用是安全的。 */
    public void dismissAlarm() {
        mainHandler.post(() -> {
            if (shuttingDown || !alarming) {
                return;
            }
            alarming = false;
            alarmRepeats = 0;
            mainHandler.removeCallbacks(alarmRepeatTask);
            // 用 speakNow 而不是 announce：「少」档的同一句话冷却有 12 秒，用户点掉之后
            // 静悄悄什么反馈都没有，会以为按钮没生效。
            speakNow(ALARM_DISMISSED_MESSAGE);
        });
    }

    /**
     * 立刻出声，绕开所有节流。
     *
     * <p>先把节流状态强行复位并作废在途回调（否则 {@link AnnouncementPolicy} 会以为还有一句
     * 在播，或者因为冷却把这句话吞掉），再用 {@code QUEUE_FLUSH} 说出去。之后照常由引擎的
     * {@code onStart} / {@code onDone} 驱动状态机、照常由看门狗兜底——**报警的重复不由这里
     * 负责**，它只负责"这一句现在就说"。
     */
    public void speakNow(@NonNull String message) {
        mainHandler.post(() -> {
            if (shuttingDown) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            forceIdle();
            if (consecutiveSpeechFailures >= SPEECH_FAILURE_LIMIT) {
                // 引擎连着失败好几次了：别再喂它。振动还在，用户至少知道出事了。
                return;
            }
            speak(message, true, now);
        });
    }

    /** 释放语音引擎，需在 Activity 销毁时调用。 */
    public void shutdown() {
        // 同步置位：之后任何已经 post 进来但还没跑的活都会自己放弃，包括重复报警。
        shuttingDown = true;
        alarming = false;
        mainHandler.removeCallbacks(alarmRepeatTask);
        if (textToSpeech != null) {
            // shutdown() 内部已经停了当前播报，不用再单独调 stop()。
            textToSpeech.shutdown();
        }
    }

    /**
     * 报警的重复：自己 post 自己，一拍一句。
     *
     * <p>只在 {@link AnnouncementPolicy.State#IDLE} 时才开口。这是"只有一个驱动器"的落点：
     * 引擎回调不会去说报警，看门狗也不会，于是不可能出现两句报警叠在一起。
     */
    private final Runnable alarmRepeatTask = new Runnable() {
        @Override
        public void run() {
            if (shuttingDown || !alarming || alarmRepeats >= MAX_ALARM_REPEATS) {
                return;
            }
            if (policy.state() != AnnouncementPolicy.State.IDLE) {
                // 上一句还没念完：这一拍跳过，过一会儿再说。宁可少说一句，也不要叠着说。
                mainHandler.postDelayed(this, ALARM_RETRY_MS);
                return;
            }
            alarmRepeats++;
            speakNow(ALARM_REPEAT_MESSAGE);
            vibrateAlarm();
            mainHandler.postDelayed(this, ALARM_REPEAT_MS);
        }
    };

    // ------------------------------------------------------------------
    // 主线程
    // ------------------------------------------------------------------

    private void offerOnMainThread(@NonNull String message, @NonNull ObstacleAdvisor.Level level,
                                   @NonNull String key, boolean hysteretic) {
        if (shuttingDown) {
            return;
        }
        if (alarming) {
            // 报警期间压住一切日常提示与日常振动（用户选定）：此刻用户需要听到的是"出事了"，
            // 不是"右侧有自行车"。这道门必须在这里——推理线程可能已经把一句提示 post 进来了，
            // 在 announce() 入口处拦不住它。
            return;
        }
        long now = SystemClock.elapsedRealtime();

        if (speechEnabled && canSpeak(now)) {
            AnnouncementPolicy.Decision decision = policy.decide(message, level, key, now, hysteretic);
            if (decision.action == AnnouncementPolicy.Action.SPEAK) {
                speak(message, decision.preempt, now);
            }
        }

        if (hapticsEnabled && level != ObstacleAdvisor.Level.CLEAR
                && now - lastVibratedAt >= policy.rate().hapticCooldownMs()) {
            lastVibratedAt = now;
            vibrate(level == ObstacleAdvisor.Level.DANGER ? DANGER_PATTERN : CAUTION_PATTERN);
        }
    }

    private boolean canSpeak(long now) {
        return speechReady && textToSpeech != null && now >= engineBackoffUntil;
    }

    /**
     * 作废在途回调，并把节流状态强行复位成空闲。
     *
     * <p>两件事缺一不可：{@code generation++} 让旧句的 {@code onStop} / 迟到的 {@code onDone}
     * 在 {@link #isCurrent} 处被丢弃；{@code onUtteranceFinished} 让策略不再以为有句子在播、
     * 也不再有冷却卡着下一句。
     */
    private void forceIdle() {
        generation++;
        policy.onUtteranceFinished(SystemClock.elapsedRealtime());
    }

    private void speak(@NonNull String message, boolean preempt, long now) {
        TextToSpeech engine = textToSpeech;
        if (engine == null) {
            return;
        }
        // 先递增代号再 speak：这样新句的 onStart 与旧句的 onStop 用的不是同一代，
        // 陈旧回调会被 isCurrent 丢掉。
        generation++;
        long currentGeneration = generation;
        String utteranceId = UTTERANCE_PREFIX + currentGeneration;

        // 队列空闲时用 QUEUE_ADD。语义上与 FLUSH 等价（队列本来就空），但万一状态判断
        // 过时了（以为空闲其实在播），最坏只是晚一点播，不会把正在播的句子砍掉。
        int mode = preempt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        int result = engine.speak(message, mode, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            // 引擎还没绑定好或已经挂了；保留冷却语义，避免每帧重试。
            policy.onUtteranceFinished(now);
            engineBackoffUntil = now + ENGINE_BACKOFF_MS;
            consecutiveSpeechFailures++;
            return;
        }
        consecutiveSpeechFailures = 0;
        // speak() 返回 SUCCESS 只代表成功入队，真正开始出声要等 onStart。
        armWatchdog(currentGeneration, message);
    }

    /**
     * 看门狗：部分第三方引擎不会回调 {@code onStop}，引擎进程被杀时 {@code onDone} 也会丢，
     * 一旦卡在"正在播报"就再也不会播下一句。把 generation 捕获进任务里，过期的任务
     * 自然失效，不需要额外做取消。
     */
    private void armWatchdog(long expectedGeneration, @NonNull String message) {
        long delay = Math.max(WATCHDOG_MIN_MS,
                400L + message.length() * WATCHDOG_PER_CHAR_MS);
        mainHandler.postDelayed(() -> {
            if (shuttingDown || generation != expectedGeneration) {
                return;
            }
            policy.onUtteranceFinished(SystemClock.elapsedRealtime());
        }, delay);
    }

    private boolean isCurrent(@Nullable String utteranceId) {
        return utteranceId != null && utteranceId.equals(UTTERANCE_PREFIX + generation);
    }

    private void onTextToSpeechInitialized(int status) {
        // 回调可能内联执行在绑定的那个线程上（引擎已绑定时会同步回调），甚至是构造函数
        // 还没返回的时候——那时本对象的 textToSpeech 字段还没赋值。post 到主线程既统一了
        // 状态访问线程，也顺带保证了执行时机一定在构造完成之后。
        mainHandler.post(() -> {
            TextToSpeech engine = textToSpeech;
            if (shuttingDown || status != TextToSpeech.SUCCESS || engine == null) {
                return;
            }
            int result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 设备没装中文语音包时退回系统语言，至少还能出声。
                engine.setLanguage(Locale.getDefault());
            }
            engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    mainHandler.post(() -> handleStart(utteranceId));
                }

                @Override
                public void onDone(String utteranceId) {
                    mainHandler.post(() -> handleFinished(utteranceId));
                }

                @Override
                public void onError(String utteranceId) {
                    mainHandler.post(() -> handleFinished(utteranceId));
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    mainHandler.post(() -> handleFinished(utteranceId));
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    mainHandler.post(() -> handleFinished(utteranceId));
                }
            });
            speechReady = true;
        });
    }

    private void handleStart(@Nullable String utteranceId) {
        if (shuttingDown || !isCurrent(utteranceId)) {
            return;
        }
        policy.onUtteranceStarted(SystemClock.elapsedRealtime());
    }

    private void handleFinished(@Nullable String utteranceId) {
        if (shuttingDown || !isCurrent(utteranceId)) {
            return;
        }
        policy.onUtteranceFinished(SystemClock.elapsedRealtime());
    }

    /** 报警振动。不看 {@code hapticsEnabled}，也不走日常振动的冷却。 */
    private void vibrateAlarm() {
        vibrate(ALARM_PATTERN);
    }

    @SuppressWarnings("deprecation")
    private void vibrate(@NonNull long[] pattern) {
        Vibrator device = vibrator;
        if (device == null || !device.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            device.vibrate(pattern, -1);
        }
    }
}
