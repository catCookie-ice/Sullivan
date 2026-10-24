package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.domain.Alert;
import sullivan.serverjava.domain.AlertDelivery;
import sullivan.serverjava.domain.LatestLocation;
import sullivan.serverjava.domain.Notification;
import sullivan.serverjava.domain.RealtimeSession;
import sullivan.serverjava.mapper.AlertDeliveryMapper;
import sullivan.serverjava.mapper.AlertMapper;
import sullivan.serverjava.mapper.NotificationMapper;
import sullivan.serverjava.web.AlertDtos.AlertCreateRequest;
import sullivan.serverjava.web.AlertDtos.AlertDeliveryView;
import sullivan.serverjava.web.AlertDtos.AlertLocation;
import sullivan.serverjava.web.AlertDtos.AlertResponse;
import sullivan.serverjava.web.AlertDtos;
import sullivan.serverjava.web.AlertDtos.LocationReportRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 报警的创建、路由与「5 秒后自动依次呼叫监护人」。
 *
 * <p><b>职责边界。</b>本类只负责报警的生命周期与投递记录；真正的实时会话创建委托给
 * {@link RealtimeAlertDispatcher}，位置读取委托给 {@link LocationService}。
 * 把「该不该呼人」和「怎么呼」拆开，是因为两者的失败方式完全不同：
 * 前者是产品规则（开关关了就跳过），后者是基础设施问题（会话已存在、目标不可用）。
 *
 * <p><b>报警先落库再路由。</b>路由过程中任何一步失败，报警本身必须已经在库里 ——
 * 报警是最重要的证据，不能因为后续投递失败而丢失。
 *
 * <p><b>没有生效监护人时怎么办。</b>{@code route_type = NONE}、{@code status = NO_RECIPIENT}，
 * 并如实记录 {@code route_note}。不降级为「广播给附近志愿者」：
 * 那是 [FULL] 的 FR-14，本版没有志愿者在线状态与位置索引，硬做会变成乱发给陌生人。
 */
@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 与 alert.status 的 CHECK 一致。 */
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_NO_RECIPIENT = "NO_RECIPIENT";

    /** 与 alert.auto_session_state 的 CHECK 一致。 */
    public static final String AUTO_NONE = "NONE";
    public static final String AUTO_SCHEDULED = "SCHEDULED";
    public static final String AUTO_RUNNING = "RUNNING";
    public static final String AUTO_SKIPPED = "SKIPPED";
    public static final String AUTO_DONE = "DONE";

    private final AlertMapper alertMapper;
    private final AlertDeliveryMapper deliveryMapper;
    private final NotificationMapper notificationMapper;
    private final BindingService bindingService;
    private final LocationService locationService;
    private final AlertSettingService settingService;
    private final RealtimeAlertDispatcher dispatcher;

    public AlertService(AlertMapper alertMapper, AlertDeliveryMapper deliveryMapper,
                        NotificationMapper notificationMapper, BindingService bindingService,
                        LocationService locationService, AlertSettingService settingService,
                        RealtimeAlertDispatcher dispatcher) {
        this.alertMapper = alertMapper;
        this.deliveryMapper = deliveryMapper;
        this.notificationMapper = notificationMapper;
        this.bindingService = bindingService;
        this.locationService = locationService;
        this.settingService = settingService;
        this.dispatcher = dispatcher;
    }

    /**
     * 创建一条报警并完成路由。
     *
     * <p>幂等：同一个 {@code protecteeUid + idempotencyKey} 重复提交返回**已有**的那条
     * 报警，而不是再建一条。弱网重试与用户连点两次都很常见，重复报警意味着重复呼叫，
     * 那比不呼叫更糟 —— 监护人会被同一件事打好几次电话。
     */
    @Transactional
    public AlertResponse create(String protecteeUid, AlertCreateRequest request) {
        Alert existing = alertMapper.selectOne(Wrappers.<Alert>lambdaQuery()
                .eq(Alert::getProtecteeUid, protecteeUid)
                .eq(Alert::getIdempotencyKey, request.idempotencyKey()));
        if (existing != null) {
            return toResponse(existing);
        }

        Alert alert = new Alert();
        alert.setId(IdGenerator.next());
        alert.setProtecteeUid(protecteeUid);
        alert.setIdempotencyKey(request.idempotencyKey());
        alert.setAlertType(request.alertType());
        alert.setStatus(STATUS_CREATED);

        // 位置快照在创建时就固化下来：latest_location 会被后续上报覆盖，
        // 而报警详情必须能还原「报警那一刻他在哪」。
        //
        // **优先用请求里带上来的实时位置**，只有在没带的时候才回落到缓存。
        // 这个顺序是刻意的：缓存行可能是几分钟前的（实测过 8 分钟），
        // 而报警是要拿去找人的 —— 旧坐标比没有坐标更糟，会让人往错的方向赶。
        // 回落只保留给「客户端确实拿不到定位」的情况（没权限、室内无信号），
        // 此时几分钟前的坐标仍是比没有强的参考。
        boolean usedLiveLocation = applyRequestLocation(alert, request.location());
        if (!usedLiveLocation) {
            LatestLocation latest = locationService.loadEntity(protecteeUid);
            if (latest != null && "AVAILABLE".equals(latest.getStatus())) {
                alert.setLatitude(latest.getLatitude());
                alert.setLongitude(latest.getLongitude());
                alert.setLocationCapturedAt(latest.getCapturedAt());
            }
        }

        route(alert);
        scheduleAutoSession(alert);
        alertMapper.insert(alert);

        // 投递记录必须在 alert 落库之后插入：alert_delivery.alert_id 有外键。
        List<String> guardians = bindingService.activeGuardianIds(protecteeUid);
        createDeliveries(alert, guardians, request.note());
        return toResponse(alert);
    }

    /**
     * 把请求里携带的实时位置写进报警。
     *
     * <p>同时把它写回 {@code latest_location}：报警这一刻的位置是全系统
     * 最可信的一次采样，没有任何理由丢弃它。这样即使后续上报延迟，
     * 缓存里也是这条最新鲜的记录。
     *
     * @return true 表示用了请求里的位置；false 表示没带（调用方回落到缓存）
     */
    private boolean applyRequestLocation(Alert alert, AlertDtos.AlertCaptureRequest location) {
        if (location == null || location.latitude() == null || location.longitude() == null) {
            return false;
        }
        LocalDateTime capturedAt = location.capturedAt() == null
                ? LocalDateTime.now() : location.capturedAt();
        alert.setLatitude(location.latitude());
        alert.setLongitude(location.longitude());
        alert.setLocationCapturedAt(capturedAt);
        // 顺手刷新缓存。失败不影响报警本身 —— 位置已经写进 alert 了。
        try {
            locationService.report(alert.getProtecteeUid(),
                    new LocationReportRequest(
                            location.latitude(), location.longitude(),
                            location.accuracyM(), capturedAt));
        } catch (RuntimeException error) {
            log.warn("报警携带的位置写回缓存失败，不影响报警本身", error);
        }
        return true;
    }

    /**
     * 路由：确定这条报警该给谁。
     *
     * <p>规则来自 00 §5「存在生效监护人时通知全部生效监护人」。
     * 只填 route_type 与状态，不在这里建投递记录 —— 投递要带 dispatch_order，
     * 而那需要 alert.id 已经生成。
     */
    private void route(Alert alert) {
        List<String> guardians = bindingService.activeGuardianIds(alert.getProtecteeUid());
        if (guardians.isEmpty()) {
            alert.setRouteType("NONE");
            alert.setStatus(STATUS_NO_RECIPIENT);
            alert.setRouteNote("报警时不存在生效监护人");
            return;
        }
        alert.setRouteType("ALL_GUARDIANS");
        alert.setStatus(STATUS_DISPATCHED);
        alert.setRouteNote("已通知全部 " + guardians.size() + " 名生效监护人");
    }

    /**
     * 安排「5 秒后自动依次呼叫」。
     *
     * <p>只写 {@code auto_session_due_at}，真正的执行在
     * {@link AlertAutoDispatchJob} 里扫库完成 —— 不能在创建请求的线程里
     * {@code Thread.sleep(5)}：那会占着一个 Web 线程，且服务重启就丢。
     */
    private void scheduleAutoSession(Alert alert) {
        if (!STATUS_DISPATCHED.equals(alert.getStatus())) {
            // 没有收件人（或路由失败）就没有可呼的人。
            alert.setAutoSessionState(AUTO_NONE);
            return;
        }
        if (!settingService.isAutoSessionEnabled(alert.getProtecteeUid())) {
            alert.setAutoSessionState(AUTO_SKIPPED);
            alert.setRouteNote(appendNote(alert.getRouteNote(), "用户已关闭自动呼叫"));
            return;
        }
        int delaySeconds = settingService.effectiveDelaySeconds(alert.getProtecteeUid());
        alert.setAutoSessionState(AUTO_SCHEDULED);
        alert.setAutoSessionDueAt(LocalDateTime.now().plusSeconds(delaySeconds));
    }

    /**
     * 生成投递记录并给每个监护人发一条站内通知。
     *
     * <p>{@code dispatch_order} 由监护人列表的顺序固定下来（按绑定确认时间）。
     * 落库而不是靠内存顺序：定时任务会被重试、可能跨实例，靠内存顺序第二次执行
     * 就会变成乱序重发。
     */
    private void createDeliveries(Alert alert, List<String> guardians, String note) {
        int order = 1;
        for (String guardianUid : guardians) {
            AlertDelivery delivery = new AlertDelivery();
            delivery.setId(IdGenerator.next());
            delivery.setAlertId(alert.getId());
            delivery.setStatus("PENDING");
            delivery.setReceiverPrincipalType("USER");
            delivery.setReceiverPrincipalId(guardianUid);
            delivery.setRouteType("ALL_GUARDIANS");
            delivery.setDispatchOrder(order++);
            delivery.setRetryCount(0);
            deliveryMapper.insert(delivery);

            notifyGuardian(alert, guardianUid, note);
        }
    }

    /**
     * 给监护人写一条站内通知。
     *
     * <p>通知与投递分开：投递回答「会话有没有建起来」，通知回答「他看不看得到」。
     * 即使实时会话创建失败，这条通知仍要留下来 —— 那正是最坏情况下唯一能到达的信息。
     */
    private void notifyGuardian(Alert alert, String guardianUid, String note) {
        Notification notification = new Notification();
        notification.setId(IdGenerator.next());
        notification.setStatus("UNREAD");
        notification.setReceiverPrincipalType("USER");
        notification.setReceiverPrincipalId(guardianUid);
        notification.setNotificationType("ALERT");
        notification.setTitle("受护人发出报警");
        notification.setBody(buildNotificationBody(alert, note));
        notification.setSourceType("ALERT");
        notification.setSourceId(alert.getId());
        notificationMapper.insert(notification);
    }

    private static String buildNotificationBody(Alert alert, String note) {
        StringBuilder body = new StringBuilder();
        body.append("紧急情况：受护人触发了报警");
        if (note != null && !note.isBlank()) {
            body.append("，说明：").append(note.trim());
        }
        body.append("。");
        if (alert.getAutoSessionState().equals(AUTO_SCHEDULED)) {
            body.append(AlertSettingService.DEFAULT_DELAY_SECONDS).append(" 秒后会发起会话请求。");
        }
        return body.toString();
    }

    /** 我的报警列表（受护人视角）。 */
    /**
     * 我能看到的报警：**我发起的**（我是受护人）+ **投递给我的**（我是监护人）。
     *
     * <p><b>这两类必须一起返回</b>，否则监护人永远看不到任何报警。
     * 原来的实现只按 {@code protectee_uid = callerId} 查，而监护人自己不是
     * 受护人 —— 他调这个接口只会得到一个空列表。
     * 结果是监护人端**没有任何途径**知道"谁在向我求助、他在哪"，
     * 只能看到一条不知来路的会话。
     *
     * <p>监护人那一半通过 {@code alert_delivery.receiver_principal_id} 反查，
     * 而不是"受护人是我监护对象"——投递记录才是"这条报警确实通知过我"的证据，
     * 也让 PENDING/已解除的绑定天然被排除在外（它们不会有投递记录）。
     */
    public List<AlertResponse> myAlerts(String callerId) {
        // 用 LinkedHashMap 按 id 去重，而不是靠 SQL 的 `ne(protecteeUid, callerId)` 排除。
        // 去重放在内存里做更稳：一旦绑定关系出现对称（某账号既是受护人又是自己的
        // 监护人，或历史数据里投递指向了本人），SQL 过滤会漏掉，
        // 而按 id 去重是结构性的，不依赖数据形状。
        java.util.Map<String, Alert> merged = new java.util.LinkedHashMap<>();

        // 我作为受护人发起的
        alertMapper.selectList(Wrappers.<Alert>lambdaQuery()
                        .eq(Alert::getProtecteeUid, callerId)
                        .orderByDesc(Alert::getCreatedAt))
                .forEach(alert -> merged.putIfAbsent(alert.getId(), alert));

        // 投递给我的（我作为监护人）
        List<String> deliveredAlertIds = deliveryMapper.selectList(
                        Wrappers.<AlertDelivery>lambdaQuery()
                                .eq(AlertDelivery::getReceiverPrincipalId, callerId))
                .stream().map(AlertDelivery::getAlertId).distinct().toList();
        if (!deliveredAlertIds.isEmpty()) {
            alertMapper.selectList(Wrappers.<Alert>lambdaQuery()
                            .in(Alert::getId, deliveredAlertIds))
                    .forEach(alert -> merged.putIfAbsent(alert.getId(), alert));
        }

        return merged.values().stream()
                .sorted((left, right) -> {
                    if (left.getCreatedAt() == null || right.getCreatedAt() == null) {
                        return 0;
                    }
                    return right.getCreatedAt().compareTo(left.getCreatedAt());
                })
                .map(this::toResponse)
                .toList();
    }

    /** 报警详情，仅受护人本人与**生效**监护人可读。 */
    public AlertResponse get(String callerId, String alertId) {
        Alert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报警不存在");
        }
        boolean isProtectee = Objects.equals(callerId, alert.getProtecteeUid());
        // 位置是敏感信息：只有生效监护人能读。PENDING/已解除的绑定一律拒绝，
        // 且不区分「不存在」与「无权」—— 后者会泄露「这条报警确实存在」。
        boolean isGuardian = bindingService.hasActiveGuardianship(callerId, alert.getProtecteeUid());
        if (!isProtectee && !isGuardian) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报警不存在");
        }
        return toResponse(alert);
    }

    /** 受护人结束报警（自己确认安全了）。 */
    @Transactional
    public AlertResponse resolve(String callerId, String alertId) {
        Alert alert = alertMapper.selectById(alertId);
        if (alert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "报警不存在");
        }
        if (!Objects.equals(callerId, alert.getProtecteeUid())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有受护人本人可以结束报警");
        }
        int updated = alertMapper.update(null, Wrappers.<Alert>lambdaUpdate()
                .set(Alert::getStatus, "RESOLVED")
                .set(Alert::getResolvedAt, LocalDateTime.now())
                .set(Alert::getAutoSessionState, AUTO_DONE)
                .setSql("version = version + 1")
                .eq(Alert::getId, alertId)
                .ne(Alert::getStatus, "RESOLVED"));
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "报警已结束");
        }
        alert.setStatus("RESOLVED");
        alert.setResolvedAt(LocalDateTime.now());
        alert.setAutoSessionState(AUTO_DONE);
        return toResponse(alert);
    }

    /**
     * 该受护人报警时会依次通知谁（受护人端用来展示「会通知谁」）。
     *
     * <p>只返回顺序与昵称，不返回手机号等联系方式：受护人自己知道谁是他的监护人，
     * 不需要通过这个接口把监护人的联系方式再取一遍。
     */
    public List<String> guardianOrder(String protecteeUid) {
        return bindingService.activeGuardianIds(protecteeUid);
    }

    private AlertResponse toResponse(Alert alert) {
        List<AlertDelivery> deliveries = deliveryMapper.selectList(
                Wrappers.<AlertDelivery>lambdaQuery()
                        .eq(AlertDelivery::getAlertId, alert.getId())
                        .orderByAsc(AlertDelivery::getDispatchOrder));
        List<AlertDeliveryView> views = new ArrayList<>(deliveries.size());
        for (AlertDelivery delivery : deliveries) {
            views.add(new AlertDeliveryView(delivery.getReceiverPrincipalId(),
                    delivery.getDispatchOrder(), delivery.getStatus(), delivery.getSessionId(),
                    delivery.getFailureReason()));
        }
        AlertLocation location = null;
        if (alert.getLatitude() != null && alert.getLongitude() != null) {
            long age = alert.getLocationCapturedAt() == null ? 0L
                    : Math.max(0L, java.time.Duration.between(
                    alert.getLocationCapturedAt(), LocalDateTime.now()).getSeconds());
            location = new AlertLocation(alert.getLatitude(), alert.getLongitude(),
                    null, alert.getLocationCapturedAt(), age);
        }
        return new AlertResponse(alert.getId(), alert.getStatus(), alert.getAlertType(),
                alert.getRouteType(), alert.getRouteNote(), alert.getProtecteeUid(),
                location, alert.getCreatedAt(), alert.getResolvedAt(),
                alert.getAutoSessionState(), alert.getAutoSessionDueAt(), views);
    }

    private static String appendNote(String note, String extra) {
        if (note == null || note.isBlank()) {
            return extra;
        }
        String combined = note + "；" + extra;
        // route_note 是 VARCHAR(200)，超长会被 MySQL 截断或报错，先截。
        return combined.length() <= 200 ? combined : combined.substring(0, 200);
    }

    /**
     * 定时任务调用：取出所有到点该自动呼叫的报警 ID。
     *
     * <p>查询条件里的 {@code status} 必须排除 RESOLVED：报警已经结束还去打电话，
     * 对监护人是纯粹的骚扰。
     */
    List<Alert> dueAutoSessions(LocalDateTime now, int limit) {
        return alertMapper.selectList(Wrappers.<Alert>lambdaQuery()
                .eq(Alert::getAutoSessionState, AUTO_SCHEDULED)
                .isNotNull(Alert::getAutoSessionDueAt)
                .le(Alert::getAutoSessionDueAt, now)
                .ne(Alert::getStatus, "RESOLVED")
                .orderByAsc(Alert::getAutoSessionDueAt)
                .last("LIMIT " + limit));
    }

    /**
     * 把一条报警从 SCHEDULED 抢到 RUNNING。
     *
     * @return 抢到了返回 true；已被其他实例/上一轮抢走返回 false
     */
    @Transactional
    public boolean claimForAutoSession(String alertId) {
        int updated = alertMapper.update(null, Wrappers.<Alert>lambdaUpdate()
                .set(Alert::getAutoSessionState, AUTO_RUNNING)
                .set(Alert::getAutoSessionStartedAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(Alert::getId, alertId)
                .eq(Alert::getAutoSessionState, AUTO_SCHEDULED));
        return updated > 0;
    }

    /** 自动会话跑完后收尾。 */
    @Transactional
    public void finishAutoSession(String alertId) {
        alertMapper.update(null, Wrappers.<Alert>lambdaUpdate()
                .set(Alert::getAutoSessionState, AUTO_DONE)
                .setSql("version = version + 1")
                .eq(Alert::getId, alertId)
                .eq(Alert::getAutoSessionState, AUTO_RUNNING));
    }

    /** 用户在这一轮里关掉了开关：把还排着期的报警标记为 SKIPPED。 */
    @Transactional
    public void skipScheduled(String protecteeUid) {
        alertMapper.update(null, Wrappers.<Alert>lambdaUpdate()
                .set(Alert::getAutoSessionState, AUTO_SKIPPED)
                .set(Alert::getAutoSessionDueAt, null)
                .setSql("version = version + 1")
                .eq(Alert::getProtecteeUid, protecteeUid)
                .eq(Alert::getAutoSessionState, AUTO_SCHEDULED));
    }

    Alert loadAlert(String alertId) {
        return alertMapper.selectById(alertId);
    }

    /** 供调度器读取投递列表（按 dispatch_order 升序，这就是「依次」的顺序）。 */
    List<AlertDelivery> deliveriesOf(String alertId) {
        return deliveryMapper.selectList(Wrappers.<AlertDelivery>lambdaQuery()
                .eq(AlertDelivery::getAlertId, alertId)
                .orderByAsc(AlertDelivery::getDispatchOrder));
    }

    /**
     * 发起一条投递对应的实时会话。
     *
     * <p>委托给 {@link RealtimeAlertDispatcher}：那里要处理「会话已存在」「目标不可用」
     * 这些基础设施层的失败，而本类只关心报警自己的状态。
     */
    RealtimeSession dispatchSession(String alertId, AlertDelivery delivery) {
        return dispatcher.openSession(alertId, delivery);
    }
}
