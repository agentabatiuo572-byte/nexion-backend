package ffdd.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.exception.BizException;
import ffdd.notification.domain.Notification;
import ffdd.notification.dto.NotificationCreateRequest;
import ffdd.notification.mapper.NotificationMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationCenterCreateTest {
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final NotificationUnreadCounter unreadCounter = mock(NotificationUnreadCounter.class);
    private final NotificationCenterService service =
            new NotificationCenterService(notificationMapper, unreadCounter);

    @Test
    void createsGenericNotification() {
        when(notificationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
                    Notification notification = invocation.getArgument(0);
                    notification.setId(88L);
                    notification.setCreatedAt(LocalDateTime.now());
                    notification.setUpdatedAt(LocalDateTime.now());
                    return 1;
                })
                .when(notificationMapper)
                .insert(any(Notification.class));
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setBizNo("SupportTicketReply:TK1:1");
        request.setUserId(1001L);
        request.setType("SUPPORT");
        request.setTitle("Support ticket updated");
        request.setBody("Your ticket has a new reply.");

        Notification notification = service.create(request);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getPushStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getReadFlag()).isZero();
        assertThat(notification.getId()).isEqualTo(88L);
        verify(unreadCounter).increment(1001L);
    }

    @Test
    void returnsExistingWhenBizNoAlreadyExists() {
        Notification existing = new Notification();
        existing.setId(7L);
        existing.setBizNo("SupportTicketStatus:TK1:RESOLVED");
        existing.setUserId(1001L);
        when(notificationMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setBizNo(existing.getBizNo());
        request.setUserId(1001L);
        request.setType("SUPPORT");
        request.setTitle("Support ticket status changed");
        request.setBody("Resolved.");

        Notification notification = service.create(request);

        assertThat(notification.getId()).isEqualTo(7L);
    }

    @Test
    void rejectsMissingBody() {
        NotificationCreateRequest request = new NotificationCreateRequest();
        request.setUserId(1001L);
        request.setType("SUPPORT");
        request.setTitle("Support ticket updated");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("body is required");
    }
}
