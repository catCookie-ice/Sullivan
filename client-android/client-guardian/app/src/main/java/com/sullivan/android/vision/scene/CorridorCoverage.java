package com.sullivan.android.vision.scene;

import androidx.annotation.NonNull;

import com.sullivan.android.vision.detect.Detection;

import java.util.Arrays;
import java.util.List;

/**
 * 前正方"通路"被遮挡了多少——一个**场景测量**，不是提示判断。
 *
 * <p>script.py 里靠全景分割得到路面掩膜，直接算可通行区域占比；YOLO 检测没有掩膜，
 * 所以这里退而求其次：把画面下半部的中央走廊切成栅格，统计被障碍框覆盖的面积比例，
 * 取补集当作"前方通路"占比。这是启发式估计，不是真实的通行区域测量。
 *
 * <p>放在感知层（{@code scene} 包）而不是提示层，是因为它描述的是画面本身；提示层
 * 只是拿这个数字去决定说哪句话。
 *
 * <p>不是线程安全的：栅格是复用数组，只允许单线程（推理线程）访问。
 */
public final class CorridorCoverage {

    /** "前方通路"的统计范围：画面下半部的中央走廊。 */
    public static final float CORRIDOR_LEFT = 0.20f;
    public static final float CORRIDOR_RIGHT = 0.80f;
    public static final float CORRIDOR_TOP = 0.55f;

    /** 栅格分辨率。32×16 时单个格子约 1.6% 的走廊面积，够用且只扫一遍。 */
    private static final int GRID_WIDTH = 32;
    private static final int GRID_HEIGHT = 16;

    /** 复用的栅格，避免每帧分配。 */
    private final boolean[] grid = new boolean[GRID_WIDTH * GRID_HEIGHT];

    /**
     * 统计中央走廊里没有被任何框覆盖的面积比例。
     *
     * @return 1 表示完全畅通，0 表示整条走廊都被挡住。
     */
    public float freeRatio(@NonNull List<Detection> obstacles) {
        Arrays.fill(grid, false);
        int total = 0;
        int blocked = 0;
        for (int gy = 0; gy < GRID_HEIGHT; gy++) {
            float cellY = (gy + 0.5f) / GRID_HEIGHT;
            for (int gx = 0; gx < GRID_WIDTH; gx++) {
                float cellX = (gx + 0.5f) / GRID_WIDTH;
                if (cellX < CORRIDOR_LEFT || cellX > CORRIDOR_RIGHT || cellY < CORRIDOR_TOP) {
                    continue;
                }
                total++;
                for (Detection obstacle : obstacles) {
                    if (cellX >= obstacle.left && cellX <= obstacle.right
                            && cellY >= obstacle.top && cellY <= obstacle.bottom) {
                        blocked++;
                        break;
                    }
                }
            }
        }
        return total == 0 ? 1f : 1f - blocked / (float) total;
    }

    /** 候选点是否落在走廊里。给"横穿目标一秒后会不会进入走廊"这类预测用。 */
    public static boolean isInCorridor(float centerX, float bottom) {
        return centerX >= CORRIDOR_LEFT && centerX <= CORRIDOR_RIGHT && bottom >= CORRIDOR_TOP;
    }
}
