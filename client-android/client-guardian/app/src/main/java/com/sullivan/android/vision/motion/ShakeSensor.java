package com.sullivan.android.vision.motion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 陀螺仪到 {@link ShakeDetector} 之间的薄适配层。
 *
 * <p><b>为什么自己开一个线程而不是用主线程的 Looper</b>：采样间隔是判断"乱不乱"的原始数据。
 * 主线程一旦卡一下（合成一帧界面、切模型、GC），那一批传感器事件会挤在一起送达，本来均匀的
 * 20 毫秒变成"30、30、5、5"，反转间隔的极值比立刻失真——而那个比值正是判据的核心。单独一条
 * {@link HandlerThread} 只做这件事，抖动小得多。
 *
 * <p><b>采样率用 {@code SENSOR_DELAY_GAME}（20 毫秒 / 50 Hz）</b>：{@code SENSOR_DELAY_UI}
 * 的 60 毫秒在一个 1.5 秒的窗口里只剩 25 个样本，反转点必然数漏。50 Hz 也远够不上
 * {@code HIGH_SAMPLING_RATE_SENSORS} 的门槛，不需要任何权限。
 *
 * <p><b>不用 {@code SENSOR_DELAY_FASTEST}，也不要充电批</b>（{@code maxReportLatencyUs = 0}）：
 * 陀螺仪是这套判据唯一的输入，晚到 200 毫秒的批量数据对"尽快报警"没有任何意义。
 *
 * <p>生命周期由界面在 {@code onResume} / {@code onPause} 里驱动。报警本身**不**随
 * {@code onPause} 停止——用户可能把手机掉在地上，屏幕熄了、应用退到后台，报警必须继续。
 */
public final class ShakeSensor implements SensorEventListener {

    private static final String TAG = "ShakeSensor";
    private static final String THREAD_NAME = "shake-sensor";

    /** 晃动触发的回调。 */
    public interface Listener {
        /**
         * 判出一次"剧烈且无规则的晃动"。
         *
         * <p><b>在传感器线程上回调</b>，实现方自己切到自己的线程（界面就直接 post 回主线程）。
         */
        void onShake(long nowMs);
    }

    @Nullable
    private final SensorManager sensorManager;
    @Nullable
    private final Sensor gyroscope;
    @NonNull
    private final Listener listener;

    /** 只在传感器线程上访问。 */
    private final ShakeDetector detector = new ShakeDetector();

    @Nullable
    private HandlerThread thread;
    @Nullable
    private Handler handler;

    public ShakeSensor(@NonNull Context context, @NonNull Listener listener) {
        this.listener = listener;
        Context appContext = context.getApplicationContext();
        this.sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        this.gyroscope = sensorManager == null
                ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    /**
     * 开始采样。重复调用是安全的。
     *
     * @return {@code false} 表示这台设备没有陀螺仪，晃动报警不可用——调用方应当告诉用户
     *         这件事（报警功能静默失效比报警误报更糟）
     */
    public boolean start() {
        SensorManager manager = sensorManager;
        if (manager == null || gyroscope == null) {
            Log.w(TAG, "设备没有陀螺仪：剧烈晃动报警不可用");
            return false;
        }
        if (thread != null) {
            return true;
        }
        HandlerThread created = new HandlerThread(THREAD_NAME);
        created.start();
        Handler createdHandler = new Handler(created.getLooper());
        thread = created;
        handler = createdHandler;
        // 传感器时间戳是"从开机算起"的，但 resuming 之后第一段一定是新的：清掉上次的尾巴。
        createdHandler.post(detector::clear);
        // 五个参数的重载：最后一个参数决定事件投递到哪条线程。
        manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, 0, createdHandler);
        return true;
    }

    /** 停止采样。没有陀螺仪、或者本来就没在跑，都安全。 */
    public void stop() {
        SensorManager manager = sensorManager;
        if (manager != null) {
            manager.unregisterListener(this);
        }
        HandlerThread current = thread;
        if (current != null) {
            current.quitSafely();
            thread = null;
            handler = null;
        }
    }

    /**
     * 从现在起 {@code durationMs} 内不做判定。用户一碰屏幕就调它。
     *
     * <p>没在采样时直接丢掉：没有输入就没有可压制的东西，而下一次 {@link #start()} 会清空历史。
     * 这样 {@link ShakeDetector} 始终只被传感器线程碰，不需要加锁。
     */
    public void suppressUntil(long nowMs, long durationMs) {
        Handler current = handler;
        if (current != null) {
            current.post(() -> detector.suppressUntil(nowMs, durationMs));
        }
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
            return;
        }
        float[] values = event.values;
        if (detector.addSample(values[0], values[1], values[2], event.timestamp,
                SystemClock.elapsedRealtime())) {
            listener.onShake(SystemClock.elapsedRealtime());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 陀螺仪的精度变化与判据无关：判据只看角速度本身，不看它准不准。
    }
}
