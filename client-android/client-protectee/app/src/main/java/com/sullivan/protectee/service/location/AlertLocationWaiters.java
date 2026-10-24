package com.sullivan.protectee.service.location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「等位置的那些报警」的登记簿。
 *
 * <p><b>为什么要把这件小事单独抽出来。</b>它看起来只是一个列表，但它守着一条
 * 不能破的底线：**任何一个登记过的报警，最终都必须收到一次结果**。
 * 因为调用方拿到结果之前不会发报警请求 —— 少了这次回调，
 * 那条报警就永远发不出去。
 *
 * <p>把它抽成纯类（不碰 Android 的 {@code LocationManager}）才能单测，
 * 而这段逻辑恰恰是最需要测的：它有两个互相冲突的要求 ——
 * 「多个等待者共享一次定位尝试」和「谁都不能被漏掉」。
 * 用单个字段实现时，后登记的会覆盖先登记的，先登记那条报警就哑了。
 *
 * <p><b>线程约束：</b>全部方法在同一个 Looper 线程调用
 * （{@code LocationReporter} 的定位回调与超时都在主线程），
 * 但 {@link #drain} 与 {@link #register} 之间仍加锁 —— 定位回调来自
 * {@code LocationManager}，不保证与超时那个 Runnable 严格串行。
 */
final class AlertLocationWaiters {

    private final List<LocationReporter.AlertLocationCallback> waiters = new ArrayList<>();

    /**
     * 登记一个等待者。
     *
     * <p>重复登记同一个对象不会去重：回调方各自有自己的状态要推进，
     * 少回调一次比多回调一次的后果严重得多（多条报警 vs 一条报警之外的浪费）。
     * 真正的防重复由调用方的幂等键负责。
     */
    void register(@NonNull LocationReporter.AlertLocationCallback callback) {
        synchronized (waiters) {
            waiters.add(callback);
        }
    }

    /** 是否还有人在等。 */
    boolean isEmpty() {
        synchronized (waiters) {
            return waiters.isEmpty();
        }
    }

    /** 当前等待者数量（测试用）。 */
    int size() {
        synchronized (waiters) {
            return waiters.size();
        }
    }

    /**
     * 结清所有等待者：取走并清空列表，逐个回调。
     *
     * <p><b>先取走再回调。</b>回调里可能又触发一次报警（用户连点），
     * 那次登记必须进到一份干净的列表里，而不是混进正在遍历的这一份 ——
     * 否则它会收到一个"已经用过的"位置，或者更糟：被下一次 drain 漏掉。
     *
     * <p>单个回调抛异常不影响其余等待者：报警是救命的，
     * 不能因为一个调用方写错了就让它后面那个人的报警也发不出去。
     *
     * @param location 可用于报警的位置；为 null 表示没拿到（**也必须回调**）
     * @return 实际回调的数量
     */
    int drain(@Nullable android.location.Location location) {
        List<LocationReporter.AlertLocationCallback> current;
        synchronized (waiters) {
            if (waiters.isEmpty()) {
                return 0;
            }
            current = new ArrayList<>(waiters);
            waiters.clear();
        }
        int delivered = 0;
        for (LocationReporter.AlertLocationCallback waiter : current) {
            try {
                waiter.onResult(location);
                delivered++;
            } catch (RuntimeException ignored) {
                // 单个回调炸了不能牵连别人，继续。
            }
        }
        return delivered;
    }
}
