package com.sullivan.android.ui.guardian.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sullivan.android.data.api.dto.RealtimeDtos;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「哪些会话是正在呼叫我」的回归测试。
 *
 * <p>这一组锁的是一个真实故障：受护人报警后服务端**确实建了会话**
 * （`alert`、`alert_delivery`、`realtime_session` 都有记录），
 * 但监护人手机上什么都不响 —— 因为客户端只在用户手动点开「实时会话」页时
 * 才去拉列表，没有任何东西主动发现"有人在呼叫我"。
 *
 * <p>{@link IncomingCallFilter} 是补上的那个判断。它必须同时做对三件事：
 * 只提醒**待接受**的、只提醒**发给我的**、并且**不把别人发给我的过时状态**
 * 当成新来电重复弹。
 */
public class IncomingCallFilterTest {

    private static final String ME = "u_guardian";
    private static final String PROTECTEE = "u_protectee";

    private static RealtimeDtos.SessionResponse session(String id, String status,
                                                        String initiator, String target) {
        RealtimeDtos.SessionResponse response = new RealtimeDtos.SessionResponse();
        response.id = id;
        response.status = status;
        response.initiatorUid = initiator;
        response.targetUid = target;
        response.sessionType = "BASIC";
        return response;
    }

    /** 受护人呼叫我、状态 REQUESTED —— 这就是必须弹出来的那一类。 */
    @Test
    public void requestedTowardsMeIsIncoming() {
        List<RealtimeDtos.SessionResponse> result = IncomingCallFilter.pending(
                Arrays.asList(session("s1", "REQUESTED", PROTECTEE, ME)), ME);
        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).id);
    }

    /**
     * 我发起的会话不算来电。
     *
     * <p>列表接口同时返回"我发起的"和"发给我的"。若不筛，
     * 监护人自己给别人打的电话会让自己手机弹出"有人正在呼叫你"。
     */
    @Test
    public void myOwnOutgoingIsNotIncoming() {
        List<RealtimeDtos.SessionResponse> result = IncomingCallFilter.pending(
                Arrays.asList(session("s1", "REQUESTED", ME, PROTECTEE)), ME);
        assertTrue(result.isEmpty());
    }

    /**
     * 已经被接受/拒绝/结束的会话不该再提醒。
     *
     * <p>否则一次通话结束后，那一行记录会永远留在列表里，
     * 每次轮询都重新弹一次"有人呼叫你"。
     */
    @Test
    public void nonRequestedStatusesAreNotIncoming() {
        List<RealtimeDtos.SessionResponse> sessions = Arrays.asList(
                session("s1", "ACTIVE", PROTECTEE, ME),
                session("s2", "REJECTED", PROTECTEE, ME),
                session("s3", "ENDED", PROTECTEE, ME));
        assertTrue(IncomingCallFilter.pending(sessions, ME).isEmpty());
    }

    /** 发给别人的会话与我无关，不能弹。 */
    @Test
    public void otherPeoplesSessionsAreIgnored() {
        List<RealtimeDtos.SessionResponse> result = IncomingCallFilter.pending(
                Arrays.asList(session("s1", "REQUESTED", PROTECTEE, "u_someone_else")), ME);
        assertTrue(result.isEmpty());
    }

    /**
     * 空输入、null 都安全返回空列表（不返回 null）。
     *
     * <p>轮询是后台跑的，任何一次异常都只会安静地让这一次检测失效；
     * 但返回 null 会让调用方在 `isEmpty()` 上崩掉，反而更糟。
     */
    @Test
    public void nullAndEmptyInputsAreSafe() {
        assertTrue(IncomingCallFilter.pending(null, ME).isEmpty());
        assertTrue(IncomingCallFilter.pending(new ArrayList<>(), ME).isEmpty());
        assertTrue(IncomingCallFilter.pending(
                Arrays.asList(session("s1", "REQUESTED", PROTECTEE, ME)), null).isEmpty());
        assertTrue(IncomingCallFilter.pending(
                Arrays.asList(session("s1", "REQUESTED", PROTECTEE, ME)), "").isEmpty());
    }

    /**
     * 一条没有 id 的会话被跳过。
     *
     * <p>id 是"已经提醒过"的去重键（`alertedSessionIds`）。没有 id 就无法去重，
     * 让它进来会导致每次轮询都弹一次。
     */
    @Test
    public void sessionWithoutIdIsSkipped() {
        assertTrue(IncomingCallFilter.pending(
                Arrays.asList(session(null, "REQUESTED", PROTECTEE, ME)), ME).isEmpty());
    }

    /** 列表里混着多条时，只挑出真正属于我的那些，且保持原顺序。 */
    @Test
    public void mixedListKeepsOnlyMineInOrder() {
        List<RealtimeDtos.SessionResponse> sessions = Arrays.asList(
                session("s1", "REQUESTED", PROTECTEE, ME),
                session("s2", "REQUESTED", ME, PROTECTEE),
                session("s3", "ENDED", PROTECTEE, ME),
                session("s4", "REQUESTED", "u_other_protectee", ME));
        List<RealtimeDtos.SessionResponse> result = IncomingCallFilter.pending(sessions, ME);
        assertEquals(2, result.size());
        assertEquals("s1", result.get(0).id);
        assertEquals("s4", result.get(1).id);
    }
}
