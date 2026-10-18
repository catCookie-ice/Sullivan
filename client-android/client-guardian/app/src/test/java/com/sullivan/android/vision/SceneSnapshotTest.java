package com.sullivan.android.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.vision.scene.SceneSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 快照的序列化契约。
 *
 * <p>用户的要求是"把初次追踪的信息转成 JSON 再交给算法处理"。落地时定的是：决策层**直接
 * 消费对象**，JSON 只做日志与回放。所以这里最关键的一条是"往返之后一模一样"——能精确回放，
 * 日志才有调参价值。
 */
public class SceneSnapshotTest {

    private static SceneSnapshot.TrackSnapshot track(int id, String label, float bottom,
                                                     float rate, float ttc, float centerXRate) {
        float height = bottom - 0.2f;
        return new SceneSnapshot.TrackSnapshot(id, label, 0.87f, 0.4f, bottom - height, 0.6f,
                bottom, rate, rate, ttc, height, centerXRate, 8, 9, false);
    }

    private static SceneSnapshot snapshot(float ego, float freeRatio,
                                          SceneSnapshot.TrackSnapshot... tracks) {
        List<SceneSnapshot.TrackSnapshot> list = new ArrayList<>();
        Collections.addAll(list, tracks);
        return new SceneSnapshot(123456789L, 720, 1280, 90, ego, freeRatio, list);
    }

    /** 往返必须**精确**等价，包括 hashCode。 */
    @Test
    public void jsonRoundTripIsExact() {
        SceneSnapshot original = snapshot(2.3333f, 0.8125f,
                track(3, "person", 0.85f, 0.42f, 1.42f, 0.07f),
                track(7, "car", 0.62f, -0.03f, Float.NaN, Float.NaN));

        SceneSnapshot restored = SceneSnapshot.fromJson(original.toJson());

        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
        assertEquals(original.tracks.get(0).label, restored.tracks.get(0).label);
        assertEquals(original.tracks.size(), restored.tracks.size());
    }

    /** NaN 不是合法 JSON：写成 null，读回来还得是 NaN，否则日志回放会静默变味。 */
    @Test
    public void notANumberSurvivesTheRoundTrip() {
        SceneSnapshot original = snapshot(Float.NaN, 1f,
                track(1, "person", 0.5f, Float.NaN, Float.NaN, Float.NaN));

        String json = original.toJson();
        assertTrue("NaN 必须写成 null：" + json, json.contains("null"));

        SceneSnapshot restored = SceneSnapshot.fromJson(json);
        assertTrue(Float.isNaN(restored.egoVelocityProxy));
        assertTrue(Float.isNaN(restored.tracks.get(0).rate));
        assertTrue(Float.isNaN(restored.tracks.get(0).ttc));
        assertTrue(Float.isNaN(restored.tracks.get(0).centerXRate));
        assertEquals(original, restored);
    }

    /** 极端数值也要精确往返：Float.MIN_VALUE 这类值最容易在格式化时被写成 0。 */
    @Test
    public void extremeFloatsRoundTrip() {
        SceneSnapshot original = snapshot(0f, Float.MIN_VALUE,
                track(1, "person", 1f, Float.MAX_VALUE, 1f / 3f, -0f));
        assertEquals(original, SceneSnapshot.fromJson(original.toJson()));
    }

    /**
     * 无穷大**不能**精确往返，会被读回 NaN——这是刻意接受的损失。
     *
     * <p>JSON 里没有 Infinity，只能写成 {@code null}；而 NaN 与 Infinity 在写法上完全一样，
     * 没有额外标记就分不出来。代价可以接受：走到这里的 Infinity 只会来自除法（1/0），
     * 语义上本来就是"算不出来"，也就是 NaN。把它钉下来，是为了让下一个人知道这是选择
     * 而不是疏漏；如果哪天真的要区分，得在字段里另开一个标记位。
     */
    @Test
    public void infinityDegradesToNotANumber() {
        SceneSnapshot original = snapshot(Float.POSITIVE_INFINITY, 1f);

        SceneSnapshot restored = SceneSnapshot.fromJson(original.toJson());

        assertTrue(Float.isNaN(restored.egoVelocityProxy));
        assertNotEquals(original, restored);
    }

    /** 类别名来自用户可导入的 class_names，可能含引号、反斜杠、换行和中文。 */
    @Test
    public void awkwardLabelsRoundTrip() {
        SceneSnapshot original = snapshot(1f, 0.5f,
                track(1, "行人\"带引号\\和换行\n", 0.9f, 0.1f, 2f, 0f));
        SceneSnapshot restored = SceneSnapshot.fromJson(original.toJson());
        assertEquals(original, restored);
    }

    /** 空轨迹列表是最常见的一帧（前方没人），不能成为特例。 */
    @Test
    public void emptyTrackListRoundTrips() {
        SceneSnapshot original = snapshot(Float.NaN, 1f);
        SceneSnapshot restored = SceneSnapshot.fromJson(original.toJson());
        assertEquals(0, restored.tracks.size());
        assertEquals(original, restored);
    }

    /** 快照必须真的不可变：谁改了都该抛异常，而不是悄悄改掉别人正在读的数据。 */
    @Test
    public void tracksAreDefensivelyCopied() {
        List<SceneSnapshot.TrackSnapshot> mutable = new ArrayList<>();
        mutable.add(track(1, "person", 0.8f, 0.2f, 2f, 0f));
        SceneSnapshot snapshot = new SceneSnapshot(1L, 1, 1, 0, 1f, 1f, mutable);

        mutable.clear();
        assertEquals("构造之后改原列表不该影响快照", 1, snapshot.tracks.size());

        try {
            snapshot.tracks.add(track(2, "car", 0.8f, 0.2f, 2f, 0f));
            throw new AssertionError("快照的轨迹列表必须是不可变的");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
    }

    /** equals 必须区分不同的内容，否则回放校验形同虚设。 */
    @Test
    public void equalsDistinguishesContent() {
        SceneSnapshot a = snapshot(1f, 0.5f, track(1, "person", 0.8f, 0.2f, 2f, 0f));
        SceneSnapshot b = snapshot(1f, 0.5f, track(1, "person", 0.8f, 0.2f, 2f, 0.01f));
        SceneSnapshot c = snapshot(1f, 0.5f, track(2, "person", 0.8f, 0.2f, 2f, 0f));

        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a, snapshot(1f, 0.5f, track(1, "person", 0.8f, 0.2f, 2f, 0f)));
    }

    /** -0.0 与 0.0 用 Float.compare 才能和 hashCode 自洽。 */
    @Test
    public void negativeZeroIsNotEqualToZero() {
        assertNotEquals(snapshot(0f, 1f), snapshot(-0f, 1f));
        assertEquals(snapshot(-0f, 1f), snapshot(-0f, 1f));
    }
}
