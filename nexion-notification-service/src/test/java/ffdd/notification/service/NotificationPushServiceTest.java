package ffdd.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationPushResult;
import ffdd.notification.mapper.NotificationMapper;
import ffdd.notification.push.PushProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationPushServiceTest {
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final PushProvider pushProvider = mock(PushProvider.class);
    private final NotificationPushService service =
            new NotificationPushService(notificationMapper, pushProvider, 2, 60);

    @Test
    void pushesDueNotificationAndMarksItSent() {
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(1L, "PENDING", 0)));

        NotificationPushResult result = service.pushPending(10);

        ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);
        verify(pushProvider).push(any(Notification.class));
        verify(notificationMapper).updateById(patch.capture());
        assertThat(result.getScanned()).isEqualTo(1);
        assertThat(result.getSent()).isEqualTo(1);
        assertThat(patch.getValue().getPushStatus()).isEqualTo("SENT");
        assertThat(patch.getValue().getPushAttempts()).isEqualTo(1);
        assertThat(patch.getValue().getPushedAt()).isNotNull();
    }

    @Test
    void schedulesRetryWhenPushFailsBelowRetryLimit() {
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(1L, "PENDING", 0)));
        doThrow(new IllegalStateException("push unavailable")).when(pushProvider).push(any(Notification.class));

        NotificationPushResult result = service.pushPending(10);

        ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(patch.capture());
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getDead()).isZero();
        assertThat(patch.getValue().getPushStatus()).isEqualTo("FAILED");
        assertThat(patch.getValue().getPushAttempts()).isEqualTo(1);
        assertThat(patch.getValue().getNextPushAt()).isNotNull();
        assertThat(patch.getValue().getLastPushError()).contains("push unavailable");
    }

    @Test
    void marksDeadWhenPushAttemptsReachRetryLimit() {
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification(1L, "FAILED", 1)));
        doThrow(new IllegalStateException("push unavailable")).when(pushProvider).push(any(Notification.class));

        NotificationPushResult result = service.pushPending(10);

        ArgumentCaptor<Notification> patch = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(patch.capture());
        assertThat(result.getDead()).isEqualTo(1);
        assertThat(patch.getValue().getPushStatus()).isEqualTo("DEAD");
        assertThat(patch.getValue().getPushAttempts()).isEqualTo(2);
        assertThat(patch.getValue().getNextPushAt()).isNull();
    }

    private Notification notification(Long id, String pushStatus, int pushAttempts) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(10001L);
        notification.setTitle("title");
        notification.setBody("body");
        notification.setPushStatus(pushStatus);
        notification.setPushAttempts(pushAttempts);
        notification.setReadFlag(0);
        notification.setIsDeleted(0);
        return notification;
    }
}
