package com.sullivan.protectee.service.alert;

import androidx.annotation.NonNull;

/**
 * 生成一个报警的幂等键。
 *
 * <p>幂等键是「同一次报警」的身份，不是「这次请求」的身份。服务端按
 * {@code (protecteeUid, idempotencyKey)} 唯一，重复提交返回**已有**那条报警。
 *
 * <p>这个类存在的唯一理由，是把「什么算同一次报警」这件事集中到一处：
 * 如果界面层各自拼键，就会出现「重试生成了新键」或者「两次不同的报警用了同一个键」
 * 两种错，而两种错的后果都很糟 —— 前者会打好几次电话，后者会让第二次报警被当成
 * 重复而**根本不发出去**。
 */
public final class AlertKeys {

    /**
     * 相邻两次报警至少间隔这么久才认为是**不同**的报警。
     *
     * <p>5 分钟：短于这个间隔的重复触发（用户连点、误触后又按了一次、端侧碰撞判定
     * 在同一个路口连续触发）都视为同一次，复用同一个键。长于它的则是新事件 ——
     * 一个人在同一个地点先后摔两次，第二次也必须能发出去。
     */
    public static final long SAME_ALERT_WINDOW_MS = 5 * 60 * 1000L;

    private AlertKeys() {
    }

    /**
     * 按「是谁 + 何时 + 什么类型」派生一个键。
     *
     * <p>用时间戳桶而不是随机串：随机串无法判断「上一次的键还在不在窗口内」，
     * 于是每次调用都会生成新键，幂等全部失效。用桶则天然满足「窗口内相同、窗口外不同」。
     *
     * @param protecteeUid 受护人账号 ID（同一账号在不同设备上重试也应命中同一条报警）
     * @param alertType    报警类型；不同类型即使同时发生也算两件事
     * @param nowMs        触发时刻
     */
    @NonNull
    public static String of(@NonNull String protecteeUid, @NonNull String alertType, long nowMs) {
        long bucket = nowMs / SAME_ALERT_WINDOW_MS;
        return alertType + '-' + bucket + '-' + safe(protecteeUid);
    }

    /**
     * 派生键，并在**上一条报警已结束时**强制换一个键。
     *
     * <p><b>这个方法修的是一个真实故障：受护人有时候发不出求助。</b>
     * 成因是纯时间桶的幂等键与「报警已解决」这件事冲突：
     * 用户在 5 分钟窗口内先误触一次、点了「我没事」（报警变 `RESOLVED`），
     * 随后**真的**需要求助时，{@link #of} 算出的仍是同一个键，
     * 服务端按 {@code (protecteeUid, idempotencyKey)} 唯一把它当成重复提交，
     * 直接返回那条**已结束的**旧报警。界面上看就是"按了没反应"。
     *
     * <p>判断依据交给调用方传入（{@code hasActiveAlert}）：
     * 手上还挂着一条未结束的报警 → 复用键（真正的重复提交）；
     * 已经没有活动报警 → 必须能开一条新的。
     *
     * @param hasActiveAlert 当前是否已经有一条未结束的报警
     */
    @NonNull
    public static String of(@NonNull String protecteeUid, @NonNull String alertType,
                            long nowMs, boolean hasActiveAlert) {
        String base = of(protecteeUid, alertType, nowMs);
        // 没有活动报警时加一个后缀，保证它与"窗口内复用"的键必然不同。
        // 用触发时刻本身（毫秒）而不是随机串：同一次点击重试仍应命中同一条。
        return hasActiveAlert ? base : base + "-n" + nowMs;
    }

    private static String safe(@NonNull String value) {
        // 键会进 VARCHAR(100) 与日志，去掉空白与可能影响日志可读性的字符。
        return value.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
