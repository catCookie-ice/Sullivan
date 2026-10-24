package com.sullivan.protectee.service.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 「等位置的报警」登记簿的测试。
 *
 * <p>对应一条硬性要求：**不能因为没拿到位置就一直等待而不报警。**
 * 这条测试锁三件事：
 * <ol>
 *   <li>多个等待者全部收到结果（谁都不能被覆盖掉）；</li>
 *   <li>没拿到位置时也要回调（传 null），而不是静默丢弃；</li>
 *   <li>回调会抛异常的调用方不牵连其他等待者。</li>
 * </ol>
 */
public class AlertLocationWaitersTest {

    /** 记录自己收到了什么。 */
    private static final class Recorder
            implements LocationReporter.AlertLocationCallback {
        final List<Location> results = new ArrayList<>();
        final boolean throwOnResult;

        Recorder() {
            this(false);
        }

        Recorder(boolean throwOnResult) {
            this.throwOnResult = throwOnResult;
        }

        @Override
        public void onResult(Location location) {
            results.add(location);
            if (throwOnResult) {
                throw new IllegalStateException("调用方自己的 bug");
            }
        }
    }

    /**
     * 两个等待者都要收到结果 —— 这是修掉"后登记覆盖先登记"的回归测试。
     *
     * <p>用单个字段实现时，第二个会挤掉第一个，第一条报警的回调永远不来，
     * 于是**那条报警永远发不出去**。这正好发生在用户连点求助的时刻。
     */
    @Test
    public void everyWaiterReceivesResult() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        waiters.register(first);
        waiters.register(second);
        assertEquals(2, waiters.size());

        Location location = new Location("test");
        int delivered = waiters.drain(location);

        assertEquals(2, delivered);
        assertEquals(1, first.results.size());
        assertEquals(1, second.results.size());
        assertSame(location, first.results.get(0));
        assertSame(location, second.results.get(0));
    }

    /**
     * 拿不到位置时**也必须回调**，参数为 null。
     *
     * <p>静默丢弃是这一条要求明确禁止的行为：调用方在等回调才发报警，
     * 不回调就等于"一直在等待而不报警"。
     */
    @Test
    public void nullLocationIsStillDelivered() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        Recorder recorder = new Recorder();
        waiters.register(recorder);

        assertEquals(1, waiters.drain(null));
        assertEquals(1, recorder.results.size());
        assertNull(recorder.results.get(0));
    }

    /** 结清之后列表为空，不会把同一次结果重复发给下一批。 */
    @Test
    public void drainClearsWaiters() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        waiters.register(new Recorder());

        assertEquals(1, waiters.drain(null));
        assertTrue(waiters.isEmpty());
        assertEquals(0, waiters.size());
        // 再结清一次什么也不做。
        assertEquals(0, waiters.drain(null));
    }

    /** 没有人等待时 drain 是安全的空操作。 */
    @Test
    public void drainWithNoWaitersIsNoOp() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        assertEquals(0, waiters.drain(null));
        assertTrue(waiters.isEmpty());
    }

    /**
     * 某个回调抛异常，不能牵连后面的等待者。
     *
     * <p>报警是救命的：一个调用方写错了，不该让下一个人的报警也发不出去。
     */
    @Test
    public void throwingWaiterDoesNotBlockOthers() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        Recorder bad = new Recorder(true);
        Recorder good = new Recorder();

        waiters.register(bad);
        waiters.register(good);

        int delivered = waiters.drain(null);

        // 抛异常那个没算进 delivered，但它确实被尝试过。
        assertEquals(1, bad.results.size());
        assertEquals(1, good.results.size());
        assertEquals(1, delivered);
        assertTrue(waiters.isEmpty());
    }

    /**
     * 回调里重新登记（用户连点），新登记必须进入干净的一份，
     * 不能被本次 drain 顺手吃掉或漏掉。
     */
    @Test
    public void registerDuringCallbackGoesToFreshBatch() {
        AlertLocationWaiters waiters = new AlertLocationWaiters();
        Recorder second = new Recorder();
        List<Object> order = new ArrayList<>();

        LocationReporter.AlertLocationCallback reentrant = location -> {
            order.add("first");
            waiters.register(second);
        };

        waiters.register(reentrant);
        waiters.drain(null);

        assertEquals(List.of("first"), order);
        // 新登记的那个还在等，等着下一次 drain。
        assertEquals(1, waiters.size());
        assertFalse(waiters.isEmpty());

        waiters.drain(null);
        assertEquals(1, second.results.size());
        assertTrue(waiters.isEmpty());
    }
}
