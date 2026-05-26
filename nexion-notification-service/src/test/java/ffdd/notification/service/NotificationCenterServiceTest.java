package ffdd.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationMutationResponse;
import ffdd.notification.mapper.NotificationMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationCenterServiceTest {
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final NotificationUnreadCounter unreadCounter = mock(NotificationUnreadCounter.class);
    private final NotificationCenterService service = new NotificationCenterService(notificationMapper, unreadCounter);

    @Test
    void pagesNotificationsForCurrentUserOnly() {
        Notification notification = notification(1L, 10001L, 0);
        when(notificationMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<Notification> page = invocation.getArgument(0);
            page.setTotal(1);
            page.setRecords(List.of(notification));
            return page;
        });

        PageResult<Notification> result = service.pageForUser(10001L, 0, "EARNING", 1, 20);

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getRecords()).containsExactly(notification);
    }

    @Test
    void marksOwnedUnreadNotificationAsReadAndInvalidatesCounter() {
        Notification notification = notification(9L, 10001L, 0);
        when(notificationMapper.selectById(9L)).thenReturn(notification);
        when(notificationMapper.updateById(any(Notification.class))).thenReturn(1);

        Notification updated = service.markRead(10001L, 9L);

        ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(patch.capture());
        assertThat(patch.getValue().getId()).isEqualTo(9L);
        assertThat(patch.getValue().getReadFlag()).isEqualTo(1);
        assertThat(updated.getReadFlag()).isEqualTo(1);
        verify(unreadCounter).invalidate(10001L);
    }

    @Test
    void rejectsReadMutationForOtherUsersNotification() {
        when(notificationMapper.selectById(9L)).thenReturn(notification(9L, 20002L, 0));

        assertThatThrownBy(() -> service.markRead(10001L, 9L))
                .isInstanceOf(BizException.class)
                .hasMessage("Notification not found");
        verify(notificationMapper, never()).updateById(any(Notification.class));
        verify(unreadCounter, never()).invalidate(10001L);
    }

    @Test
    void marksAllUnreadNotificationsAsReadAndInvalidatesCounter() {
        when(notificationMapper.update(any(Notification.class), any())).thenReturn(3);

        NotificationMutationResponse response = service.markAllRead(10001L);

        assertThat(response.getUserId()).isEqualTo(10001L);
        assertThat(response.getAffectedRows()).isEqualTo(3);
        verify(unreadCounter).invalidate(10001L);
    }

    @Test
    void softDeletesOwnedNotificationAndInvalidatesUnreadCounterWhenNeeded() {
        Notification notification = notification(9L, 10001L, 0);
        when(notificationMapper.selectById(9L)).thenReturn(notification);
        when(notificationMapper.updateById(any(Notification.class))).thenReturn(1);

        NotificationMutationResponse response = service.delete(10001L, 9L);

        assertThat(response.getAffectedRows()).isEqualTo(1);
        verify(unreadCounter).invalidate(10001L);
    }

    private Notification notification(Long id, Long userId, int readFlag) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setType("EARNING");
        notification.setTitle("Earning generated");
        notification.setBody("body");
        notification.setReadFlag(readFlag);
        notification.setPushStatus("PENDING");
        notification.setIsDeleted(0);
        return notification;
    }
}
