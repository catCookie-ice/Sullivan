package sullivan.serverjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sullivan.serverjava.domain.Alert;
import sullivan.serverjava.domain.AlertDelivery;
import sullivan.serverjava.domain.RealtimeSession;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 扫描「到点该自动呼人」的报警，按 {@code dispatch_order} **依次**向监护人发起会话。
 *
 * <p><b>为什么是扫库而不是 {@code Thread.sleep(5)}。</b>创建报警的请求线程不能睡 5 秒
 * （占着 Web 线程，且服务一重启这个「定时」就没了）。把到期时刻写进
 * {@code alert.auto_session_due_at}，由这个每秒跑一次的任务去扫，重启后仍在，多实例
 * 部署也不会重复呼人 —— 靠 {@link AlertService#claimForAutoSession} 的条件更新抢占。
 *
 * <p><b>为什么每秒一次。</b>产品的口径是「报警出现 5 秒后」，误差要肉眼不可辨。
 * 每分钟扫一次会让实际延迟变成 5–65 秒，那不是同一个功能。查询走
 * {@code ix_alert_auto_session_due}，正常情况下每次都返回空集，代价可以忽略。
 *
 * <p><b>「依次」的含义。</b>按 {@code dispatch_order} 升序**逐个**发起，顺序固定且
 * 可复现；不是并发轰炸全部监护人。这样第一个接起来的监护人会先看到请求，
 * 排在后面的人看到的是「已经有人接了」，也就不会全部同时响铃。
 */
@Component
public class AlertAutoDispatchJob {
    private static final Logger log = LoggerFactory.getLogger(AlertAutoDispatchJob.class);

    /**
     * 每轮最多处理多少条报警。
     *
     * <p>这个上限不是为了性能，是为了**故障时不失控**：如果因为某个 bug 让一批历史
     * 报警同时到期，无上限的扫描会在一秒内给所有人打电话。分批处理，最坏情况下
     * 也只是慢几秒。
     */
    private static final int BATCH_LIMIT = 20;

    private final AlertService alertService;

    public AlertAutoDispatchJob(AlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${app.alert.auto-session-scan-ms:1000}")
    public void scan() {
        List<Alert> due;
        try {
            due = alertService.dueAutoSessions(LocalDateTime.now(), BATCH_LIMIT);
        } catch (RuntimeException error) {
            // 定时任务里抛异常会被 Spring 记日志但不会停止后续调度；这里显式接住，
            // 保证「数据库抖一下」不会把日志刷成一堆堆栈。
            log.warn("扫描待自动呼叫的报警失败", error);
            return;
        }
        for (Alert alert : due) {
            try {
                dispatchOne(alert);
            } catch (RuntimeException error) {
                // 单条报警失败不能拖垮同批的其他报警：每个受护人的紧急程度是并列的。
                log.warn("报警 {} 自动呼叫失败", alert.getId(), error);
            }
        }
    }

    /** 处理一条报警：抢占 → 逐个监护人发起会话 → 收尾。 */
    void dispatchOne(Alert alert) {
        if (!alertService.claimForAutoSession(alert.getId())) {
            // 别的实例（或上一轮）已经抢走了。安静退出，不重复呼人。
            return;
        }
        List<AlertDelivery> deliveries = alertService.deliveriesOf(alert.getId());
        if (deliveries.isEmpty()) {
            // 排了期却没有收件人：理论上不会发生（route 阶段就不排期），
            // 但真发生了也不能让状态卡在 RUNNING。
            log.warn("报警 {} 有排期但无投递记录", alert.getId());
            alertService.finishAutoSession(alert.getId());
            return;
        }

        int opened = 0;
        for (AlertDelivery delivery : deliveries) {
            if ("SENT".equals(delivery.getStatus())) {
                // 已经建过会话（重试路径），不再重复。
                opened++;
                continue;
            }
            RealtimeSession session = alertService.dispatchSession(alert.getId(), delivery);
            if (session != null) {
                opened++;
            }
        }
        log.info("报警 {} 自动呼叫完成：{} / {} 名监护人已建立会话",
                alert.getId(), opened, deliveries.size());
        alertService.finishAutoSession(alert.getId());
    }
}
