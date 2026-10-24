package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.Alert;
import sullivan.serverjava.domain.AlertDelivery;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.domain.UserAccount;
import sullivan.serverjava.mapper.AlertDeliveryMapper;
import sullivan.serverjava.mapper.AlertMapper;
import sullivan.serverjava.mapper.RealtimeSessionMapper;
import sullivan.serverjava.mapper.UserAccountMapper;

/**
 * 为一条报警投递创建实时会话。
 *
 * <p><b>为什么不复用 {@link RealtimeSessionService#create}。</b>那个方法的入口是
 * 「用户主动点发起」，它把「已存在进行中会话」当成 409 错误抛给用户。而这里是
 * 系统代为发起：已存在会话不是错误，恰恰说明**已经有人在呼叫了**，此时应当把
 * 已有会话记在投递上而不是再造一条，更不能把 409 冒泡成「派发失败」。
 *
 * <p><b>会话的发起方是受护人。</b>报警要表达的是「他在向你求助」，所以
 * {@code initiator_uid = 受护人}、{@code target_uid = 监护人}。这决定了监护人侧
 * 收到的是「接听」而不是「呼叫」。
 */
@Service
public class RealtimeAlertDispatcher {

    private final RealtimeSessionMapper sessionMapper;
    private final AlertDeliveryMapper deliveryMapper;
    private final AlertMapper alertMapper;
    private final UserAccountMapper userAccountMapper;

    public RealtimeAlertDispatcher(RealtimeSessionMapper sessionMapper,
                                   AlertDeliveryMapper deliveryMapper,
                                   AlertMapper alertMapper,
                                   UserAccountMapper userAccountMapper) {
        this.sessionMapper = sessionMapper;
        this.deliveryMapper = deliveryMapper;
        this.alertMapper = alertMapper;
        this.userAccountMapper = userAccountMapper;
    }

    /**
     * 为一条投递创建（或复用）实时会话，并把结果写回投递记录。
     *
     * @return 已存在的或新建的会话；无法创建时返回 {@code null}，失败原因写进投递
     */
    @Transactional
    public RealtimeSession openSession(String alertId, AlertDelivery delivery) {
        Alert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            markFailed(delivery, "报警不存在");
            return null;
        }
        String protecteeUid = alert.getProtecteeUid();
        String guardianUid = delivery.getReceiverPrincipalId();

        UserAccount guardian = userAccountMapper.selectById(guardianUid);
        if (guardian == null || !"ENABLED".equals(guardian.getStatus())) {
            // 账号被禁用不该阻塞其余监护人：标记这一条失败，继续下一个。
            markFailed(delivery, "监护人账号不可用");
            return null;
        }

        RealtimeSession existing = findLive(protecteeUid, guardianUid);
        if (existing != null) {
            // 已经有一条在响（可能是监护人先打的，也可能是上一轮建的）。复用它，
            // 视为投递成功 —— 目的（让双方连上）已经达到。
            attachSession(delivery, existing.getId(), "SENT");
            return existing;
        }

        RealtimeSession session = new RealtimeSession();
        session.setId(IdGenerator.next());
        session.setStatus("REQUESTED");
        session.setInitiatorUid(protecteeUid);
        session.setTargetUid(guardianUid);
        session.setSessionType("BASIC");
        sessionMapper.insert(session);

        attachSession(delivery, session.getId(), "SENT");
        return session;
    }

    /** 查找这两人之间尚未结束的会话（任一方向）。 */
    private RealtimeSession findLive(String protecteeUid, String guardianUid) {
        return sessionMapper.selectOne(Wrappers.<RealtimeSession>lambdaQuery()
                .and(w -> w.and(x -> x.eq(RealtimeSession::getInitiatorUid, protecteeUid)
                                .eq(RealtimeSession::getTargetUid, guardianUid))
                        .or().and(x -> x.eq(RealtimeSession::getInitiatorUid, guardianUid)
                                .eq(RealtimeSession::getTargetUid, protecteeUid)))
                .in(RealtimeSession::getStatus, "REQUESTED", "ACTIVE")
                .last("LIMIT 1"));
    }

    private void markFailed(AlertDelivery delivery, String reason) {
        delivery.setStatus("FAILED");
        delivery.setFailureReason(reason);
        deliveryMapper.updateById(delivery);
    }

    private void attachSession(AlertDelivery delivery, String sessionId, String status) {
        delivery.setStatus(status);
        delivery.setSessionId(sessionId);
        delivery.setFailureReason(null);
        deliveryMapper.updateById(delivery);
    }
}
