package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.common.IdGenerator;
import sullivan.serverjava.common.TimeUtil;
import sullivan.serverjava.domain.UserNotification;
import sullivan.serverjava.mapper.UserNotificationMapper;
import sullivan.serverjava.web.NotificationDtos.NotificationPage;
import sullivan.serverjava.web.NotificationDtos.NotificationResponse;
import sullivan.serverjava.web.NotificationDtos.UnreadCountResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知：导航栏铃铛的数据源。
 *
 * <p><b>写侧（业务服务调用）</b>：报名待审核→机构、审核裁决→志愿者、机构撤回群消息→群成员。
 * 写通知是<b>尽力而为</b>：失败只记日志、不拖垮主流程——报名/审核是主事务，
 * 通知只是锦上添花，不能让它把审核回滚了。</p>
 *
 * <p><b>读侧（铃铛）</b>：未读数 + 按时间倒序的游标分页；单条已读、全部已读。
 * 接收者为多态引用（USER=user_account.id，ORGANIZATION=organization.id），
 * 查询一律带 recipient_type，防止跨类型撞 uid。</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public static final String TYPE_ENROLLMENT_PENDING = "ENROLLMENT_PENDING";
    public static final String TYPE_ENROLLMENT_APPROVED = "ENROLLMENT_APPROVED";
    public static final String TYPE_ENROLLMENT_REJECTED = "ENROLLMENT_REJECTED";
    public static final String TYPE_GROUP_MESSAGE_WITHDRAWN = "GROUP_MESSAGE_WITHDRAWN";
    public static final String TYPE_PENALTY_APPLIED = "PENALTY_APPLIED";
    public static final String TYPE_APPEAL_DECIDED = "APPEAL_DECIDED";
    public static final String TYPE_REPORT_DISMISSED = "REPORT_DISMISSED";

    private static final String RECIPIENT_USER = "USER";
    private static final String RECIPIENT_ORGANIZATION = "ORGANIZATION";
    private static final int MAX_PAGE_SIZE = 100;

    private final UserNotificationMapper notificationMapper;

    public NotificationService(UserNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    // ---------- 写（业务服务调用；尽力而为） ----------

    /** 落一条通知；失败只记日志（通知不能拖垮报名/审核/撤回主流程）。 */
    public void notify(String recipientUid, String recipientType, String type,
                       String title, String content, String link) {
        try {
            UserNotification notification = new UserNotification();
            notification.setId(IdGenerator.next());
            notification.setStatus("UNREAD");
            notification.setRecipientUid(recipientUid);
            notification.setRecipientType(recipientType);
            notification.setType(type);
            notification.setTitle(title);
            notification.setContent(content);
            notification.setLink(link);
            notificationMapper.insert(notification);
        } catch (RuntimeException exception) {
            log.warn("notification insert failed: recipient={} type={}", recipientUid, type, exception);
        }
    }

    // ---------- 读（铃铛） ----------

    /** 未读数（角标）。 */
    public UnreadCountResponse unreadCount(String recipientUid, String recipientType) {
        if (!supported(recipientType)) {
            return new UnreadCountResponse(0);
        }
        long count = notificationMapper.selectCount(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getRecipientUid, recipientUid)
                .eq(UserNotification::getRecipientType, recipientType)
                .eq(UserNotification::getStatus, "UNREAD"));
        return new UnreadCountResponse(count);
    }

    /**
     * 通知列表（游标分页）：{@code before} 为上一页最早一条的创建时间，为空时从最新开始。
     * 返回按时间倒序（最新在前），与铃铛抽屉的展示顺序一致。
     */
    public NotificationPage list(String recipientUid, String recipientType, String before, int limit) {
        if (!supported(recipientType)) {
            return new NotificationPage(List.of(), false);
        }
        LocalDateTime beforeAt = before == null || before.isBlank() ? null : TimeUtil.parseDateTimeLenient(before);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        List<UserNotification> rows = notificationMapper.selectList(Wrappers.<UserNotification>lambdaQuery()
                .eq(UserNotification::getRecipientUid, recipientUid)
                .eq(UserNotification::getRecipientType, recipientType)
                .lt(beforeAt != null, UserNotification::getCreatedAt, beforeAt)
                .orderByDesc(UserNotification::getCreatedAt)
                .last("LIMIT " + (safeLimit + 1)));
        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) {
            rows = rows.subList(0, safeLimit);
        }
        return new NotificationPage(rows.stream().map(this::toResponse).toList(), hasMore);
    }

    /** 标记单条已读（只能标自己的）。 */
    @Transactional
    public void markRead(String recipientUid, String recipientType, String notificationId) {
        requireSupported(recipientType);
        int updated = notificationMapper.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .set(UserNotification::getStatus, "READ")
                .set(UserNotification::getReadAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(UserNotification::getId, notificationId)
                .eq(UserNotification::getRecipientUid, recipientUid)
                .eq(UserNotification::getRecipientType, recipientType)
                .eq(UserNotification::getStatus, "UNREAD"));
        if (updated == 0) {
            // 已读/不属于自己都落在这里：对铃铛场景幂等处理比报错友好。
            UserNotification notification = notificationMapper.selectById(notificationId);
            if (notification == null || !notification.getRecipientUid().equals(recipientUid)
                    || !notification.getRecipientType().equals(recipientType)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "通知不存在");
            }
        }
    }

    /** 全部已读。 */
    @Transactional
    public void markAllRead(String recipientUid, String recipientType) {
        requireSupported(recipientType);
        notificationMapper.update(null, Wrappers.<UserNotification>lambdaUpdate()
                .set(UserNotification::getStatus, "READ")
                .set(UserNotification::getReadAt, LocalDateTime.now())
                .setSql("version = version + 1")
                .eq(UserNotification::getRecipientUid, recipientUid)
                .eq(UserNotification::getRecipientType, recipientType)
                .eq(UserNotification::getStatus, "UNREAD"));
    }

    private boolean supported(String recipientType) {
        return RECIPIENT_USER.equals(recipientType) || RECIPIENT_ORGANIZATION.equals(recipientType);
    }

    private void requireSupported(String recipientType) {
        if (!supported(recipientType)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前账号类型没有站内通知");
        }
    }

    private NotificationResponse toResponse(UserNotification notification) {
        return new NotificationResponse(notification.getId(), notification.getType(), notification.getTitle(),
                notification.getContent(), notification.getLink(), notification.getStatus(),
                notification.getReadAt(), notification.getCreatedAt());
    }
}
