package com.sullivan.android.vision.track;

/**
 * 一条轨迹最近的观测序列，用预分配的环形缓冲存住。
 *
 * <p>存的是**归一化高度**而不是面积：`h = f·H/Z ⇒ ln h = ln(fH) − ln Z`，只含一条边的
 * 噪声；`A = w·h` 会把宽度的噪声也混进来，而宽度对姿态和遮挡敏感得多（人一转身宽度就变），
 * 会凭空造出尺度变化。
 *
 * <p>之所以存原始量（高度、底边、中心横坐标）而不是预先算好的 `ln h`，是因为遮挡与裁切
 * 判断要用原始高度，而且 `ln h` 可以从高度随时算出——这里索性两者都留着，反正只有几十个
 * float。
 *
 * <p>不是线程安全的：只允许推理线程访问（整条管线都在那一个线程上）。
 */
public final class TrackWindow {

    /**
     * 容量按用户可配的最大窗口时长与可能达到的最高帧率取。
     * 1.2s 窗口在 15 FPS 下约 18 个样本、30 FPS 下约 36 个；取 32 时最老的一两个会被挤掉，
     * 窗口跨度仍有 1s 左右，够拟合用。
     */
    public static final int DEFAULT_CAPACITY = 32;

    private final long[] times;
    private final float[] heights;
    private final float[] bottoms;
    private final float[] centerXs;
    private final float[] scores;
    private final float[] lnHeights;

    /** 最旧样本所在的下标。 */
    private int start;
    private int count;

    public TrackWindow() {
        this(DEFAULT_CAPACITY);
    }

    public TrackWindow(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正数：" + capacity);
        }
        times = new long[capacity];
        heights = new float[capacity];
        bottoms = new float[capacity];
        centerXs = new float[capacity];
        scores = new float[capacity];
        lnHeights = new float[capacity];
    }

    /**
     * 追加一个观测。缓冲满时挤掉最旧的那个。
     *
     * <p>调用方需要保证时间单调不减；乱序的时间戳会让最小二乘拟合出荒谬的斜率。
     */
    public void add(long timeMs, float height, float bottom, float centerX, float score) {
        if (count > 0 && timeMs < timeMs(count - 1)) {
            // 时间倒流只可能来自取错时间戳或换了时钟基准。丢掉这一帧总比污染整条拟合好。
            return;
        }
        int capacity = times.length;
        if (count == capacity) {
            start = (start + 1) % capacity;
            count--;
        }
        int index = (start + count) % capacity;
        times[index] = timeMs;
        heights[index] = height;
        bottoms[index] = bottom;
        centerXs[index] = centerX;
        scores[index] = score;
        lnHeights[index] = height > 0f ? (float) Math.log(height) : Float.NaN;
        count++;
    }

    /**
     * 丢掉超过 {@code maxAgeMs} 的旧样本。
     *
     * <p>窗口用**时间**而不是样本数来定长：帧率会随设备负载在 5–15 FPS 之间浮动，
     * 按样本数取窗口会让"1.2 秒"在低帧率下变成"3 秒"，速度估计的时间尺度跟着漂。
     *
     * @return 丢掉的样本数
     */
    public int prune(long nowMs, long maxAgeMs) {
        long cutoff = nowMs - maxAgeMs;
        int removed = 0;
        while (count > 0 && timeMs(0) < cutoff) {
            start = (start + 1) % times.length;
            count--;
            removed++;
        }
        return removed;
    }

    public void clear() {
        start = 0;
        count = 0;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int capacity() {
        return times.length;
    }

    /** 第 {@code i} 个样本（0 = 最旧）。 */
    public long timeMs(int i) {
        return times[indexOf(i)];
    }

    public float height(int i) {
        return heights[indexOf(i)];
    }

    /** 框底边的归一化纵坐标，用于判断是否贴到画面底部。 */
    public float bottom(int i) {
        return bottoms[indexOf(i)];
    }

    public float centerX(int i) {
        return centerXs[indexOf(i)];
    }

    public float score(int i) {
        return scores[indexOf(i)];
    }

    /** {@code ln(高度)}；高度非正时为 NaN。 */
    public float lnHeight(int i) {
        return lnHeights[indexOf(i)];
    }

    /** 最旧样本的时刻；空窗口返回 {@link Long#MIN_VALUE}。 */
    public long oldestTimeMs() {
        return count == 0 ? Long.MIN_VALUE : timeMs(0);
    }

    /** 最新样本的时刻；空窗口返回 {@link Long#MIN_VALUE}。 */
    public long newestTimeMs() {
        return count == 0 ? Long.MIN_VALUE : timeMs(count - 1);
    }

    /** 窗口的时间跨度（毫秒）；样本不足两个时为 0。 */
    public long spanMs() {
        return count < 2 ? 0L : timeMs(count - 1) - timeMs(0);
    }

    private int indexOf(int i) {
        if (i < 0 || i >= count) {
            throw new IndexOutOfBoundsException("样本下标越界：" + i + " / " + count);
        }
        return (start + i) % times.length;
    }
}
