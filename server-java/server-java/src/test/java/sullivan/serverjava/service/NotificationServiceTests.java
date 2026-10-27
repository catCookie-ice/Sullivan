package sullivan.serverjava.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.domain.UserNotification;
import sullivan.serverjava.mapper.UserNotificationMapper;
import sullivan.serverjava.web.NotificationDtos.NotificationPage;
import sullivan.serverjava.web.NotificationDtos.UnreadCountResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTests {

    private UserNotificationMapper notificationMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(UserNotificationMapper.class);
        service = new NotificationService(notificationMapper);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                UserNotification.class);
    }

    @Test
    void unreadCountCountsOwnUnread() {
        when(notificationMapper.selectCount(any())).thenReturn(3L);

        UnreadCountResponse response = service.unreadCount("u-1", "USER");

        assertThat(response.count()).isEqualTo(3);
    }

    /** 管理员等没有收件箱的账号类型：返回 0，不查询。 */
    @Test
    void unreadCountForUnsupportedTypeIsZero() {
        UnreadCountResponse response = service.unreadCount("a-1", "ADMIN");

        assertThat(response.count()).isEqualTo(0);
        verify(notificationMapper, never()).selectCount(any());
    }

    @Test
    void listKeepsDescendingOrderAndReportsHasMore() {
        UserNotification newest = notification("n1", "2026-10-27T10:03:00");
        UserNotification middle = notification("n2", "2026-10-27T10:02:00");
        UserNotification oldest = notification("n3", "2026-10-27T10:01:00");
        when(notificationMapper.selectList(any())).thenReturn(List.of(newest, middle, oldest));

        NotificationPage page = service.list("u-1", "USER", null, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(page.records()).extracting("id").containsExactly("n1", "n2");
    }

    /** 标记别人的通知已读：404（条件更新带 recipient 归属，防越权）。 */
    @Test
    void markReadForeignNotificationNotFound() {
        when(notificationMapper.update(isNull(), any())).thenReturn(0);
        UserNotification foreign = notification("n1", "2026-10-27T10:03:00");
        foreign.setRecipientUid("someone-else");
        when(notificationMapper.selectById("n1")).thenReturn(foreign);

        assertThatThrownBy(() -> service.markRead("u-1", "USER", "n1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** 重复标记已读：幂等成功（自己的通知，状态已 READ）。 */
    @Test
    void markReadAlreadyReadIsIdempotent() {
        when(notificationMapper.update(isNull(), any())).thenReturn(0);
        UserNotification own = notification("n1", "2026-10-27T10:03:00");
        own.setStatus("READ");
        when(notificationMapper.selectById("n1")).thenReturn(own);

        assertThatCode(() -> service.markRead("u-1", "USER", "n1")).doesNotThrowAnyException();
    }

    @Test
    void markAllReadUpdatesOwnUnread() {
        service.markAllRead("u-1", "USER");

        verify(notificationMapper).update(isNull(), any());
    }

    /** 通知写入失败只记日志，绝不拖垮调用方的主流程（报名/审核/撤回）。 */
    @Test
    void notifySwallowsInsertFailure() {
        doThrow(new RuntimeException("db down")).when(notificationMapper).insert(any(UserNotification.class));

        assertThatCode(() -> service.notify("u-1", "USER",
                NotificationService.TYPE_ENROLLMENT_APPROVED, "标题", "正文", "/volunteer/enrollments"))
                .doesNotThrowAnyException();
    }

    private UserNotification notification(String id, String createdAt) {
        UserNotification notification = new UserNotification();
        notification.setId(id);
        notification.setStatus("UNREAD");
        notification.setRecipientUid("u-1");
        notification.setRecipientType("USER");
        notification.setType(NotificationService.TYPE_ENROLLMENT_APPROVED);
        notification.setTitle("报名审核通过");
        notification.setContent("你报名的活动已通过审核");
        notification.setLink("/volunteer/enrollments");
        notification.setCreatedAt(LocalDateTime.parse(createdAt));
        return notification;
    }
}
